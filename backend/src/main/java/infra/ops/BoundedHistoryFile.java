package infra.ops;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One bounded, disposable, append-only JSON-lines history file (STORY-OPS-001 section 7): one line per
 * closed top-level time bucket, whole-bucket eviction (oldest first) under a count and a byte bound, and a
 * rewrite only to discard expired leading buckets. Lossy by design: a torn or malformed line is skipped, no
 * repair, journal, archive or recovery. One application writer; callers serialize access.
 */
public final class BoundedHistoryFile<T> {
    private record Line<T>(T bucket, int bytes) {}

    private final Path file;
    private final Class<T> type;
    private final ObjectMapper mapper;
    private final int maxBuckets;
    private final long maxBytes;
    private final Deque<Line<T>> retained = new ArrayDeque<>();
    private long retainedBytes;
    private long fileBytes;
    private int fileLines;
    private boolean retentionTruncated;
    private boolean unavailable;

    public BoundedHistoryFile(Path file, Class<T> type, ObjectMapper mapper, int maxBuckets, long maxBytes) {
        this.file = file;
        this.type = type;
        this.mapper = mapper;
        this.maxBuckets = maxBuckets;
        this.maxBytes = maxBytes;
        removeAbandonedReplacements();
        load();
    }

    private Path replacementPath() {
        return file.toAbsolutePath().resolveSibling(file.getFileName() + ".tmp");
    }

    /** Deletes this file's own abandoned replacement files (fixed name and legacy random names) only. */
    private void removeAbandonedReplacements() {
        Path dir = file.toAbsolutePath().getParent();
        String prefix = file.getFileName().toString();
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var entries = Files.newDirectoryStream(dir, prefix + "*.tmp")) {
            for (Path p : entries) {
                if (Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ }
                }
            }
        } catch (IOException ignored) {
            // Scratch cleanup is best effort; the bound is still held by the single fixed replacement name.
        }
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String text : lines) {
                fileLines++;
                try {
                    T bucket = mapper.readValue(text, type);
                    if (bucket != null) retain(bucket, text.getBytes(StandardCharsets.UTF_8).length + 1);
                } catch (RuntimeException malformed) {
                    // A torn or malformed record is ignored; there is no repair.
                }
            }
            fileBytes = Files.size(file);
            if (fileLines > retained.size() || fileBytes > maxBytes) rewrite();
        } catch (IOException | RuntimeException e) {
            retained.clear();
            retainedBytes = 0;
            unavailable = true;
        }
    }

    /** Appends one closed bucket; a failure marks the file unavailable and never propagates to a request. */
    public void append(T bucket) {
        try {
            String text = mapper.writeValueAsString(bucket);
            int bytes = text.getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes > maxBytes) return;
            retain(bucket, bytes);
            // The file mirrors the retained buckets: any eviction (count or bytes) rewrites it without them.
            boolean overflow = fileBytes + bytes > maxBytes || fileLines + 1 > retained.size();
            if (overflow || !Files.exists(file)) {
                rewrite();
            } else {
                Files.writeString(file, text + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                fileBytes += bytes;
                fileLines++;
            }
            unavailable = false;
        } catch (IOException | RuntimeException e) {
            unavailable = true;
        }
    }

    private void retain(T bucket, int bytes) {
        retained.addLast(new Line<>(bucket, bytes));
        retainedBytes += bytes;
        while (retained.size() > maxBuckets || retainedBytes > maxBytes) {
            retainedBytes -= retained.removeFirst().bytes();
            retentionTruncated = true;
        }
    }

    private void rewrite() throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        restrict(dir, "rwx------");
        // One fixed replacement name per history file: at most one scratch file can ever exist, and a
        // leftover from an interrupted rewrite is replaced here or removed on the next start.
        Path temp = replacementPath();
        Files.deleteIfExists(temp);
        Files.createFile(temp);
        try {
            restrict(temp, "rw-------");
            StringBuilder out = new StringBuilder();
            for (Line<T> l : retained) out.append(mapper.writeValueAsString(l.bucket())).append('\n');
            Files.writeString(temp, out, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        fileBytes = retainedBytes;
        fileLines = retained.size();
    }

    private static void restrict(Path path, String perms) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem: nothing more can be asked of it.
        }
    }

    public List<T> buckets() {
        List<T> out = new ArrayList<>(retained.size());
        for (Line<T> l : retained) out.add(l.bucket());
        return out;
    }

    public boolean retentionTruncated() { return retentionTruncated; }

    public boolean unavailable() { return unavailable; }

    public long retainedBytes() { return retainedBytes; }
}
