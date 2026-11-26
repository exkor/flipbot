package com.exkor.flipbot;

import com.exkor.flipbot.flipper.trading.Trader;

import java.util.function.BooleanSupplier;

/** Coordinates menu use between automations, and the wait before each order action. */
public final class BotLock {
    /** Set by another automation while it is using the menus. */
    public static volatile BooleanSupplier otherBusy = () -> false;

    private BotLock() {}

    /** Another automation holds the menus: the Bazaar bot must keep out. */
    public static boolean isAhPriority() {
        Trader trader = Trader.get();
        return trader != null && trader.isAhPriority();
    }

    public static boolean otherBotBusy() {
        return otherBusy.getAsBoolean();
    }

    /** Fixed wait before an order action, on the bot thread. */
    public static void actionDelay(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
