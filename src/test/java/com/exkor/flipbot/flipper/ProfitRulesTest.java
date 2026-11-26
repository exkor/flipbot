package com.exkor.flipbot.flipper;

import com.exkor.flipbot.flipper.practice.PracticeBook;
import com.exkor.flipbot.flipper.trading.Trade;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfitRulesTest {
    private static final double TAX = 1.25;

    @Test
    void breakEvenCoversCostAfterTax() {
        double floor = ProfitRules.breakEven(3_121_415.3, TAX);
        // Selling at the floor nets more than the item cost.
        assertTrue(floor * (1 - TAX / 100) > 3_121_415.3);
        // ... and one step below it would not make a profit worth having (at most break-even).
        assertTrue((floor - 0.2) * (1 - TAX / 100) <= 3_121_415.3 + 0.1);
        assertEquals(Math.round(floor * 10) / 10.0, floor, 1e-9, "on the Bazaar's 0.1 grid");
    }

    @Test
    void unknownCostMeansNoFloor() {
        assertEquals(0, ProfitRules.breakEven(0, TAX));
        assertEquals(0, ProfitRules.breakEven(-5, TAX));
    }

    private static Trade trade(Trade.State state) {
        Trade t = new Trade();
        t.state = state;
        return t;
    }

    @Test
    void unsoldCountsWhatTheBotStillHolds() {
        assertEquals(0, ProfitRules.unsoldTrades(List.of(trade(Trade.State.BUY_OPEN), trade(Trade.State.DONE),
                trade(Trade.State.SOLD), trade(Trade.State.CANCELLED), trade(Trade.State.ABANDONED))),
                "open buy orders and sold items don't block the next batch");
        assertEquals(3, ProfitRules.unsoldTrades(List.of(trade(Trade.State.FILLED), trade(Trade.State.CLAIMED),
                trade(Trade.State.SELL_OPEN), trade(Trade.State.BUY_OPEN))));
    }

    @Test
    void practiceUnsoldIsOpenSellOffers() {
        PracticeBook book = new PracticeBook();
        PracticeBook.Order buy = book.recordBuy("GEM", "Gem", 100, 1, 0, 0);
        assertEquals(0, ProfitRules.unsoldPractice(book), "an open buy order doesn't block");
        PracticeBook.Order sell = new PracticeBook.Order();
        sell.side = PracticeBook.Side.SELL;
        sell.state = PracticeBook.State.OPEN;
        book.orders.add(sell);
        assertEquals(1, ProfitRules.unsoldPractice(book));
        sell.state = PracticeBook.State.DONE;
        assertEquals(0, ProfitRules.unsoldPractice(book));
        assertNotNull(buy);
    }
}
