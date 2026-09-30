package sync;

import java.util.HashSet;
import java.util.Set;

/** Compares cheap upstream ID-list snapshots with the exact locally persisted set. */
public record IdListDiff(Set<Integer> added, Set<Integer> removed) {
    public IdListDiff {
        added = Set.copyOf(added);
        removed = Set.copyOf(removed);
    }

    public static IdListDiff between(Set<Integer> local, Set<Integer> remote) {
        Set<Integer> added = new HashSet<>(remote);
        added.removeAll(local);
        Set<Integer> removed = new HashSet<>(local);
        removed.removeAll(remote);
        return new IdListDiff(added, removed);
    }

    public boolean changed() {
        return !added.isEmpty() || !removed.isEmpty();
    }
}
