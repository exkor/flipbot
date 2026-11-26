package com.exkor.flipbot.craft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The live craft batch in progress (one at a time): its ingredient buy orders are tagged in the ledger with
 * {@link #id}. Saved to config/flipbot/craft-batch.json so a restart picks it up where it was.
 */
public final class CraftBatch {
    public enum State { BUYING, CRAFTING }

    public String id;
    public String output;
    public int outputCount;
    public int crafts;
    /** Ingredient id -> units per craft. */
    public Map<String, Integer> perCraft = new LinkedHashMap<>();
    /** Ingredient id -> buy order price per unit. */
    public Map<String, Double> price = new LinkedHashMap<>();
    public double totalCost;
    public double expectedProfit;
    public State state = State.BUYING;
    public long createdAt;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static CraftBatch current;
    private static boolean loaded;

    /** Units of {@code ingredient} the whole batch needs. */
    public long needed(String ingredient) {
        return (long) perCraft.getOrDefault(ingredient, 0) * crafts;
    }

    public static CraftBatch create(CraftAnalyzer.Plan plan, Map<String, Double> unitPrices, long crafts, long now) {
        CraftBatch b = new CraftBatch();
        b.id = "C" + now;
        b.output = plan.recipe().output();
        b.outputCount = plan.recipe().outputCount();
        b.crafts = (int) crafts;
        b.perCraft.putAll(plan.recipe().ingredients());
        b.price.putAll(unitPrices);
        for (Map.Entry<String, Integer> e : b.perCraft.entrySet()) {
            b.totalCost += unitPrices.getOrDefault(e.getKey(), 0.0) * e.getValue() * crafts;
        }
        b.expectedProfit = plan.profitPerCraft() * crafts;
        b.createdAt = now;
        return b;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("flipbot").resolve("craft-batch.json");
    }

    public static synchronized CraftBatch current() {
        if (!loaded) {
            loaded = true;
            try {
                if (Files.exists(file())) current = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), CraftBatch.class);
            } catch (Exception e) {
                current = null;
            }
        }
        return current;
    }

    public static synchronized void set(CraftBatch batch) {
        current = batch;
        loaded = true;
        try {
            if (batch == null) {
                Files.deleteIfExists(file());
            } else {
                Files.createDirectories(file().getParent());
                Files.writeString(file(), GSON.toJson(batch), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.out.println("[FlipBot] craft-batch.json: " + e.getMessage());
        }
    }

    /** Whether an active batch is holding this product (as an ingredient or the result). */
    public static boolean holds(String productId) {
        CraftBatch b = current();
        return b != null && productId != null && (b.perCraft.containsKey(productId) || b.output.equals(productId));
    }
}
