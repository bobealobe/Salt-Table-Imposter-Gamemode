package com.impostorsmp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** Runs the actual game: picking roles, the countdown, deaths, win conditions. One per world that is opened. */
public final class GameController {
	private final MinecraftServer server;
	private final Rules rules;
	private final GameState state;
	private final Scheduler scheduler = new Scheduler();
	private final Path statePath;
	/** "attacker>victim" to the server tick of the last hit, used for the self-defence exception. */
	private final Map<String, Integer> recentHits = new HashMap<>();
	private int lastGateWarning = -100;

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
		if (state.phase() == Phase.RUNNING) {
			state.addTicks(1);
			if (!state.pvpUnlocked() && rules.bool("impostor_pvp_gate")) {
				checkPvpUnlock();
			}
			if (state.elapsedTicks() % 1200 == 0) {
				save(); // keeps the timer safe if the world is closed suddenly
			}
		}
	}

	// ------------------------------------------------------------------ impostor PvP gate

	private void checkPvpUnlock() {
		int minutes = rules.integer("impostor_pvp_minutes");
		if (minutes > 0 && state.elapsedTicks() >= minutes * 1200L) {
			unlockPvp(minutes + " minutes have passed.");
			return;
		}
		if (rules.bool("impostor_pvp_on_nether") && state.elapsedTicks() % 20 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				boolean alive = state.member(p.getUUID()).map(Member::alive).orElse(false);
				if (alive && p.level().dimension().equals(Level.NETHER)) {
					unlockPvp("Somebody entered the Nether.");
					return;
				}
			}
		}
	}

	private void unlockPvp(String reason) {
		state.unlockPvp();
		save();
		broadcast(Component.literal("Impostors can now attack players directly. " + reason).withStyle(ChatFormatting.RED));
		Fx.times(server, "@a", 5, 60, 20);
		Fx.subtitle(server, "@a", Fx.text(reason, "gray", false));
		Fx.title(server, "@a", Fx.text("Impostor PvP is unlocked", "red", true));
		Fx.sound(server, "@a", "minecraft:block.bell.use", 1.0, 0.7);
	}

	/** One line for /imp status. */
	String pvpStatus() {
		if (!rules.bool("impostor_pvp_gate")) {
			return "Impostor PvP: always allowed (gate is off).";
		}
		if (state.pvpUnlocked()) {
			return "Impostor PvP: UNLOCKED.";
		}
		int minutes = rules.integer("impostor_pvp_minutes");
		String timer = minutes > 0
			? "unlocks in " + Math.max(0, minutes - (int) (state.elapsedTicks() / 1200)) + " min"
			: "no timer";
		return "Impostor PvP: locked (" + timer + (rules.bool("impostor_pvp_on_nether") ? ", or when somebody enters the Nether" : "") + ").";
	}

	/**
	 * Called for every hit. Returns false to cancel it. Only direct hits (swords, fists, arrows and other
	 * projectiles) from a gated impostor are blocked; traps, lava, fire, TNT, pistons and falls always work.
	 */
	boolean allowDamage(LivingEntity victim, DamageSource source) {
		if (!(victim instanceof ServerPlayer target) || state.phase() != Phase.RUNNING) {
			return true;
		}
		if (!(source.getEntity() instanceof ServerPlayer attacker) || attacker == target) {
			return true;
		}
		Entity direct = source.getDirectEntity();
		if (direct != attacker && !(direct instanceof Projectile)) {
			return true; // an explosion, a trap and so on
		}
		Member attackerMember = state.member(attacker.getUUID()).orElse(null);
		if (attackerMember == null || attackerMember.role() != Role.IMPOSTOR || !rules.bool("impostor_pvp_gate")
			|| state.mayAttack(attacker.getUUID())) {
			recentHits.put(attacker.getUUID() + ">" + target.getUUID(), server.getTickCount());
			return true;
		}
		Integer hitBack = recentHits.get(target.getUUID() + ">" + attacker.getUUID());
		if (rules.bool("impostor_self_defense") && hitBack != null && server.getTickCount() - hitBack <= 200) {
			return true;
		}
		if (server.getTickCount() - lastGateWarning >= 40) {
			lastGateWarning = server.getTickCount();
			attacker.sendSystemMessage(Component.literal("You can't attack directly yet. Use traps, or get called out.")
				.withStyle(ChatFormatting.RED), true);
		}
		return false;
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
		recentHits.clear();

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
			if (anyoneInLobby(lobbyY)) {
				// The spread did not work for somebody. Keep the floor so nobody falls, and say so.
				broadcast(Component.literal("Could not spread everybody out, so the lobby was kept. Fix the spread rules, then /imp lobby remove.")
					.withStyle(ChatFormatting.RED));
			} else {
				removeLobby(lobbyY);
			}
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

	/** Test helper: unlocks impostor PvP right now. Returns false if it was already unlocked or no game is running. */
	public boolean forceUnlockPvp() {
		if (state.phase() != Phase.RUNNING || state.pvpUnlocked()) {
			return false;
		}
		unlockPvp("The host unlocked it for testing.");
		return true;
	}

	/** Hands the impostor a random trap kit and tells them what is in it (only they see the message). */
	public void giveKit(ServerPlayer player) {
		List<Kit.Entry> slots = Kit.roll(new Random(), rules.integer("impostor_kit_builders"),
			rules.integer("impostor_kit_set_chance"));
		player.sendSystemMessage(Component.literal("Your trap kit:").withStyle(ChatFormatting.GOLD));
		for (Kit.Entry slot : slots) {
			for (Kit.Item item : slot.items()) {
				Fx.run(server, "give " + player.getGameProfile().name() + " " + item.id() + " " + item.count());
			}
			player.sendSystemMessage(Component.literal(" - " + slot.name()).withStyle(ChatFormatting.GOLD));
		}
	}

	private void beginRunning() {
		state.startRunning();
		save();
		checkForWinner(); // in case somebody died during the countdown
	}

	/** Cancels the current game without a winner. */
	public void stop() {
		scheduler.clear();
		state.reset();
		recentHits.clear();
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
		AccuseResult r = state.accuse(accuser.getUUID(), target.getUUID(), rules.integer("accuse_votes"));
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
				broadcast(Component.literal(targetName + " has been called out and IS an IMPOSTOR! They can attack freely now.")
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

	private static final int[][] LOBBY_CORNERS = {{-12, -12}, {12, -12}, {-12, 38}, {12, 38}};

	/** Builds the lobby (after the chunks are loaded), moves everybody into it and makes it the world spawn. */
	public void createLobby() {
		if (state.phase() == Phase.COUNTDOWN || state.phase() == Phase.RUNNING) {
			throw new IllegalStateException("A game is running. Use /imp stop first.");
		}
		int oldY = rules.lobbyY();
		if (oldY >= 0) {
			removeLobby(oldY); // rebuilding: take the old one down first
		}
		int y = rules.integer("lobby_y");
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
		broadcast(Component.literal("The lobby is ready. Press the green button when everybody is here.")
			.withStyle(ChatFormatting.GREEN));
	}

	private void sendToLobby(String name, int y) {
		Fx.run(server, "gamemode adventure " + name);
		Fx.run(server, "execute in minecraft:overworld run tp " + name + " 0.5 " + y + " 0.5");
		Fx.run(server, "effect give " + name + " minecraft:resistance infinite 4 true");
	}

	/** Deletes the lobby (blocks, texts, button) and stops keeping its chunks loaded. */
	private void removeLobby(int y) {
		for (String command : LobbyPlan.remove(y)) {
			Fx.run(server, command);
		}
		Fx.run(server, "forceload remove " + LobbyPlan.FORCELOAD_AREA);
		rules.setLobbyY(-1);
	}

	/** True if a living player is still up in the lobby area. */
	private boolean anyoneInLobby(int y) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.isSpectator() && p.getY() > y - 3 && Math.abs(p.getX()) < 40 && p.getZ() > -20 && p.getZ() < 45) {
				return true;
			}
		}
		return false;
	}

	/** Takes the lobby down without starting a game: everybody goes to survival and lands near 0,0. */
	public void removeLobbyNow() {
		int y = rules.lobbyY();
		if (y < 0) {
			throw new IllegalStateException("There is no lobby.");
		}
		Fx.run(server, "gamemode survival @a[gamemode=adventure]");
		Fx.run(server, "effect clear @a minecraft:resistance");
		Fx.run(server, "spreadplayers 0 0 0 30 false @a[gamemode=!spectator]");
		if (anyoneInLobby(y)) {
			throw new IllegalStateException("Could not move everybody out, so the lobby was kept.");
		}
		removeLobby(y);
		broadcast(Component.literal("The lobby was removed.").withStyle(ChatFormatting.GRAY));
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
			if (p.isSpectator()) {
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
