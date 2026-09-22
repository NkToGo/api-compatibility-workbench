package io.github.nktogo.apicompatibility.runner;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.nktogo.apicompatibility.compatibility.*;
import io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.AssertionOutcome;
import io.github.nktogo.apicompatibility.compatibility.ExecutionObservation.Disposition;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.tools.ToolProvider;

/** Executes only the allowlisted historical Stripe case and writes local evidence. */
public final class StripeCaseRunner {
    private StripeCaseRunner() {}
    // Gson 2.8.6 reflects into RequestOptions.proxy in stripe-java 20.33.0.
    // Apply the same narrowly scoped compatibility flag to every fixture worker.
    static final List<String> WORKER_OPTIONS = List.of("--add-opens=java.base/java.net=ALL-UNNAMED",
            "-Xmx128m", "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-Duser.timezone=UTC");
    record Variant(String id, String excerpt, String sdk, String api, String side, String revision) {}
    static final List<Variant> VARIANTS = List.of(
            new Variant("baseline", "baseline", "17.4.0", "2019-12-03", "baseline", "b5445c6be4f195c5b59d7c867ad7dcc696aa7a38"),
            new Variant("target", "baseline", "20.33.0", "2020-08-27", "target", "b5445c6be4f195c5b59d7c867ad7dcc696aa7a38"),
            new Variant("upstream-reference", "upstream-reference", "20.33.0", "2020-08-27", "target", "b5b7b52e6c703f2292730996ce838a7538497762"));

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: StripeCaseRunner CASE_DIRECTORY ARTIFACT_CACHE OUTPUT_DIRECTORY");
        Path report = run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        var json = CaseInputs.object(Files.readAllBytes(report));
        for (var entry : json.getAsJsonObject("assessments").entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue().getAsJsonObject().get("conclusion").getAsString());
        }
        System.out.println("Evidence: " + report);
        System.out.println("Open in a browser: " + report.resolveSibling("report.html"));
    }

    public static Path run(Path caseDirectory, Path dependencies, Path outputDirectory) throws Exception {
        CaseInputs.require(Runtime.version().feature() == 21, "This case runner requires JDK 21");
        var inputs = new CaseInputs(caseDirectory, dependencies);
        Files.createDirectories(outputDirectory);
        Path run = Files.createDirectory(outputDirectory.toAbsolutePath().resolve(UUID.randomUUID().toString()));
        Path captured = Files.createDirectories(run.resolve("inputs"));
        for (var entry : inputs.files.entrySet()) {
            Path file = captured.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        String prefix = resource("Consumer.prefix");
        String support = resource("FixtureMain.java.txt");
        Files.writeString(captured.resolve("Consumer.prefix"), prefix);
        Files.writeString(captured.resolve("FixtureMain.java.txt"), support);
        Files.writeString(captured.resolve("LICENSE-Apache-2.0.txt"), resource("LICENSE-Apache-2.0.txt"));
        Files.writeString(captured.resolve("LICENSE-Stripe-MIT.txt"), resource("LICENSE-Stripe-MIT.txt"));
        var harness = new TreeMap<String, Object>();
        Path code = Path.of(StripeCaseRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        harness.put("runner", Files.isDirectory(code) ? CaseInputs.inventory(code) : CaseInputs.hash(Files.readAllBytes(code)));
        Path gson = Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        harness.put("jsonLibrary", CaseInputs.hash(Files.readAllBytes(gson)));
        harness.put("prefix", CaseInputs.hash(prefix.getBytes(StandardCharsets.UTF_8)));
        harness.put("support", CaseInputs.hash(support.getBytes(StandardCharsets.UTF_8)));
        harness.put("sdkArtifacts", new TreeMap<>(CaseInputs.EXECUTABLES));
        harness.put("timeoutSeconds", 30);
        var environment = new TreeMap<String, String>();
        for (String key : List.of("java.version", "java.vendor", "os.name", "os.arch", "os.version"))
            environment.put(key, System.getProperty(key));
        environment.put("javaBinary", CaseInputs.hash(Files.readAllBytes(Path.of(FixtureProcess.javaExecutable()))));
        environment.put("jdkModules", CaseInputs.hash(Files.readAllBytes(Path.of(System.getProperty("java.home"), "lib", "modules"))));
        environment.put("workerOptions", String.join(" ", WORKER_OPTIONS));
        CaseInputs.write(captured.resolve("harness.json"), harness);
        CaseInputs.write(captured.resolve("environment.json"), environment);

        var snapshots = new LinkedHashMap<String, InputSnapshot>();
        var observations = new LinkedHashMap<String, List<ExecutionObservation>>();
        var details = new LinkedHashMap<String, List<JsonObject>>();
        for (Variant variant : VARIANTS) {
            var configuration = new TreeMap<String, Object>();
            configuration.put("sdkVersion", variant.sdk());
            configuration.put("sdkSha256", CaseInputs.EXECUTABLES.get("stripe-java-" + variant.sdk() + ".jar"));
            configuration.put("apiVersion", variant.api());
            configuration.put("fixtureSide", variant.side());
            configuration.put("transport", "in-memory-no-network");
            var source = Map.of("repository", "https://github.com/killbill/killbill-stripe-plugin", "revision", variant.revision(),
                    "excerptSha256", CaseInputs.hash(inputs.files.get("consumer/" + variant.excerpt() + ".java.txt")));
            var snapshot = new InputSnapshot(CaseInputs.CASE_ID, "stripe", variant.api(),
                    digest(CaseInputs.identity(new TreeMap<>(source))), digest(CaseInputs.identity(configuration)),
                    digest(CaseInputs.hash(inputs.files.get("expectations.json"))), digest(CaseInputs.hash(inputs.files.get("fixtures.json"))),
                    digest(CaseInputs.identity(harness)), digest(CaseInputs.identity(environment)));
            snapshots.put(variant.id(), snapshot);
            Path variantDirectory = Files.createDirectory(run.resolve(variant.id()));
            CaseInputs.write(variantDirectory.resolve("configuration.json"), configuration);
            CaseInputs.write(variantDirectory.resolve("source.json"), source);
            Path classes = Files.createDirectory(variantDirectory.resolve("classes"));
            String sdkClasspath = captured.resolve("stripe-java-" + variant.sdk() + ".jar") + File.pathSeparator + captured.resolve("gson-2.8.6.jar");
            Path consumer = variantDirectory.resolve("Consumer.java");
            Files.writeString(consumer, prefix + "\n" + new String(inputs.files.get("consumer/" + variant.excerpt() + ".java.txt"), StandardCharsets.UTF_8) + "\n}\n");
            Path worker = variantDirectory.resolve("FixtureMain.java");
            Files.writeString(worker, support);
            boolean compiled = compile(consumer, worker, classes, sdkClasspath, variantDirectory.resolve("compiler.txt"));
            var variantObservations = new ArrayList<ExecutionObservation>();
            var variantDetails = new ArrayList<JsonObject>();
            for (var entry : inputs.scenarios.entrySet()) {
                Path scenarioDirectory = Files.createDirectory(variantDirectory.resolve(entry.getKey()));
                Path fixture = scenarioDirectory.resolve("fixture.json");
                CaseInputs.write(fixture, entry.getValue());
                JsonObject actual = compiled ? execute(classes, sdkClasspath, fixture, variant, scenarioDirectory)
                        : failure("INFRASTRUCTURE_ERROR", "COMPILATION_FAILED");
                actual.addProperty("scenarioId", entry.getKey());
                actual.addProperty("variant", variant.id());
                Disposition disposition = Disposition.valueOf(actual.get("status").getAsString());
                AssertionOutcome outcome = disposition == Disposition.COMPLETED
                        ? (matches(actual, inputs.expectations.get(entry.getKey())) ? AssertionOutcome.PASSED : AssertionOutcome.FAILED)
                        : AssertionOutcome.NOT_EVALUATED;
                actual.addProperty("assertionOutcome", outcome.name());
                CaseInputs.write(scenarioDirectory.resolve("observation.json"), actual);
                variantDetails.add(actual);
                variantObservations.add(new ExecutionObservation(entry.getKey(), snapshot, disposition, outcome));
            }
            observations.put(variant.id(), List.copyOf(variantObservations));
            details.put(variant.id(), variantDetails);
        }
        var assessments = new LinkedHashMap<String, ComparisonAssessment>();
        for (String target : List.of("target", "upstream-reference")) {
            var definition = new ComparisonDefinition(snapshots.get("baseline"), snapshots.get(target), List.copyOf(inputs.scenarios.keySet()));
            assessments.put(target, CompatibilityComparison.compare(definition, observations.get("baseline"), observations.get(target)));
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("formatVersion", 1);
        report.put("caseId", CaseInputs.CASE_ID);
        report.put("evidenceKind", "isolated-consumer-methods-with-synthetic-fixtures");
        Instant completedAt = Instant.now();
        report.put("completedAt", completedAt.toString());
        report.put("assessments", assessments);
        report.put("observations", observations);
        report.put("details", details);
        var definitions = new LinkedHashMap<String, ComparisonDefinition>();
        assessments.forEach((variant, assessment) -> definitions.put(variant, assessment.definition()));
        Files.writeString(run.resolve("report.html"), CaseReportView.render(completedAt, definitions, observations, details, inputs.expectations));
        report.put("artifacts", CaseInputs.inventory(run));
        Path pending = run.resolve("report.json.pending");
        CaseInputs.write(pending, report);
        return Files.move(pending, run.resolve("report.json"), StandardCopyOption.ATOMIC_MOVE);
    }

    static boolean matches(JsonObject actual, JsonObject expected) {
        return actual.get("completed").getAsBoolean()
                && CaseInputs.strings(actual, "finalMethodIds").equals(CaseInputs.strings(expected, "finalMethodIds"))
                && CaseInputs.strings(actual, "returnedMethodIds").equals(CaseInputs.strings(expected, "finalMethodIds"))
                && CaseInputs.strings(actual, "deactivatedMethodIds").equals(CaseInputs.strings(expected, "deactivatedMethodIds"))
                && actual.getAsJsonArray("unsafeDeactivationIds").isEmpty();
    }

    private static boolean compile(Path consumer, Path worker, Path classes, String classpath, Path log) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK is required");
        try (var output = Files.newOutputStream(log)) {
            return compiler.run(null, output, output, "--release", "21", "-encoding", "UTF-8", "-proc:none",
                    "-classpath", classpath, "-d", classes.toString(), consumer.toString(), worker.toString()) == 0;
        }
    }

    private static JsonObject execute(Path classes, String sdkClasspath, Path fixture, Variant variant, Path directory) throws InterruptedException {
        try {
            var command = new ArrayList<String>();
            command.add(FixtureProcess.javaExecutable());
            command.addAll(WORKER_OPTIONS);
            command.addAll(List.of("-cp", classes + File.pathSeparator + sdkClasspath,
                    "FixtureMain", fixture.toString(), variant.side(), variant.api()));
            var exit = FixtureProcess.run(command, directory, Duration.ofSeconds(30));
            if (exit.timedOut()) return failure("TIMED_OUT", "WORKER_DEADLINE_EXCEEDED");
            if (exit.code() != 0) return failure("INFRASTRUCTURE_ERROR", "WORKER_EXIT_" + exit.code());
            var result = CaseInputs.object(Files.readAllBytes(directory.resolve("stdout.json")));
            CaseInputs.require(result.get("sdkVersion").getAsString().equals(variant.sdk()) &&
                    result.get("apiVersion").getAsString().equals(variant.api()), "Worker version mismatch");
            CaseInputs.require(result.get("scenarioId").getAsString().equals(directory.getFileName().toString()), "Worker scenario mismatch");
            if (!Set.of("COMPLETED", "INFRASTRUCTURE_ERROR").contains(result.get("status").getAsString()))
                return failure("INFRASTRUCTURE_ERROR", "INVALID_WORKER_STATUS");
            if (result.get("status").getAsString().equals("COMPLETED")) {
                result.get("completed").getAsBoolean();
                for (String field : List.of("finalMethodIds", "returnedMethodIds", "deactivatedMethodIds", "unsafeDeactivationIds"))
                    CaseInputs.strings(result, field);
                CaseInputs.require(result.get("events").isJsonArray(), "Invalid event list");
            }
            return result;
        } catch (IOException | RuntimeException e) { return failure("INFRASTRUCTURE_ERROR", "INVALID_OR_MISSING_WORKER_RESULT"); }
    }

    private static JsonObject failure(String status, String code) {
        var result = new JsonObject(); result.addProperty("status", status); result.addProperty("failureCode", code); return result;
    }
    private static ContentDigest digest(String value) { return new ContentDigest(value); }
    private static String resource(String name) throws IOException {
        try (var stream = StripeCaseRunner.class.getResourceAsStream("/stripe-case/" + name)) {
            if (stream == null) throw new IOException("Missing worker resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
