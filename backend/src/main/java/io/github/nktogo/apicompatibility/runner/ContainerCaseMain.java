package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;

/** Fixed image entry point. This is not an arbitrary command or repository interface. */
public final class ContainerCaseMain {
    private ContainerCaseMain() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Worker mode and immutable image identity required");
        Map<String, Object> isolation = verifyIsolation();
        switch (args[0]) {
            case "case" -> {
                Path report = StripeCaseRunner.run(Path.of("/opt/workbench/case"), Path.of("/opt/workbench/dependencies"), Path.of("/work/runs"),
                        Map.of("boundary", "linux-container-v1", "imageId", args[1], "isolationChecks", CaseInputs.identity(new TreeMap<>(isolation)), "backstopSeconds", "185"));
                var sources = new TreeMap<String, Object>();
                for (String variant : List.of("baseline", "target", "upstream-reference"))
                    sources.put(variant, CaseInputs.object(Files.readAllBytes(report.getParent().resolve(variant + "/source.json"))));
                System.out.println(CaseInputs.JSON.toJson(Map.of("formatVersion", 1, "isolation", isolation,
                        "report", CaseInputs.object(Files.readAllBytes(report)),
                        "sources", sources,
                        "provenance", Files.readString(report.getParent().resolve("inputs/provenance.json")),
                        "harness", CaseInputs.object(Files.readAllBytes(report.getParent().resolve("inputs/harness.json"))),
                        "environment", CaseInputs.object(Files.readAllBytes(report.getParent().resolve("inputs/environment.json"))),
                        "expectations", Files.readString(Path.of("/opt/workbench/case/expectations.json")))));
            }
            case "probe" -> System.out.println(CaseInputs.JSON.toJson(Map.of("formatVersion", 1, "isolation", isolation)));
            case "deadline-probe" -> Thread.sleep(600_000);
            case "output-probe" -> { while (true) System.out.print("x".repeat(8192)); }
            case "failure-probe" -> System.exit(42);
            case "scratch-probe" -> {
                long written = 0;
                boolean denied = false;
                try (var file = Files.newOutputStream(Path.of("/work/fill"))) {
                    byte[] block = new byte[1024 * 1024];
                    while (written < 300L * 1024 * 1024) { file.write(block); written += block.length; }
                } catch (IOException e) { denied = true; }
                boolean full = Files.getFileStore(Path.of("/work")).getUsableSpace() == 0;
                Files.deleteIfExists(Path.of("/work/fill"));
                CaseInputs.require(denied && full && written >= 267386880L && written <= 268435456L, "Scratch capacity was not enforced");
                System.out.println(CaseInputs.JSON.toJson(Map.of("formatVersion", 1, "isolation", isolation, "scratchLimitEnforced", true)));
            }
            default -> throw new IllegalArgumentException("Unsupported worker mode");
        }
    }

    static Map<String, Object> verifyIsolation() throws IOException {
        var status = new HashMap<String, String>();
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            String[] pair = line.split(":", 2);
            if (pair.length == 2) status.put(pair[0], pair[1].trim());
        }
        CaseInputs.require(status.get("Uid").matches("65532\\s+65532\\s+65532\\s+65532"), "Worker must be unprivileged");
        CaseInputs.require("1".equals(status.get("NoNewPrivs")) && "2".equals(status.get("Seccomp")), "Worker security filters missing");
        CaseInputs.require("0000000000000000".equals(status.get("CapEff")), "Worker has capabilities");
        var interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        CaseInputs.require(interfaces.stream().allMatch(n -> n.getName().equals("lo")), "Unexpected network interface");
        try (var socket = new Socket()) {
            try { socket.connect(new InetSocketAddress("192.0.2.1", 443), 300); throw new IllegalStateException("External route available"); }
            catch (IOException expected) { /* No external interface or route is allowed. */ }
        }
        for (String location : List.of("/opt/workbench/write-probe", "/root/write-probe")) {
            boolean denied = false;
            try { Files.writeString(Path.of(location), "probe", StandardOpenOption.CREATE_NEW); }
            catch (IOException expected) { denied = true; }
            CaseInputs.require(denied, "Protected filesystem is writable");
        }
        CaseInputs.require(!Files.exists(Path.of("/var/run/docker.sock")), "Docker socket exposed");
        CaseInputs.require(System.getenv().keySet().equals(Set.of("PATH", "HOME", "TMPDIR", "LANG")), "Unexpected worker environment");
        var limits = new TreeMap<String, String>();
        for (var expected : Map.of("memory.max", "1073741824", "memory.swap.max", "0", "pids.max", "128", "cpu.max", "200000 100000").entrySet()) {
            String value = Files.readString(Path.of("/sys/fs/cgroup", expected.getKey())).trim();
            CaseInputs.require(expected.getValue().equals(value), "Required cgroup limit absent: " + expected.getKey());
            limits.put(expected.getKey(), value);
        }
        CaseInputs.require(Files.getFileStore(Path.of("/work")).getTotalSpace() == 268435456L, "Incorrect scratch capacity");
        return Map.of("verified", true, "uid", 65532, "network", "loopback-only", "rootWritesDenied", true,
                "dockerSocketAbsent", true, "environment", new TreeSet<>(System.getenv().keySet()), "cgroupLimits", limits,
                "scratchBytes", 268435456L, "seccomp", 2, "noNewPrivileges", true);
    }
}
