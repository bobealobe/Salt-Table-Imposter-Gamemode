package com.impostorsmp;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * The clickable /imp rules screen.
 *
 * One line per rule: name (hover it for the explanation), the current value (green = true, red = false,
 * yellow = number) and buttons to change it. Clicking a button runs "/imp rule <name> <value> menu",
 * which changes the rule and shows that section again.
 */
final class RulesMenu {
	private RulesMenu() {
	}

	/** Everything, grouped into sections. */
	static void sendAll(CommandSourceStack src, Rules rules) {
		send(src, Component.literal("Impostor SMP rules").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
			.append(Component.literal("  hover a name for details, click a button to change it").withStyle(ChatFormatting.GRAY)));
		for (RulesLayout.Section section : RulesLayout.sections(Rules.DEFS)) {
			sendSection(src, rules, section);
		}
	}

	/** Shown after a click: only the section that was changed, plus a link to everything. */
	static void sendChanged(CommandSourceStack src, Rules rules, Rules.Def changed) {
		sendSection(src, rules, RulesLayout.sectionOf(changed, Rules.DEFS));
		send(src, Component.literal("  ").append(button("all rules", ChatFormatting.AQUA, "imp rules", "Show every rule")));
	}

	static void sendOne(CommandSourceStack src, Rules rules, Rules.Def def) {
		send(src, line(rules, def));
	}

	private static void sendSection(CommandSourceStack src, Rules rules, RulesLayout.Section section) {
		send(src, Component.literal(section.title()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		for (Rules.Def def : section.rules()) {
			send(src, line(rules, def));
		}
	}

	private static void send(CommandSourceStack src, Component message) {
		src.sendSuccess(() -> message, false);
	}

	private static MutableComponent line(Rules rules, Rules.Def def) {
		MutableComponent line = Component.literal("  ");
		line.append(Component.literal(def.name()).setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(def.description()).withStyle(ChatFormatting.GRAY)))));
		line.append(Component.literal("  "));

		if (def.type() == Rules.Type.BOOL) {
			boolean on = rules.bool(def.name());
			line.append(Component.literal(Boolean.toString(on)).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD));
			line.append(Component.literal("   "));
			line.append(choice(def, "true", ChatFormatting.GREEN, on));
			line.append(Component.literal(" "));
			line.append(choice(def, "false", ChatFormatting.RED, !on));
		} else {
			int value = rules.integer(def.name());
			line.append(Component.literal(Integer.toString(value)).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
			line.append(Component.literal("   "));
			int step = RulesLayout.step(def);
			int lower = RulesLayout.down(def, value);
			int higher = RulesLayout.up(def, value);
			line.append(numberButton(def, "-" + step, lower, lower != value));
			line.append(Component.literal(" "));
			line.append(numberButton(def, "+" + step, higher, higher != value));
			line.append(Component.literal(" "));
			line.append(Component.literal("[type]").setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)
				.withClickEvent(new ClickEvent.SuggestCommand("/imp rule " + def.name() + " "))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Type your own number (" + def.min() + " to " + def.max() + ")")))));
		}
		return line;
	}

	/** A [true] / [false] button. The option that is already selected is greyed out. */
	private static Component choice(Rules.Def def, String value, ChatFormatting color, boolean selected) {
		if (selected) {
			return Component.literal("[" + value + "]").withStyle(ChatFormatting.DARK_GRAY);
		}
		return button(value, color, "imp rule " + def.name() + " " + value + " menu", "Set " + def.name() + " to " + value);
	}

	/** A [-10] / [+10] button. Greyed out when the number is already at its limit. */
	private static Component numberButton(Rules.Def def, String label, int target, boolean usable) {
		if (!usable) {
			return Component.literal("[" + label + "]").withStyle(ChatFormatting.DARK_GRAY);
		}
		return button(label, ChatFormatting.YELLOW, "imp rule " + def.name() + " " + target + " menu",
			"Set " + def.name() + " to " + target);
	}

	private static Component button(String label, ChatFormatting color, String command, String tooltip) {
		return Component.literal("[" + label + "]").setStyle(Style.EMPTY.withColor(color)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(tooltip))));
	}
}
