package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import io.github.nktogo.apicompatibility.compatibility.ContentDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static io.github.nktogo.apicompatibility.runner.CoordinationTeamRunner.*;

/** Supplied campaign-packet claim bindings; the host wrapper separately revalidates the campaign. */
public final class InvoiceCampaignSemanticReview {
    private static final int REVIEW_LIMIT = 2097152;
    private final Path evidence, packetFile;
    private final ContentDigest packetIdentity;
    public InvoiceCampaignSemanticReview(Path evidence, Path packetFile, ContentDigest packetIdentity) {
        this.evidence = evidence.toAbsolutePath().normalize(); this.packetFile = packetFile.toAbsolutePath().normalize(); this.packetIdentity = packetIdentity;
    }
    public void create(Path destination) throws IOException {
        var packet = packet(); Path target = destination.toAbsolutePath().normalize(); safeDirectory(target.getParent());
        CaseInputs.require(!target.startsWith(packetFile.getParent()), "Review output overlaps its input packet");
        var inventory = packet.getAsJsonObject("inventory");
        for (String artifact : inventory.getAsJsonObject("sourceArtifacts").keySet())
            CaseInputs.require(!target.startsWith(source(artifact).getParent()), "Review output overlaps bound source evidence");
        for (var row : inventory.getAsJsonArray("trials"))
            CaseInputs.require(!target.startsWith(source(row.getAsJsonObject().getAsJsonObject("source").get("stateDirectory").getAsString())), "Review output overlaps an original state store");
        byte[] bytes = (encoded(InvoiceSemanticReview.campaignDraft(packet, packetIdentity)) + "\n").getBytes(StandardCharsets.UTF_8);
        CaseInputs.require(bytes.length <= REVIEW_LIMIT, "Campaign claim review exceeds 2 MiB");
        packet(); // Recheck the independently pinned input immediately before creating the draft.
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    public JsonObject verify(Path reviewFile, ContentDigest reviewIdentity) throws IOException {
        Path file = reviewFile.toAbsolutePath().normalize(); byte[] bytes = read(file, REVIEW_LIMIT);
        CaseInputs.require(CaseInputs.hash(bytes).equals(reviewIdentity.sha256()), "Changed independently selected campaign review bytes");
        var result = InvoiceSemanticReview.validateCampaign(InvoiceReviewInputs.object(bytes, REVIEW_LIMIT), packet(), packetIdentity, ref -> read(source(ref), 8388608));
        CaseInputs.require(java.util.Arrays.equals(bytes, read(file, REVIEW_LIMIT)), "Campaign review changed during inspection");
        packet(); result.addProperty("reviewSha256", reviewIdentity.sha256()); return result;
    }
    private JsonObject packet() throws IOException {
        byte[] bytes = read(packetFile, REVIEW_LIMIT);
        CaseInputs.require(CaseInputs.hash(bytes).equals(packetIdentity.sha256()), "Changed independently selected campaign packet bytes");
        return InvoiceReviewInputs.object(bytes, REVIEW_LIMIT);
    }
    private Path source(String reference) {
        CaseInputs.require(reference.matches("[A-Za-z0-9_./-]+") && !reference.startsWith("/")
                && java.util.Arrays.stream(reference.split("/", -1)).noneMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")), "Invalid campaign review source reference");
        return evidence.resolve(reference).normalize();
    }
    private static void safeDirectory(Path path) { for (Path cursor = path; cursor != null; cursor = cursor.getParent()) ConsumerTasks.directory(cursor); }
    private static byte[] read(Path path, int limit) throws IOException {
        safeDirectory(path.getParent()); CaseInputs.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Missing or linked campaign review artifact");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1); CaseInputs.require(bytes.length <= limit, "Campaign review artifact exceeds its byte limit"); return bytes;
        }
    }
    public static void main(String[] args) throws IOException {
        CaseInputs.require(args.length == 5 || args.length == 6, "Usage: draft ROOT PACKET PACKET_SHA DESTINATION | verify ROOT PACKET PACKET_SHA REVIEW REVIEW_SHA");
        var engine = new InvoiceCampaignSemanticReview(Path.of(args[1]), Path.of(args[2]), new ContentDigest(args[3]));
        if (args[0].equals("draft") && args.length == 5) { engine.create(Path.of(args[4])); System.out.println("Created pending campaign claim review; no semantic judgments supplied."); }
        else { CaseInputs.require(args[0].equals("verify") && args.length == 6, "Invalid campaign claim-review action"); System.out.println(encoded(engine.verify(Path.of(args[4]), new ContentDigest(args[5])))); }
    }
}
