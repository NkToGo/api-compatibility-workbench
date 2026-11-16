package io.github.nktogo.apicompatibility.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static io.github.nktogo.apicompatibility.github.MigrationConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationConfigurationTest {
    @TempDir Path temporary;
    static final String ROOT = "pom.xml", WEBHOOK = "webhook/pom.xml", RECONCILIATION = "reconciliation/pom.xml";
    static final byte[] POM = ("<project><modelVersion>4.0.0</modelVersion><properties><stripe.sdk.version>28.4.0</stripe.sdk.version>"
            + "<private.key>PRIVATE_UNRELATED_PROPERTY</private.key></properties><dependencyManagement><dependencies><dependency>"
            + "<groupId>com.stripe</groupId><artifactId>stripe-java</artifactId><version>${stripe.sdk.version}</version>"
            + "</dependency></dependencies></dependencyManagement><profiles><profile><properties><stripe.sdk.version>29.0.0</stripe.sdk.version>"
            + "</properties></profile></profiles></project>").getBytes(StandardCharsets.UTF_8);
    static Versions baseline(boolean webhook) { return new Versions("28.4.0", ACACIA, webhook ? ACACIA : null, webhook ? ACACIA : null, ACACIA, null); }
    static Versions target(boolean webhook) { return new Versions("29.0.0", BASIL, webhook ? BASIL : null, webhook ? BASIL : null, ACACIA, null); }
    static GitHubSourceInventory.FileResult file(String path, byte[] bytes) {
        return new GitHubSourceInventory.FileResult(path, GitHubSourceInventory.FileState.READ, GitSourceObjects.gitHash("blob", bytes),
                GitSourceObjects.sha256(bytes), bytes.length, io.github.nktogo.apicompatibility.inventory.MavenPomInventory.scan(bytes));
    }
    static GitHubSourceInventory.Result inventory() {
        var snapshot = new GitHubRepositoryConnection.Snapshot(1, 10, GitHubRepositoryConnectionTest.SELECTION, "b".repeat(40),
                Instant.parse("2026-11-16T00:00:00Z"), Instant.parse("2026-11-16T01:00:00Z"), "AUTHENTICATED_GITHUB_METADATA", false);
        return new GitHubSourceInventory.Result(1, snapshot, new GitHubSourceInventory.Plan(1, List.of(ROOT, WEBHOOK, RECONCILIATION)),
                snapshot.observedAt(), GitHubSourceInventory.State.COMPLETE_WITHIN_SELECTED_FILES,
                List.of(file(ROOT, POM), file(WEBHOOK, POM), file(RECONCILIATION, POM)), 6, POM.length * 3, null, List.of("SELECTED_POMS_ONLY"));
    }
    static Plan plan(GitHubSourceInventory.Result inventory) {
        return new Plan(1, CASE_ID, Repository.from(inventory.repository()), inventory.files().stream().map(f -> new Pom(f.path(), f.contentSha256())).toList(),
                List.of(new Consumer("invoice-webhook", WEBHOOK, baseline(true), target(true)),
                        new Consumer("invoice-reconciliation", RECONCILIATION, baseline(false), target(false))));
    }
    static Plan consumers(Plan plan, List<Consumer> consumers) { return new Plan(1, plan.caseId(), plan.repository(), plan.poms(), consumers); }
    static Plan webhookTarget(Plan plan, Versions target) {
        return consumers(plan, List.of(plan.consumers().getFirst(), new Consumer("invoice-webhook", WEBHOOK, baseline(true), target)));
    }
    static GitHubSourceInventory.Result files(GitHubSourceInventory.Result old, List<GitHubSourceInventory.FileResult> files, GitHubSourceInventory.State state) {
        return new GitHubSourceInventory.Result(1, old.repository(), old.plan(), old.completedAt(), state, files, old.sourceRequests(), old.sourceBytes(), old.problem(), old.limitations());
    }
    static Set<Code> codes(Result result) { return result.issues().stream().map(Issue::code).collect(java.util.stream.Collectors.toSet()); }

    @Test void bindsDistinctVersionsAndRetainsAmbiguousPomCandidatesWithoutRuntimeOrActionAuthority() {
        var inventory = inventory(); var plan = plan(inventory); var result = bind(inventory, plan);
        assertEquals(State.DECLARATIONS_COMPLETE_NOT_VERIFIED, result.state()); assertTrue(result.issues().isEmpty());
        assertTrue(result.hasSameInputs(inventory, plan)); assertEquals(64, result.bindingIdentity().length());
        assertEquals(BASIL, result.declarations().consumers().getFirst().target().requestApiVersion());
        assertEquals(ACACIA, result.declarations().consumers().getFirst().target().accountApiVersion());
        assertEquals(2, result.inventory().files().getFirst().declarations().stripeJavaDeclarations().getFirst().propertyCandidates().size());
        assertTrue(result.limitations().contains("MAVEN_EFFECTIVE_MODEL_NOT_RESOLVED"));
        assertTrue(result.limitations().contains("CASE_MAPPING_IS_NOT_EXECUTION_AUTHORIZATION")); assertFalse(result.inventory().repository().compatibilityAssessed());
        assertFalse(GitHubJson.JSON.toJson(result).contains("PRIVATE_UNRELATED_PROPERTY"));
        assertThrows(UnsupportedOperationException.class, () -> result.issues().clear());
    }
    @Test void neverFillsUnknownVersionFieldsFromSdkOrAccountDefaults() {
        var inventory = inventory(); var plan = webhookTarget(plan(inventory), new Versions("29.0.0", null, null, null, BASIL, null));
        var result = bind(inventory, plan); assertEquals(State.DECLARATIONS_HAVE_GAPS, result.state());
        assertEquals(Set.of("requestApiVersion", "endpointApiVersion", "eventApiVersion"), result.issues().stream().filter(i -> i.code() == Code.UNKNOWN_VERSION).map(Issue::field).collect(java.util.stream.Collectors.toSet()));
        assertNull(result.declarations().consumers().get(1).target().requestApiVersion());
    }
    @Test void preservesBothDirectionsOfMixedConsumerRollout() {
        var inventory = inventory(); var base = plan(inventory);
        for (boolean requestFirst : List.of(true, false)) {
            var plan = consumers(base, List.of(new Consumer("invoice-reconciliation", RECONCILIATION, baseline(false), requestFirst ? target(false) : baseline(false)),
                    new Consumer("invoice-webhook", WEBHOOK, baseline(true), requestFirst ? baseline(true) : target(true))));
            assertEquals(State.DECLARATIONS_COMPLETE_NOT_VERIFIED, bind(inventory, plan).state());
        }
    }
    @Test void oldEventIsNotRelabeledAfterEndpointUpgradeAndAdapterDeclarationIsNotVerification() {
        var inventory = inventory(); var oldEvent = new Versions("29.0.0", BASIL, BASIL, ACACIA, ACACIA, null);
        var missing = bind(inventory, webhookTarget(plan(inventory), oldEvent)); assertTrue(codes(missing).contains(Code.ADAPTER_REQUIRED));
        var adapted = new Versions("29.0.0", BASIL, BASIL, ACACIA, ACACIA, ACACIA);
        var result = bind(inventory, webhookTarget(plan(inventory), adapted)); assertEquals(State.DECLARATIONS_COMPLETE_NOT_VERIFIED, result.state());
        assertEquals(ACACIA, result.declarations().consumers().get(1).target().eventApiVersion());
        assertTrue(result.limitations().contains("EVENT_SOURCE_NOT_AUTHENTICATED"));
        var wrong = new Versions("29.0.0", BASIL, BASIL, ACACIA, ACACIA, BASIL);
        assertTrue(codes(bind(inventory, webhookTarget(plan(inventory), wrong))).contains(Code.ADAPTER_EVENT_MISMATCH));
        var forward = new Versions("28.4.0", ACACIA, BASIL, BASIL, ACACIA, BASIL);
        assertTrue(codes(bind(inventory, webhookTarget(plan(inventory), forward))).contains(Code.ADAPTER_MAPPING_OUTSIDE_PROFILE));
    }
    @Test void unsupportedValuesAndSdkRequestPairStayOutsideThisProfileRatherThanBeingCalledIncompatible() {
        var inventory = inventory(); var outside = new Versions("31.2.0", "2025-07-30.basil", BASIL, BASIL, ACACIA, null);
        var result = bind(inventory, webhookTarget(plan(inventory), outside)); assertTrue(codes(result).contains(Code.VERSION_OUTSIDE_PROFILE));
        assertEquals(State.DECLARATIONS_HAVE_GAPS, result.state());
        var mixed = new Versions("29.0.0", ACACIA, BASIL, BASIL, ACACIA, null);
        assertTrue(codes(bind(inventory, webhookTarget(plan(inventory), mixed))).contains(Code.SDK_REQUEST_PAIR_OUTSIDE_PROFILE));
    }
    @Test void requestConsumerCannotSilentlyCarryWebhookSettings() {
        var inventory = inventory(); var base = plan(inventory);
        var invalid = consumers(base, List.of(new Consumer("invoice-reconciliation", RECONCILIATION, baseline(false), target(true)), base.consumers().get(1)));
        assertTrue(codes(bind(inventory, invalid)).contains(Code.UNEXPECTED_WEBHOOK_CONFIGURATION));
    }
    @Test void unknownCasesMissingRolesAndUnmappedRolesRemainGaps() {
        var inventory = inventory(); var plan = plan(inventory);
        assertTrue(codes(bind(inventory, new Plan(1, "other-case", plan.repository(), plan.poms(), plan.consumers()))).contains(Code.UNREGISTERED_CASE));
        assertTrue(codes(bind(inventory, consumers(plan, List.of(plan.consumers().getFirst())))).contains(Code.MISSING_CONSUMER));
        var roles = new ArrayList<>(plan.consumers()); roles.add(new Consumer("other-worker", ROOT, baseline(false), target(false)));
        assertTrue(codes(bind(inventory, consumers(plan, roles))).contains(Code.UNREGISTERED_CONSUMER));
    }
    @ParameterizedTest @ValueSource(strings = {"account", "repository", "owner", "name", "commit", "tree"})
    void rejectsStaleOrDifferentRepositoryIdentities(String changed) {
        var inventory = inventory(); var plan = plan(inventory); var r = plan.repository();
        var changedRepository = new Repository(changed.equals("account") ? 99 : r.accountId(), changed.equals("repository") ? 99 : r.repositoryId(),
                changed.equals("owner") ? "other-owner" : r.owner(), changed.equals("name") ? "other-name" : r.name(),
                changed.equals("commit") ? "c".repeat(40) : r.commitSha(), changed.equals("tree") ? "d".repeat(40) : r.treeSha());
        var next = new Plan(1, CASE_ID, changedRepository, plan.poms(), plan.consumers());
        assertTrue(codes(bind(inventory, next)).contains(Code.REPOSITORY_MISMATCH)); assertFalse(bind(inventory, plan).hasSameInputs(inventory, next));
    }
    @Test void requiresExactWholeSelectedPomSetAndEachMappedPath() {
        var inventory = inventory(); var plan = plan(inventory);
        var removed = new Plan(1, CASE_ID, plan.repository(), plan.poms().subList(0, 2), plan.consumers());
        assertTrue(codes(bind(inventory, removed)).contains(Code.POM_SET_MISMATCH));
        var missing = consumers(plan, List.of(new Consumer("invoice-reconciliation", "missing/pom.xml", baseline(false), target(false)), plan.consumers().get(1)));
        assertTrue(codes(bind(inventory, missing)).contains(Code.POM_UNAVAILABLE));
    }
    @Test void changedUnmappedPomAndFailedReadsInvalidateReuseWithoutErasingEarlierFacts() {
        var inventory = inventory(); var plan = plan(inventory); var result = bind(inventory, plan);
        var changed = new ArrayList<>(inventory.files()); changed.set(0, file(ROOT, new String(POM, StandardCharsets.UTF_8).replace("28.4.0", "28.3.0").getBytes(StandardCharsets.UTF_8)));
        var next = files(inventory, changed, inventory.state()); assertTrue(codes(bind(next, plan)).contains(Code.POM_IDENTITY_MISMATCH));
        assertFalse(result.hasSameInputs(next, plan)); assertEquals(inventory, result.inventory());
        changed.set(0, new GitHubSourceInventory.FileResult(ROOT, GitHubSourceInventory.FileState.READ_FAILED, null, null, null, null));
        next = files(inventory, changed, GitHubSourceInventory.State.INCOMPLETE);
        assertTrue(codes(bind(next, plan)).containsAll(Set.of(Code.INVENTORY_INCOMPLETE, Code.POM_UNAVAILABLE)));
        assertEquals(GitHubSourceInventory.FileState.READ, bind(next, plan).inventory().files().get(1).state());
    }
    @Test void unconditionalLiteralDifferentFromBaselineRequiresReviewWithoutChoosingProfileCandidates() {
        var inventory = inventory(); var changed = new ArrayList<>(inventory.files());
        changed.set(1, file(WEBHOOK, "<project><modelVersion>4.0.0</modelVersion><dependencies><dependency><groupId>com.stripe</groupId><artifactId>stripe-java</artifactId><version>29.0.0</version></dependency></dependencies></project>".getBytes(StandardCharsets.UTF_8)));
        var next = files(inventory, changed, inventory.state()); var plan = plan(next);
        assertTrue(codes(bind(next, plan)).contains(Code.BASELINE_POM_DECLARATION_DIFFERS));
        assertFalse(codes(bind(inventory, plan(inventory))).contains(Code.BASELINE_POM_DECLARATION_DIFFERS));
    }
    @Test void identityIgnoresObservationTimeAndConfigurationObjectOrderingButTracksEveryVersionField() {
        var inventory = inventory(); var plan = plan(inventory); var result = bind(inventory, plan);
        var s = inventory.repository(); var later = new GitHubRepositoryConnection.Snapshot(1, s.appId(), s.selection(), s.treeSha(), s.observedAt().plusSeconds(30), s.tokenExpiresAt().plusSeconds(30), s.authority(), false);
        var observed = new GitHubSourceInventory.Result(1, later, inventory.plan(), inventory.completedAt().plusSeconds(30), inventory.state(), inventory.files(), 8, inventory.sourceBytes(), null, inventory.limitations());
        assertTrue(result.hasSameInputs(observed, plan));
        var poms = new ArrayList<>(plan.poms()); Collections.reverse(poms); var roles = new ArrayList<>(plan.consumers()); Collections.reverse(roles);
        assertTrue(result.hasSameInputs(inventory, new Plan(1, CASE_ID, plan.repository(), poms, roles)));
        var sdk = new Versions("28.4.0", BASIL, BASIL, BASIL, ACACIA, null);
        var request = new Versions("29.0.0", ACACIA, BASIL, BASIL, ACACIA, null);
        var endpoint = new Versions("29.0.0", BASIL, ACACIA, BASIL, ACACIA, null);
        var event = new Versions("29.0.0", BASIL, BASIL, ACACIA, ACACIA, null);
        var account = new Versions("29.0.0", BASIL, BASIL, BASIL, BASIL, null);
        var adapter = new Versions("29.0.0", BASIL, BASIL, BASIL, ACACIA, BASIL);
        for (var v : List.of(sdk, request, endpoint, event, account, adapter)) assertFalse(result.hasSameInputs(inventory, webhookTarget(plan, v)));
    }
    @Test void identicalDeclaredSidesDoNotBecomeAMigration() {
        var inventory = inventory(); var base = plan(inventory); var plan = consumers(base, base.consumers().stream().map(c -> new Consumer(c.id(), c.pomPath(), c.baseline(), c.baseline())).toList());
        assertTrue(codes(bind(inventory, plan)).contains(Code.NO_DECLARED_TRANSITION));
    }
    @Test void readsStrictExplicitNullConfigurationAndPinsTheExistingCaseContract() throws Exception {
        var inventory = inventory(); var expected = plan(inventory); Path input = temporary.resolve("configuration.json");
        Files.writeString(input, GitHubJson.JSON.toJson(expected)); assertEquals(expected, load(input));
        var root = Path.of(System.getProperty("workbench.repository"));
        assertEquals(PROFILE.contractSha256(), GitSourceObjects.sha256(Files.readAllBytes(root.resolve("cases/" + CASE_ID + "/contract-integrity.json"))));
        var example = load(root.resolve("examples/invoice-migration-configuration.json"));
        assertEquals(CASE_ID, example.caseId()); assertEquals(Set.copyOf(PROFILE.consumers()), example.consumers().stream().map(Consumer::id).collect(java.util.stream.Collectors.toSet()));
        var extra = GitHubJson.JSON.toJsonTree(expected).getAsJsonObject(); extra.addProperty("credential", "PRIVATE_CREDENTIAL_SENTINEL"); Files.writeString(input, extra.toString());
        assertThrows(IllegalArgumentException.class, () -> load(input));
        extra = GitHubJson.JSON.toJsonTree(expected).getAsJsonObject(); extra.getAsJsonArray("consumers").get(0).getAsJsonObject().getAsJsonObject("target").remove("accountApiVersion"); Files.writeString(input, extra.toString());
        assertThrows(IllegalArgumentException.class, () -> load(input));
        Files.writeString(input, "{\"caseId\":\"a\",\"caseId\":\"b\"}"); assertThrows(IllegalArgumentException.class, () -> load(input));
        Files.write(input, new byte[32769]); assertThrows(IllegalArgumentException.class, () -> load(input));
    }
    @ParameterizedTest @ValueSource(strings = {"", "sk-proj-PRIVATE", "29.0.0;run", "${env.PRIVATE}", "29.0.0\nPRIVATE"})
    void refusesSecretsAndExpressionsInVersionFields(String value) {
        assertThrows(IllegalArgumentException.class, () -> new Versions(value, BASIL, null, null, ACACIA, null));
        assertThrows(IllegalArgumentException.class, () -> new Versions("29.0.0", value, null, null, ACACIA, null));
    }
    @Test void rejectsImpossibleDatesDuplicateMappingsAndUnsafeSourcePaths() {
        assertThrows(IllegalArgumentException.class, () -> new Versions("29.0.0", "2025-02-30.basil", null, null, ACACIA, null));
        assertThrows(IllegalArgumentException.class, () -> new Pom("../pom.xml", "a".repeat(64)));
        var plan = plan(inventory());
        assertThrows(IllegalArgumentException.class, () -> new Plan(1, CASE_ID, plan.repository(), List.of(plan.poms().getFirst(), plan.poms().getFirst()), plan.consumers()));
        assertThrows(IllegalArgumentException.class, () -> consumers(plan, List.of(plan.consumers().getFirst(), plan.consumers().getFirst())));
    }
    @Test void freshLoopbackInventoryFeedsBindingWithoutExecutionOrModelCalls() throws Exception {
        GitHubSourceInventoryTest.key();
        try (var fixture = new GitHubRepositoryConnectionTest.Fixture()) {
            var directory = new GitHubSourceInventoryTest.Directory(); directory.files.put(ROOT, POM);
            directory.directory("webhook").files.put(ROOT, POM); directory.directory("reconciliation").files.put(ROOT, POM);
            GitHubSourceInventoryTest.install(fixture, directory);
            var fresh = GitHubSourceInventoryTest.inspect(fixture, List.of(ROOT, WEBHOOK, RECONCILIATION));
            var result = bind(fresh, plan(fresh)); assertEquals(State.DECLARATIONS_COMPLETE_NOT_VERIFIED, result.state());
            assertFalse(GitHubJson.JSON.toJson(result).contains(GitHubRepositoryConnectionTest.TOKEN));
            assertEquals(fresh.sourceRequests() + 5, fixture.requests.size());
        }
    }
}
