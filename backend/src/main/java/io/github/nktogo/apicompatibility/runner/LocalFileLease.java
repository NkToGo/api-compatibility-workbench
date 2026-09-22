package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Keeps one channel per locally held lock; closing a second channel can release POSIX locks. */
record LocalFileLease(Path path, FileChannel channel, FileLock lock) implements AutoCloseable {
    private static final Set<Path> HELD = new HashSet<>();

    static LocalFileLease tryAcquire(Path path) throws IOException {
        Path key = path.toAbsolutePath().getParent().toRealPath().resolve(path.getFileName());
        synchronized (HELD) {
            if (HELD.contains(key)) return null;
            var channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                var lock = channel.tryLock();
                if (lock == null) { channel.close(); return null; }
                HELD.add(key);
                return new LocalFileLease(key, channel, lock);
            } catch (IOException | RuntimeException e) { channel.close(); throw e; }
        }
    }

    static LocalFileLease acquire(Path path) throws IOException {
        while (true) {
            var lease = tryAcquire(path);
            if (lease != null) return lease;
            try { Thread.sleep(10); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting for local lease", e); }
        }
    }

    @Override public void close() throws IOException {
        synchronized (HELD) {
            try { channel.close(); }
            finally { HELD.remove(path); }
        }
    }
}
