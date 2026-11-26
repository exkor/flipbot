package com.exkor.flipbot.flipper.practice;

import com.exkor.flipbot.flipper.ProfitRules;
import com.exkor.flipbot.flipper.data.MarketData;
import com.exkor.flipbot.flipper.trading.OrderLedgerAccess;
import com.exkor.flipbot.flipper.trading.Trade;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PracticeCraftTest {
    private static final long MIN = 60_000L;
    /** Any non-zero start: a book that never ticked has lastTickAt 0. */
    private static final long T0 = 1_000 * MIN;

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

    /** 10 080 per week = 1 per minute. */
    private static final double PER_MIN = 7 * 24 * 60;

    @Test
    void ingredientsFillThenTheResultSellsForProfit() {
        PracticeBook book = new PracticeBook();
        // 2 crafts of 160 ingots at 5.0 = 1600 coins.
        PracticeBook.Craft c = book.recordCraft("ENCHANTED_GOLD", "Enchanted Gold", 1, 2,
                Map.of("GOLD_INGOT", 160), Map.of("GOLD_INGOT", 5.0), 0);
        assertEquals(1600, c.cost, 1e-9);
        assertEquals(1600, book.investedCoins(), 1e-9, "the whole batch is committed while buying");
        assertTrue(book.openProductIds().containsAll(List.of("GOLD_INGOT", "ENCHANTED_GOLD")));

        MarketData m = market(product("GOLD_INGOT", 4.9, 6, 100 * PER_MIN, 0),
                product("ENCHANTED_GOLD", 900, 1000, 0, PER_MIN));
        book.tick(m, 1.25, 1, T0);           // first tick only starts the clock
        book.tick(m, 1.25, 1, T0 + 2 * MIN);     // 200 ingots in 2 min: not all 320 yet
        assertFalse(c.selling);
        book.tick(m, 1.25, 1, T0 + 4 * MIN);     // 400 -> all in, crafted, listed
        assertTrue(c.selling);
        assertEquals(999.9, c.sellPrice, 1e-9);
        assertEquals(1, ProfitRules.unsoldPractice(book), "the crafted result is unsold until it sells");

        book.tick(m, 1.25, 1, T0 + 6 * MIN);     // 1 per minute: both sold
        assertEquals(PracticeBook.State.DONE, c.state);
        assertEquals(2 * 999.9 * 0.9875 - 1600, book.realizedProfit, 1e-6);
        assertEquals(1, book.completedCrafts);
        assertNull(book.openCraft());
        assertEquals(0, book.investedCoins(), 1e-9);
    }

    @Test
    void outbidIngredientsWaitAndTheResultNeverSellsBelowBreakEven() {
        PracticeBook book = new PracticeBook();
        PracticeBook.Craft c = book.recordCraft("ENCHANTED_GOLD", "Enchanted Gold", 1, 1,
                Map.of("GOLD_INGOT", 160), Map.of("GOLD_INGOT", 5.0), 0);
        MarketData outbid = market(product("GOLD_INGOT", 5.5, 6, 1000 * PER_MIN, 0),
                product("ENCHANTED_GOLD", 900, 1000, 0, PER_MIN));
        book.tick(outbid, 1.25, 1, T0);
        book.tick(outbid, 1.25, 1, T0 + 5 * MIN);
        assertEquals(0, c.filled.get("GOLD_INGOT"), 1e-9, "outbid: nothing fills, nothing is cancelled");
        assertEquals(PracticeBook.State.OPEN, c.state);

        // Ingredients come in, but the result's market has crashed below what the batch cost.
        MarketData crash = market(product("GOLD_INGOT", 4.9, 6, 1000 * PER_MIN, 0),
                product("ENCHANTED_GOLD", 500, 600, 0, PER_MIN));
        book.tick(crash, 1.25, 1, T0 + 6 * MIN);
        assertTrue(c.selling);
        double breakEven = Math.round(800 / 0.9875 * 10.0 + 1) / 10.0;
        assertEquals(breakEven, c.sellPrice, 0.11, "listed at break-even, not at the crashed price");
        book.tick(crash, 1.25, 1, T0 + 8 * MIN);
        assertEquals(0, c.sold, 1e-9, "held at break-even while undercut");
    }

    @Test
    void craftIngredientsAreNeitherUnsoldNorProfit() {
        Trade ingredient = new Trade();
        ingredient.craftIngredient = true;
        ingredient.state = Trade.State.DONE;
        ingredient.qty = 160;
        ingredient.cost = 800;
        ingredient.revenue = 0;
        Trade held = new Trade();
        held.craftIngredient = true;
        held.state = Trade.State.CLAIMED;
        assertEquals(0, ProfitRules.unsoldTrades(List.of(ingredient, held)));
        assertFalse(OrderLedgerAccess.countsAsProfit(ingredient), "an ingredient's cost is not a -800 loss");

        Trade result = new Trade();
        result.craftId = "C1";
        result.state = Trade.State.DONE;
        result.qty = 1;
        result.cost = 800;
        result.revenue = 987;
        assertTrue(OrderLedgerAccess.countsAsProfit(result), "the crafted result carries the batch cost");
    }
}
