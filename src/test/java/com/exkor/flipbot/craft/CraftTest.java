package com.exkor.flipbot.craft;

import com.exkor.flipbot.flipper.FlipperConfig;
import com.exkor.flipbot.flipper.data.MarketData;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CraftTest {
    /** Real repo entry (shortened): two crafting recipes for Enchanted Gold Ingot. */
    static final String ENCHANTED_GOLD = """
        {"internalname":"ENCHANTED_GOLD","crafttext":"Requires: Gold Ingot IV","recipes":[
          {"type":"crafting","A1":"","A2":"GOLD_INGOT:32","A3":"","B1":"GOLD_INGOT:32","B2":"GOLD_INGOT:32",
           "B3":"GOLD_INGOT:32","C1":"","C2":"GOLD_INGOT:32","C3":"","count":1},
          {"type":"crafting","A1":"","A2":"GOLD_BLOCK:32","A3":"","B1":"GOLD_BLOCK:32","B2":"GOLD_BLOCK:32",
           "B3":"GOLD_BLOCK:32","C1":"","C2":"GOLD_BLOCK:32","C3":"","count":9},
          {"type":"forge","inputs":["X"]}]}""";

    @Test
    void parsesCraftingRecipesOnly() {
        List<Recipe> rs = Recipe.parse(JsonParser.parseString(ENCHANTED_GOLD).getAsJsonObject());
        assertEquals(2, rs.size(), "the forge recipe is skipped");
        Recipe r = rs.get(0);
        assertEquals("ENCHANTED_GOLD", r.output());
        assertEquals(1, r.outputCount());
        assertEquals(Map.of("GOLD_INGOT", 160), r.ingredients(), "5 cells of 32 add up");
        assertEquals("Requires: Gold Ingot IV", r.requirement());
        assertEquals(9, rs.get(1).outputCount());
    }

    @Test
    void mapsRepoIdsToBazaarIds() {
        assertEquals("INK_SACK:3", Recipe.bazaarId("INK_SACK-3"));
        assertEquals("LOG:2", Recipe.bazaarId("LOG-2"));
        assertEquals("ENCHANTED_GOLD", Recipe.bazaarId("ENCHANTED_GOLD"));
        assertEquals("SOME-NAME", Recipe.bazaarId("SOME-NAME"), "only a numeric suffix is a damage value");
        assertEquals("INK_SACK-3", Recipe.repoId("INK_SACK:3"));
    }

    @Test
    void readsOnlyBazaarRecipesFromTheRepoZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            z.putNextEntry(new ZipEntry("NotEnoughUpdates-REPO-master/items/ENCHANTED_GOLD.json"));
            z.write(ENCHANTED_GOLD.getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("NotEnoughUpdates-REPO-master/items/HYPERION.json"));
            z.write("{\"internalname\":\"HYPERION\",\"recipe\":{\"A1\":\"GOLD_INGOT:1\"}}".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("NotEnoughUpdates-REPO-master/constants/ENCHANTED_GOLD.json"));
            z.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        List<Recipe> rs = RecipeRepo.fromZip(new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                Set.of("ENCHANTED_GOLD", "GOLD_INGOT"));
        assertEquals(1, rs.size(), "HYPERION isn't a Bazaar product, GOLD_BLOCK isn't either here, constants/ is skipped");
        assertEquals(Map.of("GOLD_INGOT", 160), rs.get(0).ingredients());
    }

    private static MarketData.Product product(String id, double bid, double ask, double sellWeek, double buyWeek) {
        MarketData.Product p = new MarketData.Product();
        p.productId = id;
        p.bestBid = bid;
        p.bestAsk = ask;
        p.sellMovingWeek = sellWeek;
        p.buyMovingWeek = buyWeek;
        return p;
    }

    private static MarketData market(MarketData.Product... ps) {
        MarketData m = new MarketData();
        for (MarketData.Product p : ps) m.products.put(p.productId, p);
        return m;
    }

    private static final Recipe GOLD = new Recipe("ENCHANTED_GOLD", 1, Map.of("GOLD_INGOT", 160), "Requires: Gold Ingot IV");

    @Test
    void profitIsResultAfterTaxMinusIngredientsAtBuyOrderPrices() {
        FlipperConfig cfg = new FlipperConfig();
        // 160 ingots at 4.9 (+0.1 = 5.0) = 800; result at 1000 (-0.1) after 1.25% tax = 987.4
        MarketData m = market(product("GOLD_INGOT", 4.9, 6, 168_000, 168_000), product("ENCHANTED_GOLD", 900, 1000, 1_680, 1_680));
        List<CraftAnalyzer.Plan> plans = CraftAnalyzer.plans(m, List.of(GOLD), cfg, 100_000, 30);
        assertEquals(1, plans.size());
        CraftAnalyzer.Plan p = plans.get(0);
        assertEquals(800, p.costPerCraft(), 1e-6);
        assertEquals(999.9 * 0.9875, p.revenuePerCraft(), 1e-6);
        assertEquals(6.25, p.craftsPerHour(), 1e-9, "result sells 10/h, but ingots only supply 1000/h = 6.25 crafts");
    }

    @Test
    void throughputIsLimitedByTheSlowestSide() {
        FlipperConfig cfg = new FlipperConfig();
        MarketData m = market(product("GOLD_INGOT", 4.9, 6, 168_000, 168_000), product("ENCHANTED_GOLD", 900, 1000, 16_800, 16_800));
        CraftAnalyzer.Plan p = CraftAnalyzer.plans(m, List.of(GOLD), cfg, 100_000, 30).get(0);
        assertEquals(168_000 / 168.0 / 160, p.craftsPerHour(), 1e-9, "ingots: 1000/h, 160 per craft");
        // budget: 100k / 800 = 125 crafts; inventory: 30 slots x 64 / 160 = 12; market: 6.25/h -> 7
        assertEquals(7, p.batchCrafts());
        assertEquals(7 * p.profitPerCraft(), p.batchProfit(), 1e-6);
        assertEquals(12, CraftAnalyzer.batchCrafts(GOLD, 800, 100_000, 30, 1000));
        assertEquals(2, CraftAnalyzer.batchCrafts(GOLD, 800, 1_700, 30, 1000), "budget-bound");
    }

    @Test
    void unprofitableOrThinOrFakeCraftsAreDropped() {
        FlipperConfig cfg = new FlipperConfig();
        // Loss: ingredients cost more than the result brings.
        assertTrue(CraftAnalyzer.plans(market(product("GOLD_INGOT", 6.9, 7, 168_000, 168_000),
                product("ENCHANTED_GOLD", 900, 1000, 1_680, 1_680)), List.of(GOLD), cfg, 1e6, 30).isEmpty());
        // No buyers for the result.
        assertTrue(CraftAnalyzer.plans(market(product("GOLD_INGOT", 4.9, 6, 168_000, 168_000),
                product("ENCHANTED_GOLD", 900, 1000, 0, 0)), List.of(GOLD), cfg, 1e6, 30).isEmpty());
        // A result price far above its weekly average is likely fake.
        MarketData.Product fake = product("ENCHANTED_GOLD", 900, 5000, 1_680, 1_680);
        fake.avgBuyPrice = 1000;
        assertTrue(CraftAnalyzer.plans(market(product("GOLD_INGOT", 4.9, 6, 168_000, 168_000), fake),
                List.of(GOLD), cfg, 1e6, 30).isEmpty());
        // Missing ingredient on the Bazaar.
        assertTrue(CraftAnalyzer.plans(market(product("ENCHANTED_GOLD", 900, 1000, 1_680, 1_680)),
                List.of(GOLD), cfg, 1e6, 30).isEmpty());
    }
}
