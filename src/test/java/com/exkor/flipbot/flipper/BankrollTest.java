package com.exkor.flipbot.flipper;

import com.exkor.flipbot.flipper.trading.ScreenUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BankrollTest {
    @Test
    void readsThePurseLine() {
        assertEquals(19_815_175L, ScreenUtil.parsePurse(List.of("Server: m12AB", "Purse: 19,815,175")));
        assertEquals(42L, ScreenUtil.parsePurse(List.of("Purse:42")));
    }

    @Test
    void ignoresHypixelsGainSuffix() {
        // Hypixel's gain suffix must not hide the purse.
        assertEquals(19_815_175L, ScreenUtil.parsePurse(List.of("Purse: 19,815,175 (+5)")));
        assertEquals(1_200L, ScreenUtil.parsePurse(List.of("Purse: 1,200.7 (+1,000)")));
    }

    @Test
    void readsPiggyAndReportsMissing() {
        assertEquals(5_000_000L, ScreenUtil.parsePurse(List.of("Piggy: 5,000,000")));
        assertEquals(-1L, ScreenUtil.parsePurse(List.of("Bits: 1,234", "Motes: 50")));
        assertEquals(-1L, ScreenUtil.parsePurse(List.of()));
    }

    /** Purse rules with "trade only with profit" off. */
    private static FlipperConfig purseMode() {
        FlipperConfig cfg = new FlipperConfig();
        cfg.tradeWithProfitOnly = false;
        return cfg;
    }

    @Test
    void profitOnlyIsTheDefaultAndCapsAtProfitMade() {
        FlipperConfig cfg = new FlipperConfig();
        assertTrue(cfg.tradeWithProfitOnly);
        assertEquals(22_300_000.0, Bankroll.cap(cfg, 22_300_000));
        assertEquals(22_300_000.0, Bankroll.of(cfg, 396_000_000L, 22_300_000), "your other 370M are never used");
        assertEquals(18_815_175.0, Bankroll.of(cfg, 19_815_175L, 50_000_000), "and never more than purse minus reserve");
        assertTrue(Bankroll.hasCap(cfg));
        assertTrue(Bankroll.hasLimit(cfg));
    }

    @Test
    void noProfitMeansNothingToTradeWith() {
        FlipperConfig cfg = new FlipperConfig();
        assertEquals(0, Bankroll.cap(cfg, 0));
        assertEquals(0, Bankroll.cap(cfg, -5_000_000), "losses don't make the budget negative");
        assertEquals(0, Bankroll.of(cfg, 396_000_000L, 0));
    }

    @Test
    void wholePurseWhenProfitOnlyIsOff() {
        FlipperConfig cfg = purseMode();
        assertTrue(cfg.investFromPurse);
        assertEquals(18_815_175.0, Bankroll.of(cfg, 19_815_175L, 0));
        cfg.maxInvestedMillions = 10; // ignored while the bankroll follows the purse
        assertEquals(18_815_175.0, Bankroll.of(cfg, 19_815_175L, 0));
        assertEquals(-1, Bankroll.cap(cfg, 0));
        assertFalse(Bankroll.hasCap(cfg));
        assertTrue(Bankroll.hasLimit(cfg));
    }

    @Test
    void fixedCapAppliesOnlyWhenPurseModeIsOff() {
        FlipperConfig cfg = purseMode();
        cfg.investFromPurse = false;
        cfg.maxInvestedMillions = 10;
        assertEquals(10_000_000.0, Bankroll.of(cfg, 19_815_175L, 0));
        assertTrue(Bankroll.hasCap(cfg));
        cfg.maxInvestedMillions = 0;
        assertFalse(Bankroll.hasLimit(cfg), "no profit mode, no purse mode and no cap: going live needs a confirm");
    }

    @Test
    void unknownOrSmallPurse() {
        FlipperConfig cfg = purseMode();
        assertEquals(-1, Bankroll.of(cfg, -1, 0));
        assertEquals(0, Bankroll.of(cfg, 500_000L, 0), "below the reserve");
    }
}
