package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceRenewalRegistryTest {
    private static final List<String> CORPORA = List.of("rollout-v1", "association-v1", "refusal-v1", "delivery-v1",
            "state-v1", "unpaid-v1", "old-delivery-v1", "boundary-v1", "registry-v1");
    @TempDir Path temporary;
    private Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Path copy() throws IOException {
        Path target = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        for (String name : List.of("README.md", "business-oracle.md", "provenance.json", "contract-integrity.json"))
            Files.copy(root().resolve(name), target.resolve(name));
        for (String corpus : CORPORA) {
            Path directory = Files.createDirectories(target.resolve("fixtures").resolve(corpus));
            try (var entries = Files.newDirectoryStream(root().resolve("fixtures").resolve(corpus))) {
                for (Path file : entries) Files.copy(file, directory.resolve(file.getFileName()));
            }
        }
        return target;
    }
    private JsonArray variants(JsonObject inventory, int corpus) {
        return inventory.getAsJsonArray("corpora").get(corpus).getAsJsonObject().getAsJsonArray("variants");
    }
    private void rejected(JsonObject required, JsonObject changed) {
        var error = assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRegistry.validateCoverage(required, changed));
        assertEquals("Combined invoice variant/control coverage mismatch", error.getMessage());
    }

    @Test void completeRegistrationCoversEveryVariantOrderAndControlWithoutExecutingConsumers() throws Exception {
        var registry = InvoiceRenewalRegistry.load(root()); assertDoesNotThrow(registry::requireFullCaseCoverage);
        assertEquals(InvoiceRenewalRegistry.BUNDLE_SHA256, registry.identity());
        assertEquals(List.of("rollout-v1", "association-v1", "refusal-v1", "delivery-v1", "state-v1", "unpaid-v1"),
                new ArrayList<>(registry.businessCorpora().keySet()));
        var inventory = registry.inventory(); assertEquals(15, inventory.getAsJsonArray("requiredFamilies").size());
        assertEquals("fixture-registration-only", inventory.get("authority").getAsString());
        int variants = 0, deliveries = 0, sequences = 0;
        for (var corpus : inventory.getAsJsonArray("corpora")) for (var row : corpus.getAsJsonObject().getAsJsonArray("variants")) {
            var variant = row.getAsJsonObject(); variants++; deliveries += variant.getAsJsonArray("deliveries").size();
            sequences += variant.getAsJsonArray("sequences").size();
        }
        assertEquals(47, variants); assertEquals(100, deliveries); assertEquals(188, sequences);
        assertEquals(34, inventory.getAsJsonObject("boundary").getAsJsonArray("controls").size());
        assertEquals(JsonParser.parseString("[\"alone\",\"healthy-first\",\"healthy-last\"]"),
                inventory.getAsJsonObject("boundary").get("placements"));
        assertEquals(24, registry.boundary().controls().stream().filter(c -> !c.expected().admitted()).count());
    }

    @Test void fifteenFamilyNamesCannotConcealAnOmittedOrSubstitutedEdgeVariant() throws Exception {
        var expected = InvoiceRenewalRegistry.load(root()).inventory();
        for (int corpus = 0; corpus < 7; corpus++) {
            var omitted = expected.deepCopy(); variants(omitted, corpus).remove(0); rejected(expected, omitted);
        }
        var omittedNull = expected.deepCopy(); variants(omittedNull, 2).remove(1); rejected(expected, omittedNull);
        assertEquals(expected.get("requiredFamilies"), omittedNull.get("requiredFamilies"));
        var substituted = expected.deepCopy(); variants(substituted, 2).get(1).getAsJsonObject().addProperty("id", "missing-field-acacia");
        rejected(expected, substituted);
        var extra = expected.deepCopy(); variants(extra, 4).add(variants(extra, 4).get(0).deepCopy()); rejected(expected, extra);
    }

    @Test void missingCorpusControlOrHealthyCompanionPlacementBlocksCompleteCoverage() throws Exception {
        var expected = InvoiceRenewalRegistry.load(root()).inventory();
        for (int i = 0; i < 7; i++) {
            var missing = expected.deepCopy(); missing.getAsJsonArray("corpora").remove(i); rejected(expected, missing);
        }
        for (int i = 0; i < 34; i++) {
            var missing = expected.deepCopy(); missing.getAsJsonObject("boundary").getAsJsonArray("controls").remove(i);
            rejected(expected, missing);
        }
        var missingBoundary = expected.deepCopy(); missingBoundary.remove("boundary"); rejected(expected, missingBoundary);
        var missingPlacement = expected.deepCopy(); missingPlacement.getAsJsonObject("boundary").getAsJsonArray("placements").remove(2);
        rejected(expected, missingPlacement);
    }

    @Test void orderAndBlockedPositionCoverageCannotBeReplacedByASuccessfulFinalLedger() throws Exception {
        var expected = InvoiceRenewalRegistry.load(root()).inventory();
        var missing = expected.deepCopy(); variants(missing, 0).get(0).getAsJsonObject().getAsJsonArray("sequences").remove(3);
        rejected(expected, missing);
        var repeat = expected.deepCopy(); variants(repeat, 3).get(0).getAsJsonObject().getAsJsonArray("sequences")
                .get(0).getAsJsonObject().getAsJsonArray("deliveries").remove(1); rejected(expected, repeat);
        var reversed = expected.deepCopy(); var order = variants(reversed, 0).get(0).getAsJsonObject()
                .getAsJsonArray("sequences").get(2).getAsJsonObject().getAsJsonArray("deliveries");
        var first = order.get(0); order.set(0, order.get(1)); order.set(1, first); rejected(expected, reversed);
        var block = expected.deepCopy(); variants(block, 6).get(1).getAsJsonObject().getAsJsonArray("blockedDeliveries").remove(0);
        rejected(expected, block);
    }

    @Test void staleCorpusIdentityRoleInstallationOrAuthorityCannotSatisfyTheGate() throws Exception {
        var expected = InvoiceRenewalRegistry.load(root()).inventory();
        for (String field : List.of("bundleIdentity", "kind", "installationId")) {
            var changed = expected.deepCopy(); changed.getAsJsonArray("corpora").get(0).getAsJsonObject().addProperty(field, "changed");
            rejected(expected, changed);
        }
        for (String field : List.of("caseId", "policy", "origin", "authority")) {
            var changed = expected.deepCopy(); changed.addProperty(field, "changed"); rejected(expected, changed);
        }
        var extra = expected.deepCopy(); extra.addProperty("executionPermission", "execute"); rejected(expected, extra);
    }

    @Test void priorInputEvidenceExpectedFactsAndOrdersRemainExactlyThoseOfTheirOwnCorpora() throws Exception {
        var registry = InvoiceRenewalRegistry.load(root());
        var rollout = InvoiceRenewalFixtures.load(root());
        assertEquals(rollout.scenarios(), registry.businessCorpora().get("rollout-v1").scenarios());
        var bundles = List.of(InvoiceRenewalAssociationFixtures.load(root()), InvoiceRenewalRefusalFixtures.load(root()),
                InvoiceRenewalDeliveryFixtures.load(root()), InvoiceRenewalStateFixtures.load(root()), InvoiceRenewalUnpaidFixtures.load(root()));
        for (int i = 0; i < bundles.size(); i++) {
            var bundle = bundles.get(i); var corpus = registry.businessCorpora().get(CORPORA.get(i + 1));
            assertEquals(bundle.identity(), corpus.identity()); assertEquals(bundle.installationId(), corpus.installationId());
            assertEquals(bundle.scenarios(), corpus.scenarios());
        }
        assertEquals(InvoiceRenewalOldDeliveryFixtures.load(root()).scenarios(), registry.oldDelivery().scenarios());
        var controls = InvoiceRenewalBoundaryFixtures.load(root()).controls();
        for (int i = 0; i < controls.size(); i++) {
            var original = controls.get(i); var retained = registry.boundary().controls().get(i);
            assertEquals(original.id(), retained.id()); assertEquals(original.input(), retained.input());
            assertEquals(original.expectedFact(), retained.expectedFact()); assertEquals(original.expected(), retained.expected());
            assertEquals(original.host().inputIdentity(), retained.host().inputIdentity());
            assertEquals(original.host().evidenceIdentity(), retained.host().evidenceIdentity());
        }
    }

    @Test void completeCoverageDoesNotInventFactsForBlockedPathsOrPromoteAuthoredAuthentication() throws Exception {
        var registry = InvoiceRenewalRegistry.load(root()); registry.requireFullCaseCoverage();
        var blocked = registry.oldDelivery().scenarios().stream().filter(s -> s.id().equals("legacy-adapter-absent")).findFirst().orElseThrow();
        assertTrue(blocked.requiresBlockedPathReview()); assertNull(blocked.deliveries().get("webhook").expected());
        assertThrows(IllegalArgumentException.class, blocked.deliveries().get("webhook")::oracle);
        assertNotNull(blocked.deliveries().get("reconciliation").expected());
        for (var control : registry.boundary().controls()) if (!control.expected().admitted()) {
            assertNull(control.expectedFact()); assertNull(control.executionStatus()); assertFalse(control.admission().admitted());
            assertThrows(IllegalArgumentException.class, control.admission()::oracle);
        }
        var plan = InvoiceRenewalFixtures.object(registry.bytes("boundary-v1", "controls.json"));
        assertEquals("authored-control-condition", plan.get("hostReceiptAuthority").getAsString());
    }

    @Test void retainedDataAndRegistryInventoryAreImmutableAndMetadataHasNoWorkerAnswers() throws Exception {
        var registry = InvoiceRenewalRegistry.load(root());
        assertThrows(UnsupportedOperationException.class, () -> registry.businessCorpora().clear());
        var corpus = registry.businessCorpora().get("rollout-v1");
        assertThrows(UnsupportedOperationException.class, () -> corpus.scenarios().clear());
        var delivery = corpus.scenarios().get(0).deliveries().get("webhook");
        var input = delivery.input(); String inputIdentity = delivery.expected().inputIdentity(); input.addProperty("consumerId", "changed");
        assertEquals(inputIdentity, InvoiceRenewalOracle.inputIdentity(delivery.input()));
        var inventory = registry.inventory(); inventory.remove("boundary"); registry.requireFullCaseCoverage();
        byte[] bytes = registry.bytes("rollout-v1", "event-acacia.json"); bytes[0] = 0;
        assertArrayEquals(InvoiceRenewalFixtures.load(root()).bytes("event-acacia.json"), registry.bytes("rollout-v1", "event-acacia.json"));
        byte[] registryBytes = registry.bytes("registry-v1", "registry.json"); registryBytes[0] = 0;
        assertEquals('{', registry.bytes("registry-v1", "registry.json")[0]);
        assertThrows(IllegalArgumentException.class, () -> registry.bytes("unknown", "bundle.json"));
        assertThrows(IllegalArgumentException.class, () -> registry.bytes("registry-v1", "../expectations.json"));
        assertFalse(registry.inventory().toString().contains("expectedFact"));
    }

    @Test void everyPartialCorpusStillRejectsFullCaseUse() throws Exception {
        assertThrows(IllegalStateException.class, InvoiceRenewalFixtures.load(root())::requireFullCaseCoverage);
        for (var bundle : List.of(InvoiceRenewalAssociationFixtures.load(root()), InvoiceRenewalRefusalFixtures.load(root()),
                InvoiceRenewalDeliveryFixtures.load(root()), InvoiceRenewalStateFixtures.load(root()), InvoiceRenewalUnpaidFixtures.load(root())))
            assertThrows(IllegalStateException.class, bundle::requireFullCaseCoverage);
        assertThrows(IllegalStateException.class, InvoiceRenewalOldDeliveryFixtures.load(root())::requireFullCaseCoverage);
        assertThrows(IllegalStateException.class, InvoiceRenewalBoundaryFixtures.load(root())::requireFullCaseCoverage);
    }

    @Test void oneMissingOrChangedSourceInAnyCorpusBlocksTheWholeRegistration() throws Exception {
        for (String corpus : CORPORA) {
            Path target = copy(), dir = target.resolve("fixtures").resolve(corpus);
            var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(dir.resolve("bundle.json")));
            String name = manifest.getAsJsonObject("files").keySet().iterator().next();
            Files.writeString(dir.resolve(name), "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRegistry.load(target), corpus);
            Path absent = copy(); Files.delete(absent.resolve("fixtures").resolve(corpus).resolve(name));
            assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRegistry.load(absent), corpus);
        }
    }

    @Test void rewritingTheCombinedManifestCannotAuthorizeMissingCoverageOrChangedBusinessContract() throws Exception {
        Path target = copy(), dir = target.resolve("fixtures/registry-v1");
        var inventory = InvoiceRenewalFixtures.object(Files.readAllBytes(dir.resolve("registry.json")));
        variants(inventory, 2).remove(1); byte[] bytes = inventory.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("registry.json"), bytes);
        var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(dir.resolve("bundle.json")));
        manifest.getAsJsonObject("files").addProperty("registry.json", CaseInputs.hash(bytes));
        Files.writeString(dir.resolve("bundle.json"), manifest.toString(), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRegistry.load(target));
        Path contract = copy(); Files.writeString(contract.resolve("business-oracle.md"), "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRegistry.load(contract));
        Path extra = copy(); Files.writeString(extra.resolve("fixtures/registry-v1/unregistered.json"), "{}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> InvoiceRenewalRegistry.load(extra));
    }
}
