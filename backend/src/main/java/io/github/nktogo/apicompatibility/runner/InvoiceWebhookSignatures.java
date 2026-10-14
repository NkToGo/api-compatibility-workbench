package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Host-only authentication of exact raw bytes. It neither parses an event nor verifies an adapter. */
final class InvoiceWebhookSignatures {
    static final int MAX_SOURCE_BYTES = 256 * 1024;
    static final int MAX_HEADER_CHARS = 4096;
    static final int MAX_SIGNATURES = 8;
    static final int MAX_ACTIVE_KEYS = 4;
    static final long TOLERANCE_SECONDS = 300;
    private static final String POLICY = "stripe-v1-hmac-sha256-bounded-age-v1";
    enum Outcome { VERIFIED, SOURCE_REJECTED, HEADER_REJECTED, TIMESTAMP_REJECTED, SIGNATURE_REJECTED }

    /** Non-secret host routing/configuration references; not event-supplied metadata. */
    record Scope(String installationId, String endpointId, String keySetVersion) {
        Scope { reference(installationId); reference(endpointId); reference(keySetVersion); }
    }
    static final class SigningKey {
        private final String reference;
        private final byte[] secret;
        SigningKey(String reference, byte[] secret) {
            reference(reference);
            CaseInputs.require(secret != null && secret.length > 0 && secret.length <= 1024, "Invalid webhook signing key");
            this.reference = reference; this.secret = secret.clone();
        }
        @Override public String toString() { return "Webhook signing key [redacted]"; }
    }
    /** No public constructor or JSON-to-receipt conversion. Diagnostics cannot restore this authority. */
    static final class Receipt {
        private final Scope scope;
        private final Outcome outcome;
        private final String sourceIdentity;
        private final Instant checkedAt;
        private final Long timestamp;
        private final List<String> matchingKeyReferences;
        private Receipt(Scope scope, Outcome outcome, String sourceIdentity, Instant checkedAt,
                        Long timestamp, List<String> matchingKeyReferences) {
            this.scope = scope; this.outcome = outcome; this.sourceIdentity = sourceIdentity;
            this.checkedAt = checkedAt; this.timestamp = timestamp; this.matchingKeyReferences = List.copyOf(matchingKeyReferences);
        }
        boolean verified() { return outcome == Outcome.VERIFIED; }
        Outcome outcome() { return outcome; }
        String sourceIdentity() { return sourceIdentity; }
        boolean matchesScope(Scope expected) { return scope.equals(expected); }
        JsonObject json() {
            var value = new JsonObject(); value.addProperty("formatVersion", 1); value.addProperty("policy", POLICY);
            value.addProperty("implementationIdentity", Identity.VALUE); value.addProperty("outcome", outcome.name());
            value.addProperty("sourceIdentity", sourceIdentity); value.addProperty("checkedAt", checkedAt.toString());
            value.addProperty("signatureTimestamp", timestamp); value.addProperty("toleranceSeconds", TOLERANCE_SECONDS);
            var binding = new JsonObject(); binding.addProperty("installationId", scope.installationId());
            binding.addProperty("endpointId", scope.endpointId()); binding.addProperty("keySetVersion", scope.keySetVersion());
            value.add("scope", binding); var references = new JsonArray(); matchingKeyReferences.forEach(references::add);
            value.add("matchingKeyReferences", references); value.addProperty("receiptIdentity", CaseInputs.identity(value)); return value;
        }
        @Override public String toString() { return "Webhook signature receipt [" + outcome + "]"; }
    }
    private InvoiceWebhookSignatures() {}

    static Receipt verify(Scope scope, byte[] source, String header, List<SigningKey> activeKeys, Clock clock) {
        Objects.requireNonNull(scope); Objects.requireNonNull(clock); Objects.requireNonNull(activeKeys);
        var keys = List.copyOf(activeKeys);
        CaseInputs.require(!keys.isEmpty() && keys.size() <= MAX_ACTIVE_KEYS, "Invalid webhook signing key set");
        var references = new HashSet<String>();
        for (var key : keys) CaseInputs.require(references.add(key.reference), "Duplicate webhook signing key reference");
        Instant checkedAt = clock.instant();
        if (source == null || source.length == 0 || source.length > MAX_SOURCE_BYTES)
            return new Receipt(scope, Outcome.SOURCE_REJECTED, null, checkedAt, null, List.of());
        byte[] bytes = source.clone(); String identity = CaseInputs.hash(bytes);
        Header parsed = parse(header);
        if (parsed == null) return new Receipt(scope, Outcome.HEADER_REJECTED, identity, checkedAt, null, List.of());
        if (Duration.between(parsed.deliveredAt(), checkedAt).abs().compareTo(Duration.ofSeconds(TOLERANCE_SECONDS)) > 0)
            return new Receipt(scope, Outcome.TIMESTAMP_REJECTED, identity, checkedAt, parsed.timestamp(), List.of());
        var matching = new ArrayList<String>();
        for (var key : keys) {
            byte[] expected = signature(key.secret, parsed.timestamp(), bytes); boolean matched = false;
            for (byte[] candidate : parsed.signatures()) matched |= MessageDigest.isEqual(expected, candidate);
            Arrays.fill(expected, (byte) 0);
            if (matched) matching.add(key.reference);
        }
        return new Receipt(scope, matching.isEmpty() ? Outcome.SIGNATURE_REJECTED : Outcome.VERIFIED,
                identity, checkedAt, parsed.timestamp(), matching);
    }

    private record Header(long timestamp, Instant deliveredAt, List<byte[]> signatures) {}
    private static Header parse(String header) {
        if (header == null || header.isEmpty() || header.length() > MAX_HEADER_CHARS) return null;
        // Reject controls/non-ASCII before trimming so CR/LF cannot be hidden by whitespace handling.
        for (int i = 0; i < header.length(); i++) if (header.charAt(i) < 32 || header.charAt(i) > 126) return null;
        String[] parts = header.split(",", -1); if (parts.length > 32) return null;
        Long timestamp = null; var signatures = new ArrayList<byte[]>();
        for (String part : parts) {
            String component = part.strip(); int separator = component.indexOf('=');
            if (separator < 1) return null;
            String scheme = component.substring(0, separator); String value = component.substring(separator + 1);
            if (!scheme.matches("[A-Za-z0-9_]{1,16}") || value.isEmpty() || value.length() > 256 || value.indexOf(' ') >= 0) return null;
            if (scheme.equals("t")) {
                if (timestamp != null || !value.matches("[1-9][0-9]{0,18}")) return null;
                try { timestamp = Long.valueOf(value); Instant.ofEpochSecond(timestamp); }
                catch (NumberFormatException | DateTimeException e) { return null; }
            } else if (scheme.equals("v1")) {
                if (signatures.size() == MAX_SIGNATURES || !value.matches("[0-9a-f]{64}")) return null;
                signatures.add(HexFormat.of().parseHex(value));
            }
        }
        return timestamp == null || signatures.isEmpty() ? null : new Header(timestamp, Instant.ofEpochSecond(timestamp), List.copyOf(signatures));
    }
    private static byte[] signature(byte[] secret, long timestamp, byte[] source) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((Long.toString(timestamp) + ".").getBytes(StandardCharsets.US_ASCII)); return mac.doFinal(source);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Webhook signature implementation unavailable"); }
    }
    private static void reference(String value) {
        CaseInputs.require(value != null && value.matches("[A-Za-z0-9_-]{1,128}"), "Invalid webhook reference");
    }
    private static final class Identity {
        private static final String VALUE = calculate();
        private static String calculate() {
            var inventory = new TreeMap<String, String>(); capture(InvoiceWebhookSignatures.class, inventory);
            capture(CaseInputs.class, inventory); return CaseInputs.identity(inventory);
        }
        private static void capture(Class<?> type, Map<String, String> inventory) {
            String name = "/" + type.getName().replace('.', '/') + ".class";
            try (var stream = type.getResourceAsStream(name)) {
                if (stream == null) throw new IllegalStateException("Webhook signature implementation identity unavailable");
                inventory.put(name, CaseInputs.hash(stream.readAllBytes()));
            } catch (IOException e) { throw new IllegalStateException("Webhook signature implementation identity unavailable"); }
            for (Class<?> nested : type.getDeclaredClasses()) capture(nested, inventory);
        }
    }
}
