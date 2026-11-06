package com.impostorsmp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

/** Locks down chat and hides death / advancement / join / leave messages, according to the rules. */
final class ChatRules {
	private ChatRules() {
	}

	static void register() {
		// Normal chat. Dead players may talk to each other; everybody else is muted.
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, boundChatType) -> {
			GameController game = ImpostorMod.controller();
			if (game == null || game.chatOpen()) {
				return true;
			}
			if (sender.isSpectator() && game.rules().bool("dead_chat")) {
				game.deadChat(sender, message.signedContent());
				return false;
			}
			sender.sendSystemMessage(Component.literal("Chat is disabled.").withStyle(ChatFormatting.GRAY));
			return false;
		});

		// /me and /say from players. (The server console and command blocks are always allowed.)
		ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register((message, source, boundChatType) -> {
			GameController game = ImpostorMod.controller();
			if (game == null || game.chatOpen()) {
				return true;
			}
			return source.getPlayer() == null;
		});

		// Server announcements: deaths, advancements, joins and leaves.
		ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> {
			GameController game = ImpostorMod.controller();
			if (game == null) {
				return true;
			}
			if (message.getContents() instanceof TranslatableContents translatable) {
				String key = translatable.getKey();
				Rules rules = game.rules();
				if (key.startsWith("death.") && rules.bool("hide_death_messages")) {
					return false;
				}
				if (key.startsWith("chat.type.advancement.") && rules.bool("hide_advancements")) {
					return false;
				}
				if (rules.bool("hide_join_leave") && (key.equals("multiplayer.player.joined")
					|| key.equals("multiplayer.player.joined.renamed") || key.equals("multiplayer.player.left"))) {
					return false;
				}
			}
			return true;
		});
	}
}
