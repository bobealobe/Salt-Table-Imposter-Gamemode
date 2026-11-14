package com.impostorsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The trap kit an impostor starts with: one fast trap item plus a few builder slots. A builder slot is
 * either a single item or a ready-made set of items that go together. No Minecraft code in here on
 * purpose, so it can be tested on its own. Edit the lists to change the pool.
 */
final class Kit {
	record Item(String id, int count) {
		/** How many of this item fit in one inventory slot. */
		int stackSize() {
			return switch (id) {
				case "minecraft:lava_bucket", "minecraft:powder_snow_bucket", "minecraft:flint_and_steel",
					"minecraft:tnt_minecart" -> 1;
				default -> 64;
			};
		}

		/** The item split into legal stacks, e.g. 3 TNT minecarts are three stacks of 1. */
		List<Integer> stacks() {
			List<Integer> out = new ArrayList<>();
			for (int left = count; left > 0; left -= stackSize()) {
				out.add(Math.min(left, stackSize()));
			}
			return out;
		}
	}


	/** One slot of the kit: a name shown to the player, the items it gives, and how likely it is. */
	record Entry(String name, List<Item> items, int weight) {
	}

	private static Entry one(String id, int count, String name, int weight) {
		return new Entry(name, List.of(new Item("minecraft:" + id, count)), weight);
	}

	private static Item i(String id, int count) {
		return new Item("minecraft:" + id, count);
	}

	static final List<Entry> FAST = List.of(
		one("lava_bucket", 1, "Lava bucket", 1),
		one("flint_and_steel", 1, "Flint and steel", 1),
		one("powder_snow_bucket", 1, "Powder snow bucket", 1),
		one("pointed_dripstone", 6, "Pointed dripstone x6", 1));

	static final List<Entry> SINGLES = List.of(
		one("tnt", 6, "TNT x6", 3),
		one("tnt_minecart", 3, "TNT minecart x3", 2),
		one("cobweb", 16, "Cobweb x16", 3),
		one("ender_pearl", 3, "Ender pearl x3", 2));

	/** Ready-made sets. Each one has enough in it to build a trap of that kind. */
	static final List<Entry> BUNDLES = List.of(
		new Entry("Pitfall set (32 scaffolding, 4 observer, 4 sticky piston)",
			List.of(i("scaffolding", 32), i("observer", 4), i("sticky_piston", 4)), 3),
		new Entry("TNT set (5 TNT, 4 observer, 12 redstone dust)",
			List.of(i("tnt", 5), i("observer", 4), i("redstone", 12)), 3),
		new Entry("Web set (24 cobweb, 1 flint and steel, 3 TNT)",
			List.of(i("cobweb", 24), i("flint_and_steel", 1), i("tnt", 3)), 2),
		new Entry("Minecart set (3 TNT minecart, 16 rail, 4 powered rail)",
			List.of(i("tnt_minecart", 3), i("rail", 16), i("powered_rail", 4)), 2));

	private Kit() {
	}

	/**
	 * One fast item, then {@code builders} different builder slots. Only the last builder slot can be a
	 * ready-made set (with a {@code bundleChance} percent chance); every slot before it is a single item.
	 * So a kit never has more than one set.
	 */
	static List<Entry> roll(Random random, int builders, int bundleChance) {
		List<Entry> result = new ArrayList<>();
		result.add(pick(new ArrayList<>(FAST), random));
		List<Entry> singles = new ArrayList<>(SINGLES);
		List<Entry> bundles = new ArrayList<>(BUNDLES);
		for (int n = 0; n < builders; n++) {
			boolean last = n == builders - 1;
			boolean useBundle = !bundles.isEmpty() && (singles.isEmpty() || (last && random.nextInt(100) < bundleChance));
			List<Entry> from = useBundle ? bundles : singles;
			if (from.isEmpty()) {
				break;
			}
			result.add(pick(from, random));
		}
		return result;
	}

	/** Picks one entry by weight and removes it from the list. */
	private static Entry pick(List<Entry> pool, Random random) {
		int total = 0;
		for (Entry e : pool) {
			total += e.weight();
		}
		int roll = random.nextInt(total);
		for (Entry e : pool) {
			roll -= e.weight();
			if (roll < 0) {
				pool.remove(e);
				return e;
			}
		}
		return pool.remove(0);
	}
}
