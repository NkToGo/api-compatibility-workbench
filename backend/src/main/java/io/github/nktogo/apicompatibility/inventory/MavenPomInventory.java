package io.github.nktogo.apicompatibility.inventory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** A declaration census, not Maven effective-model resolution or runtime API-version detection. */
public final class MavenPomInventory {
    public enum State { SCANNED, POM_REJECTED }
    public enum VersionKind { LITERAL_DECLARATION, PROPERTY_REFERENCE, MANAGED_OR_UNSPECIFIED, UNSUPPORTED_OR_AMBIGUOUS }
    public record Candidate(String location, String declaredValue) {}
    public record Dependency(String location, boolean conditionalProfile, boolean dependencyManagement,
                             VersionKind versionKind, String declaredVersionExpression,
                             List<Candidate> propertyCandidates) {
        public Dependency { propertyCandidates = List.copyOf(propertyCandidates); }
    }
    public record Report(State state, boolean parentPresent, boolean profilesPresent,
                         List<Dependency> stripeJavaDeclarations, int unresolvedCoordinateDeclarations) {
        public Report { stripeJavaDeclarations = List.copyOf(stripeJavaDeclarations); }
    }
    private MavenPomInventory() {}

    public static Report scan(byte[] bytes) {
        if (bytes.length > 65536) return rejected();
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance(); factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "48");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities are not admitted"); });
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            var root = builder.parse(new InputSource(new ByteArrayInputStream(bytes))).getDocumentElement();
            String ns = root.getNamespaceURI();
            if (!"project".equals(root.getLocalName()) || (ns != null && !ns.equals("http://maven.apache.org/POM/4.0.0"))
                    || !"4.0.0".equals(text(root, "modelVersion"))) return rejected();
            var declarations = new ArrayList<Dependency>(); int unresolved = collect(root, root, "project", false, declarations);
            var profiles = children(root, "profiles");
            if (profiles.size() > 1) return rejected();
            if (!profiles.isEmpty()) {
                var values = children(profiles.getFirst(), "profile"); if (values.size() > 100) return rejected();
                for (int i = 0; i < values.size(); i++) {
                    var profile = values.get(i);
                    unresolved += collect(root, profile, "project/profiles/profile[" + (i + 1) + "]", true, declarations);
                }
            }
            return new Report(State.SCANNED, !children(root, "parent").isEmpty(), !profiles.isEmpty(), declarations, unresolved);
        } catch (Exception e) { return rejected(); }
    }
    private static int collect(Element root, Element context, String location, boolean profile, List<Dependency> result) {
        int unresolved = dependencies(root, context, location, profile, false, result);
        var management = children(context, "dependencyManagement");
        if (management.size() > 1) throw new IllegalArgumentException();
        if (!management.isEmpty()) unresolved += dependencies(root, management.getFirst(), location + "/dependencyManagement", profile, true, result);
        return unresolved;
    }
    private static int dependencies(Element root, Element context, String location, boolean profile, boolean management, List<Dependency> result) {
        int unresolved = 0; var containers = children(context, "dependencies");
        if (containers.size() > 1) throw new IllegalArgumentException();
        if (containers.isEmpty()) return 0;
        var dependencies = children(containers.getFirst(), "dependency"); if (dependencies.size() > 300) throw new IllegalArgumentException();
        for (int i = 0; i < dependencies.size(); i++) {
            var dependency = dependencies.get(i); String group = text(dependency, "groupId"), artifact = text(dependency, "artifactId");
            if (group == null || artifact == null || group.contains("${") || artifact.contains("${")) { unresolved++; continue; }
            if (!group.equals("com.stripe") || !artifact.equals("stripe-java")) continue;
            String version = text(dependency, "version"); VersionKind kind;
            var candidates = new ArrayList<Candidate>();
            if (children(dependency, "version").isEmpty()) kind = VersionKind.MANAGED_OR_UNSPECIFIED;
            else if (literal(version)) kind = VersionKind.LITERAL_DECLARATION;
            else if (version != null && version.matches("\\$\\{[A-Za-z0-9_.-]{1,80}}")) {
                kind = VersionKind.PROPERTY_REFERENCE; String property = version.substring(2, version.length() - 1);
                candidates(root, property, "project", candidates);
                var profiles = children(root, "profiles");
                if (profiles.size() == 1) {
                    var values = children(profiles.getFirst(), "profile");
                    for (int p = 0; p < values.size(); p++) candidates(values.get(p), property, "project/profiles/profile[" + (p + 1) + "]", candidates);
                }
            } else { kind = VersionKind.UNSUPPORTED_OR_AMBIGUOUS; version = null; }
            result.add(new Dependency(location + "/dependencies/dependency[" + (i + 1) + "]", profile, management, kind, version, candidates));
            if (result.size() > 300) throw new IllegalArgumentException();
        }
        return unresolved;
    }
    private static void candidates(Element context, String name, String location, List<Candidate> result) {
        var properties = children(context, "properties"); if (properties.size() != 1) return;
        String value = text(properties.getFirst(), name);
        if (literal(value)) result.add(new Candidate(location + "/properties/" + name, value));
    }
    private static boolean literal(String value) { return value != null && value.matches("[0-9][A-Za-z0-9._+\\-]{0,63}"); }
    private static String text(Element parent, String name) {
        var values = children(parent, name); if (values.size() != 1) return null;
        var value = values.getFirst();
        for (Node child = value.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element) return null;
        return value.getTextContent().strip();
    }
    private static List<Element> children(Element parent, String name) {
        var values = new ArrayList<Element>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())
                    && java.util.Objects.equals(parent.getNamespaceURI(), element.getNamespaceURI())) values.add(element);
        }
        return values;
    }
    private static Report rejected() { return new Report(State.POM_REJECTED, false, false, List.of(), 0); }
}
