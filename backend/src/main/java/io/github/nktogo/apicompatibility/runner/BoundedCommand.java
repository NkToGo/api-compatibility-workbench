package io.github.nktogo.apicompatibility.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds Docker CLI output in memory; killing the CLI alone does not kill a container. */
final class BoundedCommand {
    enum Status { EXITED, TIMED_OUT, OUTPUT_LIMIT }
    record Result(Status status, int exitCode, byte[] stdout, byte[] stderr) {}

    static Result run(List<String> command, Duration deadline, int limit) throws IOException, InterruptedException {
        if (deadline.isNegative() || deadline.isZero() || limit < 1) throw new IllegalArgumentException("Invalid process bounds");
        Process process = new ProcessBuilder(command).start();
        var exceeded = new AtomicBoolean();
        var out = new Capture(process.getInputStream(), limit, exceeded);
        var err = new Capture(process.getErrorStream(), limit, exceeded);
        Thread stdout = Thread.ofPlatform().daemon().start(out);
        Thread stderr = Thread.ofPlatform().daemon().start(err);
        Status status = Status.EXITED;
        long end = System.nanoTime() + deadline.toNanos();
        try {
            while (!process.waitFor(25, TimeUnit.MILLISECONDS)) {
                if (exceeded.get()) { status = Status.OUTPUT_LIMIT; break; }
                if (System.nanoTime() >= end) { status = Status.TIMED_OUT; break; }
            }
            if (process.isAlive()) process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("CLI process did not stop");
            stdout.join(5000); stderr.join(5000);
            if (stdout.isAlive() || stderr.isAlive()) throw new IOException("CLI output did not close");
            if (exceeded.get()) status = Status.OUTPUT_LIMIT;
            if (status == Status.EXITED && (out.failure != null || err.failure != null)) throw new IOException("CLI output could not be read");
            return new Result(status, process.exitValue(), out.bytes.toByteArray(), err.bytes.toByteArray());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
        }
    }

    private static final class Capture implements Runnable {
        private final InputStream stream;
        private final int limit;
        private final AtomicBoolean exceeded;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile IOException failure;
        Capture(InputStream stream, int limit, AtomicBoolean exceeded) {
            this.stream = stream; this.limit = limit; this.exceeded = exceeded;
        }
        @Override public void run() {
            try {
                byte[] buffer = new byte[8192];
                for (int read; (read = stream.read(buffer)) != -1;) {
                    int remaining = limit - bytes.size();
                    bytes.write(buffer, 0, Math.min(read, remaining));
                    if (read > remaining) { exceeded.set(true); break; }
                }
            } catch (IOException e) { failure = e; }
        }
    }
}
