package com.impostorsmp;

import java.util.ArrayList;
import java.util.List;

/**
 * How the rules are grouped and how big a step the [-] / [+] buttons take.
 * No Minecraft code in here on purpose, so it can be tested on its own.
 *
 * A rule that is not listed in LAYOUT still shows up, under "Other", so a newly added rule can never go missing.
 */
final class RulesLayout {
	record Section(String title, List<Rules.Def> rules) {
	}

	private static final String[][] LAYOUT = {
		{"Chat & messages", "chat", "dead_chat", "unlock_chat_after_game", "hide_death_messages", "hide_advancements", "hide_join_leave"},
		{"Deaths & combat", "death_sound", "block_totems", "spectate_on_death", "anti_rdm", "anti_rdm_seconds", "anti_rdm_level",
			"anti_rdm_weakness", "anti_rdm_glowing", "anti_rdm_escalate"},
		{"Impostor kit", "impostor_kit", "impostor_kit_builders", "impostor_kit_set_chance"},
		{"Call-outs", "accuse", "accuse_votes", "false_accuse_punish", "false_accuse_seconds"},
		{"The game", "impostors", "dragon_wins", "parity_win", "apply_gamerules"},
		{"Spawning", "lobby_y", "spread_on_start", "spread_radius", "spread_min_distance", "spread_group_size", "spread_split_impostors"}
	};

	private RulesLayout() {
	}

	static List<Section> sections(List<Rules.Def> defs) {
		List<Section> result = new ArrayList<>();
		List<Rules.Def> used = new ArrayList<>();
		for (String[] row : LAYOUT) {
			List<Rules.Def> inSection = new ArrayList<>();
			for (int i = 1; i < row.length; i++) {
				for (Rules.Def def : defs) {
					if (def.name().equals(row[i])) {
						inSection.add(def);
						used.add(def);
					}
				}
			}
			if (!inSection.isEmpty()) {
				result.add(new Section(row[0], inSection));
			}
		}
		List<Rules.Def> other = new ArrayList<>();
		for (Rules.Def def : defs) {
			if (!used.contains(def)) {
				other.add(def);
			}
		}
		if (!other.isEmpty()) {
			result.add(new Section("Other", other));
		}
		return result;
	}

	static Section sectionOf(Rules.Def def, List<Rules.Def> defs) {
		for (Section section : sections(defs)) {
			if (section.rules().contains(def)) {
				return section;
			}
		}
		return new Section("Rules", List.of(def));
	}

	/** How much one click on [-] or [+] changes a number rule. */
	static int step(Rules.Def def) {
		if (def.max() - def.min() <= 10) {
			return 1;
		}
		int base = Math.max(10, Integer.parseInt(def.defaultValue()));
		int step = 1;
		while (step * 10 <= base / 4) {
			step *= 10;
		}
		return step;
	}

	static int up(Rules.Def def, int current) {
		return (int) Math.min((long) def.max(), (long) current + step(def));
	}

	static int down(Rules.Def def, int current) {
		return (int) Math.max((long) def.min(), (long) current - step(def));
	}
}
