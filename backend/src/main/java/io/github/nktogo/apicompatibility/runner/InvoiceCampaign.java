package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;

/** Frozen invoice comparison conditions. Registration grants no participant capability. */
public final class InvoiceCampaign {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String SCOPE = "Known synthetic invoice case; post-pilot conditions, not held-out accuracy or general superiority.";
    private static final Set<String> FILES = Set.of("owner.lock", "frozen.json", "plan.json", "rubric.json", "registration.json", "receipt.json");
    public record Selection(int formatVersion, String inputDirectory, ContentDigest inputIdentity,
                            String contractDirectory, ContentDigest contractIdentity, String rubricArtifact, ContentDigest rubricIdentity) {
        public Selection {
            CaseInputs.require(formatVersion == 1, "Unsupported invoice campaign selection");
            relative(inputDirectory); relative(contractDirectory); relative(rubricArtifact);
            Objects.requireNonNull(inputIdentity); Objects.requireNonNull(contractIdentity); Objects.requireNonNull(rubricIdentity);
        }
    }
    private record Frozen(int formatVersion, String campaignId, String frozenAt, String scope, Selection selection,
                          Map<String, String> sourceArtifacts, Map<Arm, CoordinationRuntime.Policy> policies,
                          Definition definition, List<Slot> order, CoordinationTeamBudget.Allocation teamAllocation, String allocationPolicy) {}
    private final Path evidence, state, campaigns;
    public InvoiceCampaign(Path evidence, Path state) {
        this.evidence = evidence.toAbsolutePath().normalize(); this.state = state.toAbsolutePath().normalize();
        safeDirectory(this.evidence); safeDirectory(this.state); campaigns = this.state.resolve("invoice-campaigns");
    }
    public String freeze(Selection selection) throws IOException { return freeze("invoicecampaign-" + UUID.randomUUID(), selection); }
    public String freeze(String id, Selection selection) throws IOException {
        campaignId(id);
        if (selection != null) separated(selection);
        if (!Files.exists(campaigns, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(campaigns);
        safeDirectory(campaigns); Path run = campaigns.resolve(id);
        if (!Files.exists(run, LinkOption.NOFOLLOW_LINKS)) {
            CaseInputs.require(selection != null, "A new campaign requires an explicit input selection"); Files.createDirectory(run);
        }
        safeDirectory(run); var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock"));
        CaseInputs.require(lease != null, "Invoice campaign publication is active");
        try (lease) {
            checkFiles(run);
            if (!Files.exists(run.resolve("frozen.json"), LinkOption.NOFOLLOW_LINKS)) {
                CaseInputs.require(selection != null && !Files.exists(run.resolve("plan.json")) && !Files.exists(run.resolve("rubric.json"))
                        && !Files.exists(run.resolve("registration.json")) && !Files.exists(run.resolve("receipt.json")), "Incomplete pre-freeze publication; do not replace it");
                var sources = inventory(selection); var policies = currentPolicies();
                var rubric = source(selection.rubricArtifact());
                var frozen = new Frozen(1, id, Instant.now().toString(), SCOPE, selection, sources, policies,
                        definition(id, selection, policies, InvoiceReviewInputs.object(rubric)), order(), CoordinationTeamBudget.forCeiling(60), CoordinationTeamBudget.POLICY);
                publish(run, "frozen.json", encode(frozen));
            }
            var frozen = frozen(run, id);
            CaseInputs.require(selection == null || selection.equals(frozen.selection()), "Cannot replace a frozen invoice selection");
            checkedPublish(run, "plan.json", encode(new CoordinationComparisonReport.Plan(1, frozen.definition())));
            checkedPublish(run, "rubric.json", source(frozen.selection().rubricArtifact()));
            if (!Files.exists(run.resolve("registration.json"), LinkOption.NOFOLLOW_LINKS)) {
                var candidates = matchingComparisons(frozen.definition().experimentId());
                CaseInputs.require(candidates.size() <= 1, "Ambiguous comparison registration; do not select a retry");
                String comparison = candidates.isEmpty() ? new CoordinationComparisonReport(state).register(run.resolve("plan.json")) : candidates.getFirst();
                publish(run, "registration.json", encode(mapping(run, frozen, comparison)));
            }
            var mapping = read(run, "registration.json");
            CaseInputs.require(mapping.equals(mapping(run, frozen, mapping.get("comparisonId").getAsString())), "Changed invoice comparison mapping");
            var expectedReceipt = receipt(run, id);
            if (Files.exists(run.resolve("receipt.json"), LinkOption.NOFOLLOW_LINKS))
                CaseInputs.require(read(run, "receipt.json").equals(expectedReceipt), "Changed resumed invoice campaign receipt");
            else publish(run, "receipt.json", encode(expectedReceipt));
            read(id); return id;
        }
    }
    public JsonObject read(String id) throws IOException {
        Path run = directory(id); checkFiles(run); var frozen = frozen(run, id);
        CaseInputs.require(Arrays.equals(bytes(run, "plan.json"), encode(new CoordinationComparisonReport.Plan(1, frozen.definition())))
                && Arrays.equals(bytes(run, "rubric.json"), source(frozen.selection().rubricArtifact())), "Changed frozen invoice plan or rubric");
        var mapping = read(run, "registration.json");
        CaseInputs.require(mapping.equals(mapping(run, frozen, mapping.get("comparisonId").getAsString()))
                && read(run, "receipt.json").equals(receipt(run, id)), "Incomplete or changed invoice campaign receipt");
        return json(Map.of("status", "REGISTRATION_VALIDATED", "campaignId", id, "comparisonId", mapping.get("comparisonId").getAsString(),
                "plannedTrials", 12, "plannedCaseOutputs", 12, "receiptSha256", hash(run, "receipt.json"),
                "definitionIdentity", identity(frozen.definition()), "participantCallsInitiatedByThisCommand", 0,
                "qualityReview", "NOT_ESTABLISHED_BY_REGISTRATION", "scope", SCOPE));
    }
    /** A later dispatcher must call this before admitting each new slot. Historical inspection stays possible. */
    public void verifyCurrentPolicies(String id) throws IOException {
        read(id); CaseInputs.require(frozen(directory(id), id).policies().equals(currentPolicies()), "Compiled invoice policies changed; freeze a new campaign");
    }
    private Frozen frozen(Path run, String id) throws IOException {
        var tree = read(run, "frozen.json"); var f = JSON.fromJson(tree, Frozen.class);
        CaseInputs.require(tree.equals(json(f)) && f.formatVersion() == 1 && id.equals(f.campaignId()) && SCOPE.equals(f.scope()), "Invalid frozen invoice campaign shape or identity");
        Instant.parse(f.frozenAt()); separated(f.selection());
        CaseInputs.require(inventory(f.selection()).equals(f.sourceArtifacts()), "Frozen invoice evidence changed");
        CaseInputs.require(f.definition().equals(definition(id, f.selection(), f.policies(), InvoiceReviewInputs.object(source(f.selection().rubricArtifact())))), "Changed declared invoice conditions");
        CaseInputs.require(f.order().equals(order()) && CoordinationTeamBudget.forCeiling(60).equals(f.teamAllocation())
                && CoordinationTeamBudget.POLICY.equals(f.allocationPolicy()), "Changed ordered invoice denominator or team allocation"); return f;
    }
    private static Map<Arm, CoordinationRuntime.Policy> currentPolicies() throws IOException {
        var policies = new EnumMap<Arm, CoordinationRuntime.Policy>(Arm.class);
        policies.put(Arm.DETERMINISTIC, InvoiceDiagnostics.policy()); policies.put(Arm.SINGLE_AGENT, InvoiceSingleAgent.policy());
        policies.put(Arm.INDEPENDENT_WORKERS, InvoiceIndependentWorkers.policy()); policies.put(Arm.COORDINATED_TEAM, InvoiceTeamComparison.policy());
        return policies;
    }
    private static Definition definition(String id, Selection selection, Map<Arm, CoordinationRuntime.Policy> policies, JsonObject rubric) {
        InvoiceReviewInputs.validateRubric(rubric);
        CaseInputs.require(policies.keySet().equals(EnumSet.allOf(Arm.class)) && policies.values().stream().map(CoordinationRuntime.Policy::adapterIdentity).distinct().count() == 1, "Missing or mixed compiled invoice policies");
        var implementations = new EnumMap<Arm, ContentDigest>(Arm.class);
        policies.forEach((arm, policy) -> {
            CaseInputs.require(policy.maxReads() == 100 && policy.participants().stream().allMatch(p -> p.cases().equals(Set.of(InvoiceRenewalFixtures.CASE_ID))), "Changed invoice evidence scope or read ceiling");
            implementations.put(arm, CoordinationRuntime.implementationIdentity(policy));
        });
        var criteria = rubric.getAsJsonArray("criteria").asList().stream().map(v -> v.getAsJsonObject().get("id").getAsString()).toList();
        return new Definition(id, Origin.SUPPLIED_RECORDS, "gpt-5.6-terra", selection.rubricIdentity(), implementations,
                List.of(new Workload("invoice-joint", List.of(new CaseTask(InvoiceRenewalFixtures.CASE_ID, selection.inputIdentity(), ExpectedResponse.ANSWER, criteria)))),
                3, new Limits(60, 1200000, 64000, 2048));
    }
    private static List<Slot> order() {
        var slots = new ArrayList<Slot>(); var arms = Arm.values();
        for (int rep = 1; rep <= 3; rep++) for (int offset = 0; offset < arms.length; offset++) slots.add(new Slot("invoice-joint", rep, arms[(offset + rep - 1) % arms.length]));
        return List.copyOf(slots);
    }
    private SortedMap<String, String> inventory(Selection selection) throws IOException {
        InvoiceComparisonInputs.open(path(selection.inputDirectory()), selection.inputIdentity());
        var result = new TreeMap<String, String>(); var contract = new TreeMap<String, String>();
        collect(selection.inputDirectory(), result);
        collect(selection.contractDirectory(), result);
        String prefix = selection.contractDirectory() + "/";
        result.forEach((name, hash) -> { if (name.startsWith(prefix)) contract.put(name.substring(prefix.length()), hash); });
        CaseInputs.require(contract.keySet().containsAll(Set.of("README.md", "business-oracle.md", "provenance.json"))
                && selection.contractIdentity().sha256().equals(CaseInputs.identity(contract)), "Changed or incomplete protected invoice contract");
        byte[] rubric = source(selection.rubricArtifact());
        CaseInputs.require(selection.rubricIdentity().sha256().equals(CaseInputs.hash(rubric)), "Changed protected invoice rubric");
        InvoiceReviewInputs.validateRubric(InvoiceReviewInputs.object(rubric)); result.put(selection.rubricArtifact(), CaseInputs.hash(rubric)); return result;
    }
    private void collect(String reference, Map<String, String> inventory) throws IOException {
        Path directory = path(reference); safeDirectory(directory);
        try (var walk = Files.walk(directory)) {
            long total = 0; int visited = 0; var entries = walk.iterator();
            while (entries.hasNext()) {
                Path file = entries.next(); CaseInputs.require(++visited <= 2048, "Excessive invoice evidence entries");
                CaseInputs.require(!Files.isSymbolicLink(file), "Linked invoice campaign evidence");
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) { safeDirectory(file); continue; }
                if (file.getFileName().toString().endsWith(".lock")) continue;
                String name = evidence.relativize(file).toString().replace('\\', '/');
                byte[] content = source(name); total += content.length;
                CaseInputs.require(total <= 16777216 && inventory.size() < 1024 && inventory.putIfAbsent(name, CaseInputs.hash(content)) == null, "Excessive or overlapping invoice evidence");
            }
        }
    }
    private List<String> matchingComparisons(String experiment) throws IOException {
        Path root = state.resolve("coordination-comparisons"); if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        safeDirectory(root); var matches = new ArrayList<String>();
        try (var entries = Files.list(root)) {
            var iterator = entries.iterator(); int count = 0;
            while (iterator.hasNext()) {
                Path candidate = iterator.next(); CaseInputs.require(++count <= 1024, "Excessive comparison registrations");
                safeDirectory(candidate);
                if (Files.exists(candidate.resolve("plan.json"), LinkOption.NOFOLLOW_LINKS)
                        && read(candidate, "plan.json").getAsJsonObject("definition").get("experimentId").getAsString().equals(experiment)) matches.add(candidate.getFileName().toString());
            }
        }
        return matches;
    }
    private JsonObject mapping(Path run, Frozen frozen, String comparison) throws IOException {
        CaseInputs.require(comparison != null && comparison.matches("comparison-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid invoice comparison reference");
        Path directory = state.resolve("coordination-comparisons").resolve(comparison); safeDirectory(directory);
        var registration = read(directory, "registration.json");
        Instant.parse(registration.get("registeredAt").getAsString());
        CaseInputs.require(registration.keySet().equals(Set.of("formatVersion", "comparisonId", "registeredAt", "planSha256", "definitionIdentity"))
                && registration.get("formatVersion").getAsInt() == 1 && comparison.equals(registration.get("comparisonId").getAsString())
                && hash(directory, "plan.json").equals(registration.get("planSha256").getAsString())
                && JSON.toJsonTree(identity(frozen.definition())).equals(registration.get("definitionIdentity"))
                && Arrays.equals(bytes(directory, "plan.json"), bytes(run, "plan.json")), "Incomplete or conflicting invoice comparison registration");
        return json(Map.of("formatVersion", 1, "comparisonId", comparison, "comparisonPlanSha256", hash(directory, "plan.json"), "comparisonRegistrationSha256", hash(directory, "registration.json")));
    }
    private static JsonObject receipt(Path run, String id) throws IOException {
        return json(Map.of("formatVersion", 1, "campaignId", id, "frozenSha256", hash(run, "frozen.json"),
                "planSha256", hash(run, "plan.json"), "rubricSha256", hash(run, "rubric.json"), "registrationSha256", hash(run, "registration.json")));
    }
    private void separated(Selection selection) {
        var sources = List.of(path(selection.inputDirectory()), path(selection.contractDirectory()), path(selection.rubricArtifact()));
        for (Path source : sources) {
            for (Path store : List.of(campaigns, state.resolve("coordination-comparisons")))
                CaseInputs.require(!source.startsWith(store) && !store.startsWith(source), "Campaign storage overlaps evidence");
            for (Path other : sources) if (source != other) CaseInputs.require(!source.startsWith(other) && !other.startsWith(source), "Selected invoice evidence overlaps");
        }
    }
    private Path path(String ref) { relative(ref); Path path = evidence.resolve(ref); safeDirectory(path.getParent()); return path; }
    private byte[] source(String ref) throws IOException { path(ref); return ConsumerTasks.bytes(evidence, ref); }
    private static void relative(String ref) { CaseInputs.require(ref != null && ref.matches("[A-Za-z0-9_./-]+") && !ref.startsWith("/") && Arrays.stream(ref.split("/", -1)).noneMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")), "Invalid invoice campaign evidence reference"); }
    private static void campaignId(String id) { CaseInputs.require(id != null && id.matches("invoicecampaign-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid invoice campaign ID"); }
    private Path directory(String id) { campaignId(id); safeDirectory(campaigns); Path run = campaigns.resolve(id); safeDirectory(run); return run; }
    private static void safeDirectory(Path directory) {
        ConsumerTasks.directory(directory);
        if (directory.getFileSystem().provider().getClass().getName().equals("sun.nio.fs.WindowsFileSystemProvider")) return;
        for (Path current = directory.getParent(); current != null; current = current.getParent()) ConsumerTasks.directory(current);
    }
    private static void checkFiles(Path run) throws IOException {
        try (var files = Files.list(run)) { for (Path file : files.toList()) CaseInputs.require(FILES.contains(file.getFileName().toString()) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "Unexpected or pending invoice campaign artifact"); }
    }
    private static byte[] bytes(Path root, String name) throws IOException { return ConsumerTasks.bytes(root, name); }
    private static String hash(Path root, String name) throws IOException { return CaseInputs.hash(bytes(root, name)); }
    private static JsonObject read(Path root, String name) throws IOException { return InvoiceReviewInputs.object(bytes(root, name)); }
    private static JsonObject json(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    private static byte[] encode(Object value) { return (JSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8); }
    private static void checkedPublish(Path root, String name, byte[] content) throws IOException {
        if (Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS)) CaseInputs.require(Arrays.equals(bytes(root, name), content), "Changed resumed invoice campaign artifact");
        else publish(root, name, content);
    }
    private static void publish(Path root, String name, byte[] content) throws IOException {
        CaseInputs.require(content.length <= 1048576 && !Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS), "Existing or oversized invoice campaign artifact");
        Path pending = root.resolve(name + ".pending");
        try (var channel = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(content); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(pending, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 4 || args.length == 5, "Usage: freeze EVIDENCE STATE SELECTION [CAMPAIGN] | resume/read/verify-current EVIDENCE STATE CAMPAIGN");
        var campaigns = new InvoiceCampaign(Path.of(args[1]), Path.of(args[2])); String id;
        if (args[0].equals("freeze")) {
            Path selection = Path.of(args[3]).toAbsolutePath().normalize(); var tree = read(selection.getParent(), selection.getFileName().toString());
            var value = JSON.fromJson(tree, Selection.class); CaseInputs.require(tree.equals(json(value)), "Unexpected invoice campaign selection fields");
            id = args.length == 5 ? campaigns.freeze(args[4], value) : campaigns.freeze(value);
        } else {
            CaseInputs.require(args.length == 4, "Unexpected invoice campaign arguments"); id = args[3];
            switch (args[0]) { case "resume" -> campaigns.freeze(id, null); case "read" -> {} case "verify-current" -> campaigns.verifyCurrentPolicies(id); default -> throw new IllegalArgumentException("Unsupported invoice campaign action"); }
        }
        System.out.println(JSON.toJson(campaigns.read(id)));
    }
}
