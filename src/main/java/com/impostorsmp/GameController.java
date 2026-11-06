package com.impostorsmp;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import com.impostorsmp.GameState.Member;
import com.impostorsmp.GameState.Outcome;
import com.impostorsmp.GameState.Phase;
import com.impostorsmp.GameState.Role;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Runs the actual game: picking roles, the countdown, deaths, win conditions. One per world that is opened. */
public final class GameController {
	private final MinecraftServer server;
	private final Rules rules;
	private final GameState state;
	private final Scheduler scheduler = new Scheduler();
	private final Path statePath;

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
	}

	// ------------------------------------------------------------------ starting a game

	/** Picks the roles and starts the countdown. Throws IllegalStateException with a readable message if it can't. */
	public void start() {
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
			state.begin(players, impostors, new Random(), server.overworld().getSeed());
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(e.getMessage());
		}
		save();

		if (rules.bool("apply_gamerules")) {
			applyGameRules();
		}

		long delay = 0;
		if (rules.bool("spread_on_start")) {
			Fx.run(server, "spreadplayers 0 0 " + rules.integer("spread_min_distance") + " "
				+ rules.integer("spread_radius") + " false @a[gamemode=!spectator]");
			delay = 60; // give the world a moment to load around everybody
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
			String target = player.getStringUUID();
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
		save();
		Fx.run(server, "title @a clear");
		broadcast(Component.literal("The game was cancelled.").withStyle(ChatFormatting.GRAY));
	}

	// ------------------------------------------------------------------ deaths and winning

	void onDeath(LivingEntity entity, DamageSource source) {
		if (entity.getType() == EntityType.ENDER_DRAGON) {
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
		int seconds = rules.integer("anti_rdm_seconds");
		int level = rules.integer("anti_rdm_level");
		Fx.run(server, "effect give " + killer.getStringUUID() + " minecraft:slowness " + seconds + " " + (level - 1) + " true");
		killer.sendSystemMessage(Component.literal("You killed another innocent. You are slowed for " + seconds + " seconds.")
			.withStyle(ChatFormatting.RED));
	}

	private void checkForWinner() {
		switch (state.evaluate()) {
			case INNOCENTS -> finish(Outcome.INNOCENTS, "All the impostors have been eliminated!");
			case IMPOSTORS -> finish(Outcome.IMPOSTORS, "Every innocent is dead!");
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
		Fx.run(server, "gamemode spectator " + player.getStringUUID());
		if (member.isEmpty()) {
			player.sendSystemMessage(Component.literal("A game is in progress, so you are spectating.")
				.withStyle(ChatFormatting.GRAY));
		}
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
