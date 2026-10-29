package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;

/** Registers local comparison definitions and retains offline reports. No model or execution adapter is invoked. */
public final class CoordinationComparisonReport {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path root;
    public record Plan(int formatVersion, Definition definition) {
        public Plan { CaseInputs.require(formatVersion == 1 && definition != null, "Invalid comparison plan"); }
    }
    public record Records(int formatVersion, List<CoordinationTrial> trials) {
        public Records {
            CaseInputs.require(formatVersion == 1 || formatVersion == 2, "Invalid comparison records"); trials = List.copyOf(trials);
            for (var trial : trials) for (var output : trial.cases()) if (output.review() != null)
                CaseInputs.require((formatVersion == 1) == (output.review().materialUnresolvedClaims() == null), "Review count does not match record version");
        }
    }
    public CoordinationComparisonReport(Path state) throws IOException {
        root = state.toAbsolutePath().normalize().resolve("coordination-comparisons"); Files.createDirectories(root); ConsumerTasks.directory(root);
    }
    /** Pure file assessment: no registration, report directory, participant or permission is created. */
    public static JsonObject evaluateFiles(Path planFile, Path recordsFile) throws IOException {
        planFile = planFile.toAbsolutePath().normalize(); recordsFile = recordsFile.toAbsolutePath().normalize();
        byte[] planBytes = ConsumerTasks.bytes(planFile.getParent(), planFile.getFileName().toString());
        byte[] recordBytes = ConsumerTasks.bytes(recordsFile.getParent(), recordsFile.getFileName().toString());
        var plan = decode(planFile, Plan.class); var records = decode(recordsFile, Records.class);
        CaseInputs.require(records.formatVersion() == 2 || records.trials().stream().flatMap(t -> t.cases().stream()).allMatch(c -> c.review() == null),
                "New reviewed records require formatVersion 2 and an explicit unresolved claim count");
        var result = JSON.toJsonTree(Map.of("formatVersion", records.formatVersion(), "planSha256", CaseInputs.hash(planBytes),
                "recordsSha256", CaseInputs.hash(recordBytes), "assessment", assessment(plan.definition(), records))).getAsJsonObject();
        CaseInputs.require(Arrays.equals(planBytes, ConsumerTasks.bytes(planFile.getParent(), planFile.getFileName().toString()))
                && Arrays.equals(recordBytes, ConsumerTasks.bytes(recordsFile.getParent(), recordsFile.getFileName().toString())), "Comparison input changed during assessment");
        return result;
    }
    public String register(Path planFile) throws IOException { return register(decode(planFile, Plan.class)); }
    private String register(Plan plan) throws IOException {
        String id = "comparison-" + UUID.randomUUID(); Path directory = Files.createDirectory(root.resolve(id));
        write(directory.resolve("plan.json"), plan);
        write(directory.resolve("registration.json"), Map.of("formatVersion", 1, "comparisonId", id, "registeredAt", Instant.now().toString(),
                "planSha256", CaseInputs.hash(ConsumerTasks.bytes(directory, "plan.json")), "definitionIdentity", identity(plan.definition())));
        return id;
    }
    public String assess(String id, Path recordsFile) throws IOException { return assess(id, decode(recordsFile, Records.class)); }
    private String assess(String id, Records records) throws IOException {
        CaseInputs.require(records.formatVersion() == 2 || records.trials().stream().flatMap(t -> t.cases().stream()).allMatch(c -> c.review() == null),
                "New reviewed records require formatVersion 2 and an explicit unresolved claim count");
        Path directory = directory(id); var plan = decode(directory.resolve("plan.json"), Plan.class);
        var registration = CaseInputs.object(ConsumerTasks.bytes(directory, "registration.json"));
        CaseInputs.require(id.equals(registration.get("comparisonId").getAsString())
                && CaseInputs.hash(ConsumerTasks.bytes(directory, "plan.json")).equals(registration.get("planSha256").getAsString())
                && JSON.toJsonTree(identity(plan.definition())).equals(registration.get("definitionIdentity")), "Changed registered comparison plan");
        var assessment = assessment(plan.definition(), records);
        String reportId = "report-" + UUID.randomUUID(); Path report = Files.createDirectory(directory.resolve(reportId));
        write(report.resolve("records.json"), recordsTree(records));
        write(report.resolve("result.json"), Map.of("formatVersion", records.formatVersion(), "comparisonId", id, "reportId", reportId, "recordedAt", Instant.now().toString(),
                "registrationSha256", CaseInputs.hash(ConsumerTasks.bytes(directory, "registration.json")),
                "recordsSha256", CaseInputs.hash(ConsumerTasks.bytes(report, "records.json")), "assessment", assessment));
        write(report.resolve("receipt.json"), Map.of("formatVersion", 1, "resultSha256", CaseInputs.hash(ConsumerTasks.bytes(report, "result.json"))));
        return reportId;
    }
    public JsonObject read(String id, String reportId) throws IOException {
        CaseInputs.require(reportId.matches("report-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid comparison report reference");
        Path directory = directory(id), report = directory.resolve(reportId); var result = CaseInputs.object(ConsumerTasks.bytes(report, "result.json"));
        var receipt = CaseInputs.object(ConsumerTasks.bytes(report, "receipt.json"));
        CaseInputs.require(JSON.toJsonTree(Map.of("formatVersion", 1, "resultSha256", CaseInputs.hash(ConsumerTasks.bytes(report, "result.json"))))
                .equals(receipt), "Changed or incomplete comparison report");
        var plan = decode(directory.resolve("plan.json"), Plan.class); var registration = CaseInputs.object(ConsumerTasks.bytes(directory, "registration.json"));
        var records = decode(report.resolve("records.json"), Records.class);
        CaseInputs.require(id.equals(result.get("comparisonId").getAsString()) && reportId.equals(result.get("reportId").getAsString())
                && result.get("formatVersion").getAsInt() == records.formatVersion()
                && CaseInputs.hash(ConsumerTasks.bytes(directory, "plan.json")).equals(registration.get("planSha256").getAsString())
                && JSON.toJsonTree(identity(plan.definition())).equals(registration.get("definitionIdentity"))
                && CaseInputs.hash(ConsumerTasks.bytes(directory, "registration.json")).equals(result.get("registrationSha256").getAsString())
                && CaseInputs.hash(ConsumerTasks.bytes(report, "records.json")).equals(result.get("recordsSha256").getAsString())
                && assessment(plan.definition(), records).equals(result.get("assessment")), "Changed comparison report");
        return result;
    }
    private Path directory(String id) {
        CaseInputs.require(id != null && id.matches("comparison-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid comparison reference");
        ConsumerTasks.directory(root); Path directory = root.resolve(id); ConsumerTasks.directory(directory); return directory;
    }
    private static <T> T decode(Path path, Class<T> type) throws IOException {
        byte[] bytes = ConsumerTasks.bytes(path.toAbsolutePath().getParent(), path.getFileName().toString());
        var tree = CaseInputs.object(bytes);
        T decoded = JSON.fromJson(tree, type);
        // All fields, including nullable review identities, must be explicit; unknown fields cannot assert a verdict.
        CaseInputs.require(tree.equals(decoded instanceof Records records ? recordsTree(records) : JSON.toJsonTree(decoded)), "Unexpected or missing comparison fields"); return decoded;
    }
    private static JsonElement recordsTree(Records records) {
        var tree = JSON.toJsonTree(records); if (records.formatVersion() == 1) removeV2Fields(tree); return tree;
    }
    private static JsonElement assessment(Definition definition, Records records) {
        var tree = JSON.toJsonTree(records.formatVersion() == 1
                ? CoordinationComparison.evaluateHistoricalV1(definition, records.trials()) : CoordinationComparison.evaluate(definition, records.trials()));
        if (records.formatVersion() == 1) removeV2Fields(tree); return tree;
    }
    private static void removeV2Fields(JsonElement tree) {
        if (tree.isJsonArray()) tree.getAsJsonArray().forEach(CoordinationComparisonReport::removeV2Fields);
        else if (tree.isJsonObject()) {
            tree.getAsJsonObject().remove("materialUnresolvedClaims");
            tree.getAsJsonObject().entrySet().forEach(entry -> removeV2Fields(entry.getValue()));
        }
    }
    private static void write(Path path, Object value) throws IOException {
        byte[] bytes = (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
        CaseInputs.require(bytes.length <= 1048576, "Comparison artifact too large");
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    }
    public static Plan examplePlan() {
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class);
        for (Arm arm : Arm.values()) implementations.put(arm, digest((char) ('1' + arm.ordinal())));
        var cases = List.of(new CaseTask("stripe-customer-sources-2020-08-27", digest('5'), ExpectedResponse.ANSWER,
                        List.of("observed-regressions", "absence-preconditions", "null-guard-limit")),
                new CaseTask("stripe-subscription-periods-2025-07-30", digest('6'), ExpectedResponse.ANSWER,
                        List.of("observed-regressions", "bound-item-selection", "policy-and-deferral-scope")));
        return new Plan(1, new Definition("two-case-example", Origin.SYNTHETIC_EXAMPLE, "gpt-5.6-terra", digest('7'), implementations,
                List.of(new Workload("sufficient-evidence-bundle", cases)), 3, new Limits(60, 1200000, 64000, 2048)));
    }
    private static ContentDigest digest(char value) { return new ContentDigest(String.valueOf(value).repeat(64)); }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length >= 2, "Usage: CoordinationComparisonReport evaluate PLAN RECORDS | example STATE | register STATE PLAN | assess STATE COMPARISON RECORDS | read STATE COMPARISON REPORT");
        if (args[0].equals("evaluate")) {
            CaseInputs.require(args.length == 3, "Invalid read-only evaluation arguments");
            System.out.println(JSON.toJson(evaluateFiles(Path.of(args[1]), Path.of(args[2])))); return;
        }
        var reports = new CoordinationComparisonReport(Path.of(args[1])); String id, report;
        switch (args[0]) {
            case "example" -> {
                CaseInputs.require(args.length == 2, "Invalid example arguments"); id = reports.register(examplePlan()); report = reports.assess(id, new Records(1, List.of()));
            }
            case "register" -> {
                CaseInputs.require(args.length == 3, "Invalid registration arguments"); System.out.println(reports.register(Path.of(args[2]))); return;
            }
            case "assess" -> {
                CaseInputs.require(args.length == 4, "Invalid assessment arguments"); id = args[2]; report = reports.assess(id, Path.of(args[3]));
            }
            case "read" -> { CaseInputs.require(args.length == 4, "Invalid read arguments"); id = args[2]; report = args[3]; }
            default -> throw new IllegalArgumentException("Unknown comparison action");
        }
        var result = reports.read(id, report); var assessment = result.getAsJsonObject("assessment");
        System.out.println("Comparison: " + id + "; report: " + report);
        System.out.println("Origin: " + assessment.getAsJsonObject("definition").get("origin").getAsString());
        System.out.println("Status: " + assessment.get("conclusion").getAsString() + "; routing: " + assessment.get("routingDecision").getAsString());
        System.out.println("Planned slots: " + assessment.getAsJsonArray("rows").size());
        System.out.println("Report: " + reports.directory(id).resolve(report).resolve("result.json"));
    }
}
