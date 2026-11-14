package io.github.nktogo.apicompatibility.github;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Strict, bounded parsing; provider fields are selected explicitly by the caller. */
final class GitHubJson {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls()
            .registerTypeAdapter(java.time.Instant.class, (JsonSerializer<java.time.Instant>)
                    (value, type, context) -> new JsonPrimitive(value.toString())).create();
    private GitHubJson() {}
    static JsonObject parse(byte[] bytes, int limit) {
        require(bytes.length > 0 && bytes.length <= limit);
        try (var reader = new JsonReader(new StringReader(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()))) {
            reader.setStrictness(Strictness.STRICT);
            var result = object(element(reader, 0)); require(reader.peek() == JsonToken.END_DOCUMENT); return result;
        } catch (IOException | RuntimeException e) { throw new IllegalArgumentException("Invalid GitHub metadata"); }
    }
    static JsonObject object(JsonElement value) { require(value != null && value.isJsonObject()); return value.getAsJsonObject(); }
    static String text(JsonObject o, String key) {
        var value = o.get(key); require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString());
        String text = value.getAsString(); require(text.length() <= 8192); return text;
    }
    static long number(JsonObject o, String key) {
        var value = o.get(key); require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber());
        return value.getAsBigDecimal().longValueExact();
    }
    static void fields(JsonObject o, String... fields) { require(o.keySet().equals(Set.of(fields))); }
    static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid GitHub metadata"); }
    private static JsonElement element(JsonReader reader, int depth) throws IOException {
        require(depth <= 32);
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); var o = new JsonObject();
                while (reader.hasNext()) { String key = reader.nextName(); require(!o.has(key) && o.size() < 2000); o.add(key, element(reader, depth + 1)); }
                reader.endObject(); yield o;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); var a = new JsonArray();
                while (reader.hasNext()) { require(a.size() < 2000); a.add(element(reader, depth + 1)); }
                reader.endArray(); yield a;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Invalid GitHub metadata");
        };
    }
}
