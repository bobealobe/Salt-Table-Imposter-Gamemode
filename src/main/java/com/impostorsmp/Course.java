package com.impostorsmp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The obstacle course timer. A player's timer starts with their first jump off the start platform, stops when
 * they stand on the finish platform, and is cancelled if they fall off. No Minecraft code in here on purpose,
 * so it can be tested on its own. The platform positions are shared with {@link LobbyPlan}, which builds them.
 */
final class Course {
	enum Kind { NONE, STARTED, FINISHED, CANCELLED }

	record Event(Kind kind, double seconds) {
	}

	private static final Event NOTHING = new Event(Kind.NONE, 0);

	// Start platform and finish platform, in blocks (x1, z1, x2, z2 inclusive), relative to the lobby centre.
	static final int START_X1 = -2, START_Z1 = 17, START_X2 = 2, START_Z2 = 19;
	static final int FINISH_X1 = -2, FINISH_Z1 = 43, FINISH_X2 = 2, FINISH_Z2 = 48;
	/** Layer (above the lobby height) of the start and finish platform blocks. You stand one higher. */
	static final int START_DY = 3, FINISH_DY = 6;

	private final Map<UUID, Integer> started = new HashMap<>();
	/** Players who were standing on the start platform on the previous tick. */
	private final java.util.Set<UUID> onPlatform = new java.util.HashSet<>();

	boolean running(UUID id) {
		return started.containsKey(id);
	}

	/** Seconds since the timer started, or 0 if it is not running. */
	double elapsed(UUID id, int tick) {
		Integer t = started.get(id);
		return t == null ? 0 : (tick - t) / 20.0;
	}

	void clear() {
		started.clear();
		onPlatform.clear();
	}

	void forget(UUID id) {
		started.remove(id);
		onPlatform.remove(id);
	}

	private static boolean inside(double x, double z, int x1, int z1, int x2, int z2) {
		return x >= x1 && x < x2 + 1 && z >= z1 && z < z2 + 1;
	}

	/**
	 * Call once per tick per player with their position. {@code lobbyY} is the height players stand at on the
	 * lobby floor.
	 */
	Event update(UUID id, double x, double y, double z, boolean onGround, int tick, int lobbyY) {
		boolean running = started.containsKey(id);
		if (!running) {
			double standing = lobbyY + START_DY + 1;
			boolean onStart = inside(x, z, START_X1, START_Z1, START_X2, START_Z2);
			boolean wasOnPlatform = onPlatform.contains(id);
			boolean standingNow = onGround && onStart && Math.abs(y - standing) < 0.2;
			if (standingNow) {
				onPlatform.add(id);
			} else {
				onPlatform.remove(id);
			}
			// A jump straight off the platform: standing there a tick ago, now in the air and higher up. This works
			// even at the very edge, where the jump carries you out of the platform area within one tick.
			boolean jumpedOff = wasOnPlatform && !onGround && y > standing + 0.05
				&& x > START_X1 - 3 && x < START_X2 + 4 && z > START_Z1 - 3 && z < START_Z2 + 4;
			// Or jumping in the air above the platform.
			boolean hopping = onStart && !onGround && y > standing + 0.2;
			if (jumpedOff || hopping) {
				onPlatform.remove(id);
				started.put(id, tick);
				return new Event(Kind.STARTED, 0);
			}
			return NOTHING;
		}
		boolean inHall = x > -11 && x < 12 && z > 12 && z < 50;
		if (y < lobbyY + 2.5 || !inHall) {
			started.remove(id);
			return new Event(Kind.CANCELLED, 0);
		}
		if (onGround && y >= lobbyY + FINISH_DY + 0.9
			&& inside(x, z, FINISH_X1, FINISH_Z1, FINISH_X2, FINISH_Z2)) {
			double seconds = (tick - started.remove(id)) / 20.0;
			return new Event(Kind.FINISHED, seconds);
		}
		return NOTHING;
	}
}
