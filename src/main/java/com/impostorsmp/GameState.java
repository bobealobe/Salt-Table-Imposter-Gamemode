package com.impostorsmp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
		for (Map.Entry<UUID, String> e : players.entrySet()) {
			Role role = impostors.contains(e.getKey()) ? Role.IMPOSTOR : Role.INNOCENT;
			members.put(e.getKey(), new Member(e.getKey(), e.getValue(), role));
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
		return true;
	}

	/**
	 * Works out whether anybody has won. If the last impostor and the last innocent both die at once,
	 * the innocents win, because the impostors are gone.
	 */
	public Outcome evaluate() {
		if (phase != Phase.RUNNING) {
			return Outcome.NONE;
		}
		if (alive(Role.IMPOSTOR) == 0) {
			return Outcome.INNOCENTS;
		}
		if (alive(Role.INNOCENT) == 0) {
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
		} catch (IOException | RuntimeException e) {
			ImpostorLog.warn("Ignoring unreadable game state file " + file + ": " + e);
			return new GameState();
		}
		return state;
	}
}
