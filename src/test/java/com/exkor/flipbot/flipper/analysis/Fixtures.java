package com.exkor.flipbot.flipper.analysis;

import com.exkor.flipbot.flipper.FlipperConfig;
import com.exkor.flipbot.flipper.data.BazaarApi;
import com.exkor.flipbot.flipper.data.BazaarPriceHistory;
import com.exkor.flipbot.flipper.data.MarketData;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Loads the recorded Bazaar snapshot and Coflnet history, and points the analyzer at them (no network, no client). */
final class Fixtures {
    static final double BANKROLL = 500e6;
    static final int FREE_SLOTS = 20;

    private Fixtures() {}

    static MarketData market() {
        MarketData data = BazaarApi.parse(read("/bazaar-snapshot.json"));
        if (data == null || data.products.isEmpty()) throw new IllegalStateException("snapshot did not parse");
        return data;
    }

    static Map<String, BazaarPriceHistory.Snapshot> history() {
        Map<String, BazaarPriceHistory.Snapshot> map = new HashMap<>();
        JsonObject root = JsonParser.parseString(read("/coflnet-history.json")).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            JsonObject j = e.getValue().getAsJsonObject();
            BazaarPriceHistory.Snapshot s = new BazaarPriceHistory.Snapshot();
            s.min = num(j, "min"); s.median = num(j, "median"); s.mean = num(j, "mean");
            s.mode = num(j, "mode"); s.volume = num(j, "volume"); s.max = num(j, "max");
            map.put(e.getKey(), s);
        }
        return map;
    }

    /** Offline analyzer: recorded history (null for unrecorded products), fixed inventory. */
    static void install(Map<String, BazaarPriceHistory.Snapshot> history) {
        BazaarPriceHistory.source = history::get;
        BazaarPriceHistory.clearCache();
        FlipAnalyzer.freeSlots = () -> FREE_SLOTS;
    }

    static FlipperConfig defaults() {
        return new FlipperConfig();
    }

    private static double num(JsonObject j, String key) {
        return j.has(key) && !j.get(key).isJsonNull() ? j.get(key).getAsDouble() : 0;
    }

    private static String read(String resource) {
        try (InputStream in = Fixtures.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing test resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
