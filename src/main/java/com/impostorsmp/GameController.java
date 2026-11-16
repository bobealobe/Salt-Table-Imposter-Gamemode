package com.impostorsmp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.impostorsmp.GameState.Member;
import com.impostorsmp.GameState.Outcome;
import com.impostorsmp.GameState.Phase;
import com.impostorsmp.GameState.AccuseResult;
import com.impostorsmp.GameState.Role;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/** Runs the actual game: picking roles, the countdown, deaths, win conditions. One per world that is opened. */
public final class GameController {
	private final MinecraftServer server;
	private final Rules rules;
	private final GameState state;
	private final Scheduler scheduler = new Scheduler();
	private final Path statePath;
	private final Course course = new Course();
	/** Server tick of each player's last accusation, for the cooldown. Not kept across restarts. */
	private final Map<UUID, Integer> lastAccuse = new HashMap<>();
	/** The spot each nearest-player compass was last aimed at, so it is only replaced when the target moves. */
	private final Map<UUID, String> lastCompassTarget = new HashMap<>();
	private double[] decoy;
	private double[] decoyCenter;
	private double decoyAngle;
	private final Random random = new Random();
	/** Players under a Spectral Serum right now, and where to put them back. */
	private final Map<UUID, SerumState> serum = new HashMap<>();
	/** Serum users whose time ran out while they were inside blocks (so they are only told once). */
	private final Set<UUID> serumWarned = new HashSet<>();
	/** Players frozen by Sub-Zero Powder Snow, and the server tick they thaw at. */
	private final Map<UUID, Integer> frozenUntil = new HashMap<>();
	/** Spots where an impostor placed Sub-Zero Powder Snow ("dimension:blockpos"). */
	private final Set<String> snowTraps = new HashSet<>();
	private BlockPos strongholdPos;
	private boolean strongholdSearched;

	GameController(MinecraftServer server, Path configDir) {
		this.server = server;
		this.rules = new Rules(configDir.resolve("impostorsmp-rules.properties"));
		this.statePath = configDir.resolve("impostorsmp-game.properties");

		GameState loaded = GameState.load(statePath);
		long seed = server.overworld().getSeed();
		if (loaded.phase() != Phase.IDLE && loaded.worldSeed() != seed) {
			ImpostorLog.warn("The saved game belongs to a different world. Starting with a clean slate.");
			loaded = new GameState();
		} else if (loaded.phase() == Phase.COUNTDOWN) {
			ImpostorLog.warn("The world was closed during the countdown. That game was cancelled; use /imp start again.");
			loaded = new GameState();
		}
		this.state = loaded;
	}

	public Rules rules() {
		return rules;
	}

	public GameState state() {
		return state;
	}

	public void save() {
		state.save(statePath);
	}

	void onServerStarted() {
		if (rules.bool("apply_gamerules")) {
			applyGameRules();
		}
		save();
	}

	void tick() {
		scheduler.tick();
		if (rules.bool("block_totems")) {
			stripTotems();
		}
		if (rules.bool("secret_item")) {
			oneShotArrows();
		}
		if (!serum.isEmpty() || !frozenUntil.isEmpty() || !snowTraps.isEmpty()) {
			tickSerum();
			tickSnowTraps();
		}
		if (state.phase() == Phase.RUNNING) {
			int tick = server.getTickCount();
			if (tick % 20 == 0) {
				state.addSecond();
				if (rules.bool("secret_item")) {
					secretSecond();
				}
				if (state.elapsedSeconds() % 60 == 0) {
					save(); // keeps the game clock if the world is closed without a clean shutdown
				}
				if (rules.bool("compass")) {
					compassSecond();
				}
			}
			if (decoy != null && tick % 4 == 0) {
				tickDecoy();
			}
			if (rules.bool("compass") && tick % (rules.integer("compass_refresh_seconds") * 20) == 0) {
				refreshCompasses();
			}
		}
		int lobby = rules.lobbyY();
		if (lobby >= 0) {
			if (server.getTickCount() % 20 == 0) {
				purgeLobbyMobs();
			}
			tickCourse(lobby);
		}
	}

	// ------------------------------------------------------------------ starting a game

	/** Picks the roles and starts the countdown. Throws IllegalStateException with a readable message if it can't. */
	public void start() {
		start(null, null);
	}

	/**
	 * Solo test: the host gets the chosen role and the other five players are stand-ins. No spreading, and
	 * the dummies never die, so the game only ends if you make it end.
	 */
	public void startTest(ServerPlayer host, Role role) {
		start(host, role);
	}

	/**
	 * Makes sure the Nether has a fortress and a bastion remnant near the middle, so they are inside the
	 * border (the Nether is the overworld scaled down 8 times). Placing the same structure twice puts it in
	 * the same spot, so running this again is harmless. The chunks around them are generated now, which can
	 * take a few seconds.
	 */
	public void placeNetherStructures() {
		int d = rules.integer("nether_structure_distance");
		placeNetherStructure("minecraft:fortress", -d, 64, d);
		placeNetherStructure("minecraft:bastion_remnant", d, 64, -d);
	}

	private void placeNetherStructure(String structure, int x, int y, int z) {
		String error = Fx.runChecked(server, "execute in minecraft:the_nether run place structure " + structure + " " + x + " " + y + " " + z);
		if (error != null) {
			ImpostorLog.warn("Could not place " + structure + " in the Nether: " + error);
		} else {
			ImpostorLog.warn("Placed " + structure + " in the Nether at " + x + " " + z + ".");
		}
	}

	private void start(ServerPlayer testHost, Role testRole) {
		if (state.phase() == Phase.COUNTDOWN || state.phase() == Phase.RUNNING) {
			throw new IllegalStateException("A game is already running. Use /imp stop first.");
		}

		Map<UUID, String> players = new LinkedHashMap<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.isSpectator()) {
				players.put(p.getUUID(), p.getGameProfile().name());
			}
		}
		int impostors = rules.integer("impostors");
		try {
			if (testHost != null) {
				state.beginTest(testHost.getUUID(), testHost.getGameProfile().name(), testRole, impostors, 6,
					server.overworld().getSeed());
			} else {
				state.begin(players, impostors, new Random(), server.overworld().getSeed());
			}
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(e.getMessage());
		}
		save();
		lastAccuse.clear();
		clearCompassCache();
		Fx.run(server, "time set day");
		if (testHost == null && rules.bool("nether_structures")) {
			placeNetherStructures();
		}

		if (rules.bool("apply_gamerules")) {
			applyGameRules();
		}

		// Players waiting in the sky lobby must be moved down to the world, so they are always spread out.
		int lobbyY = rules.lobbyY();
		boolean fromLobby = lobbyY >= 0;

		long delay = 0;
		if (fromLobby || (testHost == null && rules.bool("spread_on_start"))) {
			spreadPlayers(players);
			delay = 60; // give the world a moment to load around everybody
		}
		if (fromLobby) {
			Fx.run(server, "gamemode survival @a[gamemode=adventure]");
			Fx.run(server, "effect clear @a minecraft:resistance");
			Fx.run(server, "effect clear @a minecraft:saturation");
			// Wait a moment for the teleports to finish, then take the lobby down (see finishLobbyRemoval).
			scheduler.after(20, () -> finishLobbyRemoval(lobbyY, 0));
		}

		Fx.run(server, "title @a clear");
		Fx.times(server, "@a", 0, 25, 5);
		scheduler.after(delay + 20, () -> countdownStep("3", "yellow"));
		scheduler.after(delay + 40, () -> countdownStep("2", "gold"));
		scheduler.after(delay + 60, () -> countdownStep("1", "red"));
		scheduler.after(delay + 90, this::youAre);
		scheduler.after(delay + 140, this::reveal);
		scheduler.after(delay + 260, this::beginRunning);
	}

	/**
	 * Spreads everybody out over the map. With spread_group_size above 1, players are put into temporary
	 * teams first, because /spreadplayers drops the members of one team on the same spot. The teams are
	 * deleted again straight away.
	 */
	private void spreadPlayers(Map<UUID, String> players) {
		int groupSize = rules.integer("spread_group_size");
		List<String> teams = new ArrayList<>();

		if (groupSize > 1) {
			Set<UUID> impostorIds = new HashSet<>();
			for (Member m : state.withRole(Role.IMPOSTOR)) {
				impostorIds.add(m.id());
			}
			List<List<UUID>> groups = Grouping.make(new ArrayList<>(players.keySet()), impostorIds, groupSize,
				rules.bool("spread_split_impostors"), new Random());
			for (int i = 0; i < groups.size(); i++) {
				List<UUID> group = groups.get(i);
				if (group.size() < 2) {
					continue; // a player on their own is spread by themselves anyway
				}
				String team = "imp_group_" + i;
				teams.add(team);
				Fx.run(server, "team remove " + team); // clears a leftover from a crash; fails quietly if none
				Fx.run(server, "team add " + team);
				for (UUID id : group) {
					Fx.run(server, "team join " + team + " " + players.get(id));
				}
			}
		}

		Fx.run(server, "spreadplayers 0 0 " + rules.integer("spread_min_distance") + " "
			+ rules.integer("spread_radius") + " " + (teams.isEmpty() ? "false" : "true") + " @a[gamemode=!spectator]");

		for (String team : teams) {
			Fx.run(server, "team remove " + team);
		}
	}

	private void countdownStep(String number, String color) {
		Fx.times(server, "@a", 0, 25, 5);
		Fx.subtitle(server, "@a", Fx.text("", "white", false));
		Fx.title(server, "@a", Fx.text(number, color, true));
		Fx.sound(server, "@a", "minecraft:block.note_block.pling", 1.0, 1.0);
	}

	private void youAre() {
		Fx.times(server, "@a", 0, 50, 10);
		Fx.title(server, "@a", Fx.text("You are...", "white", true));
		Fx.sound(server, "@a", "minecraft:block.note_block.bass", 1.0, 0.6);
	}

	private void reveal() {
		int totalImpostors = state.total(Role.IMPOSTOR);
		String who = totalImpostors == 1 ? "the impostor" : "the " + totalImpostors + " impostors";
		for (Member m : state.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(m.id());
			if (player == null) {
				continue;
			}
			String target = player.getGameProfile().name();
			boolean impostor = m.role() == Role.IMPOSTOR;
			Fx.times(server, target, 5, 70, 20);
			Fx.subtitle(server, target, Fx.text(
				impostor ? "Eliminate every innocent. You don't know your partner."
					: "Find " + who + ", or slay the Ender Dragon.",
				"gray", false));
			Fx.title(server, target, Fx.text(impostor ? "IMPOSTOR" : "INNOCENT", impostor ? "red" : "green", true));
			Fx.sound(server, target, impostor ? "minecraft:entity.ender_dragon.growl" : "minecraft:entity.player.levelup",
				1.0, 1.0);
			player.sendSystemMessage(Component.literal(impostor ? "You are an IMPOSTOR." : "You are INNOCENT.")
				.withStyle(impostor ? ChatFormatting.RED : ChatFormatting.GREEN));
			if (impostor && rules.bool("impostor_kit")) {
				giveKit(player);
			}
		}
	}

	/**
	 * Hands the impostor one random impostor kit and tells them what is in it (only they see the message).
	 * The items go into empty slots of the main inventory, never the hotbar, so nothing is held in their hand
	 * where a spawn partner could see it, and they are placed silently (the give command plays a pickup sound
	 * that nearby players can hear).
	 */
	public void giveKit(ServerPlayer player) {
		Kit.Entry kit = Kit.roll(random);
		player.sendSystemMessage(Component.literal("Your impostor kit: " + kit.name() + " (in your inventory, not your hotbar)")
			.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		for (Kit.Item item : kit.items()) {
			for (int count : item.stacks()) { // one command per legal stack: a stack bigger than the item allows is refused
				placeStack(player, item.itemText(), count);
			}
			player.sendSystemMessage(Component.literal(" - " + Kit.label(item)).withStyle(ChatFormatting.GOLD));
		}
		player.sendSystemMessage(Component.literal("Keep it out of your hands while others are watching.").withStyle(ChatFormatting.GRAY));
		if (rules.bool("compass")) {
			player.sendSystemMessage(Component.literal("After " + rules.integer("compass_minutes")
				+ " minutes you can choose a compass (you will get buttons in chat).").withStyle(ChatFormatting.GRAY));
		}
		rollSecret(player);
	}

	/** Puts one stack in the first free main-inventory slot without a pickup sound; gives it normally if that fails. */
	private void placeStack(ServerPlayer player, String item, int count) {
		String name = player.getGameProfile().name();
		Inventory inventory = player.getInventory();
		for (int slot = 9; slot < 36; slot++) { // 9..35 is the main inventory, 0..8 the hotbar
			if (inventory.getItem(slot).isEmpty()) {
				String error = Fx.runChecked(server, "item replace entity " + name + " inventory." + (slot - 9) + " with " + item + " " + count);
				if (error == null) {
					return;
				}
				ImpostorLog.warn("Kit item " + item + " x" + count + " could not be placed silently: " + error);
				break;
			}
		}
		// The inventory is full, or the quiet way failed: give it normally so the item is never lost.
		String error = Fx.runChecked(server, "give " + name + " " + item + " " + count);
		if (error != null) {
			ImpostorLog.warn("Kit item " + item + " x" + count + " could not be given: " + error);
		}
	}

	// ------------------------------------------------------------------ secret items

	private record SerumState(int untilTick) {
	}

	private static final String FREEZE_MODIFIER = "impostorsmp:freeze";

	private Secret.Settings secretSettings() {
		return new Secret.Settings(rules.integer("secret_freeze_seconds"), rules.integer("secret_serum_seconds"),
			rules.integer("secret_pit_depth"), rules.integer("secret_horde_range"));
	}

	/** Decides, once per impostor, whether they get a secret item, and tells them (the item itself comes later). */
	private void rollSecret(ServerPlayer player) {
		if (!rules.bool("secret_item")) {
			state.setSecret(player.getUUID(), null);
			return;
		}
		Secret.Kind kind = Secret.roll(random, rules.integer("secret_item_chance"));
		state.setSecret(player.getUUID(), kind == null ? null : kind.name());
		save();
		if (kind == null) {
			player.sendSystemMessage(Component.literal("You have no secret item this game.").withStyle(ChatFormatting.DARK_GRAY));
			return;
		}
		int delay = rules.integer("secret_item_delay_minutes");
		if (delay <= 0) {
			deliverSecret(player, kind);
		} else {
			player.sendSystemMessage(Component.literal("You WILL receive a secret item " + delay + " minutes into the game.")
				.withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
		}
	}

	/** Once a second: hands out secret items that are due. */
	private void secretSecond() {
		int due = rules.integer("secret_item_delay_minutes") * 60;
		if (state.elapsedSeconds() < due) {
			return;
		}
		for (Member m : state.members()) {
			String kindName = state.secretKind(m.id());
			if (kindName == null || state.secretGiven(m.id()) || !m.alive()) {
				continue;
			}
			ServerPlayer p = server.getPlayerList().getPlayer(m.id());
			if (p == null) {
				continue;
			}
			try {
				deliverSecret(p, Secret.Kind.valueOf(kindName));
			} catch (IllegalArgumentException e) {
				state.markSecretGiven(m.id());
			}
		}
	}

	private void deliverSecret(ServerPlayer p, Secret.Kind kind) {
		state.markSecretGiven(p.getUUID());
		save();
		Secret.Settings settings = secretSettings();
		for (String item : Secret.itemTexts(kind, settings)) {
			placeItem(p, item);
		}
		p.sendSystemMessage(Component.literal("SECRET ITEM: " + Secret.name(kind) + " (in your inventory)")
			.withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
		p.sendSystemMessage(Component.literal(Secret.howToUse(kind, settings)).withStyle(ChatFormatting.GRAY));
		p.sendSystemMessage(Component.literal("Hover over the item for the details.").withStyle(ChatFormatting.DARK_GRAY));
		Fx.sound(server, p.getGameProfile().name(), "minecraft:entity.experience_orb.pickup", 0.5, 0.8);
	}

	/** Test helper: gives the host a secret item. */
	public void giveSecretTest(ServerPlayer p, Secret.Kind kind) {
		deliverSecret(p, kind);
	}

	/** Puts an item in a free main-inventory slot (then the hotbar) without a pickup sound; gives it normally if that fails. */
	private void placeItem(ServerPlayer p, String item) {
		String name = p.getGameProfile().name();
		Inventory inventory = p.getInventory();
		int slot = -1;
		for (int s = 9; slot < 0 && s < 36; s++) {
			if (inventory.getItem(s).isEmpty()) {
				slot = s;
			}
		}
		for (int s = 0; slot < 0 && s < 9; s++) {
			if (inventory.getItem(s).isEmpty()) {
				slot = s;
			}
		}
		if (slot >= 0) {
			String error = Fx.runChecked(server, "item replace entity " + name + " " + slotName(slot) + " with " + item);
			if (error == null) {
				return;
			}
			ImpostorLog.warn("Secret item could not be placed silently: " + error);
		}
		String error = Fx.runChecked(server, "give " + name + " " + item + " 1");
		if (error != null) {
			ImpostorLog.warn("Secret item could not be given: " + error);
		}
	}

	/** Right-click with a secret item. Returns SUCCESS when the item was used, FAIL when it can't be used right now, PASS to let Minecraft carry on. */
	public InteractionResult onUseItem(Player player, InteractionHand hand) {
		if (!(player instanceof ServerPlayer p) || !rules.bool("secret_item")) {
			return InteractionResult.PASS;
		}
		ItemStack stack = p.getItemInHand(hand);
		if (stack.isEmpty()) {
			return InteractionResult.PASS;
		}
		Secret.Kind kind = Secret.kindForName(stack.getHoverName().getString());
		if (kind == null) {
			return InteractionResult.PASS;
		}
		switch (kind) {
			case PORTA_PIT, VOID_EGG -> tagEgg(p, kind); // Minecraft throws the egg; the pit comes where it lands
			case MAHORAGA -> {
				stack.shrink(1);
				useMahoraga(p);
				return InteractionResult.SUCCESS;
			}
			case HORDE -> {
				return useHorde(p, stack) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
			}
			case SERUM -> {
				if (serum.containsKey(p.getUUID())) {
					return InteractionResult.FAIL;
				}
				stack.shrink(1);
				startSerum(p);
				return InteractionResult.SUCCESS;
			}
			case LOOTBOX -> {
				stack.shrink(1);
				useLootbox(p);
				return InteractionResult.SUCCESS;
			}
			default -> { } // the arrow goes in a dispenser or crossbow; the snow is placed like a bucket
		}
		return InteractionResult.PASS;
	}

	// ---- eggs

	/** Marks the egg that was just thrown, so we know it when it lands. */
	private void tagEgg(ServerPlayer p, Secret.Kind kind) {
		String tag = kind == Secret.Kind.PORTA_PIT ? "impostorsmp_portapit" : "impostorsmp_voidegg";
		String name = p.getGameProfile().name();
		scheduler.after(1, () -> Fx.run(server, "execute at " + name
			+ " as @e[type=minecraft:egg,distance=..6,limit=1,sort=nearest,tag=!impostorsmp_portapit,tag=!impostorsmp_voidegg]"
			+ " run tag @s add " + tag));
	}

	/** An entity went away. If it was a marked egg, it just landed: dig the pit. */
	public void onEntityUnload(Entity entity, ServerLevel level) {
		// Only our marked eggs carry these tags, so no check of the entity type is needed.
		Set<String> tags = entity.entityTags();
		boolean porta = tags.contains("impostorsmp_portapit");
		if (!porta && !tags.contains("impostorsmp_voidegg")) {
			return;
		}
		String dimension = dimensionId(level);
		int minY = dimension.equals("minecraft:overworld") ? -64 : 0;
		int x = (int) Math.floor(entity.getX());
		int top = (int) Math.floor(entity.getY() - 0.5); // the block the egg landed on
		int z = (int) Math.floor(entity.getZ());
		List<String> commands = porta
			? Secret.pitCommands(dimension, x, top, z, rules.integer("secret_pit_depth"), minY)
			: Secret.voidCommands(dimension, x, top, z, minY);
		ImpostorLog.warn("Egg landed: digging a " + (porta ? "Porta-Pit" : "Void") + " at " + x + " " + top + " " + z + " in " + dimension);
		scheduler.after(1, () -> {
			for (String command : commands) {
				String error = Fx.runChecked(server, command);
				if (error != null) {
					ImpostorLog.warn("Egg pit command failed: " + error);
				}
			}
		});
	}

	// ---- Sub-Zero Powder Snow

	private static String trapKey(Level level, BlockPos pos) {
		return dimensionId(level) + ":" + pos.asLong();
	}

	/** A block was right-clicked. If it was with the special bucket, remember where the snow ends up. */
	public InteractionResult onUseBlock(Player player, InteractionHand hand, BlockHitResult hit) {
		if (!(player instanceof ServerPlayer p) || !rules.bool("secret_item")) {
			return InteractionResult.PASS;
		}
		ItemStack stack = p.getItemInHand(hand);
		if (stack.isEmpty() || !Secret.name(Secret.Kind.SUBZERO).equals(stack.getHoverName().getString())) {
			return InteractionResult.PASS;
		}
		Level level = p.level();
		BlockPos clicked = hit.getBlockPos();
		BlockPos beside = clicked.relative(hit.getDirection());
		boolean clickedWasSnow = level.getBlockState(clicked).is(Blocks.POWDER_SNOW);
		boolean besideWasSnow = level.getBlockState(beside).is(Blocks.POWDER_SNOW);
		scheduler.after(2, () -> {
			if (!clickedWasSnow && level.getBlockState(clicked).is(Blocks.POWDER_SNOW)) {
				snowTraps.add(trapKey(level, clicked));
			}
			if (!besideWasSnow && level.getBlockState(beside).is(Blocks.POWDER_SNOW)) {
				snowTraps.add(trapKey(level, beside));
			}
		});
		return InteractionResult.PASS;
	}

	/** Freezes whoever touches a marked powder snow block, and thaws people when their time is up. */
	private void tickSnowTraps() {
		int tick = server.getTickCount();
		for (Iterator<Map.Entry<UUID, Integer>> it = frozenUntil.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Integer> entry = it.next();
			if (tick >= entry.getValue()) {
				it.remove();
				thaw(entry.getKey());
			}
		}
		if (snowTraps.isEmpty()) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isSpectator() || frozenUntil.containsKey(p.getUUID())) {
				continue;
			}
			BlockPos feet = p.blockPosition();
			for (BlockPos pos : new BlockPos[] {feet, feet.above()}) {
				String key = trapKey(p.level(), pos);
				if (!snowTraps.contains(key)) {
					continue;
				}
				snowTraps.remove(key);
				if (p.level().getBlockState(pos).is(Blocks.POWDER_SNOW)) {
					freeze(p, pos);
					break;
				}
			}
		}
	}

	private void freeze(ServerPlayer p, BlockPos pos) {
		String name = p.getGameProfile().name();
		int seconds = rules.integer("secret_freeze_seconds");
		frozenUntil.put(p.getUUID(), server.getTickCount() + seconds * 20);
		// Slowness 255 stops walking; a jump strength of zero stops jumping (slowness alone doesn't).
		Fx.run(server, "effect give " + name + " minecraft:slowness " + seconds + " 255 false");
		Fx.run(server, "attribute " + name + " minecraft:jump_strength modifier remove " + FREEZE_MODIFIER);
		Fx.run(server, "attribute " + name + " minecraft:jump_strength modifier add " + FREEZE_MODIFIER + " -1 add_multiplied_total");
		Fx.run(server, "execute in " + dimensionId(p.level()) + " run setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " minecraft:air");
		Fx.sound(server, name, "minecraft:block.glass.break", 1.0, 0.6);
		p.sendSystemMessage(Component.literal("You are frozen solid for " + seconds + " seconds!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), true);
		p.sendSystemMessage(Component.literal("You are frozen solid for " + seconds + " seconds!").withStyle(ChatFormatting.AQUA));
	}

	private void thaw(UUID id) {
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		if (p == null) {
			return; // the modifier is removed when they rejoin
		}
		String name = p.getGameProfile().name();
		Fx.run(server, "attribute " + name + " minecraft:jump_strength modifier remove " + FREEZE_MODIFIER);
		Fx.run(server, "effect clear " + name + " minecraft:slowness");
		p.sendSystemMessage(Component.literal("You thawed out.").withStyle(ChatFormatting.AQUA));
	}

	// ---- horns

	/** Mahoraga Horn: a ravager, wither or warden on top of the user. A wither arms every innocent with a bow. */
	private void useMahoraga(ServerPlayer p) {
		Secret.Mob mob = Secret.mahoragaRoll(random);
		String at = p.getGameProfile().name();
		// A warden with nobody to hunt just digs back down and vanishes, so it starts out angry at everybody
		// close by (including the user: it is on top of them).
		List<String> angry = new ArrayList<>();
		if (mob == Secret.Mob.WARDEN) {
			for (ServerPlayer other : server.getPlayerList().getPlayers()) {
				if (!other.isSpectator() && other.level().dimension().equals(p.level().dimension()) && p.distanceTo(other) <= 64) {
					angry.add(Secret.uuidTag(other.getUUID().getMostSignificantBits(), other.getUUID().getLeastSignificantBits()));
				}
			}
		}
		for (String command : Secret.summonCommands(mob, random, angry)) {
			Fx.run(server, "execute at " + at + " run " + command);
		}
		p.sendSystemMessage(Component.literal("The horn answers: " + Secret.mobLabel(mob) + " appears on top of you!")
			.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		if (mob == Secret.Mob.WITHER) {
			for (Member m : state.members()) {
				ServerPlayer other = server.getPlayerList().getPlayer(m.id());
				if (other == null || m.role() != Role.INNOCENT || !m.alive()) {
					continue;
				}
				String name = other.getGameProfile().name();
				Fx.run(server, "give " + name + " minecraft:bow 1");
				Fx.run(server, "give " + name + " minecraft:arrow 64");
				other.sendSystemMessage(Component.literal("A WITHER has been summoned! You were given a bow and arrows.")
					.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			}
		}
	}

	/** Horde Horn: three uses, each at the closest innocent, who must be close. Returns false if nobody was close enough. */
	private boolean useHorde(ServerPlayer p, ItemStack stack) {
		int range = rules.integer("secret_horde_range");
		ServerPlayer target = nearestInnocent(p, range);
		if (target == null) {
			p.sendSystemMessage(Component.literal("No innocent is within " + range + " blocks. The horn stays silent.")
				.withStyle(ChatFormatting.RED), true);
			return false;
		}
		Secret.Mob mob = Secret.hordeRoll(random);
		String at = target.getGameProfile().name();
		for (String command : Secret.summonCommands(mob, random)) {
			Fx.run(server, "execute at " + at + " run " + command);
		}
		int used = stack.getDamageValue() + 1;
		if (used >= stack.getMaxDamage()) {
			stack.shrink(1);
		} else {
			stack.setDamageValue(used);
		}
		p.sendSystemMessage(Component.literal("The horde rises around the closest innocent: " + Secret.mobLabel(mob) + ".")
			.withStyle(ChatFormatting.DARK_GREEN));
		return true;
	}

	/** The closest living innocent within {@code range} blocks, in the same dimension, or null. */
	private ServerPlayer nearestInnocent(ServerPlayer p, int range) {
		ServerPlayer best = null;
		double bestDistance = range;
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other == p || other.isSpectator() || !p.level().dimension().equals(other.level().dimension())) {
				continue;
			}
			Optional<Member> om = state.member(other.getUUID());
			if (om.isEmpty() || !om.get().alive() || om.get().role() != Role.INNOCENT) {
				continue;
			}
			double distance = p.distanceTo(other);
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = other;
			}
		}
		return best;
	}

	// ---- Lootbox

	private void useLootbox(ServerPlayer p) {
		String[] label = new String[1];
		String name = p.getGameProfile().name();
		for (Secret.Drop drop : Secret.lootbox(random, label)) {
			String error = Fx.runChecked(server, "give " + name + " " + drop.itemText() + " " + drop.count());
			if (error != null) {
				ImpostorLog.warn("Lootbox item " + drop.id() + " could not be given: " + error);
			}
		}
		p.sendSystemMessage(Component.literal("Lootbox: " + label[0] + "!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
	}

	// ---- Spectral Serum

	/** True while this player is a spectator because of the serum (they are not "dead"). */
	boolean inSerum(UUID id) {
		return serum.containsKey(id);
	}

	private void startSerum(ServerPlayer p) {
		int seconds = rules.integer("secret_serum_seconds");
		serum.put(p.getUUID(), new SerumState(server.getTickCount() + seconds * 20));
		Fx.run(server, "gamemode spectator " + p.getGameProfile().name());
		p.sendSystemMessage(Component.literal("Spectral Serum: you are a spectator for " + seconds + " seconds. ")
			.withStyle(ChatFormatting.LIGHT_PURPLE)
			.append(chatButton("End early", ChatFormatting.GREEN, "imp serum end", "Go back to where you drank it right now.")));
	}

	/** The player clicked [End early] (or typed /imp serum end). */
	public void endSerumEarly(ServerPlayer p) {
		if (serum.containsKey(p.getUUID())) {
			if (insideBlocks(p)) {
				p.sendSystemMessage(Component.literal("You are inside blocks. Fly into open space first, then end it.").withStyle(ChatFormatting.RED));
			} else {
				endSerum(p.getUUID());
			}
		} else {
			p.sendSystemMessage(Component.literal("You are not under the serum.").withStyle(ChatFormatting.RED));
		}
	}

	private void tickSerum() {
		int tick = server.getTickCount();
		for (UUID id : new ArrayList<>(serum.keySet())) {
			if (tick < serum.get(id).untilTick()) {
				continue;
			}
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && insideBlocks(p)) {
				// Time is up, but turning back into a normal player inside a wall would suffocate them.
				// They stay a spectator until they are in open space, then it ends by itself.
				if (serumWarned.add(id)) {
					p.sendSystemMessage(Component.literal("The serum is wearing off. Fly into open space and it will end.")
						.withStyle(ChatFormatting.LIGHT_PURPLE));
				}
				continue;
			}
			endSerum(id);
		}
	}

	/** True if the player's feet or head are inside a solid block. */
	private static boolean insideBlocks(ServerPlayer p) {
		Level level = p.level();
		BlockPos feet = p.blockPosition();
		BlockPos head = feet.above();
		return !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
			|| !level.getBlockState(head).getCollisionShape(level, head).isEmpty();
	}

	private void endSerum(UUID id) {
		SerumState saved = serum.remove(id);
		serumWarned.remove(id);
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		if (saved == null || p == null) {
			return; // offline: they are put back in survival when they rejoin
		}
		String name = p.getGameProfile().name();
		Fx.run(server, "gamemode survival " + name);
		Fx.run(server, "effect give " + name + " minecraft:slow_falling 10 0 true"); // so ending it high up is survivable
		p.sendSystemMessage(Component.literal("The serum wears off. You stay where you are.").withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	/** Ends every serum and freeze (the game stopped or finished). */
	private void endAllSecretEffects() {
		for (UUID id : new ArrayList<>(serum.keySet())) {
			endSerum(id);
		}
		for (UUID id : new ArrayList<>(frozenUntil.keySet())) {
			frozenUntil.remove(id);
			thaw(id);
		}
		snowTraps.clear();
	}

	/**
	 * One Shot One Kill: an arrow with that name that comes out of a DISPENSER or a CROSSBOW gets absurd damage
	 * and can't be picked up. Arrows from a bow are left alone. Each arrow is looked at once.
	 */
	private void oneShotArrows() {
		String seen = "tag=!impostorsmp_seen";
		String oneShot = "@e[type=minecraft:arrow," + seen
			+ ",nbt={item:{components:{\"minecraft:custom_data\":{impostorsmp:\"one_shot\"}}}}]";
		Fx.run(server, "execute as @e[type=minecraft:arrow," + seen + ",nbt={weapon:{id:\"minecraft:bow\"}}] run tag @s add impostorsmp_seen");
		Fx.run(server, "execute as " + oneShot + " run data modify entity @s damage set value 1000.0d");
		Fx.run(server, "execute as " + oneShot + " run data modify entity @s pickup set value 0b");
		Fx.run(server, "execute as @e[type=minecraft:arrow," + seen + "] run tag @s add impostorsmp_seen");
	}

	// ------------------------------------------------------------------ impostor compasses

	private static final String COMPASS_NAME = "Impostor Compass";

	/** Minutes between the offer and the stronghold compass; 0 when that wait is switched off. */
	private int compassWait() {
		return rules.bool("compass_stronghold_wait") ? rules.integer("compass_wait_minutes") : 0;
	}

	private void clearCompassCache() {
		lastCompassTarget.clear();
		strongholdPos = null;
		strongholdSearched = false;
	}

	/** Once a second while the game runs: sends the offer, and hands out stronghold compasses that are due. */
	private void compassSecond() {
		int elapsed = state.elapsedSeconds();
		int offer = rules.integer("compass_minutes");
		int wait = compassWait();
		for (Member m : state.members()) {
			if (m.role() != Role.IMPOSTOR || !m.alive()) {
				continue;
			}
			ServerPlayer p = server.getPlayerList().getPlayer(m.id());
			if (p == null) {
				continue;
			}
			int stage = state.compassStage(m.id());
			if (CompassPlan.offerDue(stage, elapsed, offer)) {
				state.setCompassStage(m.id(), CompassPlan.OFFERED);
				save();
				sendCompassOffer(p);
			} else if (CompassPlan.strongholdDue(stage, elapsed, offer, wait)) {
				state.setCompassStage(m.id(), CompassPlan.STRONGHOLD);
				save();
				giveStrongholdCompass(p);
			}
		}
	}

	private void sendCompassOffer(ServerPlayer p) {
		int wait = compassWait();
		p.sendSystemMessage(Component.literal("Your impostor compass is ready. Pick ONE:").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		MutableComponent line = Component.literal("  ")
			.append(chatButton("Nearest player", ChatFormatting.GREEN, "imp compass player",
				"Points at the closest other player. Yours right now."))
			.append(Component.literal("   "))
			.append(chatButton("Stronghold", ChatFormatting.AQUA, "imp compass stronghold",
				wait > 0 ? "Points at the stronghold. Arrives " + wait + " minutes after the offer."
					: "Points at the stronghold. Yours right now."));
		p.sendSystemMessage(line);
	}

	private static Component chatButton(String label, ChatFormatting color, String command, String tooltip) {
		return Component.literal("[" + label + "]").setStyle(Style.EMPTY.withColor(color)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(tooltip))));
	}

	/** A player picked a compass (clicked a button or typed /imp compass player|stronghold). */
	public void chooseCompass(ServerPlayer p, CompassPlan.Choice choice) {
		Optional<Member> member = state.member(p.getUUID());
		if (state.phase() != Phase.RUNNING || member.isEmpty() || member.get().role() != Role.IMPOSTOR || !member.get().alive()) {
			p.sendSystemMessage(Component.literal("Only a living impostor in a running game can pick a compass.").withStyle(ChatFormatting.RED));
			return;
		}
		if (!rules.bool("compass")) {
			p.sendSystemMessage(Component.literal("Compasses are switched off.").withStyle(ChatFormatting.RED));
			return;
		}
		int offer = rules.integer("compass_minutes");
		int wait = compassWait();
		int stage = state.compassStage(p.getUUID());
		switch (CompassPlan.choose(stage, choice, state.elapsedSeconds(), offer, wait)) {
			case NOT_YET -> p.sendSystemMessage(Component.literal("Not yet. The compass unlocks after " + offer + " minutes.").withStyle(ChatFormatting.RED));
			case ALREADY_CHOSEN -> {
				if (stage == CompassPlan.NEAREST && !hasCompass(p)) {
					giveNearestCompass(p);
				} else if (stage == CompassPlan.STRONGHOLD && !hasCompass(p)) {
					giveStrongholdCompass(p);
				} else if (stage == CompassPlan.WAITING) {
					int left = CompassPlan.secondsUntilStronghold(state.elapsedSeconds(), offer, wait);
					p.sendSystemMessage(Component.literal("Your stronghold compass arrives in " + AccuseGate.format(left) + ".").withStyle(ChatFormatting.GRAY));
				} else {
					p.sendSystemMessage(Component.literal("You already chose your compass.").withStyle(ChatFormatting.RED));
				}
			}
			case GIVE_NEAREST -> {
				state.setCompassStage(p.getUUID(), CompassPlan.NEAREST);
				save();
				giveNearestCompass(p);
			}
			case WAIT_FOR_STRONGHOLD -> {
				state.setCompassStage(p.getUUID(), CompassPlan.WAITING);
				save();
				int left = CompassPlan.secondsUntilStronghold(state.elapsedSeconds(), offer, wait);
				p.sendSystemMessage(Component.literal("You will get the stronghold compass in " + AccuseGate.format(left) + ".").withStyle(ChatFormatting.AQUA));
			}
			case GIVE_STRONGHOLD -> {
				state.setCompassStage(p.getUUID(), CompassPlan.STRONGHOLD);
				save();
				giveStrongholdCompass(p);
			}
		}
	}

	/** Test helper: jumps the game clock to the moment the compass is offered. */
	public void skipToCompass() {
		if (state.phase() == Phase.RUNNING) {
			state.setElapsedSeconds(rules.integer("compass_minutes") * 60 - 1);
			save();
		}
	}

	private void giveNearestCompass(ServerPlayer p) {
		double[] target = compassTarget(p);
		int x = (int) Math.floor(target != null ? target[0] : p.getX());
		int y = (int) Math.floor(target != null ? target[1] : p.getY());
		int z = (int) Math.floor(target != null ? target[2] : p.getZ());
		placeCompass(p, compassItem("nearest player", x, y, z, dimensionId(p.level())));
		lastCompassTarget.remove(p.getUUID());
		p.sendSystemMessage(Component.literal(target != null
			? "Nearest-player compass added to your inventory. It updates every few seconds."
			: "Nearest-player compass added. Nobody else is in this dimension right now; it will lock on when somebody is.")
			.withStyle(ChatFormatting.GREEN));
	}

	private void giveStrongholdCompass(ServerPlayer p) {
		BlockPos pos = strongholdPos();
		if (pos == null) {
			p.sendSystemMessage(Component.literal("The stronghold could not be found, so no compass could be made. Tell the host.").withStyle(ChatFormatting.RED));
			return;
		}
		placeCompass(p, compassItem("stronghold", pos.getX(), pos.getY(), pos.getZ(), "minecraft:overworld"));
		p.sendSystemMessage(Component.literal("Stronghold compass added to your inventory. It only works in the Overworld.").withStyle(ChatFormatting.AQUA));
	}

	private BlockPos strongholdPos() {
		if (!strongholdSearched) {
			strongholdSearched = true;
			try {
				strongholdPos = server.overworld().findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, BlockPos.ZERO, 100, false);
			} catch (RuntimeException e) {
				ImpostorLog.warn("Could not locate the stronghold: " + e);
			}
		}
		return strongholdPos;
	}

	private static String compassItem(String label, int x, int y, int z, String dimension) {
		return "minecraft:compass[minecraft:lodestone_tracker={target:{pos:[I;" + x + "," + y + "," + z + "],dimension:\""
			+ dimension + "\"},tracked:false},minecraft:item_name={text:\"" + COMPASS_NAME + " (" + label + ")\",color:\"gold\"}]";
	}

	private static String dimensionId(Level level) {
		if (level.dimension().equals(Level.NETHER)) {
			return "minecraft:the_nether";
		}
		if (level.dimension().equals(Level.END)) {
			return "minecraft:the_end";
		}
		return "minecraft:overworld";
	}

	private static String slotName(int slot) {
		return slot < 9 ? "hotbar." + slot : "inventory." + (slot - 9);
	}

	private static boolean isImpostorCompass(ItemStack stack) {
		return stack.is(Items.COMPASS) && stack.getHoverName().getString().startsWith(COMPASS_NAME);
	}

	private static int compassSlot(ServerPlayer p) {
		Inventory inventory = p.getInventory();
		for (int slot = 0; slot < 36; slot++) {
			if (isImpostorCompass(inventory.getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}

	private static boolean hasCompass(ServerPlayer p) {
		return compassSlot(p) >= 0;
	}

	/** Puts the compass in a free main-inventory slot (then the hotbar) without a pickup sound; gives it normally if that fails. */
	private void placeCompass(ServerPlayer p, String item) {
		String name = p.getGameProfile().name();
		int existing = compassSlot(p);
		Inventory inventory = p.getInventory();
		int slot = existing;
		for (int s = 9; slot < 0 && s < 36; s++) {
			if (inventory.getItem(s).isEmpty()) {
				slot = s;
			}
		}
		for (int s = 0; slot < 0 && s < 9; s++) {
			if (inventory.getItem(s).isEmpty()) {
				slot = s;
			}
		}
		if (slot >= 0) {
			String error = Fx.runChecked(server, "item replace entity " + name + " " + slotName(slot) + " with " + item);
			if (error == null) {
				return;
			}
			ImpostorLog.warn("Compass could not be placed silently: " + error);
			p.sendSystemMessage(Component.literal("Compass problem (silent placing): " + error).withStyle(ChatFormatting.RED));
		}
		String error = Fx.runChecked(server, "give " + name + " " + item + " 1");
		if (error != null) {
			ImpostorLog.warn("Compass could not be given: " + error);
			p.sendSystemMessage(Component.literal("Compass problem (give): " + error).withStyle(ChatFormatting.RED));
		}
	}

	/** The closest living, non-spectating player to {@code p} in the same dimension, or null. */
	private ServerPlayer nearestOther(ServerPlayer p) {
		List<ServerPlayer> candidates = new ArrayList<>();
		List<double[]> points = new ArrayList<>();
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (other == p || other.isSpectator() || !p.level().dimension().equals(other.level().dimension())) {
				continue;
			}
			Optional<Member> om = state.member(other.getUUID());
			if (om.isEmpty() || !om.get().alive()) {
				continue;
			}
			candidates.add(other);
			points.add(new double[] {other.getX(), other.getY(), other.getZ()});
		}
		int index = CompassPlan.nearest(p.getX(), p.getY(), p.getZ(), points);
		return index < 0 ? null : candidates.get(index);
	}

	/** Re-aims every nearest-player compass at the closest other player. Only replaces the item when the target moved. */
	private void refreshCompasses() {
		for (Member m : state.members()) {
			if (m.role() != Role.IMPOSTOR || !m.alive() || state.compassStage(m.id()) != CompassPlan.NEAREST) {
				continue;
			}
			ServerPlayer p = server.getPlayerList().getPlayer(m.id());
			if (p == null) {
				continue;
			}
			int slot = compassSlot(p);
			double[] target = compassTarget(p);
			if (slot < 0 || target == null) {
				if (decoy != null) {
					p.sendSystemMessage(Component.literal("Compass test: " + (slot < 0 ? "no Impostor Compass found in your inventory." : "no target.")).withStyle(ChatFormatting.RED), true);
				}
				continue;
			}
			int x = (int) Math.floor(target[0]);
			int y = (int) Math.floor(target[1]);
			int z = (int) Math.floor(target[2]);
			if (decoy != null) {
				long dist = Math.round(Math.sqrt(Math.pow(p.getX() - target[0], 2) + Math.pow(p.getZ() - target[2], 2)));
				p.sendSystemMessage(Component.literal("Compass target " + x + " " + y + " " + z + " | " + dist + " blocks away").withStyle(ChatFormatting.YELLOW), true);
			}
			String key = dimensionId(p.level()) + ":" + x + ":" + z;
			if (key.equals(lastCompassTarget.get(m.id()))) {
				continue;
			}
			lastCompassTarget.put(m.id(), key);
			String error = Fx.runChecked(server, "item replace entity " + p.getGameProfile().name() + " " + slotName(slot)
				+ " with " + compassItem("nearest player", x, y, z, dimensionId(p.level())));
			if (error != null) {
				ImpostorLog.warn("Compass could not be updated: " + error);
				p.sendSystemMessage(Component.literal("Compass problem (update): " + error).withStyle(ChatFormatting.RED));
			}
		}
	}

	/** Where the nearest-player compass should point for {@code p}: the closest other player, or the test decoy. */
	private double[] compassTarget(ServerPlayer p) {
		ServerPlayer other = nearestOther(p);
		if (other != null) {
			return new double[] {other.getX(), other.getY(), other.getZ()};
		}
		if (decoy != null && p.level().dimension().equals(Level.OVERWORLD)) {
			return decoy.clone();
		}
		return null;
	}

	/** Solo test: a glowing armor stand that walks in a circle around the host; the compass should follow it. */
	public void startCompassDecoy(ServerPlayer host) {
		decoyCenter = new double[] {host.getX(), host.getY(), host.getZ()};
		decoyAngle = 0;
		decoy = new double[] {host.getX() + 30, host.getY(), host.getZ()};
		Fx.run(server, "execute as @e[tag=impostorsmp_decoy] run kill @s");
		Fx.run(server, "summon armor_stand " + decoy[0] + " " + decoy[1] + " " + decoy[2]
			+ " {Tags:[\"impostorsmp_decoy\"],NoGravity:1b,Glowing:1b,CustomName:{text:\"Compass decoy\"},CustomNameVisible:1b}");
		lastCompassTarget.clear();
		host.sendSystemMessage(Component.literal("Decoy placed 30 blocks east of you. It walks in a circle. Your compass should follow the glowing armor stand.").withStyle(ChatFormatting.GREEN));
	}

	private void tickDecoy() {
		if (decoy == null) {
			return;
		}
		decoyAngle += 0.02;
		decoy[0] = decoyCenter[0] + Math.cos(decoyAngle) * 30;
		decoy[2] = decoyCenter[2] + Math.sin(decoyAngle) * 30;
		Fx.run(server, "execute as @e[tag=impostorsmp_decoy] run tp @s " + decoy[0] + " " + decoy[1] + " " + decoy[2]);
	}

	public void stopCompassDecoy() {
		if (decoy != null) {
			Fx.run(server, "execute as @e[tag=impostorsmp_decoy] run kill @s");
		}
		decoy = null;
	}

	private void beginRunning() {
		state.startRunning();
		save();
		checkForWinner(); // in case somebody died during the countdown
	}

	/** Cancels the current game without a winner. */
	public void stop() {
		stopCompassDecoy();
		endAllSecretEffects();
		scheduler.clear();
		state.reset();
		lastAccuse.clear();
		clearCompassCache();
		save();
		Fx.run(server, "title @a clear");
		broadcast(Component.literal("The game was cancelled.").withStyle(ChatFormatting.GRAY));
	}

	// ------------------------------------------------------------------ deaths and winning

	void onDeath(LivingEntity entity, DamageSource source) {
		if (entity.getClass().getSimpleName().equals("EnderDragon")) {
			if (state.phase() == Phase.RUNNING && rules.bool("dragon_wins")) {
				finish(Outcome.INNOCENTS, "The Ender Dragon has been slain!");
			}
			return;
		}
		if (!(entity instanceof ServerPlayer victim)) {
			return;
		}

		if (rules.bool("death_sound")) {
			Fx.sound(server, "@a", "minecraft:entity.wither.spawn", 1.0, 1.0);
		}

		Optional<Member> victimMember = state.member(victim.getUUID());
		if (victimMember.isEmpty() || !state.markDead(victim.getUUID())) {
			return;
		}
		save();

		if (rules.bool("anti_rdm") && victimMember.get().role() == Role.INNOCENT) {
			Entity attacker = source.getEntity();
			if (attacker instanceof ServerPlayer killer && killer != victim) {
				Optional<Member> killerMember = state.member(killer.getUUID());
				if (killerMember.isPresent() && killerMember.get().role() == Role.INNOCENT && killerMember.get().alive()) {
					punishInnocentKill(killer);
				}
			}
		}

		checkForWinner();
	}

	private void punishInnocentKill(ServerPlayer killer) {
		int strikes = state.addStrike(killer.getUUID());
		int seconds = rules.integer("anti_rdm_seconds");
		int level = rules.integer("anti_rdm_level");
		if (rules.bool("anti_rdm_escalate") && strikes > 1) {
			seconds = (int) Math.min(3600L, (long) seconds << Math.min(strikes - 1, 4));
			level = Math.min(5, level + strikes - 1);
		}
		save();
		applyPunishment(killer, seconds, level);
		String again = strikes > 1 ? " (offence number " + strikes + ")" : "";
		killer.sendSystemMessage(Component.literal("You killed another innocent" + again + ". You are punished for "
			+ seconds + " seconds.").withStyle(ChatFormatting.RED));
	}

	/** Slowness, plus Weakness / Mining Fatigue / Glowing if those rules are on. */
	private void applyPunishment(ServerPlayer player, int seconds, int slownessLevel) {
		String name = player.getGameProfile().name();
		Fx.run(server, "effect give " + name + " minecraft:slowness " + seconds + " " + (slownessLevel - 1) + " true");
		if (rules.bool("anti_rdm_weakness")) {
			Fx.run(server, "effect give " + name + " minecraft:weakness " + seconds + " " + (slownessLevel >= 4 ? 1 : 0) + " true");
			Fx.run(server, "effect give " + name + " minecraft:mining_fatigue " + seconds + " 0 true");
		}
		if (rules.bool("anti_rdm_glowing")) {
			Fx.run(server, "effect give " + name + " minecraft:glowing " + seconds + " 0 true");
		}
	}

	// ------------------------------------------------------------------ call-outs

	public void accuse(ServerPlayer accuser, ServerPlayer target) {
		if (!rules.bool("accuse")) {
			accuser.sendSystemMessage(Component.literal("Call-outs are turned off.").withStyle(ChatFormatting.GRAY));
			return;
		}
		int left = AccuseGate.secondsLeft(lastAccuse.get(accuser.getUUID()), server.getTickCount(),
			rules.integer("accuse_cooldown_minutes"));
		if (left > 0) {
			accuser.sendSystemMessage(Component.literal("You can accuse again in " + AccuseGate.format(left) + ".")
				.withStyle(ChatFormatting.GRAY));
			return;
		}
		if (rules.bool("accuse_need_close")) {
			boolean sameDimension = accuser.level().dimension().equals(target.level().dimension());
			if (!AccuseGate.closeEnough(sameDimension, sameDimension ? accuser.distanceTo(target) : 0, rules.integer("accuse_range"))) {
				accuser.sendSystemMessage(Component.literal("You have to be within " + rules.integer("accuse_range")
					+ " blocks of somebody to accuse them.").withStyle(ChatFormatting.GRAY));
				return;
			}
		}
		AccuseResult r = state.accuse(accuser.getUUID(), target.getUUID(), rules.integer("accuse_votes"));
		switch (r.status()) {
			case VOTE, EXPOSED, CLEARED -> lastAccuse.put(accuser.getUUID(), server.getTickCount());
			default -> {
			}
		}
		String accuserName = accuser.getGameProfile().name();
		String targetName = target.getGameProfile().name();
		switch (r.status()) {
			case NOT_RUNNING -> accuser.sendSystemMessage(Component.literal("There is no game running.").withStyle(ChatFormatting.GRAY));
			case ACCUSER_OUT -> accuser.sendSystemMessage(Component.literal("Only living players in the game can accuse.").withStyle(ChatFormatting.GRAY));
			case TARGET_INVALID -> accuser.sendSystemMessage(Component.literal("You can't accuse that player.").withStyle(ChatFormatting.GRAY));
			case ALREADY_KNOWN -> accuser.sendSystemMessage(Component.literal(targetName + "'s role is already known.").withStyle(ChatFormatting.GRAY));
			case VOTE -> {
				save();
				broadcast(Component.literal(accuserName + " accuses " + targetName + " of being an impostor. ("
					+ r.votes() + "/" + r.needed() + ")").withStyle(ChatFormatting.GOLD));
			}
			case EXPOSED -> {
				save();
				broadcast(Component.literal(targetName + " has been called out and IS an IMPOSTOR!")
					.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
				Fx.times(server, "@a", 5, 60, 20);
				Fx.subtitle(server, "@a", Fx.text("has been exposed as an impostor", "gray", false));
				Fx.title(server, "@a", Fx.text(targetName, "red", true));
				Fx.sound(server, "@a", "minecraft:entity.ender_dragon.growl", 1.0, 1.0);
			}
			case CLEARED -> {
				save();
				broadcast(Component.literal(targetName + " was falsely accused. They are INNOCENT.")
					.withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
				Fx.times(server, "@a", 5, 60, 20);
				Fx.subtitle(server, "@a", Fx.text("was falsely accused and is innocent", "gray", false));
				Fx.title(server, "@a", Fx.text(targetName, "green", true));
				Fx.sound(server, "@a", "minecraft:block.note_block.bass", 1.0, 0.5);
				if (rules.bool("false_accuse_punish")) {
					int seconds = rules.integer("false_accuse_seconds");
					for (UUID id : r.accusers()) {
						ServerPlayer p = server.getPlayerList().getPlayer(id);
						if (p != null) {
							applyPunishment(p, seconds, rules.integer("anti_rdm_level"));
							p.sendSystemMessage(Component.literal("Your accusation was false. You are punished for "
								+ seconds + " seconds.").withStyle(ChatFormatting.RED));
						}
					}
				}
			}
		}
	}

	public void clearAccusation(ServerPlayer accuser) {
		boolean had = state.clearVote(accuser.getUUID());
		if (had) {
			save();
		}
		accuser.sendSystemMessage(Component.literal(had ? "You took back your accusation." : "You have no accusation to take back.")
			.withStyle(ChatFormatting.GRAY));
	}

	private void checkForWinner() {
		switch (state.evaluate(rules.bool("parity_win"))) {
			case INNOCENTS -> finish(Outcome.INNOCENTS, "All the impostors have been eliminated!");
			case IMPOSTORS -> finish(Outcome.IMPOSTORS,
					rules.bool("parity_win") && state.alive(Role.INNOCENT) > 0 ? "The impostors have caught up with the innocents!" : "Every innocent is dead!");
			default -> {
			}
		}
	}

	private void finish(Outcome outcome, String reason) {
		endAllSecretEffects();
		state.finish(outcome);
		save();

		boolean innocents = outcome == Outcome.INNOCENTS;
		String names = impostorNames();
		Fx.times(server, "@a", 10, 100, 30);
		Fx.subtitle(server, "@a", Fx.text("The impostors were " + names, "gray", false));
		Fx.title(server, "@a", Fx.text(innocents ? "INNOCENTS WIN" : "IMPOSTORS WIN", innocents ? "green" : "red", true));
		Fx.sound(server, "@a", innocents ? "minecraft:ui.toast.challenge_complete" : "minecraft:entity.ender_dragon.growl",
			1.0, 1.0);
		broadcast(Component.literal(reason).withStyle(innocents ? ChatFormatting.GREEN : ChatFormatting.RED));
		broadcast(Component.literal("The impostors were: " + names).withStyle(ChatFormatting.GRAY));
	}

	private String impostorNames() {
		StringBuilder sb = new StringBuilder();
		for (Member m : state.withRole(Role.IMPOSTOR)) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(m.name());
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ players coming and going

	/** Called when somebody joins or respawns: dead players (and late joiners during a game) become spectators. */
	void onPlayerReady(ServerPlayer player) {
		if (rules.bool("secret_item")) {
			UUID id = player.getUUID();
			if (!frozenUntil.containsKey(id)) {
				// A server restart mid-freeze would otherwise leave the no-jump modifier on them for good.
				Fx.run(server, "attribute " + player.getGameProfile().name() + " minecraft:jump_strength modifier remove " + FREEZE_MODIFIER);
			}
			Optional<Member> own = state.member(id);
			if (state.phase() == Phase.RUNNING && own.isPresent() && own.get().alive() && player.isSpectator() && !serum.containsKey(id)) {
				Fx.run(server, "gamemode survival " + player.getGameProfile().name()); // a serum that was cut short by a restart
			}
		}
		int lobbyY = rules.lobbyY();
		if (lobbyY >= 0 && state.phase() != Phase.RUNNING && state.phase() != Phase.COUNTDOWN) {
			// Somebody joining (or respawning) while the lobby is up goes straight into it.
			sendToLobby(player.getGameProfile().name(), lobbyY);
			return;
		}
		if (!rules.bool("spectate_on_death")) {
			return;
		}
		if (state.phase() != Phase.RUNNING && state.phase() != Phase.COUNTDOWN) {
			return;
		}
		Optional<Member> member = state.member(player.getUUID());
		boolean mustSpectate = member.map(m -> !m.alive()).orElse(true);
		if (!mustSpectate || player.isSpectator()) {
			return;
		}
		Fx.run(server, "gamemode spectator " + player.getGameProfile().name());
		if (member.isEmpty()) {
			player.sendSystemMessage(Component.literal("A game is in progress, so you are spectating.")
				.withStyle(ChatFormatting.GRAY));
		}
	}

	// ------------------------------------------------------------------ the sky lobby

	private static final int[][] LOBBY_CORNERS = {{-36, -13}, {12, -13}, {-36, 80}, {12, 80}};

	/** Builds the lobby (after the chunks are loaded), moves everybody into it and makes it the world spawn. */
	public void createLobby() {
		if (state.phase() == Phase.COUNTDOWN || state.phase() == Phase.RUNNING) {
			throw new IllegalStateException("A game is running. Use /imp stop first.");
		}
		int y = rules.integer("lobby_y");
		int oldY = rules.lobbyY();
		if (oldY >= 0 && oldY != y) {
			removeLobby(oldY); // the height changed: take the old one down first
		}
		Fx.run(server, "forceload add " + LobbyPlan.FORCELOAD_AREA);
		broadcast(Component.literal("Building the lobby...").withStyle(ChatFormatting.GRAY));
		waitForLobbyChunks(y, 0);
	}

	private void waitForLobbyChunks(int y, int attempt) {
		boolean ready = true;
		for (int[] corner : LOBBY_CORNERS) {
			ready &= server.overworld().hasChunkAt(new BlockPos(corner[0], y, corner[1]));
		}
		if (ready) {
			buildLobby(y);
			return;
		}
		if (attempt >= 60) {
			Fx.run(server, "forceload remove " + LobbyPlan.FORCELOAD_AREA);
			broadcast(Component.literal("The lobby area would not load. Try /imp lobby again.").withStyle(ChatFormatting.RED));
			return;
		}
		scheduler.after(10, () -> waitForLobbyChunks(y, attempt + 1));
	}

	private void buildLobby(int y) {
		for (String command : LobbyPlan.build(y, rules)) {
			Fx.run(server, command);
		}
		rules.setLobbyY(y);
		Fx.run(server, "setworldspawn 0 " + y + " 0");
		Fx.run(server, "gamerule spawn_radius 0");
		Fx.run(server, "gamemode adventure @a");
		Fx.run(server, "execute in minecraft:overworld run tp @a 0.5 " + y + " 0.5");
		Fx.run(server, "effect give @a minecraft:resistance infinite 4 true");
		Fx.run(server, "effect give @a minecraft:saturation infinite 0 true");
		broadcast(Component.literal("The lobby is ready. Press the green button when everybody is here.")
			.withStyle(ChatFormatting.GREEN));
	}

	private void sendToLobby(String name, int y) {
		Fx.run(server, "gamemode adventure " + name);
		Fx.run(server, "execute in minecraft:overworld run tp " + name + " 0.5 " + y + " 0.5");
		Fx.run(server, "effect give " + name + " minecraft:resistance infinite 4 true");
		Fx.run(server, "effect give " + name + " minecraft:saturation infinite 0 true");
	}

	/**
	 * Deletes the lobby (blocks, texts, button) and stops keeping its chunks loaded. Returns null if it worked,
	 * otherwise the reason it did not. The lobby is only forgotten once it is really gone.
	 */
	private String removeLobby(int y) {
		String error = null;
		for (String command : LobbyPlan.remove(y)) {
			String e = Fx.runChecked(server, command);
			boolean harmless = e != null && (e.contains("No blocks") || e.contains("Could not set") || e.contains("No entity"));
			if (e != null && !harmless && error == null) {
				error = e;
			}
		}
		ServerLevel level = server.overworld();
		BlockPos[] samples = {new BlockPos(0, y - 1, 0), new BlockPos(0, y + 9, 3), new BlockPos(0, y - 1, 30),
			new BlockPos(0, y + 1, -13)};
		for (BlockPos pos : samples) {
			if (!level.getBlockState(pos).isAir()) {
				return error != null ? error : "blocks are still there at " + pos.toShortString();
			}
		}
		Fx.run(server, "forceload remove " + LobbyPlan.FORCELOAD_AREA);
		course.clear();
		rules.setLobbyY(-1);
		return null;
	}

	/** Waits until nobody is up in the lobby, then removes it, and says so if that did not work. */
	private void finishLobbyRemoval(int y, int attempt) {
		if (rules.lobbyY() < 0) {
			return; // already gone
		}
		if (anyoneInLobby(y)) {
			if (attempt < 5) {
				scheduler.after(20, () -> finishLobbyRemoval(y, attempt + 1));
				return;
			}
			broadcast(Component.literal("Somebody is still in the lobby, so it was kept. Use /imp lobby remove once everybody is out.")
				.withStyle(ChatFormatting.RED));
			return;
		}
		String error = removeLobby(y);
		if (error != null) {
			broadcast(Component.literal("The lobby could not be removed: " + error + ". Try /imp lobby remove.")
				.withStyle(ChatFormatting.RED));
		}
	}

	// ------------------------------------------------------------------ the obstacle course timer

	private void tickCourse(int lobbyY) {
		int tick = server.getTickCount();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isSpectator()) {
				continue;
			}
			UUID id = p.getUUID();
			String name = p.getGameProfile().name();
			Course.Event event = course.update(id, p.getX(), p.getY(), p.getZ(), p.onGround(), tick, lobbyY);
			switch (event.kind()) {
				case STARTED -> p.sendSystemMessage(Component.literal("Timer started!").withStyle(ChatFormatting.GREEN), true);
				case CANCELLED -> p.sendSystemMessage(Component.literal("You fell. Timer reset.").withStyle(ChatFormatting.GRAY), true);
				case FINISHED -> {
					broadcast(Component.literal(name + " completed the obstacle course in "
						+ String.format(Locale.ROOT, "%.1f", event.seconds()) + " seconds!")
						.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
					Fx.sound(server, name, "minecraft:ui.toast.challenge_complete", 1.0, 1.0);
				}
				default -> {
					if (tick % 4 == 0 && course.running(id)) {
						p.sendSystemMessage(Component.literal("Course time: "
							+ String.format(Locale.ROOT, "%.1f", course.elapsed(id, tick)) + " s").withStyle(ChatFormatting.YELLOW), true);
					}
				}
			}
		}
	}

	/** True if a living player is still up in the lobby area. */
	private boolean anyoneInLobby(int y) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.isSpectator() && p.getY() > y - 3 && Math.abs(p.getX()) < 40 && p.getZ() > -20 && p.getZ() < 90) {
				return true;
			}
		}
		return false;
	}

	/** Anything that is not a player, a text or an item is removed from the lobby once a second (mobs, mostly). */
	private void purgeLobbyMobs() {
		int y = rules.lobbyY();
		// "execute as ... run kill @s" so that an empty area is not logged as a failed command every second.
		Fx.run(server, "execute as @e[type=!minecraft:player,type=!minecraft:text_display,type=!minecraft:item,"
			+ "type=!minecraft:armor_stand,x=-36,y=" + (y - 2) + ",z=-14,dx=50,dy=24,dz=70] run kill @s");
	}

	/**
	 * Takes the lobby down without starting a game: everybody goes to survival and is moved to the ground away
	 * from the lobby first (spreading around 0,0 would drop people on the lobby roof). Works even if the mod
	 * forgot where the lobby was, by using the lobby_y rule.
	 */
	public void removeLobbyNow() {
		int y = rules.lobbyY() >= 0 ? rules.lobbyY() : rules.integer("lobby_y");
		Fx.run(server, "gamemode survival @a[gamemode=adventure]");
		Fx.run(server, "effect clear @a minecraft:resistance");
		Fx.run(server, "effect clear @a minecraft:saturation");
		Fx.run(server, "spreadplayers 100 100 0 40 false @a[gamemode=!spectator]");
		broadcast(Component.literal("Removing the lobby...").withStyle(ChatFormatting.GRAY));
		scheduler.after(20, () -> {
			if (rules.lobbyY() < 0) {
				rules.setLobbyY(y); // the mod had lost track of it: remove by the lobby_y rule
			}
			finishLobbyRemoval(y, 0);
			if (rules.lobbyY() < 0) {
				broadcast(Component.literal("The lobby was removed.").withStyle(ChatFormatting.GRAY));
			}
		});
	}

	// ------------------------------------------------------------------ chat

	boolean chatOpen() {
		return rules.bool("chat") || (state.phase() == Phase.ENDED && rules.bool("unlock_chat_after_game"));
	}

	/** A message from a dead player, shown only to other dead players. */
	void deadChat(ServerPlayer sender, String text) {
		Component line = Component.literal("[Dead] <" + sender.getGameProfile().name() + "> " + text)
			.withStyle(ChatFormatting.GRAY);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isSpectator() && !serum.containsKey(p.getUUID())) {
				p.sendSystemMessage(line);
			}
		}
	}

	void broadcast(Component message) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			p.sendSystemMessage(message);
		}
	}

	// ------------------------------------------------------------------ world rules and totems

	/** Locator bar off, reduced F3, no death / advancement messages. Safe to run any number of times. */
	public void applyGameRules() {
		String[] settings = {
			"log_admin_commands false", // first, so the others are not announced to operators
			"locator_bar false",
			"reduced_debug_info true",
			"show_death_messages false",
			"show_advancement_messages false"
		};
		for (String s : settings) {
			Fx.run(server, "gamerule " + s);
		}
	}

	private void stripTotems() {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		for (ServerPlayer p : players) {
			Inventory inventory = p.getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				if (inventory.getItem(i).is(Items.TOTEM_OF_UNDYING)) {
					inventory.setItem(i, ItemStack.EMPTY);
				}
			}
		}
	}
}
