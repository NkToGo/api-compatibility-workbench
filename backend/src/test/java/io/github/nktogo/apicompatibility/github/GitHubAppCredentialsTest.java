package io.github.nktogo.apicompatibility.github;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class GitHubAppCredentialsTest {
    @TempDir Path temporary;
    static KeyPair pair;
    @BeforeAll static void keys() throws GeneralSecurityException { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); pair = generator.generateKeyPair(); }

    static byte[] pem(KeyPair pair, boolean pkcs1) {
        byte[] der = pair.getPrivate().getEncoded(); String type = "PRIVATE KEY";
        if (pkcs1) {
            var rsa = (RSAPrivateCrtKey) pair.getPrivate(); var sequence = new ByteArrayOutputStream();
            for (var value : new java.math.BigInteger[]{java.math.BigInteger.ZERO, rsa.getModulus(), rsa.getPublicExponent(),
                    rsa.getPrivateExponent(), rsa.getPrimeP(), rsa.getPrimeQ(), rsa.getPrimeExponentP(), rsa.getPrimeExponentQ(), rsa.getCrtCoefficient()}) {
                sequence.writeBytes(der(2, value.toByteArray()));
            }
            der = der(48, sequence.toByteArray()); type = "RSA PRIVATE KEY";
        }
        return ("-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(der)
                + "\n-----END " + type + "-----\n").getBytes(StandardCharsets.US_ASCII);
    }
    private static byte[] der(int tag, byte[] value) {
        var result = new ByteArrayOutputStream(); result.write(tag);
        if (value.length < 128) result.write(value.length);
        else { result.write(130); result.write(value.length >> 8); result.write(value.length & 255); }
        result.writeBytes(value); return result.toByteArray();
    }
    static boolean validJwt(String jwt, PublicKey publicKey) throws GeneralSecurityException {
        String[] parts = jwt.split("\\."); if (parts.length != 3) return false;
        var verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void signsShortLivedRs256JwtFromPkcs8AndGitHubPkcs1(boolean pkcs1) throws Exception {
        Path path = temporary.resolve("generated-test-key.pem"); Files.write(path, pem(pair, pkcs1));
        var credentials = GitHubAppCredentials.load("Iv1.fixture", path); Instant now = Instant.parse("2026-11-14T00:00:00Z");
        String jwt = credentials.jwt(now); assertTrue(validJwt(jwt, pair.getPublic()));
        var parts = jwt.split("\\.");
        var header = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
        var claims = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("RS256", header.get("alg").getAsString());
        assertEquals(now.minusSeconds(60).getEpochSecond(), claims.get("iat").getAsLong());
        assertEquals(now.plusSeconds(540).getEpochSecond(), claims.get("exp").getAsLong());
        assertEquals("Iv1.fixture", claims.get("iss").getAsString());
        assertEquals("GitHubAppCredentials[redacted]", credentials.toString());
    }

    @Test void refusesWeakNonRsaMalformedAndOversizedCredentialsWithoutEchoingThem() throws Exception {
        var weak = KeyPairGenerator.getInstance("RSA"); weak.initialize(1024);
        var ec = KeyPairGenerator.getInstance("EC"); ec.initialize(256);
        for (byte[] bytes : new byte[][]{pem(weak.generateKeyPair(), false), pem(ec.generateKeyPair(), false),
                "PRIVATE_KEY_SENTINEL".getBytes(StandardCharsets.UTF_8), new byte[16385]}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> GitHubAppCredentials.parse("Iv1.fixture", bytes));
            assertEquals("Invalid GitHub App credentials", failure.getMessage()); assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> GitHubAppCredentials.parse("bad\r\nissuer", pem(pair, false)));
        Path empty = temporary.resolve("empty.pem"); Files.write(empty, new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> GitHubAppCredentials.load("123", empty));
    }

    @Test @EnabledOnOs(OS.LINUX)
    void refusesLinkedKeyAndLinkedParent() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real")); Path file = real.resolve("key.pem"); Files.write(file, pem(pair, false));
        Path link = temporary.resolve("linked.pem"); Files.createSymbolicLink(link, file);
        assertThrows(IllegalArgumentException.class, () -> GitHubAppCredentials.load("123", link));
        Path parent = temporary.resolve("linked-directory"); Files.createSymbolicLink(parent, real);
        assertThrows(IllegalArgumentException.class, () -> GitHubAppCredentials.load("123", parent.resolve("key.pem")));
    }
}
