package io.github.nktogo.apicompatibility.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MavenPomInventoryTest {
    private static byte[] pom(String body) {
        return ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>" + body + "</project>").getBytes(StandardCharsets.UTF_8);
    }
    private static final String COORDINATES = "<groupId>com.stripe</groupId><artifactId>stripe-java</artifactId>";

    @Test void preservesManagedDeclarationAndInactiveProfileCandidatesWithoutChoosingAnEffectiveVersion() {
        var report = MavenPomInventory.scan(pom("""
                <properties><stripe.sdk.version>28.4.0</stripe.sdk.version><api.key>PRIVATE_PROPERTY_SENTINEL</api.key></properties>
                <dependencyManagement><dependencies><dependency>
                <groupId>com.stripe</groupId><artifactId>stripe-java</artifactId><version>${stripe.sdk.version}</version>
                </dependency></dependencies></dependencyManagement>
                <profiles><profile><id>PRIVATE_PROFILE_SENTINEL</id><properties><stripe.sdk.version>29.0.0</stripe.sdk.version></properties>
                <dependencies><dependency><groupId>com.stripe</groupId><artifactId>stripe-java</artifactId><version>29.0.0</version></dependency></dependencies>
                </profile></profiles>
                """));
        assertEquals(MavenPomInventory.State.SCANNED, report.state()); assertTrue(report.profilesPresent());
        assertEquals(2, report.stripeJavaDeclarations().size()); var managed = report.stripeJavaDeclarations().getFirst();
        assertTrue(managed.dependencyManagement()); assertFalse(managed.conditionalProfile());
        assertEquals(MavenPomInventory.VersionKind.PROPERTY_REFERENCE, managed.versionKind());
        assertEquals("${stripe.sdk.version}", managed.declaredVersionExpression());
        assertEquals(java.util.List.of("28.4.0", "29.0.0"), managed.propertyCandidates().stream().map(MavenPomInventory.Candidate::declaredValue).toList());
        assertTrue(report.stripeJavaDeclarations().get(1).conditionalProfile());
        assertFalse(report.toString().contains("PRIVATE_"));
        assertThrows(UnsupportedOperationException.class, () -> report.stripeJavaDeclarations().clear());
    }
    @Test void missingInheritedVersionAndDynamicCoordinatesRemainUnresolved() {
        var report = MavenPomInventory.scan(pom("<parent><groupId>x</groupId><artifactId>parent</artifactId><version>1</version><relativePath>../../PRIVATE_PARENT</relativePath></parent>"
                + "<dependencies><dependency>" + COORDINATES + "</dependency><dependency><groupId>${vendor}</groupId><artifactId>stripe-java</artifactId></dependency></dependencies>"));
        assertTrue(report.parentPresent()); assertEquals(1, report.unresolvedCoordinateDeclarations());
        assertEquals(MavenPomInventory.VersionKind.MANAGED_OR_UNSPECIFIED, report.stripeJavaDeclarations().getFirst().versionKind());
        assertNull(report.stripeJavaDeclarations().getFirst().declaredVersionExpression());
        assertFalse(report.toString().contains("PRIVATE_PARENT"));
    }
    @Test void doesNotReadEnvironmentOrRecursivelyExpandProperties() {
        var report = MavenPomInventory.scan(pom("<properties><a>${env.PRIVATE_SECRET}</a></properties><dependencies><dependency>"
                + COORDINATES + "<version>${a}</version></dependency></dependencies>"));
        assertEquals(MavenPomInventory.VersionKind.PROPERTY_REFERENCE, report.stripeJavaDeclarations().getFirst().versionKind());
        assertTrue(report.stripeJavaDeclarations().getFirst().propertyCandidates().isEmpty());
        assertFalse(report.toString().contains("PRIVATE_SECRET"));
    }
    @ParameterizedTest @ValueSource(strings = {"[28,30)", "PRIVATE_KEY_SENTINEL", "${recursive:-default}", "28.4.0\nPRIVATE_PAYLOAD"})
    void redactsUnsupportedVersionExpressions(String version) {
        var declaration = MavenPomInventory.scan(pom("<dependencies><dependency>" + COORDINATES + "<version>" + version
                + "</version></dependency></dependencies>")).stripeJavaDeclarations().getFirst();
        assertEquals(MavenPomInventory.VersionKind.UNSUPPORTED_OR_AMBIGUOUS, declaration.versionKind());
        assertNull(declaration.declaredVersionExpression());
    }
    @Test void duplicateVersionIsNotSilentlyLastWinsAndPluginsAreNotDependencies() {
        var report = MavenPomInventory.scan(pom("<dependencies><dependency>" + COORDINATES
                + "<version>28.4.0</version><version>29.0.0</version></dependency></dependencies>"
                + "<build><plugins><plugin><dependencies><dependency>" + COORDINATES + "<version>999</version></dependency></dependencies></plugin></plugins></build>"));
        assertEquals(1, report.stripeJavaDeclarations().size());
        assertEquals(MavenPomInventory.VersionKind.UNSUPPORTED_OR_AMBIGUOUS, report.stripeJavaDeclarations().getFirst().versionKind());
    }
    @Test void acceptsUnnamespacedPomAndIgnoresForeignNamespaceLookalikes() {
        var report = MavenPomInventory.scan(("<project><modelVersion>4.0.0</modelVersion><dependencies><dependency>" + COORDINATES
                + "<version>28.4.0</version></dependency></dependencies><x:dependencies xmlns:x=\"urn:untrusted\"><x:dependency>PRIVATE_SENTINEL</x:dependency></x:dependencies></project>").getBytes(StandardCharsets.UTF_8));
        assertEquals(MavenPomInventory.State.SCANNED, report.state()); assertEquals(1, report.stripeJavaDeclarations().size());
        assertEquals("28.4.0", report.stripeJavaDeclarations().getFirst().declaredVersionExpression());
    }
    @ParameterizedTest @ValueSource(strings = {"<!DOCTYPE project SYSTEM 'https://127.0.0.1:1/PRIVATE'>",
            "<!DOCTYPE project [<!ENTITY data SYSTEM 'file:///PRIVATE_FILE'>]>", "<!DOCTYPE project [<!ENTITY a 'repeated'>]>"})
    void refusesExternalAndInternalDoctypes(String doctype) {
        assertEquals(MavenPomInventory.State.POM_REJECTED,
                MavenPomInventory.scan((doctype + "<project><modelVersion>4.0.0</modelVersion></project>").getBytes(StandardCharsets.UTF_8)).state());
    }
    @Test void rejectsDeepLargeDuplicateOrUnsupportedModelsWithoutRawParserDiagnostics() {
        var previous = System.err; var bytes = new java.io.ByteArrayOutputStream();
        try (var captured = new java.io.PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            for (byte[] input : new byte[][]{pom("<x>".repeat(60) + "</x>".repeat(60)), new byte[65537],
                    pom("<dependencies/><dependencies/>"), "<project><PRIVATE_BAD_XML".getBytes(StandardCharsets.UTF_8),
                    "<project><modelVersion>4.1.0</modelVersion></project>".getBytes(StandardCharsets.UTF_8)})
                assertEquals(MavenPomInventory.State.POM_REJECTED, MavenPomInventory.scan(input).state());
        } finally { System.setErr(previous); }
        assertEquals(0, bytes.size());
    }
}
