package com.exkor.flipbot.flipper.trading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Item-name symbols: "❈ Perfect Amethyst Gemstone" in the ledger vs " Perfect Amethyst Gemstone" in Manage Orders. */
class OrderNameTest {
    @Test
    void symbolsDontBreakTheMatch() {
        assertTrue(ScreenUtil.sameName("❈ Perfect Amethyst Gemstone", " Perfect Amethyst Gemstone"));
        assertTrue(ScreenUtil.sameName("❤ Flawless Ruby Gemstone", "Flawless Ruby Gemstone"));
        assertFalse(ScreenUtil.sameName("❈ Perfect Amethyst Gemstone", "Perfect Jade Gemstone"));
    }

    @Test
    void ledgerFindsTheTradeByNameWithoutTheSymbol(@TempDir Path dir) {
        OrderLedger ledger = new OrderLedger(dir.resolve("trades.json"));
        Trade t = new Trade();
        t.productId = "PERFECT_AMETHYST_GEM";
        t.displayName = "❈ Perfect Amethyst Gemstone";
        t.qty = 1;
        t.state = Trade.State.BUY_OPEN;
        ledger.add(t);
        assertSame(t, ledger.firstMatching(" Perfect Amethyst Gemstone", Trade.State.BUY_OPEN));
    }
}
