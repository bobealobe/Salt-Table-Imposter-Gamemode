package com.impostorsmp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * All the toggleable rules, saved in a small text file in the config folder.
 * Contains no Minecraft code on purpose, so it can be tested on its own.
 */
public final class Rules {
	public enum Type { BOOL, INT }

	public record Def(String name, Type type, String defaultValue, int min, int max, String description) {
	}

	public static final List<Def> DEFS = List.of(
		bool("chat", false, "Normal chat for everybody. Off = chat is disabled."),
		bool("dead_chat", true, "Dead players can chat, but only other dead players see it."),
		bool("unlock_chat_after_game", true, "Chat opens up again once a game has ended."),
		bool("death_sound", true, "Everybody hears the Wither spawn sound when a player dies."),
		bool("hide_death_messages", true, "Hide the death messages in chat."),
		bool("hide_advancements", true, "Hide the advancement messages in chat (the pop-up toast still shows)."),
		bool("hide_join_leave", false, "Hide the joined / left the game messages."),
		bool("block_totems", true, "Totems of Undying are removed from inventories, so they never work."),
		bool("spectate_on_death", true, "Dead players are forced into spectator mode (also when they respawn or rejoin)."),
		bool("anti_rdm", true, "An innocent who kills another innocent is punished."),
		integer("anti_rdm_seconds", 300, 5, 3600, "How many seconds the punishment lasts."),
		integer("anti_rdm_level", 3, 1, 5, "Slowness level (1 = Slowness I, 2 = Slowness II, ...)."),
		bool("anti_rdm_weakness", true, "Punishment also gives Weakness and Mining Fatigue."),
		bool("anti_rdm_glowing", true, "Punishment also makes the player glow, so everybody can see them through walls."),
		bool("anti_rdm_escalate", true, "Every repeat offence doubles the time and adds a slowness level."),
		bool("impostor_kit", true, "Each impostor starts with a random trap kit: one fast trap item plus some building items."),
		integer("impostor_kit_builders", 2, 1, 4, "How many trap slots are in each impostor's kit (the quick trap is extra)."),
		integer("impostor_kit_set_chance", 40, 0, 100, "Percent chance that the last building slot is a ready-made set (piston trap, TNT, ...) instead of a single item."),
		bool("accuse", true, "Players can call each other out with /imp accuse <player>."),
		integer("accuse_votes", 3, 2, 5, "How many living players must accuse the same person to settle their role."),
		bool("false_accuse_punish", true, "If the accused turns out innocent, everybody who accused them is punished."),
		integer("false_accuse_seconds", 180, 5, 3600, "How many seconds the false-accusation punishment lasts."),
		bool("dragon_wins", true, "Killing the Ender Dragon makes the innocents win."),
		bool("parity_win", false, "Impostors also win once they are at least as many as the innocents left (2 v 2)."),
		integer("impostors", 2, 1, 5, "How many impostors /imp start picks."),
		bool("apply_gamerules", true, "At world start and /imp start: locator bar off, reduced F3, no death or advancement messages."),
		bool("spread_on_start", false, "/imp start first spreads everybody out randomly with /spreadplayers."),
		integer("spread_radius", 2500, 100, 29999999, "Spread area is a square this many blocks from 0,0 in every direction."),
		integer("spread_min_distance", 500, 0, 100000, "Minimum distance between players after spreading."),
		integer("lobby_y", 250, 64, 300, "Height of the sky lobby made by /imp lobby."),
		integer("spread_group_size", 1, 1, 6, "Players spawn in groups of this size (1 = everybody alone, 2 = pairs). Teammates land on the same spot."),
		bool("spread_split_impostors", true, "Never put two impostors in the same spawn group, so they don't find each other at the start.")
	);

	private static Def bool(String name, boolean def, String description) {
		return new Def(name, Type.BOOL, Boolean.toString(def), 0, 0, description);
	}

	private static Def integer(String name, int def, int min, int max, String description) {
		return new Def(name, Type.INT, Integer.toString(def), min, max, description);
	}

	private final Path file;
	private final Properties props = new Properties();

	public Rules(Path file) {
		this.file = file;
		load();
	}

	public static Optional<Def> find(String name) {
		for (Def d : DEFS) {
			if (d.name().equals(name)) {
				return Optional.of(d);
			}
		}
		return Optional.empty();
	}

	private static Def require(String name) {
		return find(name).orElseThrow(() -> new IllegalArgumentException("Unknown rule: " + name));
	}

	public boolean bool(String name) {
		Def d = require(name);
		return Boolean.parseBoolean(props.getProperty("rule." + name, d.defaultValue()));
	}

	public int integer(String name) {
		Def d = require(name);
		try {
			return Integer.parseInt(props.getProperty("rule." + name, d.defaultValue()).trim());
		} catch (NumberFormatException e) {
			return Integer.parseInt(d.defaultValue());
		}
	}

	public String valueText(String name) {
		Def d = require(name);
		return props.getProperty("rule." + name, d.defaultValue());
	}

	/** Sets a rule from text. Throws IllegalArgumentException with a readable message if the value is bad. */
	public void set(String name, String raw) {
		Def d = require(name);
		String value = raw.trim().toLowerCase(Locale.ROOT);
		if (d.type() == Type.BOOL) {
			if (!value.equals("true") && !value.equals("false")) {
				throw new IllegalArgumentException(name + " must be true or false.");
			}
		} else {
			int n;
			try {
				n = Integer.parseInt(value);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(name + " must be a whole number.");
			}
			if (n < d.min() || n > d.max()) {
				throw new IllegalArgumentException(name + " must be between " + d.min() + " and " + d.max() + ".");
			}
		}
		props.setProperty("rule." + name, value);
		save();
	}

	public void resetAll() {
		List<Object> keys = new ArrayList<>(props.keySet());
		for (Object k : keys) {
			if (String.valueOf(k).startsWith("rule.")) {
				props.remove(k);
			}
		}
		save();
	}

	// ---- admins (host lock) ----

	/** Lower-case player names allowed to run admin commands. Empty = anyone with operator permission. */
	public Set<String> admins() {
		Set<String> out = new LinkedHashSet<>();
		for (String part : props.getProperty("admins", "").split(",")) {
			String p = part.trim().toLowerCase(Locale.ROOT);
			if (!p.isEmpty()) {
				out.add(p);
			}
		}
		return out;
	}

	// ---- lobby ----

	/** Height of the lobby that currently exists, or -1 if there is none. */
	public int lobbyY() {
		try {
			return Integer.parseInt(props.getProperty("lobby.y", "-1").trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	public void setLobbyY(int y) {
		props.setProperty("lobby.y", Integer.toString(y));
		save();
	}

	public void setAdmins(Set<String> names) {
		props.setProperty("admins", String.join(",", names));
		save();
	}

	// ---- file ----

	private void load() {
		if (!Files.exists(file)) {
			return;
		}
		try (InputStream in = Files.newInputStream(file)) {
			props.load(in);
		} catch (IOException e) {
			ImpostorLog.warn("Could not read " + file + ": " + e.getMessage());
		}
	}

	private void save() {
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (OutputStream out = Files.newOutputStream(file)) {
				props.store(out, "Impostor SMP rules. Change them in game with /imp rule <name> <value>.");
			}
		} catch (IOException e) {
			ImpostorLog.warn("Could not save " + file + ": " + e.getMessage());
		}
	}
}
