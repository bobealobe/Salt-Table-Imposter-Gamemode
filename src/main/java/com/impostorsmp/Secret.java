package com.impostorsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The secret impostor items: which ones exist, how they are written as /give text (with a description and
 * the odds in the tooltip), what a Lootbox contains, what the horns summon, and the commands that dig the
 * egg pits. No Minecraft code in here on purpose, so it can be tested on its own.
 */
final class Secret {
	enum Kind { PORTA_PIT, VOID_EGG, SUBZERO, ONE_SHOT, MAHORAGA, HORDE, SERUM, LOOTBOX }

	enum Mob { RAVAGER, WITHER, WARDEN, ZOMBIES, SKELETONS, CREEPERS }

	/** The numbers from the rules that the descriptions mention. */
	record Settings(int freezeSeconds, int serumSeconds, int pitDepth, int hordeRange) {
	}

	/** One thing a Lootbox gives: an item id with optional components in [] and a count. */
	record Drop(String id, int count, String components) {
		String itemText() {
			return components.isEmpty() ? id : id + "[" + components + "]";
		}
	}

	// Mahoraga Horn weights: ravager 50, wither 20, warden 20 (they add up to 90, so they are used as weights).
	static final int MAHORAGA_RAVAGER = 50;
	static final int MAHORAGA_WITHER = 20;
	static final int MAHORAGA_WARDEN = 20;

	static final String SWORD = "minecraft:wooden_sword[minecraft:enchantments={\"minecraft:knockback\":2}]";

	private Secret() {
	}

	static String name(Kind kind) {
		return switch (kind) {
			case PORTA_PIT -> "Porta-Pit Egg";
			case VOID_EGG -> "Void Egg";
			case SUBZERO -> "Sub-Zero Powder Snow";
			case ONE_SHOT -> "One Shot One Kill";
			case MAHORAGA -> "Mahoraga Horn";
			case HORDE -> "Horde Horn";
			case SERUM -> "Spectral Serum";
			case LOOTBOX -> "Lootbox";
		};
	}

	/** The kind whose item has this name, or null. */
	static Kind kindForName(String name) {
		for (Kind k : Kind.values()) {
			if (name(k).equals(name)) {
				return k;
			}
		}
		return null;
	}

	private static String color(Kind kind) {
		return switch (kind) {
			case PORTA_PIT -> "gold";
			case VOID_EGG -> "dark_purple";
			case SUBZERO -> "aqua";
			case ONE_SHOT -> "dark_red";
			case MAHORAGA -> "red";
			case HORDE -> "dark_green";
			case SERUM -> "light_purple";
			case LOOTBOX -> "yellow";
		};
	}

	/** Percent of the Mahoraga Horn rolls for each mob, rounded so they add up to 100. */
	static int[] mahoragaPercents() {
		int total = MAHORAGA_RAVAGER + MAHORAGA_WITHER + MAHORAGA_WARDEN;
		int ravager = Math.round(100f * MAHORAGA_RAVAGER / total);
		int wither = Math.round(100f * MAHORAGA_WITHER / total);
		return new int[] {ravager, wither, 100 - ravager - wither};
	}

	/** The tooltip: what it does and the odds of any random outcome. No double quotes in here. */
	static List<String> lore(Kind kind, Settings s) {
		List<String> out = new ArrayList<>();
		switch (kind) {
			case PORTA_PIT -> {
				out.add("Throw it. Where it lands, the ground becomes");
				out.add("a 4x4 pit lined with crafting tables, with");
				out.add("2-block-tall pointed dripstone at the bottom");
				out.add("(" + s.pitDepth() + " blocks deep).");
				out.add("Comes with a knockback 2 wooden sword.");
				out.add("One use.");
			}
			case VOID_EGG -> {
				out.add("Throw it. Where it lands, the ground becomes");
				out.add("a 2x2 shaft that goes all the way down");
				out.add("to the void.");
				out.add("Comes with a knockback 2 wooden sword.");
				out.add("One use.");
			}
			case SUBZERO -> {
				out.add("Place it like a normal powder snow bucket.");
				out.add("Whoever touches it first is frozen solid for");
				out.add(s.freezeSeconds() + " seconds: no moving, no jumping.");
				out.add("The snow is used up.");
			}
			case ONE_SHOT -> {
				out.add("Load it into a dispenser or a crossbow.");
				out.add("Whoever it hits dies instantly.");
				out.add("Does nothing special from a bow.");
				out.add("It can not be picked back up.");
			}
			case MAHORAGA -> {
				int[] p = mahoragaPercents();
				out.add("Right-click: summons a mob directly on");
				out.add("top of YOU. That is the downside.");
				out.add("Ravager " + p[0] + "%");
				out.add("Wither " + p[1] + "% (every innocent gets a bow");
				out.add("and arrows)");
				out.add("Warden " + p[2] + "%");
				out.add("One use.");
			}
			case HORDE -> {
				out.add("Right-click: summons a horde directly on the");
				out.add("closest innocent. One must be within");
				out.add(s.hordeRange() + " blocks.");
				out.add("33% helmeted zombies");
				out.add("33% helmeted skeletons");
				out.add("33% creepers");
				out.add("3 uses (the durability bar shows what is left).");
			}
			case SERUM -> {
				out.add("Right-click: you become a spectator for");
				out.add(s.serumSeconds() + " seconds. Click the chat message to end");
				out.add("it early. You stay wherever you end up (it can");
				out.add("only end in open space, not inside blocks).");
			}
			case LOOTBOX -> {
				out.add("Right-click to open. You always get diamond");
				out.add("armor, plus one of these (25% each):");
				out.add("Diamond tools (no hoe), shield, bow,");
				out.add("arrows and 3 golden apples");
				out.add("16 end crystals and 16 obsidian");
				out.add("Mace and 16 wind charges");
				out.add("Lunge 3 diamond spear and 64 golden carrots");
			}
		}
		return out;
	}

	/** One item as text for /give or /item replace, with its name, tooltip and a hidden tag. */
	private static String item(String base, String baseComponents, Kind kind, Settings s) {
		StringBuilder c = new StringBuilder();
		if (!baseComponents.isEmpty()) {
			c.append(baseComponents).append(',');
		}
		c.append("minecraft:item_name={text:\"").append(name(kind)).append("\",color:\"").append(color(kind)).append("\"},");
		c.append("minecraft:custom_data={impostorsmp:\"").append(kind.name().toLowerCase()).append("\"},");
		c.append("minecraft:lore=[");
		List<String> lines = lore(kind, s);
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				c.append(',');
			}
			c.append("{text:\"").append(lines.get(i)).append("\",color:\"gray\",italic:false}");
		}
		c.append(']');
		return "minecraft:" + base + "[" + c + "]";
	}

	/** Everything the impostor receives for this secret item (usually one item; the eggs come with a sword). */
	static List<String> itemTexts(Kind kind, Settings s) {
		List<String> out = new ArrayList<>();
		switch (kind) {
			case PORTA_PIT, VOID_EGG -> {
				out.add(item("egg", "", kind, s));
				out.add(SWORD);
			}
			case SUBZERO -> out.add(item("powder_snow_bucket", "", kind, s));
			case ONE_SHOT -> out.add(item("arrow", "", kind, s));
			case MAHORAGA -> out.add(item("goat_horn", "minecraft:instrument=\"minecraft:ponder_goat_horn\"", kind, s));
			case HORDE -> out.add(item("goat_horn", "minecraft:instrument=\"minecraft:seek_goat_horn\",minecraft:max_damage=3", kind, s));
			case SERUM -> out.add(item("dragon_breath", "", kind, s));
			case LOOTBOX -> out.add(item("echo_shard", "minecraft:item_model=\"minecraft:chest\"", kind, s));
		}
		return out;
	}

	/** What the impostor is told in chat when the item arrives. */
	static String howToUse(Kind kind, Settings s) {
		return switch (kind) {
			case PORTA_PIT -> "Throw the egg where you want a deadly pit (" + s.pitDepth() + " deep). Use the sword to knock people in.";
			case VOID_EGG -> "Throw the egg where you want a shaft to the void. Use the sword to knock people in.";
			case SUBZERO -> "Place the snow as a trap. The first player to touch it is frozen for " + s.freezeSeconds() + " seconds.";
			case ONE_SHOT -> "Load it into a dispenser or a crossbow. Whoever it hits dies instantly.";
			case MAHORAGA -> "Right-click to summon a ravager, a wither or a warden ON TOP OF YOU. Careful.";
			case HORDE -> "Right-click within " + s.hordeRange() + " blocks of an innocent to send a horde at the closest one. 3 uses.";
			case SERUM -> "Right-click to spectate for " + s.serumSeconds() + " seconds. You stay wherever you end up, in open space.";
			case LOOTBOX -> "Right-click to open it for diamond armor and a random loadout.";
		};
	}

	/** Rolls whether an impostor gets a secret item (chance in percent) and which one. Null = no secret item. */
	static Kind roll(Random random, int chancePercent) {
		if (random.nextInt(100) >= chancePercent) {
			return null;
		}
		return Kind.values()[random.nextInt(Kind.values().length)];
	}

	private static Drop d(String id, int count) {
		return new Drop("minecraft:" + id, count, "");
	}

	/** A random Lootbox: diamond armor plus one of four loadouts, each 25%. {@code label[0]} gets a description. */
	static List<Drop> lootbox(Random random, String[] label) {
		List<Drop> out = new ArrayList<>();
		for (String s : new String[] {"diamond_helmet", "diamond_chestplate", "diamond_leggings", "diamond_boots"}) {
			out.add(d(s, 1));
		}
		switch (random.nextInt(4)) {
			case 0 -> {
				label[0] = "diamond armor, tools, shield, bow and 3 golden apples";
				for (String s : new String[] {"diamond_sword", "diamond_pickaxe", "diamond_axe", "diamond_shovel", "shield", "bow"}) {
					out.add(d(s, 1));
				}
				out.add(d("arrow", 64));
				out.add(d("golden_apple", 3));
			}
			case 1 -> {
				label[0] = "diamond armor, 16 end crystals and 16 obsidian";
				out.add(d("end_crystal", 16));
				out.add(d("obsidian", 16));
			}
			case 2 -> {
				label[0] = "diamond armor, a mace and 16 wind charges";
				out.add(d("mace", 1));
				out.add(d("wind_charge", 16));
			}
			default -> {
				label[0] = "diamond armor, a Lunge III spear and 64 golden carrots";
				out.add(new Drop("minecraft:diamond_spear", 1, "minecraft:enchantments={\"minecraft:lunge\":3}"));
				out.add(d("golden_carrot", 64));
			}
		}
		return out;
	}

	/** Mahoraga Horn: ravager, wither or warden by weight. */
	static Mob mahoragaRoll(Random random) {
		int roll = random.nextInt(MAHORAGA_RAVAGER + MAHORAGA_WITHER + MAHORAGA_WARDEN);
		if (roll < MAHORAGA_RAVAGER) {
			return Mob.RAVAGER;
		}
		return roll < MAHORAGA_RAVAGER + MAHORAGA_WITHER ? Mob.WITHER : Mob.WARDEN;
	}

	/** Horde Horn: zombies, skeletons or creepers, a third each. */
	static Mob hordeRoll(Random random) {
		return switch (random.nextInt(3)) {
			case 0 -> Mob.ZOMBIES;
			case 1 -> Mob.SKELETONS;
			default -> Mob.CREEPERS;
		};
	}

	static String mobLabel(Mob mob) {
		return switch (mob) {
			case RAVAGER -> "a ravager";
			case WITHER -> "a WITHER";
			case WARDEN -> "a WARDEN";
			case ZOMBIES -> "a horde of helmeted zombies";
			case SKELETONS -> "a horde of helmeted skeletons";
			case CREEPERS -> "a horde of creepers";
		};
	}

	/**
	 * The /summon commands for a mob, to be run at the spot where they should appear (they use relative
	 * coordinates, so run them with "execute at ... run").
	 */
	static List<String> summonCommands(Mob mob, Random random) {
		List<String> out = new ArrayList<>();
		String helmet = "head:{id:\"minecraft:iron_helmet\",count:1}";
		switch (mob) {
			case RAVAGER -> out.add("summon minecraft:ravager ~ ~ ~ {PersistenceRequired:1b}");
			case WITHER -> out.add("summon minecraft:wither ~ ~ ~ {Invul:100}");
			case WARDEN -> out.add("summon minecraft:warden ~ ~ ~ {PersistenceRequired:1b}");
			case ZOMBIES -> {
				for (int i = 0; i < 8; i++) {
					out.add("summon minecraft:zombie " + offset(random) + " ~ " + offset(random)
						+ " {equipment:{" + helmet + "},PersistenceRequired:1b}");
				}
			}
			case SKELETONS -> {
				for (int i = 0; i < 6; i++) {
					out.add("summon minecraft:skeleton " + offset(random) + " ~ " + offset(random)
						+ " {equipment:{" + helmet + ",mainhand:{id:\"minecraft:bow\",count:1}},PersistenceRequired:1b}");
				}
			}
			case CREEPERS -> {
				for (int i = 0; i < 6; i++) {
					out.add("summon minecraft:creeper " + offset(random) + " ~ " + offset(random) + " {PersistenceRequired:1b}");
				}
			}
		}
		return out;
	}

	/** A relative offset of 2 to 6 blocks either way, so mobs ring the spot instead of stacking in it. */
	private static String offset(Random random) {
		int n = 2 + random.nextInt(5);
		return "~" + (random.nextBoolean() ? n : -n);
	}

	// ------------------------------------------------------------------ the egg pits

	private static String in(String dimension, String command) {
		return "execute in " + dimension + " run " + command;
	}

	private static String fill(String dimension, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		return in(dimension, "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block);
	}

	/**
	 * Porta-Pit: a 4x4 hole whose walls and floor are crafting tables, with 2-block-tall pointed dripstone on
	 * every floor block. {@code top} is the y of the block the egg landed on (the top of the hole).
	 */
	static List<String> pitCommands(String dimension, int x, int top, int z, int depth, int minY) {
		int floor = Math.max(top - depth, minY + 2);
		List<String> out = new ArrayList<>();
		out.add(fill(dimension, x - 3, floor, z - 3, x + 2, top, z + 2, "minecraft:crafting_table"));
		out.add(fill(dimension, x - 2, floor + 1, z - 2, x + 1, top + 2, z + 1, "minecraft:air"));
		out.add(fill(dimension, x - 2, floor + 1, z - 2, x + 1, floor + 1, z + 1,
			"minecraft:pointed_dripstone[thickness=frustum,vertical_direction=up]"));
		out.add(fill(dimension, x - 2, floor + 2, z - 2, x + 1, floor + 2, z + 1,
			"minecraft:pointed_dripstone[thickness=tip,vertical_direction=up]"));
		return out;
	}

	/** Void Egg: a plain 2x2 shaft, with no lining, that goes all the way down to the void (so it is hard to spot). */
	static List<String> voidCommands(String dimension, int x, int top, int z, int minY) {
		List<String> out = new ArrayList<>();
		out.add(fill(dimension, x - 1, minY, z - 1, x, top + 2, z, "minecraft:air"));
		return out;
	}
}
