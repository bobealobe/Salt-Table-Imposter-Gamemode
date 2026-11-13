package com.impostorsmp;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.server.MinecraftServer;

/**
 * Titles, sounds and other effects, done by running ordinary Minecraft commands as the server.
 * Commands are the most stable part of the game, so this keeps the mod simple and easy to repair.
 */
final class Fx {
	private Fx() {
	}

	/** Runs one command as the server console (no leading slash needed). Never throws. */
	static void run(MinecraftServer server, String command) {
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
		} catch (RuntimeException e) {
			ImpostorLog.warn("Command failed: " + command + " (" + e + ")");
		}
	}

	/** Runs one command and returns null if it worked, otherwise the reason it failed (so it can be shown). */
	static String runChecked(MinecraftServer server, String command) {
		try {
			server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
			return null;
		} catch (CommandSyntaxException e) {
			return e.getMessage();
		} catch (RuntimeException e) {
			return e.toString();
		}
	}

	/** A text component in JSON form, e.g. {"text":"IMPOSTOR","color":"red","bold":true}. */
	static String text(String text, String color, boolean bold) {
		return "{\"text\":\"" + escape(text) + "\",\"color\":\"" + color + "\",\"bold\":" + bold + "}";
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/** Title timing in ticks (20 ticks = 1 second). */
	static void times(MinecraftServer server, String target, int fadeIn, int stay, int fadeOut) {
		run(server, "title " + target + " times " + fadeIn + " " + stay + " " + fadeOut);
	}

	static void subtitle(MinecraftServer server, String target, String json) {
		run(server, "title " + target + " subtitle " + json);
	}

	static void title(MinecraftServer server, String target, String json) {
		run(server, "title " + target + " title " + json);
	}

	/**
	 * Plays a sound to the target(s) at full volume wherever they are, because it is played at each
	 * listener's own position.
	 */
	static void sound(MinecraftServer server, String target, String soundId, double volume, double pitch) {
		run(server, "execute as " + target + " at @s run playsound " + soundId + " master @s ~ ~ ~ " + volume + " " + pitch);
	}
}
