package com.exkor.flipbot.flipper;

import com.exkor.flipbot.flipper.practice.PracticeBook;
import com.exkor.flipbot.flipper.trading.Trade;

import java.util.Collection;

/**
 * Profit rules (pure Java, unit tested):
 * <ul>
 *   <li><b>Never sell at a loss</b> ({@code onlySellAtProfit}): a sell offer is never placed below
 *       {@link #breakEven}: what the item cost, after the Bazaar tax, plus 0.1.</li>
 *   <li><b>Finish each batch first</b> ({@code finishBatchFirst}): no new buy orders while the bot still holds items
 *       it bought and hasn't sold (filled, claimed, or listed).</li>
 * </ul>
 */
public final class ProfitRules {
    private ProfitRules() {}

    /** Lowest sell price per unit that still makes a profit after tax; 0 when the cost is unknown. */
    public static double breakEven(double buyPrice, double taxPercent) {
        if (buyPrice <= 0) return 0;
        double net = 1.0 - taxPercent / 100.0;
        return Math.ceil(buyPrice / net * 10.0 - 1e-9) / 10.0 + 0.1;
    }

    /** Items the bot bought and hasn't sold yet: filled or claimed buy orders, and open sell offers. */
    public static int unsoldTrades(Collection<Trade> trades) {
        int n = 0;
        for (Trade t : trades) {
            if (t.state == Trade.State.FILLED || t.state == Trade.State.CLAIMED || t.state == Trade.State.SELL_OPEN) n++;
        }
        return n;
    }

    /** Practice mode: open simulated sell offers are items not sold yet. */
    public static int unsoldPractice(PracticeBook book) {
        int n = 0;
        for (PracticeBook.Order o : book.orders) {
            if (o.state == PracticeBook.State.OPEN && o.side == PracticeBook.Side.SELL) n++;
        }
        return n;
    }
}
