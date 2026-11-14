package com.impostorsmp;

import java.util.List;

/**
 * The impostor compass rules: when it is offered, what each choice gives and when, and which player is
 * nearest. No Minecraft code in here on purpose, so it can be tested on its own.
 *
 * Stages of one impostor: NONE (not offered yet) -> OFFERED (the buttons were sent) -> NEAREST (has the
 * nearest-player compass) or WAITING (picked the stronghold compass, not here yet) -> STRONGHOLD.
 */
final class CompassPlan {
	static final int NONE = 0;
	static final int OFFERED = 1;
	static final int NEAREST = 2;
	static final int WAITING = 3;
	static final int STRONGHOLD = 4;

	enum Choice { NEAREST_PLAYER, STRONGHOLD }

	enum Outcome { NOT_YET, ALREADY_CHOSEN, GIVE_NEAREST, WAIT_FOR_STRONGHOLD, GIVE_STRONGHOLD }

	private CompassPlan() {
	}

	/** True once the choice should be offered to an impostor who has not been offered it yet. */
	static boolean offerDue(int stage, int elapsedSeconds, int offerMinutes) {
		return stage == NONE && elapsedSeconds >= offerMinutes * 60;
	}

	/** True when a waiting impostor's stronghold compass is ready. */
	static boolean strongholdDue(int stage, int elapsedSeconds, int offerMinutes, int waitMinutes) {
		return stage == WAITING && elapsedSeconds >= (offerMinutes + waitMinutes) * 60;
	}

	/** What happens when an impostor picks a compass. */
	static Outcome choose(int stage, Choice choice, int elapsedSeconds, int offerMinutes, int waitMinutes) {
		if (elapsedSeconds < offerMinutes * 60) {
			return Outcome.NOT_YET;
		}
		if (stage >= NEAREST) {
			return Outcome.ALREADY_CHOSEN;
		}
		if (choice == Choice.NEAREST_PLAYER) {
			return Outcome.GIVE_NEAREST;
		}
		return elapsedSeconds >= (offerMinutes + waitMinutes) * 60 ? Outcome.GIVE_STRONGHOLD : Outcome.WAIT_FOR_STRONGHOLD;
	}

	/** Seconds until the stronghold compass is ready for someone who picked it (0 if it is ready). */
	static int secondsUntilStronghold(int elapsedSeconds, int offerMinutes, int waitMinutes) {
		return Math.max(0, (offerMinutes + waitMinutes) * 60 - elapsedSeconds);
	}

	/** Index of the nearest point in {@code others} (each {x, y, z}), or -1 if there are none. */
	static int nearest(double x, double y, double z, List<double[]> others) {
		int best = -1;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < others.size(); i++) {
			double[] o = others.get(i);
			double d = (o[0] - x) * (o[0] - x) + (o[1] - y) * (o[1] - y) + (o[2] - z) * (o[2] - z);
			if (d < bestDistance) {
				bestDistance = d;
				best = i;
			}
		}
		return best;
	}
}
