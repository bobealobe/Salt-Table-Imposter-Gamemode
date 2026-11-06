package com.impostorsmp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Runs things a number of game ticks from now (20 ticks = 1 second). Call tick() once per server tick. */
public final class Scheduler {
	private record Task(long runAt, Runnable action) {
	}

	private final List<Task> tasks = new ArrayList<>();
	private long now = 0;

	public void after(long ticks, Runnable action) {
		tasks.add(new Task(now + Math.max(0, ticks), action));
	}

	public void clear() {
		tasks.clear();
	}

	public boolean isEmpty() {
		return tasks.isEmpty();
	}

	public void tick() {
		now++;
		List<Task> due = new ArrayList<>();
		Iterator<Task> it = tasks.iterator();
		while (it.hasNext()) {
			Task t = it.next();
			if (t.runAt <= now) {
				due.add(t);
				it.remove();
			}
		}
		// Earliest first; tasks due at the same moment keep the order they were added in (the sort is stable).
		due.sort(Comparator.comparingLong(Task::runAt));
		for (Task t : due) {
			t.action.run();
		}
	}
}
