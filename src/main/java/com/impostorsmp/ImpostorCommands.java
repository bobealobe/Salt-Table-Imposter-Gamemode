package com.impostorsmp;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.impostorsmp.GameState.Member;
import com.impostorsmp.GameState.Phase;
import com.impostorsmp.GameState.Role;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;

/**
 * /imp ...
 *   role                  anybody: shows your own role
 *   start / stop          host: begin or cancel a game
 *   accuse <player>       anybody: call somebody out (accuse clear takes it back)
 *   status / reveal       host: progress / everybody's roles (only after the game ends)
 *   rules / rule <name> [value]   host: look at or change the rules
 *   lock / unlock         host: make yourself the only one who can use the host commands
 */
final class ImpostorCommands {
	private static final Identifier ADMIN = Identifier.fromNamespaceAndPath(ImpostorMod.MOD_ID, "command/admin");

	private ImpostorCommands() {
	}

	private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
		return Commands.literal(name).requires(PermissionPredicates.require(ADMIN, PermissionLevel.GAMEMASTERS));
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("imp");
			root.executes(ctx -> help(ctx.getSource()));
			root.then(Commands.literal("role").executes(ctx -> role(ctx.getSource())));
			root.then(Commands.literal("accuse")
				.then(Commands.literal("clear").executes(ctx -> clearAccusation(ctx.getSource())))
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> accuse(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));
			root.then(admin("start").executes(ctx -> start(ctx.getSource())));
			root.then(admin("test")
				.then(Commands.literal("impostor").executes(ctx -> startTest(ctx.getSource(), Role.IMPOSTOR)))
				.then(Commands.literal("innocent").executes(ctx -> startTest(ctx.getSource(), Role.INNOCENT)))
				.then(Commands.literal("unlock").executes(ctx -> testUnlock(ctx.getSource())))
				.then(Commands.literal("kit").executes(ctx -> testKit(ctx.getSource()))));
			root.then(admin("lobby")
				.executes(ctx -> lobby(ctx.getSource()))
				.then(Commands.literal("remove").executes(ctx -> lobbyRemove(ctx.getSource()))));
			root.then(admin("stop").executes(ctx -> stop(ctx.getSource())));
			root.then(admin("status").executes(ctx -> status(ctx.getSource())));
			root.then(admin("reveal").executes(ctx -> reveal(ctx.getSource())));
			root.then(admin("rules").executes(ctx -> listRules(ctx.getSource())));
			root.then(admin("lock").executes(ctx -> lock(ctx.getSource())));
			root.then(admin("unlock").executes(ctx -> unlock(ctx.getSource())));

			LiteralArgumentBuilder<CommandSourceStack> rule = admin("rule");
			for (Rules.Def def : Rules.DEFS) {
				LiteralArgumentBuilder<CommandSourceStack> one = Commands.literal(def.name())
					.executes(ctx -> showRule(ctx.getSource(), def));
				// The extra "menu" ending is what the clickable buttons use: it changes the rule and then
				// shows the section again. Typing /imp rule <name> <value> works exactly as before.
				if (def.type() == Rules.Type.BOOL) {
					one.then(Commands.argument("value", BoolArgumentType.bool())
						.executes(ctx -> setRule(ctx.getSource(), def,
							Boolean.toString(BoolArgumentType.getBool(ctx, "value")), false))
						.then(Commands.literal("menu").executes(ctx -> setRule(ctx.getSource(), def,
							Boolean.toString(BoolArgumentType.getBool(ctx, "value")), true))));
				} else {
					one.then(Commands.argument("value", IntegerArgumentType.integer(def.min(), def.max()))
						.executes(ctx -> setRule(ctx.getSource(), def,
							Integer.toString(IntegerArgumentType.getInteger(ctx, "value")), false))
						.then(Commands.literal("menu").executes(ctx -> setRule(ctx.getSource(), def,
							Integer.toString(IntegerArgumentType.getInteger(ctx, "value")), true))));
				}
				rule.then(one);
			}
			rule.then(Commands.literal("reset").executes(ctx -> resetRules(ctx.getSource())));
			root.then(rule);

			dispatcher.register(root);
		});
	}

	// ------------------------------------------------------------------ helpers

	private static GameController game(CommandSourceStack src) {
		GameController game = ImpostorMod.controller();
		if (game == null) {
			src.sendFailure(Component.literal("The game is not ready yet."));
		}
		return game;
	}

	/** Host check on top of the operator permission: if /imp lock was used, only the locked-in name(s) pass. */
	private static boolean allowed(CommandSourceStack src, GameController game) {
		Set<String> admins = game.rules().admins();
		if (admins.isEmpty()) {
			return true;
		}
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			return true; // server console
		}
		if (admins.contains(player.getGameProfile().name().toLowerCase(Locale.ROOT))) {
			return true;
		}
		src.sendFailure(Component.literal("Only the host can use that command."));
		return false;
	}

	private static void say(CommandSourceStack src, String text) {
		src.sendSuccess(() -> Component.literal(text), false);
	}

	// ------------------------------------------------------------------ commands

	private static int help(CommandSourceStack src) {
		say(src, "/imp role - see your own role");
		say(src, "/imp accuse <player> - call somebody out (enough accusations reveal their role; /imp accuse clear takes it back)");
		say(src, "Solo test (host): /imp test impostor | innocent | unlock | kit");
		say(src, "Lobby (host): /imp lobby builds a sky lobby with a start button, /imp lobby remove takes it down");
		say(src, "Host: /imp start, /imp stop, /imp status, /imp reveal, /imp rules, /imp rule <name> <value>, /imp lock");
		return 1;
	}

	private static int role(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null) {
			return 0;
		}
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			src.sendFailure(Component.literal("Only players have a role."));
			return 0;
		}
		GameState state = game.state();
		if (state.phase() == Phase.IDLE) {
			say(src, "No game is running.");
			return 1;
		}
		Member member = state.member(player.getUUID()).orElse(null);
		if (member == null) {
			say(src, "You are not part of this game.");
		} else {
			say(src, "You are " + (member.role() == Role.IMPOSTOR ? "an IMPOSTOR" : "INNOCENT")
				+ (member.alive() ? "." : " (dead)."));
		}
		return 1;
	}

	private static int accuse(CommandSourceStack src, ServerPlayer target) throws CommandSyntaxException {
		GameController game = game(src);
		if (game == null) {
			return 0;
		}
		ServerPlayer player = src.getPlayerOrException();
		game.accuse(player, target);
		return 1;
	}

	private static int clearAccusation(CommandSourceStack src) throws CommandSyntaxException {
		GameController game = game(src);
		if (game == null) {
			return 0;
		}
		game.clearAccusation(src.getPlayerOrException());
		return 1;
	}

	private static int start(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		try {
			game.start();
		} catch (IllegalStateException e) {
			src.sendFailure(Component.literal(e.getMessage()));
			if (src.getPlayer() == null) {
				// Pressed from the lobby button: there is no player to read the failure, so tell everybody.
				game.broadcast(Component.literal(e.getMessage()).withStyle(net.minecraft.ChatFormatting.RED));
			}
			return 0;
		}
		say(src, "Starting. Roles are revealed after the countdown.");
		return 1;
	}

	private static int startTest(CommandSourceStack src, Role role) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			src.sendFailure(Component.literal("Run this as a player."));
			return 0;
		}
		try {
			game.startTest(player, role);
		} catch (IllegalStateException e) {
			src.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		say(src, "Test game: you are " + (role == Role.IMPOSTOR ? "an IMPOSTOR" : "INNOCENT")
			+ ", the other 5 players are stand-ins. /imp stop ends it.");
		return 1;
	}

	private static int testUnlock(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		if (!game.forceUnlockPvp()) {
			src.sendFailure(Component.literal("Needs a running game with impostor PvP still locked."));
			return 0;
		}
		return 1;
	}

	private static int testKit(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			src.sendFailure(Component.literal("Run this as a player."));
			return 0;
		}
		game.giveKit(player);
		return 1;
	}

	private static int lobby(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		try {
			game.createLobby();
		} catch (IllegalStateException e) {
			src.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		return 1;
	}

	private static int lobbyRemove(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		try {
			game.removeLobbyNow();
		} catch (IllegalStateException e) {
			src.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		return 1;
	}

	private static int stop(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		game.stop();
		return 1;
	}

	private static int status(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		GameState state = game.state();
		int total = state.members().size();
		int alive = state.alive(Role.IMPOSTOR) + state.alive(Role.INNOCENT);
		say(src, "Phase: " + state.phase() + (state.phase() == Phase.IDLE ? "" : ", players alive: " + alive + "/" + total));
		if (state.phase() == Phase.ENDED) {
			say(src, "Winner: " + state.outcome());
		}
		if (state.phase() == Phase.RUNNING) {
			say(src, game.pvpStatus());
		}
		return 1;
	}

	private static int reveal(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		GameState state = game.state();
		if (state.phase() != Phase.ENDED) {
			src.sendFailure(Component.literal("Roles stay secret until the game has ended."));
			return 0;
		}
		for (Member m : state.members()) {
			say(src, m.name() + ": " + m.role() + (m.alive() ? "" : " (dead)"));
		}
		return 1;
	}

	private static int listRules(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		RulesMenu.sendAll(src, game.rules());
		return 1;
	}

	private static int showRule(CommandSourceStack src, Rules.Def def) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		RulesMenu.sendOne(src, game.rules(), def);
		return 1;
	}

	private static int setRule(CommandSourceStack src, Rules.Def def, String value, boolean showMenu) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		try {
			game.rules().set(def.name(), value);
		} catch (IllegalArgumentException e) {
			src.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		if (def.name().equals("apply_gamerules") && game.rules().bool("apply_gamerules")) {
			game.applyGameRules();
		}
		if (showMenu) {
			RulesMenu.sendChanged(src, game.rules(), def);
		} else {
			say(src, def.name() + " is now " + game.rules().valueText(def.name()));
		}
		return 1;
	}

	private static int resetRules(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		game.rules().resetAll();
		say(src, "All rules are back to their defaults.");
		return 1;
	}

	private static int lock(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			src.sendFailure(Component.literal("Run this as a player, so the mod knows who the host is."));
			return 0;
		}
		Set<String> names = new LinkedHashSet<>();
		names.add(player.getGameProfile().name().toLowerCase(Locale.ROOT));
		game.rules().setAdmins(names);
		say(src, "Locked. Only you can use the host commands now.");
		return 1;
	}

	private static int unlock(CommandSourceStack src) {
		GameController game = game(src);
		if (game == null || !allowed(src, game)) {
			return 0;
		}
		game.rules().setAdmins(new LinkedHashSet<>());
		say(src, "Unlocked. Anybody with operator permission can use the host commands.");
		return 1;
	}
}
