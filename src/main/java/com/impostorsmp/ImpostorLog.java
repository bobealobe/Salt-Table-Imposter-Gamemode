package com.impostorsmp;

/**
 * Tiny logging shim so the plain-Java classes (Rules, GameState) don't depend on any library.
 * The mod's entrypoint routes this into the real game log.
 */
public final class ImpostorLog {
	public interface Sink {
		void warn(String message);
	}

	private static Sink sink = message -> System.err.println("[impostorsmp] " + message);

	private ImpostorLog() {
	}

	public static void setSink(Sink newSink) {
		sink = newSink;
	}

	public static void warn(String message) {
		sink.warn(message);
	}
}
