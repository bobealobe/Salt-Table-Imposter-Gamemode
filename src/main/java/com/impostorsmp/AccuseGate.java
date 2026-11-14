package com.impostorsmp;

/** The two limits on call-outs: a cooldown after each accusation, and being close enough. No Minecraft code, so it can be tested. */
final class AccuseGate {
	private AccuseGate() {
	}

	/** Seconds until this player may accuse again; 0 or less means now. {@code lastTick} is null if they never accused. */
	static int secondsLeft(Integer lastTick, int nowTick, int cooldownMinutes) {
		if (lastTick == null || cooldownMinutes <= 0) {
			return 0;
		}
		long endsAt = lastTick + cooldownMinutes * 1200L;
		long left = endsAt - nowTick;
		return left <= 0 ? 0 : (int) Math.ceil(left / 20.0);
	}

	/** "12 min 30 s", "45 s". */
	static String format(int seconds) {
		return seconds >= 60 ? (seconds / 60) + " min " + (seconds % 60) + " s" : seconds + " s";
	}

	static boolean closeEnough(boolean sameDimension, double distance, int range) {
		return sameDimension && distance <= range;
	}
}
