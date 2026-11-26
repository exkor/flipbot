package com.exkor.flipbot.flipper;

import com.exkor.flipbot.Chat;
import com.exkor.flipbot.flipper.data.ItemNames;
import com.exkor.flipbot.flipper.data.MarketData;
import com.exkor.flipbot.flipper.practice.PracticeBook;
import com.exkor.flipbot.flipper.practice.PracticeStore;
import com.exkor.flipbot.flipper.trading.BazaarDailyLimit;
import com.exkor.flipbot.flipper.trading.OrderLedger;
import com.exkor.flipbot.flipper.trading.ScreenUtil;
import com.exkor.flipbot.flipper.trading.Trade;
import com.exkor.flipbot.flipper.trading.Trader;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.Locale;

/** Owns the config, the order ledger, the Trader and the practice book. */
public final class FlipperMod {
    public static final FlipperConfig CONFIG = FlipperConfig.load();

    private static OrderLedger ledger;
    private static PracticeStore practiceStore;
    /** Start time of the current Bazaar session, 0 while stopped. */
    private static volatile long sessionStartedAt;
    private static volatile MarketData lastMarket;

    private FlipperMod() {}

    public static void init() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("flipbot");
        ledger = new OrderLedger(dir.resolve("flipper-trades.json"));
        practiceStore = new PracticeStore(dir);
        Trader.init(CONFIG, ledger);
        ItemNames.load();
    }

    public static OrderLedger ledger() {
        return ledger;
    }

    private static volatile double profitAtSessionStart;

    /** Real profit of the live bot's finished flips (only trades with a known cost; see OrderLedger). */
    public static double profitMade() {
        return ledger == null ? 0 : ledger.realizedProfit();
    }

    /** Live profit per hour of the current session, or -1 while stopped / too early to tell. */
    public static double liveProfitPerHour() {
        long started = sessionStartedAt;
        if (started <= 0) return -1;
        double hours = (System.currentTimeMillis() - started) / 3_600_000.0;
        return hours < 0.05 ? -1 : (profitMade() - profitAtSessionStart) / hours;
    }

    public static PracticeBook practice() {
        return practiceStore.book();
    }

    public static void savePractice() {
        practiceStore.save();
    }

    public static PracticeBook resetPractice() {
        return practiceStore.reset();
    }

    public static boolean isRunning() {
        Trader trader = Trader.get();
        return trader != null && trader.isRunning();
    }

    public static void toggle() {
        if (isRunning()) stop();
        else start();
    }

    public static void start() {
        Trader trader = Trader.get();
        if (trader.isRunning()) {
            message("§eThe Bazaar bot is already running.");
            return;
        }
        if (!ScreenUtil.onSkyBlock()) {
            message("§cThe Bazaar bot only starts on Hypixel SkyBlock.");
            return;
        }
        message(CONFIG.practiceMode
                ? "§d[Practice] §7Orders are walked up to the confirm screen and simulated, never placed."
                : "§c§lLIVE §7Real orders, " + (CONFIG.tradeWithProfitOnly
                        ? "trading only with the §f" + ScreenUtil.formatCoins(Math.max(0, profitMade())) + "§7 of profit made."
                        : Bankroll.hasCap(CONFIG)
                        ? "at most §f" + ScreenUtil.formatCoins(CONFIG.maxInvestedMillions * 1e6) + "§7 invested."
                        : CONFIG.investFromPurse ? "using the whole purse minus the §f"
                                + ScreenUtil.formatCoins(CONFIG.reserveCoins) + "§7 reserve." : "no invest cap."));
        long purse = ScreenUtil.readPurse();
        if (purse >= 0) {
            message("§7Purse §f" + ScreenUtil.formatCoins(purse) + " §7→ bankroll §f"
                    + ScreenUtil.formatCoins(Bankroll.of(CONFIG, purse, profitMade())));
        }
        sessionStartedAt = System.currentTimeMillis();
        profitAtSessionStart = profitMade();
        trader.start();
    }

    public static void stop() {
        Trader trader = Trader.get();
        if (!trader.isRunning()) {
            message("§eThe Bazaar bot is not running.");
            return;
        }
        trader.stop();
        sessionStartedAt = 0;
        savePractice();
    }

    /**
     * Switches practice mode. Going live needs the bot stopped and an invest cap, or an explicit confirm
     * (rollout plan). Returns null on success, else the reason it was refused. Used by /flipbot and the GUI.
     */
    public static String setPracticeMode(boolean on, boolean confirmed) {
        if (isRunning()) {
            return "Stop the bot first.";
        }
        if (!on && !Bankroll.hasLimit(CONFIG) && !confirmed) {
            return "No limit: turn on investFromPurse or set maxInvestedMillions, or confirm going live without one.";
        }
        CONFIG.practiceMode = on;
        CONFIG.save();
        return null;
    }

    public static void setPaused(boolean paused) {
        Trader.get().setPaused(paused);
        message(paused ? "§7Paused (the current action finishes first)." : "§aResumed.");
    }

    public static boolean pauseForRestart() {
        Trader trader = Trader.get();
        if (trader != null && trader.isRunning()) {
            trader.setPaused(true);
            return true;
        }
        return false;
    }

    public static void resumeAfterRestart(boolean wasRunning) {
        Trader trader = Trader.get();
        if (wasRunning && trader != null) {
            trader.setPaused(false);
        }
    }

    /** One status line for the HUD. */
    public static String hudLine() {
        Trader trader = Trader.get();
        if (!CONFIG.hudEnabled || trader == null || !trader.isRunning()) return null;
        String mode = CONFIG.practiceMode ? "§dPRACTICE" : "§cLIVE";
        String state = trader.isPaused() ? "§6paused" : "§arunning";
        int open = CONFIG.practiceMode ? practice().openCount() : ledger.openOrderCount();
        return "§6FlipBot " + mode + " " + state + " §7orders §f" + open + "/" + CONFIG.maxOrders;
    }

    public static void status() {
        Trader trader = Trader.get();
        String state = trader.isRunning()
                ? (trader.isPaused() ? "§6PAUSED" : "§aRUNNING")
                : "§cSTOPPED";
        message("§7Bazaar bot: " + state + " §7· mode "
                + (CONFIG.practiceMode ? "§dpractice" : "§cLIVE"));
        long purse = ScreenUtil.readPurse();
        if (purse >= 0) {
            message("§7Purse: §f" + ScreenUtil.formatCoins(purse)
                    + " §7· Reserve: §f" + ScreenUtil.formatCoins(CONFIG.reserveCoins)
                    + " §7· Bankroll: §f" + ScreenUtil.formatCoins(Bankroll.of(CONFIG, purse, profitMade()))
                    + (Bankroll.hasCap(CONFIG) ? " §7(capped)" : " §7(whole purse)"));
        }
        MarketData market = marketSnapshot();
        if (market != null) {
            message("§7Market data: §f" + market.products.size() + " §7products");
        }
        int open = CONFIG.practiceMode ? practice().openCount() : ledger.openOrderCount();
        message("§7Open orders: §f" + open + "§7/§f" + CONFIG.maxOrders
                + " §7· Blacklisted: §f" + trader.blacklistCount());
        message("§7Daily limits: §f" + BazaarDailyLimit.summary() + " §7(resets 00:00 UTC)");
    }

    public static void report() {
        PracticeBook.Report r = practice().report();
        message("§6§l=== Practice report ===");
        message(String.format(Locale.ROOT,
                "§7Running §f%.2f h §7· buys placed §f%d §7filled §f%d §7cancelled §f%d §7· sells done §f%d §7· open §f%d",
                r.hours(), r.placedBuys(), r.filledBuys(), r.cancelledBuys(), r.completedSells(), r.open()));
        message("§7Profit after tax: §f" + ScreenUtil.formatCoins(r.realizedProfit())
                + " §7· tied up: §f" + ScreenUtil.formatCoins(r.invested()));
        message("§7Coins/hour: §areal " + ScreenUtil.formatCoins(r.realizedPerHour())
                + " §7vs §epredicted " + ScreenUtil.formatCoins(r.predictedPerHour())
                + (r.predictedPerHour() > 0 ? String.format(Locale.ROOT, " §7(%.0f%%)",
                        100.0 * r.realizedPerHour() / r.predictedPerHour()) : ""));
        if (ledger.tradesCompleted() > 0 || ledger.openOrderCount() > 0) {
            message("§6§l=== Live ledger ===");
            message("§7Completed: §f" + ledger.tradesCompleted() + " §7· open: §f"
                    + ledger.openOrderCount() + " §7· profit: §f" + ScreenUtil.formatCoins(ledger.realizedProfit()));
            for (Trade trade : ledger.all()) {
                if (trade.state == Trade.State.ABANDONED) continue;
                message("§7" + trade.displayName + " §8[" + trade.state.name() + "] §7" + trade.qty
                        + "x @ " + ScreenUtil.formatPrice(trade.buyPrice) + " §7cost §f" + ScreenUtil.formatCoins(trade.cost)
                        + (trade.revenue > 0 ? " §7revenue §f" + ScreenUtil.formatCoins(trade.revenue) : ""));
            }
        }
    }

    public static void message(String text) {
        Chat.message(text);
    }

    public static long sessionStartedAt() {
        return sessionStartedAt;
    }

    /** The Trader's market if it has one, else the last one a command fetched. */
    public static MarketData marketSnapshot() {
        Trader trader = Trader.get();
        MarketData fromTrader = trader == null ? null : trader.marketSnapshot();
        return fromTrader != null ? fromTrader : lastMarket;
    }

    public static void setMarketSnapshot(MarketData data) {
        lastMarket = data;
    }
}
