package com.impostorsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The impostor kits. Every impostor gets exactly one of them at random (all equally likely). No Minecraft
 * code in here on purpose, so it can be tested on its own. Edit KITS to change what is in them.
 */
final class Kit {
	/** One item: id, how many, and optional components in [] (enchantments and so on). */
	record Item(String id, int count, String components) {
		/** How many of this item fit in one inventory slot. */
		int stackSize() {
			String name = id.substring(id.indexOf(':') + 1);
			if (name.endsWith("_bucket") || name.endsWith("_pickaxe") || name.endsWith("_shovel") || name.endsWith("_axe")
				|| name.endsWith("_sword") || name.endsWith("_spear") || name.endsWith("_hoe")) {
				return 1;
			}
			return switch (name) {
				case "flint_and_steel", "tnt_minecart", "bow", "crossbow", "shield", "mace" -> 1;
				case "ender_pearl", "egg" -> 16;
				default -> 64;
			};
		}

		/** The item split into legal stacks, e.g. 3 pufferfish buckets are three stacks of 1. */
		List<Integer> stacks() {
			List<Integer> out = new ArrayList<>();
			for (int left = count; left > 0; left -= stackSize()) {
				out.add(Math.min(left, stackSize()));
			}
			return out;
		}

		/** The item as text for /give or /item replace (without the count). */
		String itemText() {
			return components.isEmpty() ? id : id + "[" + components + "]";
		}
	}

	/** One kit: its name, the items, and a few short lines describing it (shown in the lobby). */
	record Entry(String name, List<Item> items, List<String> blurb) {
	}

	private static Item i(String id, int count) {
		return new Item("minecraft:" + id, count, "");
	}

	/** An item with enchantments, e.g. e("iron_pickaxe", "efficiency", 5). */
	private static Item e(String id, String enchantment, int level) {
		return new Item("minecraft:" + id, 1, "minecraft:enchantments={\"minecraft:" + enchantment + "\":" + level + "}");
	}

	static final List<Entry> KITS = List.of(
		new Entry("Pitfall Kit",
			List.of(i("scaffolding", 32), i("pointed_dripstone", 16), e("iron_pickaxe", "efficiency", 5),
				e("iron_shovel", "efficiency", 5), e("wooden_sword", "knockback", 2)),
			List.of("32 scaffolding, 16 dripstone", "Efficiency 5 iron pickaxe + shovel", "Knockback 2 wooden sword")),
		new Entry("TNT Kit",
			List.of(i("tnt", 10), i("flint_and_steel", 1), e("iron_spear", "knockback", 2)),
			List.of("10 TNT, flint and steel", "Knockback 2 iron spear")),
		new Entry("Full-Box Kit",
			List.of(i("cobweb", 24), i("lava_bucket", 1), i("water_bucket", 1), i("diamond_pickaxe", 1)),
			List.of("24 cobwebs, lava + water bucket", "Diamond pickaxe")),
		new Entry("TNT Minecart Kit",
			List.of(i("tnt_minecart", 6), i("rail", 16), i("powered_rail", 16), i("observer", 3)),
			List.of("6 TNT minecarts, 16 rails", "16 powered rails, 3 observers")),
		new Entry("Pufferfish Kit",
			List.of(i("pufferfish_bucket", 3), i("kelp", 64), i("soul_sand", 16), e("iron_pickaxe", "efficiency", 5),
				e("iron_shovel", "efficiency", 5), e("wooden_sword", "knockback", 2)),
			List.of("3 pufferfish buckets, 64 kelp", "16 soul sand", "Efficiency 5 iron pickaxe + shovel,", "knockback 2 wooden sword")));

	private Kit() {
	}

	/** A random kit; every kit is equally likely. */
	static Entry roll(Random random) {
		return KITS.get(random.nextInt(KITS.size()));
	}

	/** "32 scaffolding" or "efficiency-enchanted iron pickaxe", for the chat message. */
	static String label(Item item) {
		String name = item.id().substring(item.id().indexOf(':') + 1).replace('_', ' ');
		String text = item.count() > 1 ? item.count() + " " + name : name;
		return item.components().isEmpty() ? text : text + " (enchanted)";
	}
}
