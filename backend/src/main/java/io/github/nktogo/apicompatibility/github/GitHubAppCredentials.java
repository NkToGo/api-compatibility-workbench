package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/** Host-only signer. Neither private key bytes nor JWTs are part of result records. */
public final class GitHubAppCredentials {
    private final String issuer;
    private final PrivateKey key;

    private GitHubAppCredentials(String issuer, PrivateKey key) {
        if (issuer == null || !issuer.matches("[A-Za-z0-9_.-]{1,96}") || !(key instanceof RSAPrivateKey rsa)
                || rsa.getModulus().bitLength() < 2048 || rsa.getModulus().bitLength() > 8192)
            throw new IllegalArgumentException("Invalid GitHub App credentials");
        this.issuer = issuer; this.key = key;
    }
    public static GitHubAppCredentials load(String issuer, Path keyFile) throws IOException {
        Path file = keyFile.toAbsolutePath().normalize();
        for (Path part = file; part != null; part = part.getParent()) {
            var attributes = Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IllegalArgumentException("Linked credential path");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid credential file");
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var stream = Channels.newInputStream(channel)) {
            byte[] pem = stream.readNBytes(16385);
            try { return parse(issuer, pem); } finally { Arrays.fill(pem, (byte) 0); }
        }
    }
    static GitHubAppCredentials parse(String issuer, byte[] pem) {
        byte[] der = null;
        try {
            if (pem.length == 0 || pem.length > 16384) throw new IllegalArgumentException();
            String text = new String(pem, StandardCharsets.US_ASCII).strip();
            boolean pkcs1 = text.startsWith("-----BEGIN RSA PRIVATE KEY-----");
            String kind = pkcs1 ? "RSA PRIVATE KEY" : "PRIVATE KEY";
            String begin = "-----BEGIN " + kind + "-----", end = "-----END " + kind + "-----";
            if (!text.startsWith(begin) || !text.endsWith(end)) throw new IllegalArgumentException();
            String encoded = text.substring(begin.length(), text.length() - end.length()).replaceAll("[\\r\\n\\t ]", "");
            der = Base64.getDecoder().decode(encoded);
            if (pkcs1) {
                // PKCS#1 RSA is wrapped in PKCS#8's algorithm identifier and private-key octet string.
                var contents = new ByteArrayOutputStream();
                contents.writeBytes(new byte[]{2, 1, 0, 48, 13, 6, 9, 42, (byte) 134, 72, (byte) 134, (byte) 247, 13, 1, 1, 1, 5, 0});
                contents.writeBytes(tagged(4, der)); byte[] wrapped = tagged(48, contents.toByteArray());
                Arrays.fill(der, (byte) 0); der = wrapped;
            }
            return new GitHubAppCredentials(issuer, KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der)));
        } catch (GeneralSecurityException | RuntimeException e) { throw new IllegalArgumentException("Invalid GitHub App credentials"); }
        finally { if (der != null) Arrays.fill(der, (byte) 0); }
    }
    private static byte[] tagged(int tag, byte[] bytes) {
        var result = new ByteArrayOutputStream(); result.write(tag);
        if (bytes.length < 128) result.write(bytes.length);
        else if (bytes.length < 256) { result.write(129); result.write(bytes.length); }
        else { result.write(130); result.write(bytes.length >> 8); result.write(bytes.length & 255); }
        result.writeBytes(bytes); return result.toByteArray();
    }
    boolean matches(JsonObject app) {
        if (issuer.matches("[1-9][0-9]*")) return issuer.equals(Long.toString(GitHubJson.number(app, "id")));
        return issuer.equals(GitHubJson.text(app, "client_id"));
    }
    String jwt(Instant now) {
        var claims = new JsonObject(); claims.addProperty("iat", now.minusSeconds(60).getEpochSecond());
        claims.addProperty("exp", now.plusSeconds(540).getEpochSecond()); claims.addProperty("iss", issuer);
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String unsigned = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.US_ASCII))
                + "." + encoder.encodeToString(GitHubJson.JSON.toJson(claims).getBytes(StandardCharsets.UTF_8));
        try {
            var signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key); signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + encoder.encodeToString(signer.sign());
        } catch (GeneralSecurityException e) { throw new IllegalStateException("GitHub App signing failed"); }
    }
    @Override public String toString() { return "GitHubAppCredentials[redacted]"; }
}
