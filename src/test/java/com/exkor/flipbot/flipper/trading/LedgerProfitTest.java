package com.exkor.flipbot.flipper.trading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Trades without a known cost must not count as profit. */
class LedgerProfitTest {
    private static Trade done(long qty, double cost, double revenue) {
        Trade t = new Trade();
        t.productId = "X";
        t.displayName = "X";
        t.state = Trade.State.DONE;
        t.qty = qty;
        t.cost = cost;
        t.revenue = revenue;
        return t;
    }

    @Test
    void onlyTradesWithARealCostCountAsProfit(@TempDir Path dir) {
        OrderLedger ledger = new OrderLedger(dir.resolve("trades.json"));
        ledger.add(done(3, 9_000_000, 10_000_000));          // real flip: +1M
        ledger.add(done(2, 0, 1_562_867));                    // adopted sell offer, cost unknown (Diamante's Handle)
        ledger.add(done(0, 0, 18_713_289));                   // left-over of a partly claimed order (Perfect Onyx)
        Trade loss = done(1, 5_000_000, 4_500_000);           // real loss: -0.5M
        ledger.add(loss);
        Trade open = done(1, 1_000_000, 2_000_000);
        open.state = Trade.State.SELL_OPEN;                    // not sold yet
        ledger.add(open);
        assertEquals(500_000, ledger.realizedProfit(), 1e-6);
        assertEquals(2, ledger.tradesCompleted());
    }
}
