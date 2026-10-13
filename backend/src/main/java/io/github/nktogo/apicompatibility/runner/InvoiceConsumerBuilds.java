package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import javax.tools.*;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalFixtures.*;

/** Compiles only the pinned authored source catalog; never loads consumer classes or executes Maven. */
final class InvoiceConsumerBuilds {
    static final String REGISTRATION_SHA256 = "1f74dcacd771d6a9202186fa501e29dc97426c60452887ec42835ccf49463610";
    private static final String PACKAGE = "io/github/nktogo/invoiceconsumer/";
    private static final String CORE = "core/src/main/java/" + PACKAGE + "RenewalPolicy.java";
    private static final Set<String> ARTIFACTS = Set.of("stripe-java-28.4.0.jar", "stripe-java-28.4.0.pom",
            "stripe-java-29.0.0.jar", "stripe-java-29.0.0.pom", "gson-2.10.1.jar", "gson-2.10.1.pom");
    enum Disposition { SUCCEEDED, FAILED }
    record Build(String id, String consumerId, String sourceTrain, String sdkVersion, String apiVersion,
                 String entryClass, Disposition expectedCompilation) {}
    record DiagnosticRecord(String code, String source, long line, long column, String kind) {}
    record Result(String buildId, String sourceIdentity, String configurationIdentity, String dependenciesIdentity,
                  String compilerIdentity, Disposition disposition, List<DiagnosticRecord> diagnostics) {
        Result { diagnostics = List.copyOf(diagnostics); }
    }
    static final class Registration {
        private final Map<String, byte[]> files, artifacts;
        private final List<Build> builds;
        private final String dependenciesIdentity;
        private Registration(Map<String, byte[]> files, Map<String, byte[]> artifacts, List<Build> builds) {
            this.files = immutable(files); this.artifacts = immutable(artifacts); this.builds = List.copyOf(builds);
            var inventory = new TreeMap<String, String>(); artifacts.forEach((name, bytes) -> inventory.put(name, CaseInputs.hash(bytes)));
            dependenciesIdentity = CaseInputs.identity(inventory);
        }
        String identity() { return REGISTRATION_SHA256; }
        String dependenciesIdentity() { return dependenciesIdentity; }
        List<Build> builds() { return builds; }
        byte[] bytes(String name) { CaseInputs.require(files.containsKey(name), "Unregistered invoice consumer source"); return files.get(name).clone(); }
        private Build build(String id) { return builds.stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unregistered invoice consumer build")); }
        String sourceIdentity(String id) {
            var build = build(id); var inventory = new TreeMap<String, String>();
            for (String name : selected(build)) inventory.put(name, CaseInputs.hash(files.get(name)));
            return CaseInputs.identity(inventory);
        }
        String configurationIdentity(String id) {
            var build = build(id);
            return CaseInputs.identity(Map.of("consumerId", build.consumerId(), "sourceTrain", build.sourceTrain(),
                    "sdkVersion", build.sdkVersion(), "apiVersion", build.apiVersion(), "javaRelease", 21,
                    "compilerOptions", List.of("--release", "21", "-proc:none", "-encoding", "UTF-8", "-Xlint:all", "-Werror")));
        }
    }
    private InvoiceConsumerBuilds() {}
    static Registration load(Path caseRoot) throws IOException {
        var fixtures = InvoiceRenewalRegistry.load(caseRoot); fixtures.requireFullCaseCoverage();
        Path sourceRoot = caseRoot.resolve("consumer-sources-v1");
        byte[] index = read(sourceRoot.resolve("build-registration.json"), 262144);
        CaseInputs.require(REGISTRATION_SHA256.equals(CaseInputs.hash(index)), "Invoice consumer registration changed");
        var manifest = object(index);
        fields(manifest, "formatVersion", "caseId", "policy", "origin", "stage", "contractIdentity", "fixtureRegistryIdentity", "javaRelease", "files", "artifacts", "builds");
        header(manifest); equal(manifest, "origin", "authored-consumer-sources"); equal(manifest, "stage", "SOURCE_AND_BUILD_ONLY");
        equal(manifest, "fixtureRegistryIdentity", fixtures.identity());
        CaseInputs.require(CaseInputs.hash(read(caseRoot.resolve("contract-integrity.json"), 262144)).equals(text(manifest, "contractIdentity"))
                && manifest.get("javaRelease").getAsInt() == 21, "Invoice consumer contract/runtime mismatch");
        var expectedFiles = fileNames(); var declared = manifest.getAsJsonObject("files");
        CaseInputs.require(declared.keySet().equals(expectedFiles), "Unexpected invoice consumer source inventory");
        var files = new TreeMap<String, byte[]>(); files.put("build-registration.json", index);
        for (String name : expectedFiles) {
            byte[] bytes = read(sourceRoot.resolve(name), 262144);
            CaseInputs.require(CaseInputs.hash(bytes).equals(text(declared, name)), "Invoice consumer source changed"); files.put(name, bytes);
        }
        verifyInventory(sourceRoot, expectedFiles);
        var artifacts = new TreeMap<String, byte[]>(); var names = new HashSet<String>();
        for (var value : array(manifest, "artifacts")) {
            var artifact = asObject(value); fields(artifact, "coordinate", "file", "url", "sha256"); String name = text(artifact, "file");
            CaseInputs.require(ARTIFACTS.contains(name) && names.add(name), "Unexpected invoice dependency");
            byte[] bytes = read(caseRoot.resolve("target/dependencies").resolve(name), 41943040);
            CaseInputs.require(CaseInputs.hash(bytes).equals(text(artifact, "sha256")), "Invoice dependency changed"); artifacts.put(name, bytes);
        }
        CaseInputs.require(names.equals(ARTIFACTS), "Incomplete invoice dependency closure");
        var builds = new ArrayList<Build>();
        for (var value : array(manifest, "builds")) {
            var row = asObject(value); fields(row, "id", "consumerId", "sourceTrain", "sdkVersion", "apiVersion", "entryClass", "expectedCompilation");
            builds.add(new Build(text(row, "id"), text(row, "consumerId"), text(row, "sourceTrain"), text(row, "sdkVersion"),
                    text(row, "apiVersion"), text(row, "entryClass"), Disposition.valueOf(text(row, "expectedCompilation"))));
        }
        return new Registration(files, artifacts, builds);
    }
    static Result compile(Registration registration, String buildId, Path outputRoot) throws IOException {
        var build = registration.build(buildId); var compiler = ToolProvider.getSystemJavaCompiler();
        CaseInputs.require(compiler != null && Runtime.version().feature() == 21, "Invoice build requires JDK 21");
        Files.createDirectories(outputRoot); plainDirectories(outputRoot);
        Path run = Files.createDirectory(outputRoot.resolve(UUID.randomUUID().toString()));
        Path sources = Files.createDirectory(run.resolve("sources")), libraries = Files.createDirectory(run.resolve("libraries")), classes = Files.createDirectory(run.resolve("classes"));
        var paths = new ArrayList<Path>();
        for (String name : selected(build)) if (name.endsWith(".java")) {
            Path target = sources.resolve(Path.of(name).getFileName()); Files.write(target, registration.files.get(name), StandardOpenOption.CREATE_NEW); paths.add(target);
        }
        var classpath = new ArrayList<String>();
        for (String name : List.of("stripe-java-" + build.sdkVersion() + ".jar", "gson-2.10.1.jar")) {
            Path target = libraries.resolve(name); Files.write(target, registration.artifacts.get(name), StandardOpenOption.CREATE_NEW); classpath.add(target.toAbsolutePath().toString());
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>(); boolean succeeded;
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            var options = List.of("--release", "21", "-proc:none", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
                    "-classpath", String.join(java.io.File.pathSeparator, classpath), "-d", classes.toAbsolutePath().toString());
            succeeded = compiler.getTask(java.io.Writer.nullWriter(), manager, diagnostics, options, null,
                    manager.getJavaFileObjectsFromPaths(paths)).call();
        }
        CaseInputs.require(diagnostics.getDiagnostics().size() <= 64, "Excessive invoice compiler diagnostics");
        var records = diagnostics.getDiagnostics().stream().map(d -> new DiagnosticRecord(d.getCode(),
                d.getSource() == null ? null : Path.of(d.getSource().toUri()).getFileName().toString(), d.getLineNumber(), d.getColumnNumber(), d.getKind().name())).toList();
        String compilerIdentity = CaseInputs.identity(Map.of("runtime", System.getProperty("java.runtime.version"),
                "vendor", System.getProperty("java.vendor"), "compiler", compiler.getClass().getName(), "os", System.getProperty("os.name"), "arch", System.getProperty("os.arch")));
        return new Result(build.id(), registration.sourceIdentity(build.id()), registration.configurationIdentity(build.id()),
                registration.dependenciesIdentity(), compilerIdentity, succeeded ? Disposition.SUCCEEDED : Disposition.FAILED, records);
    }
    private static List<String> selected(Build build) {
        String role = build.consumerId().equals("invoice-webhook") ? "webhook" : "reconciliation";
        return List.of("pom.xml", "core/pom.xml", role + "/pom.xml", CORE,
                role + "/src/" + build.sourceTrain() + "/java/" + PACKAGE + (role.equals("webhook") ? "InvoiceWebhookConsumer" : "InvoiceReconciliationConsumer") + ".java");
    }
    private static Set<String> fileNames() {
        var result = new HashSet<>(Set.of("pom.xml", "core/pom.xml", "webhook/pom.xml", "reconciliation/pom.xml", CORE));
        for (String role : List.of("webhook", "reconciliation")) for (String train : List.of("acacia", "basil"))
            result.add(role + "/src/" + train + "/java/" + PACKAGE + (role.equals("webhook") ? "InvoiceWebhookConsumer" : "InvoiceReconciliationConsumer") + ".java");
        return Set.copyOf(result);
    }
    private static void verifyInventory(Path root, Set<String> expected) throws IOException {
        var allowed = new HashSet<>(expected); allowed.add("build-registration.json"); var actual = new HashSet<String>();
        var directories = new HashSet<String>(); directories.add("");
        for (String name : allowed) {
            Path parent = Path.of(name).getParent();
            while (parent != null) { directories.add(parent.toString().replace('\\', '/')); parent = parent.getParent(); }
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                String name = root.relativize(directory).toString().replace('\\', '/');
                if (Set.of("target", "core/target", "webhook/target", "reconciliation/target").contains(name)) return FileVisitResult.SKIP_SUBTREE;
                CaseInputs.require(directories.contains(name) && !attributes.isSymbolicLink(), "Unregistered invoice source directory");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String name = root.relativize(file).toString().replace('\\', '/');
                CaseInputs.require(attributes.isRegularFile() && !attributes.isSymbolicLink() && allowed.contains(name) && actual.add(name), "Unregistered invoice source entry");
                return FileVisitResult.CONTINUE;
            }
        });
        CaseInputs.require(actual.equals(allowed), "Incomplete invoice source inventory");
    }
    private static byte[] read(Path file, long limit) throws IOException {
        plainDirectories(file.toAbsolutePath().normalize().getParent());
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        CaseInputs.require(attributes.isRegularFile() && !attributes.isSymbolicLink() && attributes.size() <= limit, "Invalid invoice build input");
        byte[] bytes = Files.readAllBytes(file); CaseInputs.require(bytes.length <= limit, "Oversized invoice build input"); return bytes;
    }
    private static void plainDirectories(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(), current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part); var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            CaseInputs.require(attributes.isDirectory() && !attributes.isSymbolicLink(), "Linked or invalid invoice build directory");
        }
    }
    private static Map<String, byte[]> immutable(Map<String, byte[]> input) {
        var result = new TreeMap<String, byte[]>(); input.forEach((name, bytes) -> result.put(name, bytes.clone())); return Collections.unmodifiableMap(result);
    }
}
