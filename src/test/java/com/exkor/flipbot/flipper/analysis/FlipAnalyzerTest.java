package com.exkor.flipbot.flipper.analysis;

import com.exkor.flipbot.flipper.FlipperConfig;
import com.exkor.flipbot.flipper.data.BazaarPriceHistory;
import com.exkor.flipbot.flipper.data.MarketData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each reject reason: a known snapshot product is dropped with the default config, and comes back when only
 * that rule is relaxed. Real products are used where the snapshot has one that fails exactly that rule;
 * otherwise a passing product (PERFECT_ONYX_GEM) gets one field changed to trip the rule.
 */
class FlipAnalyzerTest {
    private static final String GOOD = "PERFECT_ONYX_GEM";

    private MarketData market;
    private Map<String, BazaarPriceHistory.Snapshot> history;

    @BeforeEach
    void setUp() {
        market = Fixtures.market();
        history = Fixtures.history();
        Fixtures.install(history);
    }

    @Test
    void goldenTop10MatchesRecordedList() throws IOException {
        List<FlipAnalyzer.FlipPlan> plans = FlipAnalyzer.candidates(market, Fixtures.defaults(), Fixtures.BANKROLL,
                Set.of(), null, 0);
        List<String> top = plans.stream().limit(10).map(p -> p.productId).toList();
        assertEquals(golden(), top);
        assertEquals(39, plans.size(), "candidates that fit the limit budget (flipcompare.ps1 says 39)");
    }

    /** Small bankrolls (10M, 18.8M) must still find flips, with orders sized to the per-item budget. */
    @Test
    void smallBankrollsStillFindFlips() {
        for (double bankroll : new double[] {10e6, 18.8e6}) {
            List<FlipAnalyzer.FlipPlan> plans = FlipAnalyzer.candidates(market, Fixtures.defaults(), bankroll, Set.of(), null, 0);
            assertFalse(plans.isEmpty(), "no candidates at bankroll " + bankroll);
            double perItem = bankroll * Fixtures.defaults().maxBankFractionPerItem;
            for (FlipAnalyzer.FlipPlan p : plans) {
                assertTrue(p.qty * p.buyPrice <= perItem + 1e-6, p.productId + " order over the per-item budget");
            }
            List<FlipAnalyzer.FlipPlan> placed = FlipAnalyzer.compute(market, Fixtures.defaults(), bankroll, Set.of(), 14);
            assertFalse(placed.isEmpty(), "compute() places nothing at bankroll " + bankroll);
        }
    }

    @Test
    void controlProductPasses() {
        assertTrue(accepted(product(GOOD), Fixtures.defaults()));
    }

    @Test
    void spreadTooSmall() {
        MarketData.Product p = product(GOOD);
        p.bestAsk = p.bestBid + 0.1; // after the 0.1 undercut on both sides, sell <= buy
        assertFalse(accepted(p, Fixtures.defaults()));
    }

    @Test
    void noProfitAfterTax() {
        MarketData.Product p = product(GOOD);
        p.bestAsk = p.bestBid * 1.01; // 1% spread is less than the 1.25% tax
        assertFalse(accepted(p, Fixtures.defaults()));
        assertTrue(accepted(p, cfg(c -> c.taxPercent = 0)));
    }

    @Test
    void marginBelowMinimum() {
        MarketData.Product p = product("GLACITE_AMALGAMATION"); // 0.24%
        assertFalse(accepted(p, Fixtures.defaults()));
        assertTrue(accepted(p, cfg(c -> { c.minMarginPercent = 0.1; c.requireHistoricalPrice = false; })));
    }

    @Test
    void marginAboveCap() {
        MarketData.Product p = product("GIANT_FRAGMENT_LASER"); // 27.7%
        assertFalse(accepted(p, Fixtures.defaults()));
        assertTrue(accepted(p, cfg(c -> { c.maxMarginPercent = 100; c.requireHistoricalPrice = false; })));
    }

    @Test
    void unitPriceTooLow() {
        MarketData.Product p = product("DO_NOT_EAT_SHROOM"); // ~80k per unit
        assertFalse(accepted(p, Fixtures.defaults()));
        assertTrue(accepted(p, cfg(c -> { c.minUnitCoins = 10_000; c.requireHistoricalPrice = false; })));
    }

    @Test
    void weeklyVolumeTooLow() {
        MarketData.Product p = product("ENCHANTMENT_EXPERIENCE_5"); // 68 per week
        assertFalse(accepted(p, Fixtures.defaults()));
        assertTrue(accepted(p, cfg(c -> { c.minWeeklyVolume = 1; c.requireHistoricalPrice = false; })));
    }

    @Test
    void topBuyOrderFarAboveSecond() {
        MarketData.Product p = product(GOOD);
        p.bestBid2 = p.bestBid * 0.90; // top bid 10% over the next one: manipulation guard
        assertFalse(accepted(p, Fixtures.defaults()));
    }

    @Test
    void priceTooFarFromAverage() {
        MarketData.Product p = product(GOOD);
        p.avgSellPrice = p.bestBid * 2; // buy price 50% off the average
        assertFalse(accepted(p, Fixtures.defaults()));
        assertTrue(accepted(p, cfg(c -> c.maxPriceDeviationPercent = 60)));
    }

    @Test
    void priceTooFarFromHistory() {
        BazaarPriceHistory.Snapshot far = new BazaarPriceHistory.Snapshot();
        far.mode = product(GOOD).bestBid * 3;
        far.volume = 1000;
        Map<String, BazaarPriceHistory.Snapshot> changed = new HashMap<>(history);
        changed.put(GOOD, far);
        Fixtures.install(changed);
        assertFalse(accepted(product(GOOD), Fixtures.defaults()));
        assertTrue(accepted(product(GOOD), cfg(c -> c.maxHistoricalDeviationPercent = 1000)));
    }

    @Test
    void historyVolumeTooLow() {
        MarketData.Product p = product("AOTE_STONE"); // the one history reject in the snapshot
        assertFalse(accepted(p, Fixtures.defaults()));
        assertTrue(accepted(p, cfg(c -> c.minHistoryVolume = 0)));
    }

    /** Runs the analyzer on a market holding only this product, so other items and the limit budget can't interfere. */
    private static boolean accepted(MarketData.Product p, FlipperConfig cfg) {
        MarketData one = new MarketData();
        one.products.put(p.productId, p);
        return FlipAnalyzer.candidates(one, cfg, Fixtures.BANKROLL, Set.of(), null, 0).stream()
                .anyMatch(plan -> plan.productId.equals(p.productId));
    }

    private static FlipperConfig cfg(Consumer<FlipperConfig> change) {
        FlipperConfig c = Fixtures.defaults();
        change.accept(c);
        return c;
    }

    /** A copy, so a test's changes never leak into the shared snapshot. */
    private MarketData.Product product(String id) {
        MarketData.Product s = market.products.get(id);
        assertNotNull(s, id + " missing from snapshot");
        MarketData.Product p = new MarketData.Product();
        p.productId = s.productId; p.bestBid = s.bestBid; p.bestBidVolume = s.bestBidVolume;
        p.bestBidOrders = s.bestBidOrders; p.bestBid2 = s.bestBid2; p.bestAsk = s.bestAsk;
        p.bestAskVolume = s.bestAskVolume; p.bestAskOrders = s.bestAskOrders;
        p.sellMovingWeek = s.sellMovingWeek; p.buyMovingWeek = s.buyMovingWeek;
        p.avgSellPrice = s.avgSellPrice; p.avgBuyPrice = s.avgBuyPrice; p.sellVolume = s.sellVolume;
        p.buyVolume = s.buyVolume; p.sellOrders = s.sellOrders; p.buyOrders = s.buyOrders;
        return p;
    }

    private static List<String> golden() throws IOException {
        try (InputStream in = FlipAnalyzerTest.class.getResourceAsStream("/analyzer-golden-top10.txt")) {
            assertNotNull(in, "golden file missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
        }
    }
}
