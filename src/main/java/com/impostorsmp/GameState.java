package com.impostorsmp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Who is an impostor, who is alive, and who has won. Saved to disk after every change so the
 * game survives closing and reopening the world. Contains no Minecraft code on purpose.
 */
public final class GameState {
	public enum Role { IMPOSTOR, INNOCENT }

	public enum Phase { IDLE, COUNTDOWN, RUNNING, ENDED }

	public enum Outcome { NONE, INNOCENTS, IMPOSTORS }

	public static final class Member {
		private final UUID id;
		private final String name;
		private final Role role;
		private boolean alive = true;

		Member(UUID id, String name, Role role) {
			this.id = id;
			this.name = name;
			this.role = role;
		}

		public UUID id() {
			return id;
		}

		public String name() {
			return name;
		}

		public Role role() {
			return role;
		}

		public boolean alive() {
			return alive;
		}
	}

	private final Map<UUID, Member> members = new LinkedHashMap<>();
	private Phase phase = Phase.IDLE;
	private Outcome outcome = Outcome.NONE;
	private long worldSeed;

	// Things that change during a game and must survive a restart.
	private final Set<UUID> exposed = new LinkedHashSet<>();
	private final Set<UUID> cleared = new LinkedHashSet<>();
	private final Map<UUID, UUID> votes = new LinkedHashMap<>(); // accuser -> accused
	private final Map<UUID, Integer> strikes = new HashMap<>();

	public enum AccuseStatus { NOT_RUNNING, ACCUSER_OUT, TARGET_INVALID, ALREADY_KNOWN, VOTE, EXPOSED, CLEARED }

	/** What happened to an accusation. {@code accusers} is filled when it was settled (EXPOSED or CLEARED). */
	public record AccuseResult(AccuseStatus status, int votes, int needed, List<UUID> accusers) {
	}

	public Phase phase() {
		return phase;
	}

	public Outcome outcome() {
		return outcome;
	}

	public long worldSeed() {
		return worldSeed;
	}

	public List<Member> members() {
		return Collections.unmodifiableList(new ArrayList<>(members.values()));
	}

	public Optional<Member> member(UUID id) {
		return Optional.ofNullable(members.get(id));
	}

	public int alive(Role role) {
		int n = 0;
		for (Member m : members.values()) {
			if (m.role == role && m.alive) {
				n++;
			}
		}
		return n;
	}

	public int total(Role role) {
		int n = 0;
		for (Member m : members.values()) {
			if (m.role == role) {
				n++;
			}
		}
		return n;
	}

	public List<Member> withRole(Role role) {
		List<Member> out = new ArrayList<>();
		for (Member m : members.values()) {
			if (m.role == role) {
				out.add(m);
			}
		}
		return out;
	}

	/** Picks the impostors at random from the given players (uuid to name, in order) and starts the countdown phase. */
	public void begin(Map<UUID, String> players, int impostorCount, Random random, long seed) {
		if (impostorCount < 1) {
			throw new IllegalArgumentException("There must be at least one impostor.");
		}
		if (players.size() <= impostorCount) {
			throw new IllegalArgumentException("Need more players than impostors: have " + players.size()
				+ " player(s) for " + impostorCount + " impostor(s).");
		}
		List<UUID> ids = new ArrayList<>(players.keySet());
		Collections.shuffle(ids, random);
		Set<UUID> impostors = new HashSet<>(ids.subList(0, impostorCount));

		members.clear();
		clearExtras();
		for (Map.Entry<UUID, String> e : players.entrySet()) {
			Role role = impostors.contains(e.getKey()) ? Role.IMPOSTOR : Role.INNOCENT;
			members.put(e.getKey(), new Member(e.getKey(), e.getValue(), role));
		}
		phase = Phase.COUNTDOWN;
		outcome = Outcome.NONE;
		worldSeed = seed;
	}

	/**
	 * Solo test game: the host plays {@code hostRole} and the rest of the six are stand-ins that don't exist
	 * in the world (they never move or die), so one person can try everything alone.
	 */
	public void beginTest(UUID hostId, String hostName, Role hostRole, int impostorCount, int total, long seed) {
		if (impostorCount < 1 || total <= impostorCount) {
			throw new IllegalArgumentException("A test game needs at least one impostor and more players than impostors.");
		}
		int impostorsLeft = impostorCount - (hostRole == Role.IMPOSTOR ? 1 : 0);
		int innocentsLeft = total - impostorCount - (hostRole == Role.INNOCENT ? 1 : 0);
		if (impostorsLeft < 0 || innocentsLeft < 0) {
			throw new IllegalArgumentException("Those numbers don't leave room for your role.");
		}
		members.clear();
		clearExtras();
		members.put(hostId, new Member(hostId, hostName, hostRole));
		for (int i = 1; i <= total - 1; i++) {
			UUID id = UUID.nameUUIDFromBytes(("impostorsmp-test-dummy-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			Role role = impostorsLeft > 0 ? Role.IMPOSTOR : Role.INNOCENT;
			if (role == Role.IMPOSTOR) {
				impostorsLeft--;
			} else {
				innocentsLeft--;
			}
			members.put(id, new Member(id, "Dummy " + i, role));
		}
		phase = Phase.COUNTDOWN;
		outcome = Outcome.NONE;
		worldSeed = seed;
	}

	public void startRunning() {
		if (phase == Phase.COUNTDOWN) {
			phase = Phase.RUNNING;
		}
	}

	/** Marks a member dead. Returns true only if they were alive before. */
	public boolean markDead(UUID id) {
		Member m = members.get(id);
		if (m == null || !m.alive) {
			return false;
		}
		if (phase != Phase.COUNTDOWN && phase != Phase.RUNNING) {
			return false;
		}
		m.alive = false;
		// A dead player's votes disappear, and so do the votes against them.
		votes.remove(id);
		votes.values().removeIf(target -> target.equals(id));
		return true;
	}

	private void clearExtras() {
		exposed.clear();
		cleared.clear();
		votes.clear();
		strikes.clear();
	}

	// ---- call-out results ----

	public boolean exposed(UUID id) {
		return exposed.contains(id);
	}

	public boolean cleared(UUID id) {
		return cleared.contains(id);
	}

	// ---- RDM strikes ----

	/** Adds one strike and returns the new total. */
	public int addStrike(UUID id) {
		return strikes.merge(id, 1, Integer::sum);
	}

	public int strikes(UUID id) {
		return strikes.getOrDefault(id, 0);
	}

	// ---- accusations ----

	/** How many votes a call-out needs right now: the threshold, but never more than the people left who could vote. */
	public int votesNeeded(int threshold) {
		int alive = alive(Role.IMPOSTOR) + alive(Role.INNOCENT);
		return Math.max(1, Math.min(threshold, alive - 1));
	}

	public int votesAgainst(UUID target) {
		int n = 0;
		for (UUID t : votes.values()) {
			if (t.equals(target)) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Records that {@code accuser} accuses {@code target} (replacing any earlier accusation by them).
	 * When enough living players accuse the same person, that person's role is settled: an impostor is
	 * EXPOSED, an innocent is CLEARED and the accusers are returned so they can be punished.
	 */
	public AccuseResult accuse(UUID accuser, UUID target, int threshold) {
		int needed = votesNeeded(threshold);
		if (phase != Phase.RUNNING) {
			return new AccuseResult(AccuseStatus.NOT_RUNNING, 0, needed, List.of());
		}
		Member a = members.get(accuser);
		if (a == null || !a.alive) {
			return new AccuseResult(AccuseStatus.ACCUSER_OUT, 0, needed, List.of());
		}
		Member t = members.get(target);
		if (t == null || !t.alive || accuser.equals(target)) {
			return new AccuseResult(AccuseStatus.TARGET_INVALID, 0, needed, List.of());
		}
		if (exposed.contains(target) || cleared.contains(target)) {
			return new AccuseResult(AccuseStatus.ALREADY_KNOWN, votesAgainst(target), needed, List.of());
		}
		votes.put(accuser, target);
		int count = votesAgainst(target);
		if (count < needed) {
			return new AccuseResult(AccuseStatus.VOTE, count, needed, List.of());
		}
		List<UUID> accusers = new ArrayList<>();
		for (Map.Entry<UUID, UUID> e : votes.entrySet()) {
			if (e.getValue().equals(target)) {
				accusers.add(e.getKey());
			}
		}
		votes.values().removeIf(v -> v.equals(target));
		if (t.role == Role.IMPOSTOR) {
			exposed.add(target);
			return new AccuseResult(AccuseStatus.EXPOSED, count, needed, accusers);
		}
		cleared.add(target);
		return new AccuseResult(AccuseStatus.CLEARED, count, needed, accusers);
	}

	/** Takes back your own accusation. Returns true if you had one. */
	public boolean clearVote(UUID accuser) {
		return votes.remove(accuser) != null;
	}

	/**
	 * Works out whether anybody has won. If the last impostor and the last innocent both die at once,
	 * the innocents win, because the impostors are gone.
	 */
	public Outcome evaluate() {
		return evaluate(false);
	}

	/**
	 * Same as {@link #evaluate()}, but with {@code parity} on the impostors also win as soon as they are
	 * at least as many as the innocents who are left (they can no longer be outvoted or outfought).
	 */
	public Outcome evaluate(boolean parity) {
		if (phase != Phase.RUNNING) {
			return Outcome.NONE;
		}
		int imps = alive(Role.IMPOSTOR);
		int inns = alive(Role.INNOCENT);
		if (imps == 0) {
			return Outcome.INNOCENTS;
		}
		if (inns == 0) {
			return Outcome.IMPOSTORS;
		}
		if (parity && imps >= inns) {
			return Outcome.IMPOSTORS;
		}
		return Outcome.NONE;
	}

	public void finish(Outcome result) {
		phase = Phase.ENDED;
		outcome = result;
	}

	public void reset() {
		members.clear();
		clearExtras();
		phase = Phase.IDLE;
		outcome = Outcome.NONE;
	}

	// ---- file ----

	public void save(Path file) {
		Properties p = new Properties();
		p.setProperty("phase", phase.name());
		p.setProperty("outcome", outcome.name());
		p.setProperty("seed", Long.toString(worldSeed));
		p.setProperty("count", Integer.toString(members.size()));
		int i = 0;
		for (Member m : members.values()) {
			p.setProperty("m." + i + ".id", m.id.toString());
			p.setProperty("m." + i + ".name", m.name);
			p.setProperty("m." + i + ".role", m.role.name());
			p.setProperty("m." + i + ".alive", Boolean.toString(m.alive));
			i++;
		}
		p.setProperty("exposed", join(exposed));
		p.setProperty("cleared", join(cleared));
		p.setProperty("votes", Integer.toString(votes.size()));
		int v = 0;
		for (Map.Entry<UUID, UUID> e : votes.entrySet()) {
			p.setProperty("v." + v + ".from", e.getKey().toString());
			p.setProperty("v." + v + ".to", e.getValue().toString());
			v++;
		}
		p.setProperty("strikes", Integer.toString(strikes.size()));
		int k = 0;
		for (Map.Entry<UUID, Integer> e : strikes.entrySet()) {
			p.setProperty("s." + k + ".id", e.getKey().toString());
			p.setProperty("s." + k + ".n", e.getValue().toString());
			k++;
		}
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (OutputStream out = Files.newOutputStream(file)) {
				p.store(out, "Impostor SMP game state. Secret: contains everybody's role. Delete it or use /imp stop to reset.");
			}
		} catch (IOException e) {
			ImpostorLog.warn("Could not save " + file + ": " + e.getMessage());
		}
	}

	private static String join(Set<UUID> ids) {
		StringBuilder sb = new StringBuilder();
		for (UUID id : ids) {
			if (sb.length() > 0) {
				sb.append(',');
			}
			sb.append(id);
		}
		return sb.toString();
	}

	private static List<UUID> split(String text) {
		List<UUID> out = new ArrayList<>();
		for (String part : text.split(",")) {
			if (!part.isBlank()) {
				out.add(UUID.fromString(part.trim()));
			}
		}
		return out;
	}

	/** Loads a saved state, or returns a fresh idle one if there is no usable file. */
	public static GameState load(Path file) {
		GameState state = new GameState();
		if (!Files.exists(file)) {
			return state;
		}
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			p.load(in);
			state.phase = Phase.valueOf(p.getProperty("phase", "IDLE"));
			state.outcome = Outcome.valueOf(p.getProperty("outcome", "NONE"));
			state.worldSeed = Long.parseLong(p.getProperty("seed", "0"));
			int count = Integer.parseInt(p.getProperty("count", "0"));
			for (int i = 0; i < count; i++) {
				UUID id = UUID.fromString(p.getProperty("m." + i + ".id"));
				Member m = new Member(id, p.getProperty("m." + i + ".name", "?"),
					Role.valueOf(p.getProperty("m." + i + ".role")));
				m.alive = Boolean.parseBoolean(p.getProperty("m." + i + ".alive", "true"));
				state.members.put(id, m);
			}
			state.exposed.addAll(split(p.getProperty("exposed", "")));
			state.cleared.addAll(split(p.getProperty("cleared", "")));
			int voteCount = Integer.parseInt(p.getProperty("votes", "0"));
			for (int i = 0; i < voteCount; i++) {
				state.votes.put(UUID.fromString(p.getProperty("v." + i + ".from")), UUID.fromString(p.getProperty("v." + i + ".to")));
			}
			int strikeCount = Integer.parseInt(p.getProperty("strikes", "0"));
			for (int i = 0; i < strikeCount; i++) {
				state.strikes.put(UUID.fromString(p.getProperty("s." + i + ".id")), Integer.parseInt(p.getProperty("s." + i + ".n")));
			}
		} catch (IOException | RuntimeException e) {
			ImpostorLog.warn("Ignoring unreadable game state file " + file + ": " + e);
			return new GameState();
		}
		return state;
	}
}
