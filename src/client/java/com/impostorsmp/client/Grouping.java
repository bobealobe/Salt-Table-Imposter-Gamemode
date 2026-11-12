package com.impostorsmp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Splits players into spawn groups (pairs, trios, ...). No Minecraft code in here on purpose,
 * so it can be tested on its own.
 */
final class Grouping {
	private Grouping() {
	}

	/**
	 * Splits the players into as few groups as possible, each holding at most {@code size} players,
	 * with the group sizes as even as possible (6 players, size 2 -> 2+2+2; 5 players -> 2+2+1).
	 *
	 * @param splitImpostors when true, impostors are spread over different groups so two of them are
	 *                       never put together, as long as there are enough groups to do that.
	 */
	static List<List<UUID>> make(List<UUID> players, Set<UUID> impostors, int size, boolean splitImpostors, Random random) {
		int n = players.size();
		List<List<UUID>> groups = new ArrayList<>();
		if (n == 0) {
			return groups;
		}
		int groupCount = Math.max(1, (int) Math.ceil(n / (double) Math.max(1, size)));
		int[] capacity = new int[groupCount];
		for (int i = 0; i < groupCount; i++) {
			capacity[i] = n / groupCount + (i < n % groupCount ? 1 : 0);
			groups.add(new ArrayList<>());
		}
		// Which group gets the extra person is random too.
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < groupCount; i++) {
			order.add(i);
		}

		List<UUID> impostorList = new ArrayList<>();
		List<UUID> otherList = new ArrayList<>();
		for (UUID id : players) {
			(impostors.contains(id) ? impostorList : otherList).add(id);
		}
		Collections.shuffle(impostorList, random);
		Collections.shuffle(otherList, random);

		if (splitImpostors) {
			for (UUID imp : impostorList) {
				Collections.shuffle(order, random);
				int best = -1;
				int bestCount = Integer.MAX_VALUE;
				for (int g : order) {
					if (groups.get(g).size() >= capacity[g]) {
						continue;
					}
					int count = countOf(groups.get(g), impostors);
					if (count < bestCount) {
						best = g;
						bestCount = count;
					}
				}
				groups.get(best).add(imp);
			}
		} else {
			otherList.addAll(impostorList);
			Collections.shuffle(otherList, random);
		}

		for (UUID id : otherList) {
			Collections.shuffle(order, random);
			for (int g : order) {
				if (groups.get(g).size() < capacity[g]) {
					groups.get(g).add(id);
					break;
				}
			}
		}
		return groups;
	}

	private static int countOf(List<UUID> group, Set<UUID> impostors) {
		int count = 0;
		for (UUID id : group) {
			if (impostors.contains(id)) {
				count++;
			}
		}
		return count;
	}
}
