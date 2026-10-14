package io.github.nktogo.apicompatibility.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceVersionedConsumerBuilds.*;

class InvoiceVersionedConsumerBuildsTest {
    @TempDir Path temporary;
    private Path root() { return Path.of(System.getProperty("workbench.repository"), "cases", InvoiceRenewalFixtures.CASE_ID); }
    private Path copy() throws IOException {
        Path target = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
        try (var entries = Files.walk(root())) {
            for (Path entry : entries.toList()) {
                String name = root().relativize(entry).toString().replace('\\', '/');
                if (name.contains("/target") || name.startsWith("target/") && !name.equals("target/dependencies") && !name.startsWith("target/dependencies/")) continue;
                Path output = target.resolve(name);
                if (Files.isDirectory(entry)) Files.createDirectories(output);
                else { Files.createDirectories(output.getParent()); Files.copy(entry, output); }
            }
        }
        return target;
    }
    private void archive(String id, Registration registration, InvoiceConsumerBuilds.Result result) throws IOException {
        var record = new com.google.gson.JsonObject(); record.addProperty("formatVersion", 1); record.addProperty("stage", "SOURCE_AND_BUILD_ONLY");
        record.addProperty("registrationIdentity", registration.identity()); record.addProperty("baseRegistrationIdentity", registration.baseIdentity());
        record.add("compilation", CaseInputs.JSON.toJsonTree(result));
        Path directory = Path.of(System.getProperty("workbench.repository"), "backend/target/versioned-invoice-build-tests"); Files.createDirectories(directory);
        CaseInputs.write(directory.resolve(id + ".json"), record);
    }
    @Test void explicitRegistrationPreservesMainAndCompanionVersionsAndImportsOnlyThePinnedPolicy() throws Exception {
        var registration = load(root());
        assertEquals(REGISTRATION_SHA256, registration.identity()); assertEquals(InvoiceConsumerBuilds.REGISTRATION_SHA256, registration.baseIdentity());
        assertEquals(List.of(Component.MAIN, Component.ACACIA_ADAPTER), registration.components());
        assertEquals("29.0.0", registration.component("main").sdk); assertEquals("28.4.0", registration.component("acacia-adapter").sdk);
        assertEquals(Set.of("RenewalPolicy.java", "VersionedInvoiceWebhookConsumer.java"), registration.sources("main").keySet());
        assertEquals(Set.of("AcaciaInvoiceAdapter.java"), registration.sources("acacia-adapter").keySet());
        assertArrayEquals(InvoiceConsumerBuilds.load(root()).bytes(CORE), registration.sources("main").get("RenewalPolicy.java"));
    }
    @Test void actualCompilerBuildsBothIsolatedComponentsAndRetainsOnlyCompilerFacts() throws Exception {
        var registration = load(root());
        for (var component : registration.components()) {
            var result = compile(registration, component.id, temporary.resolve("compiled")); archive(component.id, registration, result);
            assertEquals(InvoiceConsumerBuilds.Disposition.SUCCEEDED, result.disposition(), result.diagnostics().toString()); assertTrue(result.diagnostics().isEmpty());
            assertEquals(BUILD_ID + "/" + component.id, result.buildId()); assertEquals(registration.sourceIdentity(component.id), result.sourceIdentity());
            assertEquals(registration.configurationIdentity(component.id), result.configurationIdentity()); assertEquals(registration.dependenciesIdentity(component.id), result.dependenciesIdentity());
            assertTrue(result.compilerIdentity().matches("[0-9a-f]{64}")); assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().clear());
        }
    }
    @Test void companionCannotBeCompiledAgainstTheMainSdkBySilentlySubstitutingItsClasspath() throws Exception {
        var registration = load(root()); var jars = new LinkedHashMap<String, byte[]>();
        for (String name : List.of("stripe-java-29.0.0.jar", "gson-2.10.1.jar")) jars.put(name, registration.artifactBytes(name));
        var result = InvoiceConsumerBuilds.compileCaptured(registration.sources("acacia-adapter"), jars, temporary.resolve("wrong-runtime"));
        assertEquals(InvoiceConsumerBuilds.Disposition.FAILED, result.disposition());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.kind().equals("ERROR") && d.code().contains("cant.resolve")));
        var control = new com.google.gson.JsonObject(); control.addProperty("stage", "COMPILATION_CONTROL");
        control.addProperty("control", "companion-with-main-sdk"); control.addProperty("actualSdkVersion", "29.0.0");
        control.add("compilation", CaseInputs.JSON.toJsonTree(result));
        Path directory = Path.of(System.getProperty("workbench.repository"), "backend/target/versioned-invoice-build-tests"); Files.createDirectories(directory);
        CaseInputs.write(directory.resolve("wrong-runtime-control.json"), control);
    }
    @Test void identitiesAreComponentScopedAndOldBuildsAndFixtureRegistrationStayUnchanged() throws Exception {
        var before = InvoiceConsumerBuilds.load(root()); var fixtures = InvoiceRenewalRegistry.load(root()); var registration = load(root());
        assertNotEquals(registration.sourceIdentity("main"), registration.sourceIdentity("acacia-adapter"));
        assertNotEquals(registration.configurationIdentity("main"), registration.configurationIdentity("acacia-adapter"));
        assertNotEquals(registration.dependenciesIdentity("main"), registration.dependenciesIdentity("acacia-adapter"));
        assertEquals(before.dependenciesIdentity(), registration.dependenciesIdentity());
        var after = InvoiceConsumerBuilds.load(root()); assertEquals(before.builds(), after.builds());
        for (var build : before.builds()) { assertEquals(before.sourceIdentity(build.id()), after.sourceIdentity(build.id())); assertEquals(before.configurationIdentity(build.id()), after.configurationIdentity(build.id())); }
        assertEquals(fixtures.inventory(), InvoiceRenewalRegistry.load(root()).inventory());
        var delivery = InvoiceSandboxTest.delivery("target-agreement", "webhook");
        var admission = InvoiceSandboxTest.admit(InvoiceRenewalFixtures.load(root()), delivery);
        assertThrows(IllegalArgumentException.class, () -> InvoiceSandbox.startup(after, BUILD_ID, admission));
    }
    @Test void unknownComponentsAndFileNamesCannotReachCompilationOrCaptureMutableState() throws Exception {
        var registration = load(root());
        assertThrows(IllegalArgumentException.class, () -> compile(registration, "invented", temporary.resolve("absent"))); assertFalse(Files.exists(temporary.resolve("absent")));
        assertThrows(IllegalArgumentException.class, () -> registration.bytes("../expectations.json"));
        assertThrows(IllegalArgumentException.class, () -> registration.artifactBytes("../unexpected.jar"));
        assertThrows(UnsupportedOperationException.class, () -> registration.sources("main").put("Injected.java", new byte[0]));
        byte[] policy = registration.sources("main").get("RenewalPolicy.java"); policy[0] = 0;
        assertNotEquals(0, registration.sources("main").get("RenewalPolicy.java")[0]);
        byte[] jar = registration.artifactBytes("gson-2.10.1.jar"); jar[0] = 0; assertNotEquals(0, registration.artifactBytes("gson-2.10.1.jar")[0]);
    }
    @Test void changedSourcesAndLocalRepinningCannotAuthorizeNewConsumerCode() throws Exception {
        Path target = copy(), directory = target.resolve("versioned-webhook-v1"), source = directory.resolve(Component.MAIN.source());
        Files.writeString(source, "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND); assertThrows(IllegalArgumentException.class, () -> load(target));
        var manifest = InvoiceRenewalFixtures.object(Files.readAllBytes(directory.resolve("build-registration.json")));
        manifest.getAsJsonObject("files").addProperty(Component.MAIN.source(), CaseInputs.hash(Files.readAllBytes(source)));
        Files.writeString(directory.resolve("build-registration.json"), manifest.toString(), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> load(target));
    }
    @Test void extraMissingOrUnexpectedSourcesFailBeforeACompilerCanRun() throws Exception {
        Path target = copy(), directory = target.resolve("versioned-webhook-v1");
        Path extra = directory.resolve("Injected.java"); Files.writeString(extra, "class Injected {}", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> load(target)); Files.delete(extra);
        Path unexpected = Files.createDirectory(directory.resolve("unexpected")); assertThrows(IllegalArgumentException.class, () -> load(target)); Files.delete(unexpected);
        Files.delete(directory.resolve(Component.ACACIA_ADAPTER.source())); assertThrows(IOException.class, () -> load(target));
    }
    @Test void importedPolicyAndRuntimeBytesRemainBoundThroughTheOriginalRegistration() throws Exception {
        Path target = copy(), policy = target.resolve("consumer-sources-v1").resolve(CORE);
        Files.writeString(policy, "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND); assertThrows(IllegalArgumentException.class, () -> load(target));
        Path dependency = copy(); Files.writeString(dependency.resolve("target/dependencies/stripe-java-28.4.0.pom"), "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> load(dependency));
    }
    @Test void capturedSourceCopiesSurviveLocalChangesAndGeneratedOutputsCannotReplaceInputs() throws Exception {
        Path target = copy(), directory = target.resolve("versioned-webhook-v1");
        Files.createDirectories(directory.resolve("acacia-adapter/target/classes")); Files.writeString(directory.resolve("acacia-adapter/target/classes/generated.txt"), "not source", StandardCharsets.UTF_8);
        var registration = load(target); byte[] original = registration.bytes(Component.ACACIA_ADAPTER.source());
        Files.writeString(directory.resolve(Component.ACACIA_ADAPTER.source()), "changed", StandardCharsets.UTF_8);
        assertArrayEquals(original, registration.bytes(Component.ACACIA_ADAPTER.source())); assertThrows(IllegalArgumentException.class, () -> load(target));
    }
}
