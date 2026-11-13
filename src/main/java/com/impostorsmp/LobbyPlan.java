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
	static final String FORCELOAD_AREA = "-36 -13 12 80";

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

	// ------------------------------------------------------------------ helpers for building

	/** A one-block-thick rectangle outline lying flat. (The fill command's "outline" mode fills a flat area completely.) */
	private static List<String> ring(int x1, int x2, int yy, int z1, int z2, String block) {
		return List.of(
			fill(x1, yy, z1, x2, yy, z1, block),
			fill(x1, yy, z2, x2, yy, z2, block),
			fill(x1, yy, z1, x1, yy, z2, block),
			fill(x2, yy, z1, x2, yy, z2, block));
	}

	private static String set(int x, int y, int z, String block) {
		return "setblock " + x + " " + y + " " + z + " " + block;
	}

	private static final String LIGHT = "minecraft:light[level=15]";

	// ------------------------------------------------------------------ build / remove

	/**
	 * Commands that remove the lobby built at height y. The caller also removes the forceload.
	 * Also clears the shapes older versions built, so a lobby from an old version goes away too.
	 */
	static List<String> remove(int y) {
		List<String> c = new ArrayList<>();
		c.add("kill @e[type=minecraft:text_display,tag=" + TAG + "]");
		// Carpets, lanterns and buttons first: if their support went first they would break and drop as items.
		for (String fragile : new String[] {"minecraft:lime_carpet", "minecraft:red_carpet", "minecraft:lantern",
			"minecraft:polished_blackstone_button"}) {
			c.add(fill(-11, y, -11, 11, y + 4, 11, "minecraft:air replace " + fragile));
		}
		c.add(fill(-12, y - 1, -12, 12, y + 9, 12, "minecraft:air")); // the room
		c.add(fill(-11, y - 1, 12, 11, y + 13, 50, "minecraft:air")); // the obstacle course
		c.add(fill(-34, y - 1, -3, -12, y + 4, 3, "minecraft:air")); // the rules corridor
		c.add(set(0, y + 1, -13, "minecraft:air")); // the hidden command block behind the start button
		c.add(fill(-8, y - 1, 12, 8, y + 7, 34, "minecraft:air")); // older version: hallway
		c.add(fill(-11, y - 1, 34, 11, y + 13, 72, "minecraft:air")); // older version: obstacle course
		// Anything that was dropped by an earlier removal.
		c.add("kill @e[type=minecraft:item,x=-36,y=" + (y - 2) + ",z=-14,dx=50,dy=24,dz=70]");
		return c;
	}

	/** Commands that build the whole lobby for the current rules. Chunks must be loaded first. */
	static List<String> build(int y, Rules rules) {
		List<String> c = new ArrayList<>(remove(y)); // start from nothing, so leftovers of older lobbies are gone too

		// Shells. The room is closed so nobody can fall out (the world is hardcore).
		c.add(fill(-12, y - 1, -12, 12, y + 9, 12, "minecraft:black_concrete hollow"));
		c.add(fill(-11, y - 1, -11, 11, y - 1, 11, "minecraft:smooth_quartz"));
		c.add(fill(-11, y - 1, 12, 11, y + 13, 50, "minecraft:black_concrete hollow"));
		c.add(fill(-10, y - 1, 13, 10, y - 1, 49, "minecraft:light_blue_concrete"));
		c.add(fill(-34, y - 1, -3, -12, y + 4, 3, "minecraft:black_concrete hollow"));
		c.add(fill(-33, y - 1, -2, -13, y - 1, 2, "minecraft:smooth_quartz"));
		// Doors: the obstacle course opens straight off the room, the rules corridor is a small side door.
		c.add(fill(-2, y, 12, 2, y + 4, 12, "minecraft:air"));
		c.add(fill(-12, y, -1, -12, y + 2, 1, "minecraft:air"));

		c.addAll(lights(y));
		c.addAll(decorations(y));
		c.addAll(startConsole(y));
		c.addAll(obstacleCourse(y));

		c.addAll(roomTexts(y, rules));
		c.addAll(kitTexts(y, rules));
		c.addAll(corridorTexts(y, rules));
		return c;
	}

	// ------------------------------------------------------------------ lights

	/** Sea lanterns you can see, plus invisible light blocks, so nothing in the lobby is dark enough for mobs. */
	private static List<String> lights(int y) {
		List<String> c = new ArrayList<>();
		for (int x : new int[] {-8, 0, 8}) {
			for (int z : new int[] {-8, 0, 8}) {
				c.add(set(x, y + 9, z, "minecraft:sea_lantern"));
				c.add(set(x, y + 6, z, LIGHT));
				c.add(set(x, y + 1, z, LIGHT));
			}
		}
		for (int x : new int[] {-8, 0, 8}) {
			for (int z : new int[] {20, 30, 40, 48}) {
				c.add(set(x, y + 13, z, "minecraft:sea_lantern"));
				c.add(set(x, y + 8, z, LIGHT));
				c.add(set(x, y + 1, z, LIGHT));
			}
		}
		for (int x : new int[] {-16, -22, -28, -32}) {
			c.add(set(x, y + 4, 0, "minecraft:sea_lantern"));
			c.add(set(x, y + 2, 0, LIGHT));
		}
		return c;
	}

	// ------------------------------------------------------------------ decorations

	private static List<String> decorations(int y) {
		List<String> c = new ArrayList<>();

		// Room: inlaid floor, gold emblem in the middle, an orange stripe round the walls, corner pillars.
		c.addAll(ring(-9, 9, y - 1, -9, 9, "minecraft:polished_blackstone"));
		c.addAll(ring(-7, 7, y - 1, -7, 7, "minecraft:gold_block"));
		c.add(fill(-1, y - 1, -1, 1, y - 1, 1, "minecraft:gold_block"));
		c.addAll(ring(-11, 11, y + 6, -11, 11, "minecraft:orange_concrete"));
		for (int x1 : new int[] {-11, 10}) {
			for (int z1 : new int[] {-11, 10}) {
				c.add(fill(x1, y, z1, x1 + 1, y + 8, z1 + 1, "minecraft:quartz_pillar"));
			}
		}
		// Bookshelves under each wall panel, with a lantern on top.
		for (int x : new int[] {-11, 11}) {
			for (int zc : new int[] {-6, 6}) {
				c.add(fill(x, y, zc - 3, x, y + 2, zc + 3, "minecraft:bookshelf"));
				for (int dz : new int[] {-3, 0, 3}) {
					c.add(set(x, y + 3, zc + dz, "minecraft:lantern"));
				}
			}
		}
		// A carpet from the start button to the middle, a red runner to the obstacle course, two benches.
		c.add(fill(-1, y, -10, 1, y, -2, "minecraft:lime_carpet"));
		c.add(fill(-1, y, 2, 1, y, 11, "minecraft:red_carpet"));
		c.add(fill(-9, y, 9, -4, y, 9, "minecraft:quartz_slab"));
		c.add(fill(4, y, 9, 9, y, 9, "minecraft:quartz_slab"));
		// A skylight: only sky is visible through it, never the ground.
		c.add(fill(-2, y + 9, -2, 2, y + 9, 2, "minecraft:glass"));

		// Obstacle course hall: a cyan stripe along the walls.
		c.addAll(ring(-10, 10, y + 6, 13, 49, "minecraft:cyan_concrete"));
		return c;
	}

	// ------------------------------------------------------------------ the start button

	/**
	 * A gold-framed emerald panel in the north wall with a button on it. The command block is hidden behind the
	 * wall: a button powers the block it is stuck to, and that block powers the command block next to it.
	 */
	private static List<String> startConsole(int y) {
		List<String> c = new ArrayList<>();
		c.add(fill(-2, y, -12, 2, y + 3, -12, "minecraft:gold_block"));
		c.add(fill(-1, y + 1, -12, 1, y + 2, -12, "minecraft:emerald_block"));
		c.add(set(-3, y + 1, -12, "minecraft:sea_lantern"));
		c.add(set(3, y + 1, -12, "minecraft:sea_lantern"));
		c.add(set(0, y + 1, -13, "minecraft:command_block{Command:\"imp start\",auto:0b,TrackOutput:0b}"));
		c.add(set(0, y + 1, -11, "minecraft:polished_blackstone_button[face=wall,facing=south]"));
		return c;
	}

	// ------------------------------------------------------------------ obstacle course

	/** One platform of the course: a rectangle of blocks at height y+dy. */
	record Node(int x1, int z1, int x2, int z2, int dy, String block) {
	}

	private static Node stone(int x, int z, int dy, String block) {
		return new Node(x, z, x, z, dy, block);
	}

	/**
	 * The course in order, start to finish. It winds across the hall in an S shape. Every jump is a 3 or 4 block
	 * jump (2 or 3 blocks of air), the last three climb one block each, and the stones are far enough from the
	 * ones before and after that none can be skipped.
	 */
	static final List<Node> COURSE = List.of(
		new Node(Course.START_X1, Course.START_Z1, Course.START_X2, Course.START_Z2, Course.START_DY, "minecraft:lime_concrete"),
		stone(5, 21, 3, "minecraft:cyan_concrete"),
		stone(9, 22, 3, "minecraft:cyan_concrete"),
		stone(8, 26, 3, "minecraft:cyan_concrete"),
		stone(5, 28, 3, "minecraft:cyan_concrete"),
		stone(1, 29, 3, "minecraft:packed_ice"),
		stone(-3, 29, 3, "minecraft:packed_ice"),
		stone(-7, 29, 3, "minecraft:cyan_concrete"),
		stone(-9, 32, 3, "minecraft:cyan_concrete"),
		stone(-6, 35, 3, "minecraft:white_concrete"),
		stone(-2, 36, 3, "minecraft:white_concrete"),
		stone(2, 36, 3, "minecraft:white_concrete"),
		stone(6, 37, 3, "minecraft:white_concrete"),
		stone(9, 38, 4, "minecraft:orange_concrete"),
		stone(9, 41, 5, "minecraft:orange_concrete"),
		stone(6, 42, 6, "minecraft:orange_concrete"),
		new Node(Course.FINISH_X1, Course.FINISH_Z1, Course.FINISH_X2, Course.FINISH_Z2, Course.FINISH_DY, "minecraft:gold_block"));

	/**
	 * A parkour hall that opens straight off the room's south wall. It is closed and has a floor, and everybody in
	 * the lobby has Resistance, so falling off only means walking back to the start. Nothing hangs over the course:
	 * the highest platform is at y+6 and the ceiling is at y+13.
	 */
	private static List<String> obstacleCourse(int y) {
		List<String> c = new ArrayList<>();

		// Stairs up to the start platform.
		for (int k = 0; k < 4; k++) {
			c.add(fill(-1, y, 14 + k, 1, y + k, 14 + k, "minecraft:blue_concrete"));
		}
		for (Node n : COURSE) {
			c.add(fill(n.x1(), y + n.dy(), n.z1(), n.x2(), y + n.dy(), n.z2(), n.block()));
		}

		c.add(display(0, y + 6.2, 18, 1.5, "center", 200,
			new Text().add("OBSTACLE COURSE", "gold", true).nl()
				.add("Jump off to start the timer. Falling is safe.", "gray")));
		c.add(display(0, y + 9.5, 46, 2, "center", 200,
			new Text().add("YOU MADE IT!", "green", true)));
		return c;
	}

	// ------------------------------------------------------------------ the texts

	private static String onOff(boolean on) {
		return on ? "ON" : "OFF";
	}

	private static List<String> roomTexts(int y, Rules r) {
		List<String> c = new ArrayList<>();
		int impostors = r.integer("impostors");

		c.add(display(0, y + 6.0, -9, 3, "center", 200,
			new Text().add("IMPOSTOR SMP", "gold", true)));
		c.add(display(0, y + 4.3, -9, 1.2, "center", 200,
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
		c.add(display(-9.3, y + 2.5, -6, 1, "left", 140, win));

		Text know = new Text().add("GOOD TO KNOW", "aqua", true);
		if (r.bool("impostor_kit")) {
			know.nl().add("Impostors start with a secret trap kit.", "white");
		}
		know.nl().add("Traps, lava, TNT and spleefing are all fair game.", "white");
		if (r.bool("anti_rdm")) {
			know.nl().add("An innocent who kills an innocent is ", "white").add("punished", "red").add(".", "white");
		}
		if (r.bool("block_totems")) {
			know.nl().add("Totems do not work.", "white");
		}
		know.nl().add("Dead players become spectators.", "white");
		c.add(display(9.3, y + 2.5, -6, 1, "left", 140, know));

		Text commands = new Text().add("COMMANDS", "aqua", true).nl()
			.add("/imp role", "yellow").add(" - see your role", "white").nl()
			.add("/imp accuse <player>", "yellow").add(" - call somebody out", "white").nl()
			.add("/imp accuse clear", "yellow").add(" - take it back", "white");
		c.add(display(-9.3, y + 2.5, 6, 1, "left", 140, commands));

		Text callouts = new Text().add("CALL-OUTS", "aqua", true).nl();
		if (r.bool("accuse")) {
			callouts.add(r.integer("accuse_votes") + " accusations on the same person reveal their role.", "white").nl();
			callouts.add("Impostor: exposed, free to attack.", "red").nl();
			callouts.add("Innocent: cleared, ", "green");
			callouts.add(r.bool("false_accuse_punish") ? "and every accuser is punished!" : "nothing else happens.", "white");
		} else {
			callouts.add("Call-outs are turned off.", "gray");
		}
		c.add(display(9.3, y + 2.5, 6, 1, "left", 140, callouts));

		// Small signs over the two doors.
		c.add(display(0, y + 5.0, 10.5, 1.1, "center", 200, new Text().add("OBSTACLE COURSE", "gold", true)));
		c.add(display(-10.3, y + 3.4, 0, 0.7, "center", 200, new Text().add("<- RULES (ON / OFF)", "gray")));
		return c;
	}

	private static List<String> kitTexts(int y, Rules r) {
		List<String> c = new ArrayList<>();
		if (!r.bool("impostor_kit")) {
			c.add(display(0, y + 3.5, 1, 1.5, "center", 200,
				new Text().add("TRAP KITS ARE OFF", "red", true)));
			return c;
		}
		int builders = r.integer("impostor_kit_builders");

		Text fast = new Text().add("IMPOSTOR KIT", "red", true).nl().add("1 fast trap item:", "gray");
		for (Kit.Entry e : Kit.FAST) {
			fast.nl().add("- " + e.name(), "white");
		}
		c.add(display(-4, y + 3.5, 1, 0.9, "left", 130, fast));

		Text singles = new Text().add(builders + " building slot" + (builders == 1 ? "" : "s") + ":", "gray");
		for (Kit.Entry e : Kit.SINGLES) {
			singles.nl().add("- " + e.name(), "white");
		}
		singles.nl().add("Each slot may be a SET instead (" + r.integer("impostor_kit_set_chance") + "%)", "yellow");
		c.add(display(0, y + 3.5, 1, 0.9, "left", 130, singles));

		Text sets = new Text().add("READY-MADE SETS", "gold", true);
		for (Kit.Entry e : Kit.BUNDLES) {
			int paren = e.name().indexOf(" (");
			sets.nl().add("- " + (paren > 0 ? e.name().substring(0, paren) : e.name()), "white", true);
			if (paren > 0) {
				sets.nl().add("  " + e.name().substring(paren + 2, e.name().length() - 1), "gray");
			}
		}
		c.add(display(4, y + 3.5, 1, 0.9, "left", 130, sets));
		return c;
	}

	/** The rules, small and out of the way, inside the side corridor. */
	private static List<String> corridorTexts(int y, Rules r) {
		List<String> c = new ArrayList<>();
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
			// One straight row along the north wall of the corridor, in order.
			double x = -15.5 - 3.4 * i;
			c.add(display(x, y + 2.0, -0.7, 0.6, "left", 170, t));
		}
		return c;
	}
}
