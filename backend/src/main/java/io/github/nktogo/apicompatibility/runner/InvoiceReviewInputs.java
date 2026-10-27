package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import com.google.gson.stream.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import io.github.nktogo.apicompatibility.evaluation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static io.github.nktogo.apicompatibility.evaluation.CoordinationExperiment.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

/** Credential-free review inventories over exactly selected, already retained invoice trials. */
public final class InvoiceReviewInputs {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final String CASE = InvoiceRenewalFixtures.CASE_ID;
    private static final Map<Arm, String> STORES = Map.of(Arm.DETERMINISTIC, "invoice-diagnostics", Arm.SINGLE_AGENT, "invoice-single-agent",
            Arm.INDEPENDENT_WORKERS, "invoice-independent-workers", Arm.COORDINATED_TEAM, "invoice-team-comparisons");
    public record Source(Arm arm, String stateDirectory, String runId, ContentDigest resultIdentity) {
        public Source { Objects.requireNonNull(arm); relative(stateDirectory); trialId(runId); Objects.requireNonNull(resultIdentity); }
    }
    public record Selection(int formatVersion, String inputDirectory, ContentDigest inputIdentity, String contractDirectory, ContentDigest contractIdentity,
                            String rubricArtifact, ContentDigest rubricIdentity, List<Source> sources) {
        public Selection {
            CaseInputs.require(formatVersion == 1, "Unsupported invoice review selection"); relative(inputDirectory); relative(contractDirectory); relative(rubricArtifact);
            Objects.requireNonNull(inputIdentity); Objects.requireNonNull(contractIdentity); Objects.requireNonNull(rubricIdentity); sources = List.copyOf(sources);
            CaseInputs.require(sources.size() == Arm.values().length && sources.stream().map(Source::arm).toList().equals(List.of(Arm.values()))
                    && sources.stream().map(s -> s.stateDirectory() + "/" + s.runId()).distinct().count() == sources.size(), "Exactly four ordered distinct operational arm sources required");
        }
    }
    private final Path evidence, packets;
    public InvoiceReviewInputs(Path evidence, Path state) throws IOException {
        this.evidence = evidence.toAbsolutePath().normalize(); safeDirectory(this.evidence); safeDirectory(state.toAbsolutePath().normalize());
        packets = state.toAbsolutePath().normalize().resolve("invoice-review-packets");
    }
    public String prepare(Selection selection) throws IOException { return prepare("reviewpacket-" + UUID.randomUUID(), selection); }
    public String prepare(String id, Selection selection) throws IOException {
        packetId(id); separated(selection); if (!Files.exists(packets, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(packets); safeDirectory(packets); Path run = packets.resolve(id);
        if (!Files.exists(run, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(run); safeDirectory(run);
        var lease = LocalFileLease.tryAcquire(run.resolve("owner.lock")); CaseInputs.require(lease != null, "Invoice review publication is active");
        try (lease) {
            checkPacketFiles(run);
            var inventory = derive(selection); var packet = json(Map.of("formatVersion", 1, "packetId", id, "selection", selection, "inventory", inventory,
                    "qualityReview", "NOT_PERFORMED", "status", "REVIEW_INPUTS_BOUND", "authority", "post-hoc-operational-review-inputs-not-semantic-acceptance-or-paired-campaign"));
            checkedPublish(run, "packet.json", packet); checkedPublish(run, "receipt.json", Map.of("packetSha256", hash(run, "packet.json"))); return id;
        }
    }
    public JsonObject read(String id) throws IOException {
        Path run = directory(id); checkPacketFiles(run); var packet = readJson(run, "packet.json");
        CaseInputs.require(!Files.exists(run.resolve("packet.json.pending"), LinkOption.NOFOLLOW_LINKS) && !Files.exists(run.resolve("receipt.json.pending"), LinkOption.NOFOLLOW_LINKS), "Pending invoice review publication cannot be inspected");
        CaseInputs.require(readJson(run, "receipt.json").equals(json(Map.of("packetSha256", hash(run, "packet.json")))), "Incomplete or changed invoice review packet");
        var selection = selection(packet.getAsJsonObject("selection")); var derived = derive(selection);
        var expected = json(Map.of("formatVersion", 1, "packetId", id, "selection", selection, "inventory", derived, "qualityReview", "NOT_PERFORMED", "status", "REVIEW_INPUTS_BOUND",
                "authority", "post-hoc-operational-review-inputs-not-semantic-acceptance-or-paired-campaign"));
        CaseInputs.require(packet.equals(expected), "Changed invoice review inventory or scope"); return packet;
    }
    /** Transient inventory; timing and campaign membership must be established by the caller. */
    public JsonObject inspect(Selection selection) throws IOException { return derive(selection, true); }
    private JsonObject derive(Selection selection) throws IOException { return derive(selection, false); }
    private JsonObject derive(Selection selection, boolean neutralTiming) throws IOException {
        separated(selection);
        var roots = new ArrayList<String>(); roots.add(selection.inputDirectory()); roots.add(selection.contractDirectory()); roots.add(selection.rubricArtifact());
        var observed = new TreeMap<String, String>();
        InvoiceComparisonInputs.open(path(selection.inputDirectory()), selection.inputIdentity());
        var contract = inventory(List.of(selection.contractDirectory())); var contractLocal = new TreeMap<String, String>();
        contract.forEach((name, digest) -> contractLocal.put(name.substring(selection.contractDirectory().length() + 1), digest));
        CaseInputs.require(new ContentDigest(CaseInputs.identity(contractLocal)).equals(selection.contractIdentity()) && contractLocal.keySet().containsAll(Set.of("README.md", "business-oracle.md", "provenance.json")), "Changed or incomplete protected invoice contract");
        observed.putAll(contract); var rubric = sourceJson(selection.rubricArtifact(), observed);
        CaseInputs.require(new ContentDigest(observed.get(selection.rubricArtifact())).equals(selection.rubricIdentity()), "Changed protected invoice rubric"); validateRubric(rubric);
        var rows = new JsonArray();
        for (var source : selection.sources()) {
            String adapter = source.stateDirectory() + "/" + STORES.get(source.arm()) + "/" + source.runId();
            String runtime = source.stateDirectory() + "/invoice-comparison-trials/" + source.runId();
            safeDirectory(path(adapter)); safeDirectory(path(runtime));
            if (source.arm() == Arm.COORDINATED_TEAM) safeDirectory(path(source.stateDirectory() + "/invoice-team-runs/" + source.runId()));
            var result = sourceJson(adapter + "/result.json", observed);
            CaseInputs.require(source.resultIdentity().sha256().equals(observed.get(adapter + "/result.json")), "Selected invoice trial result changed");
            Path state = path(source.stateDirectory()), inputs = path(selection.inputDirectory());
            JsonObject retained = switch (source.arm()) {
                case DETERMINISTIC -> new InvoiceDiagnostics(state, inputs, selection.inputIdentity()).retained(source.runId());
                case SINGLE_AGENT -> new InvoiceSingleAgent(state, inputs, selection.inputIdentity()).retained(source.runId());
                case INDEPENDENT_WORKERS -> new InvoiceIndependentWorkers(state, inputs, selection.inputIdentity()).retained(source.runId());
                case COORDINATED_TEAM -> new InvoiceTeamComparison(state, inputs, selection.inputIdentity()).retained(source.runId());
            };
            CaseInputs.require(result.equals(retained) && result.get("qualityReview").getAsString().equals("NOT_PERFORMED"), "Changed or previously reviewed invoice adapter result");
            var trial = JSON.fromJson(result.get("trial"), CoordinationTrial.class);
            CaseInputs.require(trial.slot().arm() == source.arm() && trial.runId().equals(source.runId()) && trial.status() != CoordinationTrial.Status.PENDING && trial.cases().size() == 1,
                    "Changed invoice trial selection");
            var output = trial.cases().getFirst(); CaseInputs.require(output.caseId().equals(CASE) && output.inputIdentity().equals(selection.inputIdentity()) && output.review() == null, "Foreign or reviewed invoice output");
            roots.add(adapter); roots.add(runtime); if (source.arm() == Arm.COORDINATED_TEAM) roots.add(source.stateDirectory() + "/invoice-team-runs/" + source.runId());
            var units = new JsonArray(); unit(units, "TRIAL_OUTCOME", adapter + "/result.json", "/trial", "", "", observed);
            String dossierPath = adapter + "/dossier.json"; JsonObject dossier = null;
            if (Files.exists(path(dossierPath), LinkOption.NOFOLLOW_LINKS)) {
                dossier = sourceJson(dossierPath, observed);
                CaseInputs.require(dossier.get("caseId").getAsString().equals(CASE) && dossier.get("bundleIdentity").getAsString().equals(selection.inputIdentity().sha256())
                        && dossier.get("response").getAsString().equals(output.response().name()), "Changed invoice dossier scope");
                CaseInputs.require(output.response() == CoordinationTrial.Response.NOT_PROVIDED ? source.arm() == Arm.INDEPENDENT_WORKERS && output.outputIdentity() == null
                        : output.outputIdentity().sha256().equals(observed.get(dossierPath)), "Changed invoice dossier identity");
                sections(units, output.response() == CoordinationTrial.Response.NOT_PROVIDED ? "PARTIAL_AGGREGATE" : "FINAL_DOSSIER", dossierPath, dossier, observed);
            } else CaseInputs.require(output.response() == CoordinationTrial.Response.NOT_PROVIDED && output.outputIdentity() == null, "Missing selected invoice dossier");
            if (source.arm() == Arm.INDEPENDENT_WORKERS) {
                var dispositions = result.getAsJsonArray("workers");
                for (int i = 0; i < dispositions.size(); i++) {
                    var w = dispositions.get(i).getAsJsonObject(); String worker = w.get("workerId").getAsString();
                    unit(units, "WORKER_OUTCOME", adapter + "/result.json", "/workers/" + i, worker, "", observed);
                    if (w.get("status").getAsString().equals("COMPLETED")) {
                        String file = adapter + "/" + worker + ".json"; sections(units, "WORKER_DOSSIER", file, sourceJson(file, observed), observed);
                    }
                }
            }
            if (source.arm() == Arm.COORDINATED_TEAM) {
                var team = sourceJson(adapter + "/evidence.json", observed); var cited = citations(dossier);
                for (String group : List.of("originalClaims", "freshClaims")) for (int i = 0; i < team.getAsJsonArray(group).size(); i++) {
                    var claim = team.getAsJsonArray(group).get(i).getAsJsonObject(); String ref = claim.get("referenceId").getAsString();
                    unit(units, group.equals("originalClaims") ? "ORIGINAL_CLAIM" : "FRESH_CLAIM", adapter + "/evidence.json", "/" + group + "/" + i, ref, cited.contains(ref) ? "CITED" : "NOT_CITED", observed);
                }
                for (String group : List.of("workers", "freshWorkers", "coordinatorReview", "possibleConflicts")) unit(units, "TEAM_CONTEXT", adapter + "/evidence.json", "/" + group, "", "", observed);
                unit(units, "RECONCILIATION", adapter + "/reconciliation.json", "", "", "", observed);
            }
            var runtimeFiles = inventory(List.of(runtime));
            for (String file : runtimeFiles.keySet()) if (file.matches(".*/broker/model-[0-9a-f-]+/result\\.json")) {
                var response = sourceJson(file, observed);
                if (neutralTiming && !response.get("status").getAsString().equals("COMPLETED")) unit(units, "BROKER_OUTCOME", file, "", "", "", observed);
                if (response.has("outputText")) unit(units, "MODEL_RESPONSE", file, "/outputText", "", "", observed);
            }
            var row = json(Map.of("position", rows.size() + 1, "source", source, "trial", trial, "units", units));
            row.add("dossierArtifact", dossier == null ? JsonNull.INSTANCE : new JsonPrimitive(dossierPath)); rows.add(row);
        }
        var all = inventory(roots); for (var entry : observed.entrySet()) CaseInputs.require(entry.getValue().equals(all.get(entry.getKey())), "Review evidence changed during derivation");
        var after = inventory(roots); CaseInputs.require(all.equals(after), "Review source inventory changed during inspection");
        return json(Map.of("inputIdentity", selection.inputIdentity(), "rubricArtifact", selection.rubricArtifact(), "rubricIdentity", selection.rubricIdentity(),
                "contractDirectory", selection.contractDirectory(), "contractIdentity", selection.contractIdentity(), "sourceArtifacts", all, "trials", rows,
                "criteriaTiming", neutralTiming ? "NOT_INFERRED_FROM_SELECTION" : "POST_HOC_FOR_EXISTING_OPERATIONAL_TRIALS", "campaignAcceptance", "NOT_ESTABLISHED"));
    }
    private void sections(JsonArray units, String kind, String artifact, JsonObject value, Map<String, String> observed) throws IOException {
        unit(units, kind, artifact, "", "", "", observed);
        for (String key : new TreeSet<>(value.keySet())) {
            if (key.equals("findings")) for (int i = 0; i < value.getAsJsonArray(key).size(); i++) unit(units, "FINDING", artifact, "/findings/" + i, "", "", observed);
            else unit(units, key.equals("summary") ? "SUMMARY" : "DOSSIER_SECTION", artifact, "/" + escape(key), "", "", observed);
        }
    }
    private void unit(JsonArray units, String kind, String artifact, String pointer, String ref, String relation, Map<String, String> observed) throws IOException {
        var value = pointer(sourceJson(artifact, observed), pointer);
        units.add(json(Map.of("unitId", "unit-" + (units.size() + 1), "kind", kind, "artifact", artifact, "pointer", pointer,
                "valueIdentity", digest(encoded(value)), "referenceId", ref, "directFinalCitation", relation)));
        CaseInputs.require(units.size() <= 2000, "Invoice review unit limit exceeded");
    }
    static JsonElement pointer(JsonElement value, String pointer) {
        CaseInputs.require(pointer.isEmpty() || pointer.startsWith("/") && !pointer.matches(".*~(?![01]).*"), "Invalid invoice review pointer");
        if (pointer.isEmpty()) return value.deepCopy();
        for (String part : pointer.substring(1).split("/", -1)) {
            String key = part.replace("~1", "/").replace("~0", "~");
            if (value.isJsonObject()) { CaseInputs.require(value.getAsJsonObject().has(key), "Missing invoice pointer member"); value = value.getAsJsonObject().get(key); }
            else if (value.isJsonArray()) { CaseInputs.require(key.matches("0|[1-9][0-9]{0,7}"), "Invalid invoice pointer index"); int i = Integer.parseInt(key); CaseInputs.require(i < value.getAsJsonArray().size(), "Invoice pointer index outside array"); value = value.getAsJsonArray().get(i); }
            else throw new IllegalArgumentException("Invoice pointer crosses a scalar");
        }
        return value.deepCopy();
    }
    private static Set<String> citations(JsonObject dossier) {
        var result = new HashSet<String>(); if (dossier == null || !dossier.has("summaryCitations")) return result;
        dossier.getAsJsonArray("summaryCitations").forEach(v -> result.add(v.getAsString())); dossier.getAsJsonArray("findings").forEach(f -> f.getAsJsonObject().getAsJsonArray("citations").forEach(v -> result.add(v.getAsString()))); return result;
    }
    private Map<String, String> inventory(List<String> roots) throws IOException {
        var map = new TreeMap<String, String>(); var queue = new ArrayDeque<>(roots); int directories = 0; long bytes = 0;
        while (!queue.isEmpty()) {
            String ref = queue.removeFirst(); Path path = path(ref);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                safeDirectory(path); CaseInputs.require(++directories <= 5000, "Invoice review directory limit exceeded");
                try (var files = Files.list(path)) { for (var child : files.toList()) queue.add(ref + "/" + child.getFileName()); }
            } else if (!path.getFileName().toString().endsWith(".lock")) {
                byte[] content = ConsumerTasks.bytes(evidence, ref); bytes += content.length; map.put(ref, CaseInputs.hash(content));
                CaseInputs.require(map.size() <= 5000 && bytes <= 67108864, "Invoice review source limit exceeded");
            } else CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Linked invoice review lock");
        }
        return map;
    }
    private JsonObject sourceJson(String ref, Map<String, String> observed) throws IOException {
        path(ref); byte[] bytes = ConsumerTasks.bytes(evidence, ref); String digest = CaseInputs.hash(bytes), old = observed.putIfAbsent(ref, digest);
        CaseInputs.require(old == null || old.equals(digest), "Invoice review source changed while reading"); return object(bytes);
    }
    static void validateRubric(JsonObject value) {
        CaseInputs.require(value.keySet().equals(Set.of("formatVersion", "rubricId", "caseId", "scope", "expectedResponse", "criteria", "claimAudit", "acceptance"))
                && value.get("formatVersion").isJsonPrimitive() && value.get("formatVersion").getAsJsonPrimitive().isNumber() && value.get("formatVersion").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) == 0 && value.get("rubricId").getAsString().equals("invoice-joint-evidence-v1")
                && value.get("caseId").getAsString().equals(CASE) && value.get("expectedResponse").getAsString().equals("ANSWERED"), "Invalid joint invoice rubric");
        CaseInputs.require(rubricText(value.get("scope")), "Missing invoice rubric scope");
        var ids = new HashSet<String>(); var criteria = value.getAsJsonArray("criteria"); CaseInputs.require(criteria.size() == 6, "Incomplete invoice criteria");
        for (var item : criteria) { var c = item.getAsJsonObject(); CaseInputs.require(c.keySet().equals(Set.of("id", "requirement")) && c.get("id").getAsString().matches("[a-z][a-z-]{0,59}") && ids.add(c.get("id").getAsString()) && rubricText(c.get("requirement")), "Invalid invoice criterion"); }
        CaseInputs.require(ids.equals(Set.of("versioned-association", "independent-version-boundaries", "application-policy-gates", "joint-ledger-behavior", "execution-and-causal-scope", "evidence-and-uncertainty")), "Changed invoice criterion membership");
        var audit = value.getAsJsonObject("claimAudit"); CaseInputs.require(audit.keySet().equals(Set.of("scope", "verdicts", "binding")) && rubricText(audit.get("scope")) && rubricText(audit.get("binding")), "Incomplete invoice claim audit");
        var verdicts = audit.getAsJsonArray("verdicts"); CaseInputs.require(verdicts.size() == 3 && verdicts.asList().stream().map(JsonElement::getAsString).collect(java.util.stream.Collectors.toSet()).equals(Set.of("SUPPORTED_WITHIN_RECORDED_SCOPE", "UNSUPPORTED", "UNRESOLVED")), "Invalid invoice review verdicts");
        var acceptance = value.getAsJsonObject("acceptance"); CaseInputs.require(acceptance.keySet().equals(Set.of("criteria", "claims", "disposition", "comparison")) && acceptance.entrySet().stream().allMatch(e -> rubricText(e.getValue())), "Incomplete invoice acceptance contract");
    }
    private static boolean rubricText(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && !value.getAsString().isBlank() && value.getAsString().length() <= 4000; }
    private Path path(String ref) { relative(ref); Path path = evidence.resolve(ref).normalize(); safeDirectory(path.getParent()); CaseInputs.require(!Files.isSymbolicLink(path), "Linked invoice review source"); return path; }
    private void separated(Selection selection) {
        var sources = new ArrayList<>(List.of(selection.inputDirectory(), selection.contractDirectory(), selection.rubricArtifact()));
        for (var s : selection.sources()) {
            sources.add(s.stateDirectory() + "/" + STORES.get(s.arm()) + "/" + s.runId()); sources.add(s.stateDirectory() + "/invoice-comparison-trials/" + s.runId());
            if (s.arm() == Arm.COORDINATED_TEAM) sources.add(s.stateDirectory() + "/invoice-team-runs/" + s.runId());
        }
        for (String source : sources) { Path path = path(source); CaseInputs.require(!packets.startsWith(path) && !path.startsWith(packets), "Invoice packet storage overlaps selected evidence"); }
    }
    private static void relative(String ref) { CaseInputs.require(ref != null && ref.matches("[A-Za-z0-9_./-]+") && !ref.startsWith("/") && Arrays.stream(ref.split("/", -1)).noneMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")), "Invalid invoice review relative path"); }
    private static void safeDirectory(Path path) {
        ConsumerTasks.directory(path);
        // The Windows helper already checks every ancestor, including junctions.
        if (path.getFileSystem().provider().getClass().getName().equals("sun.nio.fs.WindowsFileSystemProvider")) return;
        for (Path current = path.toAbsolutePath().getParent(); current != null; current = current.getParent()) ConsumerTasks.directory(current);
    }
    private Path directory(String id) { packetId(id); safeDirectory(packets); Path run = packets.resolve(id); safeDirectory(run); return run; }
    private static void packetId(String id) { CaseInputs.require(id != null && id.matches("reviewpacket-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid invoice review packet ID"); }
    private static void trialId(String id) { CaseInputs.require(id != null && id.matches("trial-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"), "Invalid invoice trial ID"); }
    private static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }
    private static Selection selection(JsonObject value) { var selection = JSON.fromJson(value, Selection.class); CaseInputs.require(value.equals(JSON.toJsonTree(selection)), "Unexpected invoice selection fields"); return selection; }
    private static JsonObject readJson(Path root, String name) throws IOException { return object(ConsumerTasks.bytes(root, name)); }
    static JsonObject object(byte[] bytes) {
        return object(bytes, 1048576);
    }
    static JsonObject object(byte[] bytes, int limit) {
        CaseInputs.require(limit > 0 && limit <= 8388608 && bytes.length <= limit, "Invoice review JSON exceeds its byte limit");
        try (var reader = new JsonReader(new StringReader(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()))) {
            reader.setStrictness(Strictness.STRICT); var value = element(reader, 0).getAsJsonObject(); CaseInputs.require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing invoice review JSON"); return value;
        } catch (IOException e) { throw new IllegalArgumentException("Invalid invoice review JSON", e); }
    }
    private static JsonElement element(JsonReader reader, int depth) throws IOException {
        CaseInputs.require(depth <= 48, "Invoice review JSON nesting exceeded");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); var value = new JsonObject();
                while (reader.hasNext()) { String key = reader.nextName(); CaseInputs.require(!value.has(key) && value.size() < 5000, "Duplicate or excessive invoice review fields"); value.add(key, element(reader, depth + 1)); }
                reader.endObject(); yield value;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); var value = new JsonArray(); while (reader.hasNext()) { CaseInputs.require(value.size() < 5000, "Invoice review array exceeded"); value.add(element(reader, depth + 1)); } reader.endArray(); yield value;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Invalid invoice review JSON value");
        };
    }
    public ContentDigest contractIdentity(String directory) throws IOException {
        safeDirectory(path(directory));
        var files = inventory(List.of(directory)); var names = new TreeMap<String, String>();
        files.forEach((ref, hash) -> names.put(ref.substring(directory.length() + 1), hash)); return new ContentDigest(CaseInputs.identity(names));
    }
    private static void checkPacketFiles(Path run) throws IOException {
        try (var files = Files.list(run)) { for (var p : files.toList()) CaseInputs.require(Set.of("owner.lock", "packet.json", "receipt.json", "packet.json.pending", "receipt.json.pending").contains(p.getFileName().toString()), "Unexpected invoice packet artifact"); }
    }
    private static void checkedPublish(Path run, String name, Object value) throws IOException {
        var content = JSON.toJsonTree(value); byte[] expected = (encoded(content) + "\n").getBytes(StandardCharsets.UTF_8); CaseInputs.require(expected.length <= 1048576, "Invoice review packet exceeds 1 MiB");
        // Preserve completed bytes and their receipt; object iteration order is not a new inventory.
        if (Files.exists(run.resolve(name), LinkOption.NOFOLLOW_LINKS)) { CaseInputs.require(readJson(run, name).equals(content) && !Files.exists(run.resolve(name + ".pending"), LinkOption.NOFOLLOW_LINKS), "Changed invoice packet publication"); return; }
        Path pending = run.resolve(name + ".pending");
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) CaseInputs.require(Arrays.equals(ConsumerTasks.bytes(run, name + ".pending"), expected), "Changed pending invoice publication");
        else try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { var buffer = java.nio.ByteBuffer.wrap(expected); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true); }
        Files.move(pending, run.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 4 || args.length == 5, "Usage: prepare EVIDENCE STATE SELECTION [PACKET] | inspect EVIDENCE STATE SELECTION | read EVIDENCE STATE PACKET | contract EVIDENCE STATE DIRECTORY");
        var inputs = new InvoiceReviewInputs(Path.of(args[1]), Path.of(args[2])); String id;
        if (args[0].equals("contract") && args.length == 4) { System.out.println("Contract inventory SHA-256: " + inputs.contractIdentity(args[3]).sha256()); return; }
        if (args[0].equals("inspect") && args.length == 4) { Path plan = Path.of(args[3]).toAbsolutePath(); System.out.println(encoded(inputs.inspect(selection(readJson(plan.getParent(), plan.getFileName().toString()))))); return; }
        if (args[0].equals("prepare")) { Path plan = Path.of(args[3]).toAbsolutePath(); var selection = selection(readJson(plan.getParent(), plan.getFileName().toString())); id = args.length == 5 ? inputs.prepare(args[4], selection) : inputs.prepare(selection); }
        else { CaseInputs.require(args[0].equals("read") && args.length == 4, "Invalid invoice review action"); id = args[3]; }
        var packet = inputs.read(id); var rows = packet.getAsJsonObject("inventory").getAsJsonArray("trials");
        System.out.println("Packet: " + id + "; status: REVIEW_INPUTS_BOUND; trials: " + rows.size() + "; semantic review: NOT_PERFORMED");
        System.out.println("Sources: " + packet.getAsJsonObject("inventory").getAsJsonObject("sourceArtifacts").size() + "; packet SHA-256: " + hash(inputs.directory(id), "packet.json"));
    }
}
