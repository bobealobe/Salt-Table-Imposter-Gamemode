package com.impostorsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the list of Minecraft commands that make (or remove) the sky lobby. The lobby is a closed room with
 * the rules on its walls, the kit list in the middle, a start button, and a hallway that shows every rule as ON / OFF.
 * No Minecraft code in here on purpose: it only produces command text, so it can be tested on its own.
 *
 * The room is centred on x=0, z=0. Players stand on y (the floor block is y-1).
 */
final class LobbyPlan {
	static final String TAG = "imp_lobby";
	/** The area to keep loaded while the lobby exists: x1 z1 x2 z2 in blocks. */
	static final String FORCELOAD_AREA = "-12 -12 12 80";

	private LobbyPlan() {
	}

	// ------------------------------------------------------------------ text helper

	/** A multi-coloured text for a text display. Every piece is a sibling of an empty base, so styles never leak. */
	static final class Text {
		private final StringBuilder sb = new StringBuilder("[{text:\"\"}");

		Text add(String text, String color, boolean bold) {
			sb.append(",{text:\"").append(escape(text)).append("\",color:\"").append(color).append('"');
			if (bold) {
				sb.append(",bold:true");
			}
			sb.append('}');
			return this;
		}

		Text add(String text, String color) {
			return add(text, color, false);
		}

		Text nl() {
			sb.append(",{text:\"\\n\"}");
			return this;
		}

		String snbt() {
			return sb + "]";
		}

		private static String escape(String s) {
			return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
		}
	}

	private static String display(double x, double y, double z, double scale, String align, int lineWidth, Text text) {
		return String.format(Locale.ROOT,
			"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\"],billboard:\"center\",alignment:\"%s\",line_width:%d,"
				+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],"
				+ "scale:[%.2ff,%.2ff,%.2ff]},text:%s}",
			x, y, z, TAG, align, lineWidth, scale, scale, scale, text.snbt());
	}

	private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		return "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block;
	}

	// ------------------------------------------------------------------ build / remove

	/** Commands that remove the lobby built at height y. The caller also removes the forceload. */
	static List<String> remove(int y) {
		List<String> c = new ArrayList<>();
		c.add("kill @e[type=minecraft:text_display,tag=" + TAG + "]");
		c.add(fill(-12, y - 1, -12, 12, y + 9, 12, "minecraft:air"));
		c.add(fill(-8, y - 1, 12, 8, y + 7, 34, "minecraft:air"));
		c.add(fill(-11, y - 1, 34, 11, y + 13, 72, "minecraft:air"));
		return c;
	}

	/** Commands that build the whole lobby for the current rules. Chunks must be loaded first. */
	static List<String> build(int y, Rules rules) {
		List<String> c = new ArrayList<>();
		c.add("kill @e[type=minecraft:text_display,tag=" + TAG + "]");

		// The room: a closed box so nobody can fall out (the world is hardcore).
		c.add(fill(-12, y - 1, -12, 12, y + 9, 12, "minecraft:black_concrete hollow"));
		c.add(fill(-11, y - 1, -11, 11, y - 1, 11, "minecraft:smooth_quartz"));
		for (int x : new int[] {-8, 0, 8}) {
			for (int z : new int[] {-8, 0, 8}) {
				c.add("setblock " + x + " " + (y + 9) + " " + z + " minecraft:sea_lantern");
			}
		}

		// The hallway through the south wall.
		c.add(fill(-8, y - 1, 12, 8, y + 7, 34, "minecraft:black_concrete hollow"));
		c.add(fill(-7, y - 1, 13, 7, y - 1, 33, "minecraft:smooth_quartz"));
		c.add(fill(-2, y, 12, 2, y + 3, 12, "minecraft:air"));
		for (int x : new int[] {-5, 0, 5}) {
			for (int z : new int[] {16, 22, 28}) {
				c.add("setblock " + x + " " + (y + 7) + " " + z + " minecraft:sea_lantern");
			}
		}

		// The start button: a lime panel in the north wall with a command block in the middle of it.
		// A button pressed on a command block powers it.
		c.add(fill(-2, y, -12, 2, y + 3, -12, "minecraft:lime_concrete"));
		c.add("setblock 0 " + (y + 1) + " -12 minecraft:command_block{Command:\"imp start\",auto:0b,TrackOutput:0b}");
		c.add("setblock 0 " + (y + 1) + " -11 minecraft:stone_button[face=wall,facing=south]");

		c.addAll(decorations(y));
		c.addAll(obstacleCourse(y));

		c.addAll(roomTexts(y, rules));
		c.addAll(kitTexts(y, rules));
		c.addAll(hallwayTexts(y, rules));
		return c;
	}

	// ------------------------------------------------------------------ decorations

	private static List<String> decorations(int y) {
		List<String> c = new ArrayList<>();

		// Room: inlaid floor, gold emblem in the middle, an orange stripe round the walls, corner pillars.
		c.add(fill(-9, y - 1, -9, 9, y - 1, 9, "minecraft:polished_blackstone outline"));
		c.add(fill(-1, y - 1, -1, 1, y - 1, 1, "minecraft:gold_block"));
		c.add(fill(-11, y + 6, -11, 11, y + 6, 11, "minecraft:orange_concrete outline"));
		for (int sx : new int[] {-1, 1}) {
			for (int sz : new int[] {-1, 1}) {
				int x1 = sx < 0 ? -11 : 10;
				int z1 = sz < 0 ? -11 : 10;
				c.add(fill(x1, y, z1, x1 + 1, y + 8, z1 + 1, "minecraft:quartz_pillar"));
			}
		}
		// A carpet from the start button to the middle, a red runner to the hallway, two benches.
		c.add(fill(-1, y, -10, 1, y, -2, "minecraft:lime_carpet"));
		c.add(fill(-1, y, 2, 1, y, 11, "minecraft:red_carpet"));
		c.add(fill(-9, y, 9, -4, y, 9, "minecraft:quartz_slab"));
		c.add(fill(4, y, 9, 9, y, 9, "minecraft:quartz_slab"));
		// A skylight: only sky is visible through it, never the ground.
		c.add(fill(-2, y + 9, -2, 2, y + 9, 2, "minecraft:glass"));

		// Hallway: red runner and an orange stripe.
		c.add(fill(-1, y, 13, 1, y, 33, "minecraft:red_carpet"));
		c.add(fill(-7, y + 5, 13, 7, y + 5, 33, "minecraft:orange_concrete outline"));
		return c;
	}

	// ------------------------------------------------------------------ obstacle course

	/**
	 * A parkour hall past the end of the hallway. It is closed and has a floor, and everybody in the lobby has
	 * Resistance, so falling off only means walking back to the start. The course layer is y+3 (you stand on y+4).
	 */
	private static List<String> obstacleCourse(int y) {
		List<String> c = new ArrayList<>();
		int level = y + 3;

		c.add(fill(-11, y - 1, 34, 11, y + 13, 72, "minecraft:black_concrete hollow"));
		c.add(fill(-10, y - 1, 35, 10, y - 1, 71, "minecraft:light_blue_concrete"));
		c.add(fill(-10, y + 6, 35, 10, y + 6, 71, "minecraft:cyan_concrete outline"));
		c.add(fill(-2, y, 34, 2, y + 3, 34, "minecraft:air")); // the door from the hallway
		for (int x : new int[] {-8, 0, 8}) {
			for (int z : new int[] {42, 52, 62, 70}) {
				c.add("setblock " + x + " " + (y + 13) + " " + z + " minecraft:sea_lantern");
			}
		}

		// Stairs up to the start platform.
		for (int k = 0; k < 4; k++) {
			c.add(fill(-1, y, 36 + k, 1, y + k, 36 + k, "minecraft:blue_concrete"));
		}
		c.add(fill(-2, level, 39, 2, level, 41, "minecraft:lime_concrete"));

		// 1. Stepping stones.
		int[][] stones = {{0, 43}, {-1, 45}, {1, 47}, {-1, 49}, {1, 51}, {0, 53}};
		for (int[] s : stones) {
			c.add("setblock " + s[0] + " " + level + " " + s[1] + " minecraft:cyan_concrete");
		}
		// 2. Slippery ice.
		c.add(fill(-1, level, 55, 1, level, 59, "minecraft:packed_ice"));
		// 3. Climbing pillars.
		c.add("setblock 0 " + (level + 1) + " 61 minecraft:orange_concrete");
		c.add("setblock -1 " + (level + 2) + " 63 minecraft:orange_concrete");
		c.add("setblock 1 " + (level + 3) + " 65 minecraft:orange_concrete");
		// The finish.
		c.add(fill(-2, level + 3, 67, 2, level + 3, 70, "minecraft:gold_block"));

		c.add(display(0, y + 7.2, 40, 1.5, "center", 200,
			new Text().add("OBSTACLE COURSE", "gold", true).nl()
				.add("Falling is safe. Just walk back to the start.", "gray")));
		c.add(display(0, y + 10.5, 69, 2, "center", 200,
			new Text().add("YOU MADE IT!", "green", true)));
		c.add(display(0, y + 3, 31, 1.2, "center", 200,
			new Text().add("OBSTACLE COURSE ->", "gold", true)));
		return c;
	}

	// ------------------------------------------------------------------ the texts

	private static String onOff(boolean on) {
		return on ? "ON" : "OFF";
	}

	private static List<String> roomTexts(int y, Rules r) {
		List<String> c = new ArrayList<>();
		int impostors = r.integer("impostors");

		c.add(display(0, y + 6.8, -9, 3, "center", 200,
			new Text().add("IMPOSTOR SMP", "gold", true)));
		c.add(display(0, y + 4.6, -9, 1.4, "center", 200,
			new Text().add("PRESS THE GREEN BUTTON", "green", true).nl()
				.add("when everybody has joined", "gray")));

		Text win = new Text().add("HOW TO WIN", "aqua", true).nl()
			.add("Innocents: ", "green", true).add("kill all " + impostors + " impostor" + (impostors == 1 ? "" : "s"), "white");
		if (r.bool("dragon_wins")) {
			win.add(" or slay the Ender Dragon", "white");
		}
		win.nl().add("Impostors: ", "red", true).add("kill every innocent", "white");
		if (r.bool("parity_win")) {
			win.add(", or become as many as the innocents left", "white");
		}
		win.nl().add("Impostors do not know each other.", "gray");
		c.add(display(-8.5, y + 3.5, -3, 1, "left", 150, win));

		Text combat = new Text().add("COMBAT", "aqua", true).nl();
		if (r.bool("impostor_pvp_gate")) {
			combat.add("Impostors cannot hit you directly until ", "white");
			int minutes = r.integer("impostor_pvp_minutes");
			boolean nether = r.bool("impostor_pvp_on_nether");
			if (minutes > 0) {
				combat.add(minutes + " minutes pass", "yellow");
				combat.add(nether ? " or someone enters the Nether" : "", "yellow");
				combat.add(", or they are called out.", "white").nl();
			} else if (nether) {
				combat.add("someone enters the Nether", "yellow");
				combat.add(", or they are called out.", "white").nl();
			} else {
				combat.add("they are called out.", "white").nl();
			}
			combat.add("Traps, lava, TNT and spleefing always work.", "gray").nl();
		} else {
			combat.add("Impostors may fight right away.", "white").nl();
		}
		if (r.bool("anti_rdm")) {
			combat.add("Innocent kills innocent: ", "white").add("punished", "red").nl();
		}
		if (r.bool("block_totems")) {
			combat.add("Totems do not work.", "white").nl();
		}
		combat.add("Dead players become spectators.", "white");
		c.add(display(8.5, y + 3.5, -3, 1, "left", 150, combat));

		Text commands = new Text().add("COMMANDS", "aqua", true).nl()
			.add("/imp role", "yellow").add(" - see your role", "white").nl()
			.add("/imp accuse <player>", "yellow").add(" - call somebody out", "white").nl()
			.add("/imp accuse clear", "yellow").add(" - take it back", "white");
		c.add(display(-8.5, y + 3.5, 5, 1, "left", 150, commands));

		Text callouts = new Text().add("CALL-OUTS", "aqua", true).nl();
		if (r.bool("accuse")) {
			callouts.add(r.integer("accuse_votes") + " accusations on the same person reveal their role.", "white").nl();
			callouts.add("Impostor: exposed, free to attack.", "red").nl();
			callouts.add("Innocent: cleared, ", "green");
			callouts.add(r.bool("false_accuse_punish") ? "and every accuser is punished!" : "nothing else happens.", "white");
		} else {
			callouts.add("Call-outs are turned off.", "gray");
		}
		c.add(display(8.5, y + 3.5, 5, 1, "left", 150, callouts));
		return c;
	}

	private static List<String> kitTexts(int y, Rules r) {
		List<String> c = new ArrayList<>();
		if (!r.bool("impostor_kit")) {
			c.add(display(0, y + 4.5, 1, 1.5, "center", 200,
				new Text().add("TRAP KITS ARE OFF", "red", true)));
			return c;
		}
		int builders = r.integer("impostor_kit_builders");

		Text fast = new Text().add("IMPOSTOR KIT", "red", true).nl().add("1 fast trap item:", "gray");
		for (Kit.Entry e : Kit.FAST) {
			fast.nl().add("- " + e.name(), "white");
		}
		c.add(display(-4, y + 4.5, 1, 0.9, "left", 130, fast));

		Text singles = new Text().add(builders + " building slot" + (builders == 1 ? "" : "s") + ":", "gray");
		for (Kit.Entry e : Kit.SINGLES) {
			singles.nl().add("- " + e.name(), "white");
		}
		singles.nl().add("Each slot may be a SET instead (" + r.integer("impostor_kit_set_chance") + "%)", "yellow");
		c.add(display(0, y + 4.5, 1, 0.9, "left", 130, singles));

		Text sets = new Text().add("READY-MADE SETS", "gold", true);
		for (Kit.Entry e : Kit.BUNDLES) {
			int paren = e.name().indexOf(" (");
			sets.nl().add("- " + (paren > 0 ? e.name().substring(0, paren) : e.name()), "white", true);
			if (paren > 0) {
				sets.nl().add("  " + e.name().substring(paren + 2, e.name().length() - 1), "gray");
			}
		}
		c.add(display(4, y + 4.5, 1, 0.9, "left", 130, sets));
		return c;
	}

	private static List<String> hallwayTexts(int y, Rules r) {
		List<String> c = new ArrayList<>();
		c.add(display(0, y + 5, 14.5, 1.6, "center", 200,
			new Text().add("RULES HALLWAY", "gold", true).nl()
				.add("ON", "green", true).add(" = enabled   ", "white")
				.add("OFF", "red", true).add(" = disabled   ", "white")
				.add("12", "yellow", true).add(" = a number", "white")));

		List<RulesLayout.Section> sections = RulesLayout.sections(Rules.DEFS);
		for (int i = 0; i < sections.size(); i++) {
			RulesLayout.Section section = sections.get(i);
			Text t = new Text().add(section.title(), "aqua", true);
			for (Rules.Def def : section.rules()) {
				t.nl();
				if (def.type() == Rules.Type.BOOL) {
					boolean on = r.bool(def.name());
					t.add(onOff(on) + " ", on ? "green" : "red", true);
				} else {
					t.add(r.integer(def.name()) + " ", "yellow", true);
				}
				t.add(def.name(), "white");
			}
			double x = (i % 2 == 0 ? -1 : 1) * 4.6;
			double z = 18 + Math.min(i / 2, 2) * 6;
			c.add(display(x, y + 2.9, z, 0.8, "left", 220, t));
		}
		return c;
	}
}
