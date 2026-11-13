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
		one("piston", 12, "Piston x12", 3),
		one("observer", 12, "Observer x12", 3),
		one("sticky_piston", 8, "Sticky piston x8", 2),
		one("redstone", 32, "Redstone dust x32", 2),
		one("repeater", 4, "Repeater x4", 1),
		one("tnt", 6, "TNT x6", 2),
		one("tnt_minecart", 3, "TNT minecart x3", 2),
		one("slime_block", 4, "Slime block x4", 1));

	/** Ready-made sets. Each one has enough in it to build a trap of that kind. */
	static final List<Entry> BUNDLES = List.of(
		new Entry("Piston trap set (8 piston, 8 observer, 20 redstone dust)",
			List.of(i("piston", 8), i("observer", 8), i("redstone", 20)), 3),
		new Entry("Crusher set (6 sticky piston, 4 slime block, 6 observer, 16 redstone dust)",
			List.of(i("sticky_piston", 6), i("slime_block", 4), i("observer", 6), i("redstone", 16)), 2),
		new Entry("TNT set (5 TNT, 4 observer, 12 redstone dust, 2 repeater)",
			List.of(i("tnt", 5), i("observer", 4), i("redstone", 12), i("repeater", 2)), 2),
		new Entry("Minecart set (3 TNT minecart, 16 rail, 4 powered rail, 8 redstone dust)",
			List.of(i("tnt_minecart", 3), i("rail", 16), i("powered_rail", 4), i("redstone", 8)), 2));

	private Kit() {
	}

	/**
	 * One fast item, then {@code builders} different builder slots. Each builder slot is a ready-made set
	 * with a {@code bundleChance} percent chance, otherwise a single item.
	 */
	static List<Entry> roll(Random random, int builders, int bundleChance) {
		List<Entry> result = new ArrayList<>();
		result.add(pick(new ArrayList<>(FAST), random));
		List<Entry> singles = new ArrayList<>(SINGLES);
		List<Entry> bundles = new ArrayList<>(BUNDLES);
		for (int n = 0; n < builders; n++) {
			boolean useBundle = !bundles.isEmpty() && (singles.isEmpty() || random.nextInt(100) < bundleChance);
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
