package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Bounded local subprocess, deliberately not an OS security sandbox. */
final class FixtureProcess {
    record Exit(boolean timedOut, int code) {}

    static Exit run(List<String> command, Path directory, Duration timeout) throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        var environment = builder.environment();
        String systemRoot = environment.get("SystemRoot");
        environment.clear();
        if (systemRoot != null) environment.put("SystemRoot", systemRoot);
        environment.put("TMP", directory.toString());
        environment.put("TEMP", directory.toString());
        environment.put("TMPDIR", directory.toString());
        builder.redirectOutput(directory.resolve("stdout.json").toFile());
        builder.redirectError(directory.resolve("stderr.txt").toFile());
        Process process = builder.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                return new Exit(true, process.exitValue());
            }
            return new Exit(false, process.exitValue());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
        }
    }

    static String javaExecutable() {
        String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
