package com.impostorsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The secret impostor items: which ones exist, how they are named and written as /give text, what a Lootbox
 * contains and what the Mahoraga Horn summons. No Minecraft code in here on purpose, so it can be tested on
 * its own. Edit the numbers in lootbox() and hornRoll() to change the odds.
 */
final class Secret {
	enum Kind { VANISH, ONE_SHOT, HORN, LOOTBOX }

	enum Mob { ZOMBIES, CREEPERS, RAVAGER, WITHER, WARDEN }

	/** One thing a Lootbox gives: an item id with optional components in [] and a count. */
	record Drop(String id, int count, String components) {
		String itemText() {
			return components.isEmpty() ? id : id + "[" + components + "]";
		}
	}

	private Secret() {
	}

	static String name(Kind kind) {
		return switch (kind) {
			case VANISH -> "Vanish Cloak";
			case ONE_SHOT -> "One Shot One Kill";
			case HORN -> "Mahoraga Horn";
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

	static Kind parse(String text) {
		String t = text.toLowerCase(Locale.ROOT);
		return switch (t) {
			case "vanish", "invisibility" -> Kind.VANISH;
			case "arrow", "oneshot", "one_shot" -> Kind.ONE_SHOT;
			case "horn", "mahoraga" -> Kind.HORN;
			case "lootbox", "loot" -> Kind.LOOTBOX;
			default -> null;
		};
	}

	/** The item as text for /give or /item replace, with its name and a hidden tag. */
	static String itemText(Kind kind) {
		String color = switch (kind) {
			case VANISH -> "dark_purple";
			case ONE_SHOT -> "dark_red";
			case HORN -> "gold";
			case LOOTBOX -> "yellow";
		};
		String base = switch (kind) {
			case VANISH -> "minecraft:phantom_membrane";
			case ONE_SHOT -> "minecraft:arrow";
			case HORN -> "minecraft:goat_horn[minecraft:instrument=\"minecraft:ponder_goat_horn\",";
			case LOOTBOX -> "minecraft:echo_shard[minecraft:item_model=\"minecraft:chest\",";
		};
		String common = "minecraft:item_name={text:\"" + name(kind) + "\",color:\"" + color + "\"},"
			+ "minecraft:custom_data={impostorsmp:\"" + kind.name().toLowerCase(Locale.ROOT) + "\"}";
		return base.endsWith(",") ? base + common + "]" : base + "[" + common + "]";
	}

	/** What the impostor is told when the item arrives. */
	static String howToUse(Kind kind, int vanishSeconds) {
		return switch (kind) {
			case VANISH -> "Right-click to vanish for " + vanishSeconds + " seconds: no nametag, no armor, no held item. "
				+ "Your armor and hotbar are tucked into your inventory and come back when it ends.";
			case ONE_SHOT -> "Load it into a dispenser. When the dispenser fires it, whoever it hits dies instantly. "
				+ "It does nothing special from a bow or crossbow.";
			case HORN -> "Right-click to summon a random mob: zombies, creepers, a ravager, a wither or a warden.";
			case LOOTBOX -> "Right-click to open it and get a random loadout.";
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

	/** A random Lootbox: 50% iron kit, 20% shield and gear, 10% mace, 10% end crystals, 10% spear. Returns a description and the drops. */
	static List<Drop> lootbox(Random random, String[] label) {
		int roll = random.nextInt(100);
		List<Drop> out = new ArrayList<>();
		if (roll < 50) {
			label[0] = "Full iron set";
			for (String s : new String[] {"iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots", "iron_sword",
				"iron_pickaxe", "iron_axe", "iron_shovel", "iron_hoe"}) {
				out.add(d(s, 1));
			}
		} else if (roll < 70) {
			label[0] = "Shield, diamond sword, diamond axe, iron pickaxe and copper armor";
			for (String s : new String[] {"shield", "diamond_sword", "diamond_axe", "iron_pickaxe", "copper_helmet",
				"copper_chestplate", "copper_leggings", "copper_boots"}) {
				out.add(d(s, 1));
			}
		} else if (roll < 80) {
			label[0] = "Mace and wind charges";
			out.add(d("mace", 1));
			out.add(d("wind_charge", 16));
		} else if (roll < 90) {
			label[0] = "End crystals and obsidian";
			out.add(d("end_crystal", 6));
			out.add(d("obsidian", 16));
		} else {
			label[0] = "Diamond spear (Lunge III) and food";
			out.add(new Drop("minecraft:diamond_spear", 1, "minecraft:enchantments={\"minecraft:lunge\":3}"));
			out.add(d("cooked_beef", 32));
		}
		return out;
	}

	/** 50% zombie horde, 25% creeper horde, 15% ravager, 5% wither, 5% warden. */
	static Mob hornRoll(Random random) {
		int roll = random.nextInt(100);
		if (roll < 50) {
			return Mob.ZOMBIES;
		}
		if (roll < 75) {
			return Mob.CREEPERS;
		}
		if (roll < 90) {
			return Mob.RAVAGER;
		}
		if (roll < 95) {
			return Mob.WITHER;
		}
		return Mob.WARDEN;
	}

	static String mobLabel(Mob mob) {
		return switch (mob) {
			case ZOMBIES -> "a horde of helmeted zombies";
			case CREEPERS -> "a horde of creepers";
			case RAVAGER -> "a ravager";
			case WITHER -> "a WITHER";
			case WARDEN -> "a WARDEN";
		};
	}

	/**
	 * The /summon commands for a mob, to be run at the spot where they should appear (they use relative
	 * coordinates, so run them with "execute at ... run").
	 */
	static List<String> summonCommands(Mob mob, Random random) {
		List<String> out = new ArrayList<>();
		switch (mob) {
			case ZOMBIES -> {
				for (int i = 0; i < 8; i++) {
					out.add("summon minecraft:zombie " + offset(random) + " ~ " + offset(random)
						+ " {equipment:{head:{id:\"minecraft:iron_helmet\",count:1}},PersistenceRequired:1b}");
				}
			}
			case CREEPERS -> {
				for (int i = 0; i < 6; i++) {
					out.add("summon minecraft:creeper " + offset(random) + " ~ " + offset(random) + " {PersistenceRequired:1b}");
				}
			}
			case RAVAGER -> out.add("summon minecraft:ravager ~ ~ ~ {PersistenceRequired:1b}");
			case WITHER -> out.add("summon minecraft:wither ~ ~ ~ {Invul:100}");
			case WARDEN -> out.add("summon minecraft:warden ~ ~ ~ {PersistenceRequired:1b}");
		}
		return out;
	}

	/** A relative offset of 2 to 6 blocks either way, so mobs ring the spot instead of stacking in it. */
	private static String offset(Random random) {
		int n = 2 + random.nextInt(5);
		return "~" + (random.nextBoolean() ? n : -n);
	}
}
