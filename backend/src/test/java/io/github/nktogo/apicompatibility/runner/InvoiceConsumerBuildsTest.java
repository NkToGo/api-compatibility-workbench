package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceConsumerBuildsTest {
    @TempDir Path temporary;
    private Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Path copy() throws IOException {
        Path target = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        try (var entries = Files.walk(root())) {
            for (Path entry : entries.toList()) {
                String name = root().relativize(entry).toString().replace('\\', '/');
                if (name.startsWith("target/") && !name.equals("target/dependencies") && !name.startsWith("target/dependencies/")) continue;
                Path output = target.resolve(name);
                if (Files.isDirectory(entry)) Files.createDirectories(output);
                else { Files.createDirectories(output.getParent()); Files.copy(entry, output); }
            }
        }
        return target;
    }
    @Test void completeSourceAndDependencyRegistrationContainsBothRolesAndAllSixBuilds() throws Exception {
        var registration = InvoiceConsumerBuilds.load(root());
        assertEquals(InvoiceConsumerBuilds.REGISTRATION_SHA256, registration.identity());
        assertEquals(List.of("webhook-baseline", "webhook-unrepaired-target", "webhook-basil-source", "reconciliation-baseline",
                "reconciliation-unrepaired-target", "reconciliation-basil-source"), registration.builds().stream().map(InvoiceConsumerBuilds.Build::id).toList());
        assertEquals(4, registration.builds().stream().filter(b -> b.expectedCompilation() == InvoiceConsumerBuilds.Disposition.SUCCEEDED).count());
        assertEquals(2, registration.builds().stream().filter(b -> b.expectedCompilation() == InvoiceConsumerBuilds.Disposition.FAILED).count());
    }
    @Test void actualCompilerRetainsBothUnchangedSourceUpgradeFailuresAndAllFourSuccessfulBuilds() throws Exception {
        var registration = InvoiceConsumerBuilds.load(root());
        for (var build : registration.builds()) {
            var result = InvoiceConsumerBuilds.compile(registration, build.id(), temporary.resolve("compiled"));
            assertEquals(build.expectedCompilation(), result.disposition(), build.id());
            assertEquals(registration.sourceIdentity(build.id()), result.sourceIdentity());
            assertEquals(registration.configurationIdentity(build.id()), result.configurationIdentity());
            assertEquals(registration.dependenciesIdentity(), result.dependenciesIdentity());
            assertTrue(result.compilerIdentity().matches("[0-9a-f]{64}"));
            if (result.disposition() == InvoiceConsumerBuilds.Disposition.FAILED) {
                assertTrue(result.diagnostics().stream().anyMatch(d -> d.kind().equals("ERROR") && d.code().contains("cant.resolve")));
                assertTrue(result.diagnostics().stream().allMatch(d -> d.source() == null || !d.source().contains("/") && !d.source().contains("\\")));
            } else assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
            assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
        }
    }
    @Test void sourceConfigAndDependencyIdentitiesStaySeparateAndRoleScoped() throws Exception {
        var registration = InvoiceConsumerBuilds.load(root());
        for (String role : List.of("webhook", "reconciliation")) {
            assertEquals(registration.sourceIdentity(role + "-baseline"), registration.sourceIdentity(role + "-unrepaired-target"));
            assertNotEquals(registration.configurationIdentity(role + "-baseline"), registration.configurationIdentity(role + "-unrepaired-target"));
            assertNotEquals(registration.sourceIdentity(role + "-baseline"), registration.sourceIdentity(role + "-basil-source"));
        }
        assertNotEquals(registration.sourceIdentity("webhook-baseline"), registration.sourceIdentity("reconciliation-baseline"));
    }
    @Test void unknownBuildCannotInvokeCompilerAndCapturedDataCannotBeMutated() throws Exception {
        var registration = InvoiceConsumerBuilds.load(root());
        assertThrows(IllegalArgumentException.class, () -> InvoiceConsumerBuilds.compile(registration, "invented", temporary.resolve("not-created")));
        assertFalse(Files.exists(temporary.resolve("not-created")));
        assertThrows(UnsupportedOperationException.class, () -> registration.builds().clear());
        var bytes = registration.bytes("pom.xml"); bytes[0] = 0;
        assertArrayEquals(Files.readAllBytes(root().resolve("consumer-sources-v1/pom.xml")), registration.bytes("pom.xml"));
        assertThrows(IllegalArgumentException.class, () -> registration.bytes("../expectations.json"));
    }
    @Test void alteredSourceAndLocalRepinningCannotAuthorizeAChangedBuild() throws Exception {
        Path target = copy(), sources = target.resolve("consumer-sources-v1");
        var index = InvoiceRenewalFixtures.object(Files.readAllBytes(sources.resolve("build-registration.json")));
        String name = index.getAsJsonObject("files").keySet().stream().filter(x -> x.endsWith(".java")).findFirst().orElseThrow();
        Files.writeString(sources.resolve(name), "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> InvoiceConsumerBuilds.load(target));
        index.getAsJsonObject("files").addProperty(name, CaseInputs.hash(Files.readAllBytes(sources.resolve(name))));
        Files.writeString(sources.resolve("build-registration.json"), index.toString(), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> InvoiceConsumerBuilds.load(target));
    }
    @Test void unregisteredSourceOrDirectoryAndMissingSourceBlockRegistration() throws Exception {
        Path extra = copy(); Files.writeString(extra.resolve("consumer-sources-v1/core/unregistered.java"), "class Injected {}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> InvoiceConsumerBuilds.load(extra));
        Path directory = copy(); Files.createDirectory(directory.resolve("consumer-sources-v1/unregistered"));
        assertThrows(IllegalArgumentException.class, () -> InvoiceConsumerBuilds.load(directory));
        Path missing = copy(); Files.delete(missing.resolve("consumer-sources-v1/core/pom.xml"));
        assertThrows(IOException.class, () -> InvoiceConsumerBuilds.load(missing));
    }
    @Test void changedDependencyBytesAreRejectedEvenWhenTheCoordinatesAreUnchanged() throws Exception {
        Path target = copy(); Files.writeString(target.resolve("target/dependencies/gson-2.10.1.pom"), "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> InvoiceConsumerBuilds.load(target));
    }
    @Test void absentDependencyAndChangedFixtureOrBusinessContractPreventBuildRegistration() throws Exception {
        Path missing = copy(); Files.delete(missing.resolve("target/dependencies/stripe-java-29.0.0.jar"));
        assertThrows(IOException.class, () -> InvoiceConsumerBuilds.load(missing));
        Path fixture = copy(); Files.writeString(fixture.resolve("fixtures/rollout-v1/event-acacia.json"), "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> InvoiceConsumerBuilds.load(fixture));
        Path contract = copy(); Files.writeString(contract.resolve("business-oracle.md"), "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> InvoiceConsumerBuilds.load(contract));
    }
    @Test void priorRegisteredInputAndEvidenceIdentitiesAreUnchangedBySourceRegistration() throws Exception {
        var before = InvoiceRenewalRegistry.load(root()); InvoiceConsumerBuilds.load(root()); var after = InvoiceRenewalRegistry.load(root());
        assertEquals(before.identity(), after.identity()); assertEquals(before.inventory(), after.inventory());
        assertEquals(before.businessCorpora(), after.businessCorpora()); assertEquals(before.oldDelivery().scenarios(), after.oldDelivery().scenarios());
        assertEquals(before.boundary().controls().stream().map(c -> c.host().inputIdentity()).toList(), after.boundary().controls().stream().map(c -> c.host().inputIdentity()).toList());
    }
    @Test void generatedMavenOutputIsExcludedButCannotReplaceCapturedSource() throws Exception {
        Path target = copy(); Files.createDirectories(target.resolve("consumer-sources-v1/core/target/classes"));
        Files.writeString(target.resolve("consumer-sources-v1/core/target/classes/generated.txt"), "build output", StandardCharsets.UTF_8);
        var registration = InvoiceConsumerBuilds.load(target); var original = registration.bytes("core/pom.xml");
        Files.writeString(target.resolve("consumer-sources-v1/core/pom.xml"), "changed", StandardCharsets.UTF_8);
        assertArrayEquals(original, registration.bytes("core/pom.xml"));
        assertThrows(IllegalArgumentException.class, () -> InvoiceConsumerBuilds.load(target));
    }
}
