package com.exkor.flipbot.flipper.trading;

/** Test access to the ledger's package-private profit rule. */
public final class OrderLedgerAccess {
    private OrderLedgerAccess() {}

    public static boolean countsAsProfit(Trade t) {
        return OrderLedger.countsAsProfit(t);
    }
}
