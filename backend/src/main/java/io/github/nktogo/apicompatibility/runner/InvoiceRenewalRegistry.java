package io.github.nktogo.apicompatibility.runner;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import static io.github.nktogo.apicompatibility.runner.InvoiceRenewalFixtures.*;

/** Complete authored fixture registration. It neither executes nor authorizes a consumer. */
final class InvoiceRenewalRegistry {
    static final String BUNDLE_SHA256 = "ca7579e6856f50ce0264961f8511aed7c9f83af256bb934671217a99a867f9be";
    private static final List<String> PLACEMENTS = List.of("alone", "healthy-first", "healthy-last");

    record BusinessCorpus(String id, String identity, String installationId, List<Scenario> scenarios) {
        BusinessCorpus { scenarios = List.copyOf(scenarios); }
    }

    static final class Registration {
        private final Map<String, BusinessCorpus> business;
        private final InvoiceRenewalOldDeliveryFixtures.Bundle oldDelivery;
        private final InvoiceRenewalBoundaryFixtures.Bundle boundary;
        private final Map<String, Function<String, byte[]>> sources;
        private final JsonObject inventory;

        private Registration(Map<String, BusinessCorpus> business,
                             InvoiceRenewalOldDeliveryFixtures.Bundle oldDelivery,
                             InvoiceRenewalBoundaryFixtures.Bundle boundary,
                             Map<String, Function<String, byte[]>> sources, JsonObject inventory) {
            this.business = Collections.unmodifiableMap(new LinkedHashMap<>(business));
            this.oldDelivery = oldDelivery; this.boundary = boundary;
            this.sources = Map.copyOf(sources); this.inventory = inventory.deepCopy();
        }
        String identity() { return BUNDLE_SHA256; }
        Map<String, BusinessCorpus> businessCorpora() { return business; }
        InvoiceRenewalOldDeliveryFixtures.Bundle oldDelivery() { return oldDelivery; }
        InvoiceRenewalBoundaryFixtures.Bundle boundary() { return boundary; }
        JsonObject inventory() { return inventory.deepCopy(); }
        byte[] bytes(String corpus, String file) {
            CaseInputs.require(sources.containsKey(corpus), "Unregistered invoice corpus");
            return sources.get(corpus).apply(file);
        }
        /** Only byte-validated, complete registrations can be constructed by load(). */
        void requireFullCaseCoverage() { validateCoverage(inventory, describe(business, oldDelivery, boundary)); }
    }
    private InvoiceRenewalRegistry() {}

    static Registration load(Path root) throws IOException {
        var files = pinnedFiles(root, "registry-v1", BUNDLE_SHA256,
                "COMPLETE_FIXTURE_REGISTRATION_ONLY", Set.of("registry.json"));
        var inventory = object(files.get("registry.json"));
        var business = new LinkedHashMap<String, BusinessCorpus>();
        var sources = new LinkedHashMap<String, Function<String, byte[]>>();
        var rollout = InvoiceRenewalFixtures.load(root);
        business.put("rollout-v1", new BusinessCorpus("rollout-v1", rollout.identity(), rollout.installationId(), rollout.scenarios()));
        sources.put("rollout-v1", rollout::bytes);
        add(business, sources, "association-v1", InvoiceRenewalAssociationFixtures.load(root));
        add(business, sources, "refusal-v1", InvoiceRenewalRefusalFixtures.load(root));
        add(business, sources, "delivery-v1", InvoiceRenewalDeliveryFixtures.load(root));
        add(business, sources, "state-v1", InvoiceRenewalStateFixtures.load(root));
        add(business, sources, "unpaid-v1", InvoiceRenewalUnpaidFixtures.load(root));
        var oldDelivery = InvoiceRenewalOldDeliveryFixtures.load(root);
        var boundary = InvoiceRenewalBoundaryFixtures.load(root);
        sources.put("old-delivery-v1", oldDelivery::bytes); sources.put("boundary-v1", boundary::bytes);
        sources.put("registry-v1", name -> {
            CaseInputs.require(files.containsKey(name), "Unregistered invoice registry file"); return files.get(name).clone();
        });
        String contractIdentity = text(object(files.get("bundle.json")), "contractIdentity");
        for (String corpus : sources.keySet())
            CaseInputs.require(contractIdentity.equals(text(object(sources.get(corpus).apply("bundle.json")), "contractIdentity")),
                    "Invoice corpus contract mismatch");
        var result = new Registration(business, oldDelivery, boundary, sources, inventory);
        result.requireFullCaseCoverage();
        return result;
    }
    private static void add(Map<String, BusinessCorpus> business, Map<String, Function<String, byte[]>> sources,
                            String id, InvoiceRenewalVariantFixtures.Bundle bundle) {
        business.put(id, new BusinessCorpus(id, bundle.identity(), bundle.installationId(), bundle.scenarios()));
        sources.put(id, bundle::bytes);
    }

    private static JsonObject describe(Map<String, BusinessCorpus> business,
                                       InvoiceRenewalOldDeliveryFixtures.Bundle oldDelivery,
                                       InvoiceRenewalBoundaryFixtures.Bundle boundary) {
        var result = new JsonObject(); result.addProperty("formatVersion", 1); result.addProperty("caseId", CASE_ID);
        result.addProperty("policy", POLICY); result.addProperty("origin", "authored-synthetic");
        result.addProperty("authority", "fixture-registration-only");
        var families = new LinkedHashSet<String>(); var corpora = new JsonArray();
        for (var corpus : business.values()) {
            var row = corpus(corpus.id(), "business", corpus.identity(), corpus.installationId()); var variants = new JsonArray();
            for (var scenario : corpus.scenarios()) {
                String family = corpus.id().equals("rollout-v1") ? scenario.id()
                        : text(scenario.deliveries().values().iterator().next().input(), "familyId");
                families.add(family);
                var variant = variant(scenario.id(), family, scenario.deliveries().keySet(), Set.of());
                var sequences = new JsonArray();
                for (var sequence : scenario.sequences()) sequences.add(sequence(sequence.id(), sequence.deliveries()));
                variant.add("sequences", sequences); variants.add(variant);
            }
            row.add("variants", variants); corpora.add(row);
        }
        var old = corpus("old-delivery-v1", "old-delivery", oldDelivery.identity(), oldDelivery.installationId());
        var variants = new JsonArray(); String family = "old-delivery-after-upgrade"; families.add(family);
        for (var scenario : oldDelivery.scenarios()) {
            var blocked = scenario.deliveries().values().stream().filter(InvoiceRenewalOldDeliveryFixtures.Delivery::blocked)
                    .map(InvoiceRenewalOldDeliveryFixtures.Delivery::id).toList();
            var variant = variant(scenario.id(), family, scenario.deliveries().keySet(), blocked); var sequences = new JsonArray();
            for (var sequence : scenario.sequences()) sequences.add(sequence(sequence.id(), sequence.deliveries()));
            variant.add("sequences", sequences); variants.add(variant);
        }
        old.add("variants", variants); corpora.add(old);
        result.add("requiredFamilies", strings(families)); result.add("corpora", corpora);
        var controls = new JsonObject(); controls.addProperty("id", "boundary-v1");
        controls.addProperty("kind", "admission-and-result-controls"); controls.addProperty("bundleIdentity", boundary.identity());
        controls.add("controls", strings(boundary.controls().stream().map(InvoiceRenewalBoundaryFixtures.Control::id).toList()));
        controls.add("placements", strings(PLACEMENTS)); result.add("boundary", controls);
        return result;
    }
    /** Metadata comparison cannot mint a Registration, execution permission or observation. */
    static void validateCoverage(JsonObject required, JsonObject actual) {
        CaseInputs.require(required.equals(actual), "Combined invoice variant/control coverage mismatch");
    }
    private static JsonObject corpus(String id, String kind, String identity, String installation) {
        var row = new JsonObject(); row.addProperty("id", id); row.addProperty("kind", kind);
        row.addProperty("bundleIdentity", identity); row.addProperty("installationId", installation); return row;
    }
    private static JsonObject variant(String id, String family, Collection<String> deliveries, Collection<String> blocked) {
        var row = new JsonObject(); row.addProperty("id", id); row.addProperty("familyId", family);
        row.add("deliveries", strings(deliveries.stream().sorted().toList()));
        row.add("blockedDeliveries", strings(blocked.stream().sorted().toList())); return row;
    }
    private static JsonObject sequence(String id, List<String> deliveries) {
        var row = new JsonObject(); row.addProperty("id", id); row.add("deliveries", strings(deliveries)); return row;
    }
    private static JsonArray strings(Collection<String> values) {
        var array = new JsonArray(); values.forEach(array::add); return array;
    }
}
