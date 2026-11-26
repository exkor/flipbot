package com.exkor.flipbot.flipper.trading;

import com.exkor.flipbot.BotLock;
import com.exkor.flipbot.Chat;
import com.exkor.flipbot.flipper.practice.PracticeBook;
import com.exkor.flipbot.flipper.FlipperConfig;
import com.exkor.flipbot.flipper.analysis.FlipAnalyzer;
import com.exkor.flipbot.flipper.data.BazaarApi;
import com.exkor.flipbot.flipper.data.BazaarPriceHistory;
import com.exkor.flipbot.flipper.data.ItemNames;
import com.exkor.flipbot.flipper.data.MarketData;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Trader {

    private static final long CHAT_MIN_INTERVAL_MS = 1300L;
    private static final long CLICK_MIN_INTERVAL_MS = 500L;
    private static final long RECONCILE_INTERVAL_MS = 2 * 60 * 1000L;
    private static final long STUCK_LIMIT_MS = 90_000L;
    private static final long SCAN_MIN_INTERVAL_MS = 15_000L;
    private static final long CLAIM_LIST_RETRY_MS = 30_000L;
    private static final long AH_PRIORITY_DEFAULT_MS = 60_000L;
    private static final long AH_PRIORITY_MIN_MS = 2_000L;
    private static final long AH_PRIORITY_MAX_MS = 60_000L;

    public enum State {
        IDLE, RUNNING, STOPPING
    }

    private static final class TraderStoppedException extends RuntimeException {
        private TraderStoppedException() {
            super("Trader stopped");
        }
    }

    private static volatile Trader instance;

    private final FlipperConfig cfg;
    private final OrderLedger ledger;
    private volatile State state = State.IDLE;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean ahPriorityActive;
    private volatile long ahPriorityUntil;
    private volatile long guiHoldUntil;
    private volatile boolean stopRequested;
    private Thread thread;
    private volatile MarketData market;
    private volatile long marketFetchedAt;
    private volatile long lastReconciliationAt;
    private volatile long lastChatAt;
    private volatile long lastClickAt;
    private volatile boolean traderOwnsGui;
    private volatile long lastBazaarFailAt;
    private volatile long lastOrdersFailAt;
    private volatile long lastLowBankrollAt;
    private volatile boolean manualClaimFlow;
    private volatile long flowStartAt;
    private volatile boolean lastScanHadOrders;
    private volatile long lastScannedSnapshot;
    private volatile long lastFullScanAt;
    private volatile long lastOrdersScanAt;
    private volatile boolean ordersBusy;
    private volatile boolean recentFillChat;
    private volatile boolean orderLimitReached;
    /** Hypixel said the inventory is full (or items went to the stash). */
    private volatile long inventoryFullAt;
    private volatile boolean stashPending;
    private int inventoryClearPasses;
    private long inventoryClearBlockedUntil;
    private long lastInventoryLogAt;
    private volatile boolean lastFailureItemSpecific;
    private volatile int lastScannedOrderCount;
    private volatile long lastDailyLimitLogAt;
    private volatile long heartbeat = System.currentTimeMillis();
    /** Stuck guard: no progress for this long while something keeps failing -> flipper off. */
    private static final long STUCK_STOP_MS = 60_000L;
    /** First failure since the last successful Bazaar action, 0 = none. */
    private volatile long troubleSince;
    private volatile String lastTrouble = "";
    /** A sell offer was started but Hypixel did not confirm one since, 0 = none. */
    private volatile long sellPendingSince;
    /** Hypixel: "Placing orders is on cooldown for up to 1 minute!" - no orders until then. */
    private volatile long orderCooldownUntil;
    private volatile long orderCooldownAt;
    /**
     * Prices of our own open sell offers per item (key: {@link #sellKey}), from the last order
     * scan plus the offers placed since. If the best offer is one of these, a new offer for the
     * same item takes the same price instead of undercutting ourselves, and offers that are all
     * on the best price are left alone instead of being merged.
     */
    private final Map<String, List<Double>> ownSellPrices = new java.util.concurrent.ConcurrentHashMap<>();
    /** Learned from the inventory: true = one item per slot (Summoning Eye ...), false = stacks. */
    private final Map<String, Boolean> singleStack = new java.util.concurrent.ConcurrentHashMap<>();
    private Thread supervisor;
    private final List<ScreenUtil.GuiOrder> lastScannedBuyOrders = new ArrayList<>();
    private final List<ScreenUtil.GuiOrder> lastScannedSellOffers = new ArrayList<>();
    private long lastLimitLogAt;
    private volatile List<ScreenUtil.GuiOrder> lastScannedOrders = List.of();
    private volatile List<PlanView> lastPlans = List.of();

    public record OrderView(String itemName, String productId, boolean sellSide,
                            double unitPrice, long amount, long filled) {
    }

    public record PlanView(String itemName, String productId, double buyPrice,
                           double sellPrice, double marginPercent,
                           long expectedProfit, double coinsPerHour) {
    }

    public static List<OrderView> ordersView() {
        List<OrderView> result = new ArrayList<>();
        Trader trader = instance;
        if (trader == null) {
            return result;
        }
        for (ScreenUtil.GuiOrder order : trader.lastScannedOrders) {
            if (order == null || order.itemName == null || order.itemName.isBlank()) {
                continue;
            }
            result.add(new OrderView(order.itemName, com.exkor.flipbot.flipper.data.ItemNames.productId(order.itemName), order.sellSide,
                    order.unitPrice, order.amount, order.filled));
        }
        return result;
    }

    public static List<PlanView> lastPlansView() {
        return Trader.instance == null ? List.of() : Trader.instance.lastPlans;
    }

    public static int scannedOrderCount() {
        Trader trader = instance;
        return trader == null ? 0 : trader.lastScannedOrderCount;
    }

    private void cachePlans(List<FlipAnalyzer.FlipPlan> plans) {
        List<PlanView> views = new ArrayList<>();
        if (plans != null) {
            for (FlipAnalyzer.FlipPlan plan : plans) {
                views.add(new PlanView(plan.displayName, plan.productId,
                        plan.buyPrice, plan.sellPrice, plan.marginPercent,
                        (long) plan.expectedProfit(), (long) plan.coinsPerHour));
            }
        }
        lastPlans = List.copyOf(views);
    }
    private final Map<String, MarketData.Product> productByName = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> scannedOrderProductIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Long> itemBlacklist = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> blacklistCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> sellRepriceCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    private Trader(FlipperConfig cfg, OrderLedger ledger) {
        this.cfg = cfg;
        this.ledger = ledger;
    }

    public static void init(FlipperConfig cfg, OrderLedger ledger) {
        instance = new Trader(cfg, ledger);
    }

    public static Trader get() {
        return instance;
    }

    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    public State state() {
        return state;
    }

    public boolean isPaused() {
        return paused;
    }

    public synchronized void start() {
        if (state != State.IDLE) {
            return;
        }
        running = true;
        paused = false;
        stopRequested = false;
        state = State.RUNNING;
        heartbeat = System.currentTimeMillis();
        troubleSince = 0;
        sellPendingSince = 0;
        thread = Thread.ofVirtual().name("skycofl-flipper").start(this::run);
        supervisor = Thread.ofVirtual().name("skycofl-flipper-supervisor").start(this::supervise);
    }

    private void supervise() {
        while (!stopRequested) {
            ScreenUtil.sleep(5_000);
            if (stopRequested || state != State.RUNNING) return;
            long nowMs = System.currentTimeMillis();
            boolean ahBusy = BotLock.otherBotBusy();
            if (paused || isAhPriority() || ahBusy || nowMs < orderCooldownUntil) {
                // Waiting for the AH (or Hypixel's order cooldown) is no lack of progress:
                // the minute starts again afterwards.
                if (troubleSince > 0) troubleSince = nowMs;
                if (sellPendingSince > 0) sellPendingSince = nowMs;
            } else if (sellPendingSince > 0 && nowMs - sellPendingSince > STUCK_STOP_MS) {
                recoverBecause("Selling has not worked for 1 minute");
            } else if (troubleSince > 0 && nowMs - troubleSince > STUCK_STOP_MS) {
                recoverBecause("No progress for 1 minute (" + lastTrouble + ")");
            }
            long idle = System.currentTimeMillis() - heartbeat;
            if (idle > 45_000L) {
                log("SUPERVISOR: trader for " + idle + "ms without a heartbeat. screen="
                        + ScreenUtil.currentScreen() + " market=" + (market != null ? market.products.size() : -1)
                        + " threadAlive=" + (thread != null && thread.isAlive()));
                closeOwnGuiOnly();
                traderOwnsGui = false;
                SignInput.clear();
                flowStartAt = 0;
                heartbeat = System.currentTimeMillis();
                if ((thread == null || !thread.isAlive()) && state == State.RUNNING) {
                    log("SUPERVISOR: trader thread dead - restarting");
                    stopRequested = false;
                    running = true;
                    thread = Thread.ofVirtual().name("skycofl-flipper").start(this::run);
                }
            }
        }
    }

    /** No progress for a minute: close our menu, reset the flags and timers and try again (no stop). */
    private void recoverBecause(String reason) {
        log("Recover: " + reason);
        chat("§6[Flipper] §e" + reason + " - closing the menu and trying again.");
        closeOwnGuiOnly();
        traderOwnsGui = false;
        SignInput.clear();
        flowStartAt = 0;
        long nowMs = System.currentTimeMillis();
        if (troubleSince > 0) troubleSince = nowMs;
        if (sellPendingSince > 0) sellPendingSince = nowMs;
        heartbeat = nowMs;
    }

    private void stopBecause(String reason) {
        log("Stop: " + reason);
        chat("\u00a76[Flipper] \u00a7c" + reason + " - Bazaar flipper stopped.");
        stop();
        ScreenUtil.onClient(() -> {
            if (net.minecraft.client.Minecraft.getInstance().gui.screen() != null
                    && !BotLock.otherBotBusy()) {
                ScreenUtil.closeCurrentScreen();
            }
        });
    }

    public synchronized void stop() {
        if (state == State.IDLE) {
            return;
        }
        state = State.STOPPING;
        stopRequested = true;
        ahPriorityActive = false;
        ahPriorityUntil = 0;
        SignInput.clear();
        traderOwnsGui = false;
        ScreenUtil.onClient(() -> {
            if (isBazaarScreen(ScreenUtil.currentScreen()) && !isAhPriority()) {
                ScreenUtil.closeCurrentScreen();
            }
        });
        // Stop requests also come from the GUI; never sleep on the render thread.
        if (thread != null) thread.interrupt();
        if (supervisor != null) supervisor.interrupt();
        chat("\u00a76[Flipper] \u00a7cStopping.");
    }

    public void setPaused(boolean value) {
        paused = value;
    }

    /** True while something else holds the menus and the bot must keep out. */
    public boolean isAhPriority() {
        if (System.currentTimeMillis() < guiHoldUntil) {
            return true;
        }
        if (!ahPriorityActive) {
            return false;
        }
        if (System.currentTimeMillis() < ahPriorityUntil) {
            return true;
        }
        ahPriorityActive = false;
        return false;
    }

    public void yieldToAhFlipper() {
        yieldToAhFlipper(AH_PRIORITY_DEFAULT_MS);
    }

    public void yieldToAhFlipper(long holdMs) {
        boolean wasActive = isAhPriority();
        long hold = Math.max(AH_PRIORITY_MIN_MS, Math.min(AH_PRIORITY_MAX_MS, holdMs));
        long until = System.currentTimeMillis() + hold;
        ahPriorityActive = true;
        if (until > ahPriorityUntil) {
            ahPriorityUntil = until;
        }
        if (!wasActive) {
            handOverGui();
            log("AH priority active (" + hold + "ms) - Bazaar menu closed and paused");
        }
    }

    /** Keeps the bot away from the menus for a while ("/bz" would close whatever is open). */
    public void holdGuiFor(long holdMs) {
        boolean wasActive = isAhPriority();
        guiHoldUntil = Math.max(guiHoldUntil, System.currentTimeMillis() + holdMs);
        if (!wasActive) {
            handOverGui();
            log("GUI hold active (" + holdMs + "ms) - Bazaar paused");
        }
    }

    /** Only when the priority starts: SignInput is shared, so clearing it on every refresh would break the other flow. */
    private void handOverGui() {
        traderOwnsGui = false;
        SignInput.clear();
        flowStartAt = 0;
        for (int i = 0; i < 3 && isBazaarScreen(ScreenUtil.currentScreen()); i++) {
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(10);
        }
    }

    /** A Bazaar menu is open (a sign does not count: it can belong to anyone). */
    public boolean hasBazaarContainer() {
        ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
        return isBazaarScreen(screen) && screen != ScreenUtil.BzScreen.SIGN;
    }

    public void releaseAhPriority() {
        if (!isAhPriority()) {
            ahPriorityActive = false;
            ahPriorityUntil = 0;
            return;
        }
        ahPriorityActive = false;
        ahPriorityUntil = 0;
        log("AH priority ended - Bazaar may continue");
    }

    public boolean hasBazaarGui() {
        return isBazaarScreen(ScreenUtil.currentScreen());
    }

    public boolean isGuiOwned() {
        return traderOwnsGui;
    }

    /** Closes Bazaar menus; signs stay open, since during a priority they belong to someone else. */
    public void closeBazaarGui() {
        for (int i = 0; i < 3 && hasBazaarContainer(); i++) {
            ScreenUtil.closeCurrentScreen();
            ScreenUtil.sleep(10);
        }
    }

    /** Self-healing: only ever a menu of the flipper itself, never someone else's. */
    private void closeOwnGuiOnly() {
        if (!isAhPriority() && isBazaarScreen(ScreenUtil.currentScreen())) {
            ScreenUtil.closeCurrentScreen();
        }
    }

    private static boolean isBazaarScreen(ScreenUtil.BzScreen screen) {
        return screen == ScreenUtil.BzScreen.BAZAAR
                || screen == ScreenUtil.BzScreen.SEARCH
                || screen == ScreenUtil.BzScreen.PRODUCT
                || screen == ScreenUtil.BzScreen.BUY_AMOUNT
                || screen == ScreenUtil.BzScreen.BUY_PRICE
                || screen == ScreenUtil.BzScreen.BUY_CONFIRM
                || screen == ScreenUtil.BzScreen.SELL_AMOUNT
                || screen == ScreenUtil.BzScreen.SELL_PRICE
                || screen == ScreenUtil.BzScreen.SELL_CONFIRM
                || screen == ScreenUtil.BzScreen.ORDERS
                || screen == ScreenUtil.BzScreen.ORDER_OPTIONS
                || screen == ScreenUtil.BzScreen.SIGN;
    }

    public MarketData marketSnapshot() {
        return market;
    }

    public boolean isBlacklisted(String productId) {
        Long expiry = itemBlacklist.get(productId);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() > expiry) {
            itemBlacklist.remove(productId);
            return false;
        }
        return true;
    }

    public void clearBlacklist() {
        itemBlacklist.clear();
        blacklistCounts.clear();
    }

    private void blacklistProduct(String productId, String displayName) {
        long blacklistMs = (long) Math.max(1.0, cfg.blacklistMinutes * 60_000.0);
        itemBlacklist.put(productId, System.currentTimeMillis() + blacklistMs);
        int count = blacklistCounts.merge(productId, 1, Integer::sum);
        if (count >= 3 && !cfg.blacklist.contains(productId)) {
            cfg.blacklist.add(productId);
            cfg.save();
            chat("\u00a76[Flipper] \u00a7cItem permanently blacklisted after repeated failures: \u00a7f" + displayName);
        }
    }

    /**
     * After a failed buy order. An interruption is not the item's fault (no strike); a menu hiccup skips the item
     * for now. Returns false if the order was only interrupted.
     */
    private boolean onBuyOrderFailed(FlipAnalyzer.FlipPlan plan) {
        if (isAhPriority()) {
            log("Buy order interrupted (AH has priority): " + plan.displayName);
            return false;
        }
        if (orderCooldownActive()) {
            // Hypixel's order cooldown, not the item: no blacklist.
            log("Buy order not placed (Bazaar cooldown): " + plan.displayName);
            return false;
        }
        if (lastFailureItemSpecific) {
            blacklistProduct(plan.productId, plan.displayName);
        } else {
            itemBlacklist.put(plan.productId, System.currentTimeMillis()
                    + (long) Math.max(1.0, cfg.blacklistMinutes * 60_000.0));
        }
        return true;
    }

    /** A new order counts as existing right away, the next scan can be 60 s away. */
    private void rememberPlacedOrder(FlipAnalyzer.FlipPlan plan) {
        scannedOrderProductIds.add(plan.productId);
        lastScannedOrderCount++;
        markProductSuccess(plan.productId);
    }

    /** The sign text is shared: during a priority it belongs to someone else. */
    private void setSign(String text) {
        if (!isAhPriority()) {
            SignInput.set(text);
        }
    }

    private void markProductSuccess(String productId) {
        if (productId != null) {
            blacklistCounts.remove(productId);
        }
    }

    public int blacklistCount() {
        return itemBlacklist.size();
    }

    public OrderLedger ledger() {
        return ledger;
    }

    private void chat(String message) {
        Chat.message(Component.literal(message));
    }

    private void log(String message) {
        System.out.println("[Flipper] " + message);
    }

    private void beat() {
        heartbeat = System.currentTimeMillis();
    }

    private void checkStop() {
        if (stopRequested || Thread.currentThread().isInterrupted()) {
            throw new TraderStoppedException();
        }
    }

    private boolean isStopRequested() {
        return stopRequested || Thread.currentThread().isInterrupted();
    }

    private boolean waitForScreenCancel(ScreenUtil.BzScreen target, long timeoutMs) {
        return ScreenUtil.waitForScreen(target, timeoutMs, this::isStopRequested);
    }

    private boolean waitForChatCancel(String contains, long timeoutMs) {
        // Small look-back: the chat line often arrives while the click that
        // caused it is still being verified.
        return BazaarChatListener.waitForChatSince(contains,
                System.currentTimeMillis() - 1500L, timeoutMs, this::isStopRequested);
    }

    /** Time of the last verified confirm click (Setup messages come right after it). */
    private volatile long lastConfirmClickAt;

    /** Waits for the chat confirmation of the last confirm click; it often arrives before this wait starts. */
    private boolean waitForConfirmChat(String contains, long timeoutMs) {
        long now = System.currentTimeMillis();
        long since = lastConfirmClickAt > 0 && now - lastConfirmClickAt < 15_000L
                ? lastConfirmClickAt - 250L : now - 1500L;
        // Hypixel's order cooldown answers instead of "Setup!": stop waiting right away.
        return BazaarChatListener.waitForChatSince(contains, since, timeoutMs,
                () -> isStopRequested() || orderCooldownAt >= since);
    }

    /** Hypixel's order cooldown is running (set from chat). */
    private boolean orderCooldownActive() {
        return System.currentTimeMillis() < orderCooldownUntil;
    }

    private static String sellKey(String itemName) {
        return ScreenUtil.cleanSpecials(itemName).toLowerCase();
    }

    /** Is {@code price} the price of one of our own open sell offers for this item? */
    private boolean ownsSellPrice(String itemName, double price) {
        List<Double> prices = ownSellPrices.get(sellKey(itemName));
        if (prices == null || price <= 0) {
            return false;
        }
        synchronized (prices) {
            for (double own : prices) {
                if (Math.abs(own - price) < 0.05) {
                    return true;
                }
            }
        }
        return false;
    }

    private void rememberOwnSellPrice(String itemName, double price) {
        if (price <= 0) {
            return;
        }
        List<Double> prices = ownSellPrices.computeIfAbsent(sellKey(itemName),
                k -> java.util.Collections.synchronizedList(new ArrayList<>()));
        prices.add(price);
    }

    /** Learns from the inventory whether this item stacks (call while the items are in it). */
    private void learnStacking(String itemName) {
        long[] stacks = ScreenUtil.inventoryStacks(itemName);
        if (stacks[1] > stacks[0]) {
            singleStack.put(sellKey(itemName), false);
        } else if (stacks[0] >= 2) {
            singleStack.put(sellKey(itemName), true);
        }
    }

    /** Inventory slots {@code qty} items need (unknown items count as stacking to 64). */
    private int slotsNeeded(String itemName, long qty) {
        if (qty <= 0) {
            return 0;
        }
        boolean single = Boolean.TRUE.equals(singleStack.get(sellKey(itemName)));
        return (int) Math.min(36, single ? qty : (qty + 63) / 64);
    }

    /**
     * Room for {@code qty} items coming back from a cancelled sell offer, with one slot to
     * spare. A cancelled offer returns all its items at once; what does not fit goes to the
     * stash and the inventory is full.
     */
    private boolean inventoryRoomFor(String itemName, long qty) {
        return ScreenUtil.freeInventorySlots() - 1 >= slotsNeeded(itemName, qty);
    }

    private void sleepOrStop(long ms) {
        long end = System.currentTimeMillis() + Math.max(1L, ms);
        while (System.currentTimeMillis() < end) {
            checkStop();
            long remaining = end - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            try {
                Thread.sleep(Math.min(remaining, 100L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                checkStop();
            }
        }
    }

    /** Start message: is item mode on, and which items it flips. */
    private String itemModeLine() {
        if (!cfg.onlyItemsMode) {
            return "§6[Flipper] §7Item mode §cOFF §7· flips the best Bazaar items (analyzer)";
        }
        java.util.List<String> items = cfg.onlyItems == null ? java.util.List.of() : cfg.onlyItems;
        if (items.isEmpty()) {
            return "§6[Flipper] §7Item mode §eON §7but the list is empty §8· §7flips the best Bazaar items (analyzer)";
        }
        String shown = items.size() <= 4 ? String.join(", ", items)
                : String.join(", ", items.subList(0, 4)) + " +" + (items.size() - 4);
        return "§6[Flipper] §7Item mode §aON §7· " + items.size() + " item"
                + (items.size() == 1 ? "" : "s") + ": §f" + shown;
    }

    private void jitterSleepOrStop(long base, long spread) {
        sleepOrStop(ScreenUtil.jitterAround(base, spread));
    }

    private void run() {
        chat("\u00a76[Flipper] \u00a7aBazaar flipper started \u00a77\u00b7 reserve \u00a7f" + ScreenUtil.formatCoins(cfg.reserveCoins) + " \u00a77\u00b7 max orders \u00a7f" + cfg.maxOrders + " \u00a77\u00b7 tax \u00a7f" + cfg.taxPercent + "%"
                + (cfg.maxCoinsPerItem > 0 ? " \u00a77\u00b7 max/item \u00a7f" + ScreenUtil.formatCoins(cfg.maxCoinsPerItem) : ""));
        chat(itemModeLine());
        long lastCycleAt = 0;
        long lastHeartbeatAt = System.currentTimeMillis();
        int watchdogStrikes = 0;
        long sessionStartAt = System.currentTimeMillis();
        long sessionMs = cfg.sessionMinutes * 60_000L;
        long pauseMs = cfg.pauseMinutes * 60_000L;
        while (!stopRequested) {
            try {
                checkStop();
                heartbeat = System.currentTimeMillis();
                long heartbeatNow = System.currentTimeMillis();
                if (heartbeatNow - lastHeartbeatAt > 30_000L) {
                    lastHeartbeatAt = heartbeatNow;
                    watchdogStrikes++;
                    log("WATCHDOG: screen=" + ScreenUtil.currentScreen() + " paused=" + paused
                            + " onSkyBlock=" + ScreenUtil.onSkyBlock() + " combat=" + ScreenUtil.inCombatOrDead()
                            + " market=" + (market != null ? market.products.size() : -1)
                            + " ownsGui=" + traderOwnsGui + " stop=" + stopRequested);
                    if (watchdogStrikes >= 3 && !isAhPriority()) {
                        log("WATCHDOG: self-healing - closing the menu and resetting flags");
                        closeOwnGuiOnly();
                        traderOwnsGui = false;
                        SignInput.clear();
                        flowStartAt = 0;
                        watchdogStrikes = 0;
                    }
                }
                if (sessionMs > 0 && System.currentTimeMillis() - sessionStartAt >= sessionMs) {
                    cleanupGui();
                    chat("\u00a76[Flipper] \u00a77Session ended - pause " + cfg.pauseMinutes + " min.");
                    pauseInterruptibly(pauseMs);
                    if (stopRequested) {
                        break;
                    }
                    sessionStartAt = System.currentTimeMillis();
                    chat("\u00a76[Flipper] \u00a7aNew session started.");
                    continue;
                }
                if (!waitUntilSafe()) {
                    continue;
                }
                refreshMarketIfStale();
                MarketData data = market;
                if (data == null) {
                    sleepOrStop(5000);
                    continue;
                }
                long now = System.currentTimeMillis();
                boolean urgent = recentFillChat
                        || !ledger.inState(Trade.State.FILLED).isEmpty()
                        || !ledger.inState(Trade.State.SOLD).isEmpty()
                        || !ledger.inState(Trade.State.CLAIMED).isEmpty();
                if (now - lastCycleAt >= cfg.cycleIntervalSeconds * 1000L || urgent) {
                    lastCycleAt = now;
                    runTradingCycle(data);
                }
                watchdogStrikes = 0;
                lastHeartbeatAt = System.currentTimeMillis();
                jitterSleepOrStop(1000, 500);
            } catch (TraderStoppedException stopped) {
                break;
            } catch (InterruptedException e) {
                stopRequested = true;
            } catch (Exception e) {
                System.out.println("[Flipper] cycle error: " + e.getMessage());
                e.printStackTrace();
                failPause("Unexpected error: " + e.getMessage());
            }
        }
        running = false;
        state = State.IDLE;
        cleanupGui();
    }

    private void runTradingCycle(MarketData data) {
        beat();
        if (isAhPriority()) {
            closeBazaarGui();
            return;
        }
        if (orderCooldownActive()) {
            // Every order placed now fails anyway; wait for Hypixel's cooldown to end.
            logLimit("Bazaar cooldown active - waiting "
                    + (orderCooldownUntil - System.currentTimeMillis()) / 1000 + "s");
            cleanupGui();
            return;
        }
        long purse = ScreenUtil.readPurse();
        for (int attempt = 0; attempt < 3 && purse < 0; attempt++) {
            sleepOrStop(400);
            purse = ScreenUtil.readPurse();
        }
        if (purse < 0) {
            log("Purse unreadable - cycle skipped");
            return;
        }
        log("Cycle: purse=" + purse + " Bankroll=" + (purse - cfg.reserveCoins)
                + " offeneOrders=" + lastScannedOrderCount
                + " (Ledger: " + ledger.openOrderCount() + ")");
        if (cfg.practiceMode) {
            runPracticeCycle(data, purse);
            return;
        }
        if (clearFullInventory(data)) {
            cleanupGui();
            return;
        }
        boolean ordersBusy = prioritizeExistingOrders(data);
        beat();
        if (stopRequested) {
            return;
        }
        if (!ordersBusy) {
            boolean placed = craftStep(data, purse) || placeNewBuyOrders(data, purse);
            if (!placed) {
                replaceWorstOrderIfWorthwhile(data, purse);
            }
        } else {
            log("Orders not up to date yet - no new buys");
        }
        maybeReconcile();
        beat();
        cleanupGui();
    }

    /**
     * Practice mode: never touches real orders or the inventory (no claim, cancel, flip, relist,
     * inventory clearing or reconcile, so the player's own orders are safe). Only new buy orders are walked
     * through the real menus up to the confirm screen; the rest is simulated by the PracticeBook.
     */
    private void runPracticeCycle(MarketData data, long purse) {
        PracticeBook book = com.exkor.flipbot.flipper.FlipperMod.practice();
        long now = System.currentTimeMillis();
        if (book.lastTickAt == 0 || now - book.lastTickAt >= 60_000L) {
            // Live cancels an outbid buy order at the next scan, so practice does too.
            book.tick(data, cfg.taxPercent, 1, now);
            com.exkor.flipbot.flipper.FlipperMod.savePractice();
        }
        if (!craftStep(data, purse)) {
            placeNewBuyOrders(data, purse);
        }
        beat();
        cleanupGui();
    }

    private void cleanupGui() {
        if (isAhPriority()) {
            // The GUI and the sign text belong to someone else now.
            traderOwnsGui = false;
            flowStartAt = 0;
            closeBazaarGui();
            return;
        }
        if (traderOwnsGui) {
            traderOwnsGui = false;
            ScreenUtil.closeAndWait(250);
        }
        SignInput.clear();
        flowStartAt = 0;
    }

    private boolean stuck() {
        return flowStartAt > 0 && System.currentTimeMillis() - flowStartAt > STUCK_LIMIT_MS;
    }

    private void pauseInterruptibly(long totalMs) {
        checkStop();
        long deadline = System.currentTimeMillis() + totalMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            sleepOrStop(500);
        }
    }

    private void failPause(String reason) {
        if (isAhPriority()) {
            log("Interrupted (AH has priority): " + reason);
            cleanupGui();
            return;
        }
        log("Error - 5 s pause: " + reason);
        if (troubleSince == 0) {
            troubleSince = System.currentTimeMillis();
        }
        lastTrouble = reason;
        cleanupGui();
        ScreenUtil.BzScreen current = ScreenUtil.currentScreen();
        if (current == ScreenUtil.BzScreen.BAZAAR || current == ScreenUtil.BzScreen.PRODUCT
                || current == ScreenUtil.BzScreen.SEARCH || current == ScreenUtil.BzScreen.BUY_AMOUNT
                || current == ScreenUtil.BzScreen.BUY_PRICE || current == ScreenUtil.BzScreen.BUY_CONFIRM
                || current == ScreenUtil.BzScreen.SELL_AMOUNT || current == ScreenUtil.BzScreen.SELL_PRICE
                || current == ScreenUtil.BzScreen.SELL_CONFIRM || current == ScreenUtil.BzScreen.ORDERS
                || current == ScreenUtil.BzScreen.ORDER_OPTIONS || current == ScreenUtil.BzScreen.SIGN) {
            ScreenUtil.closeCurrentScreen();
        }
        pauseInterruptibly(ScreenUtil.jitterAround(5000, 2000));
    }

    private boolean waitUntilSafe() throws InterruptedException {
        checkStop();
        long lastHeartbeatAt = System.currentTimeMillis();
        int watchdogStrikes = 0;
        while (!stopRequested) {
            heartbeat = System.currentTimeMillis();
            long heartbeatNow = System.currentTimeMillis();
            if (isAhPriority()) {
                // Waiting for a priority is not a hang: don't let the watchdog close its menus.
                lastHeartbeatAt = heartbeatNow;
                watchdogStrikes = 0;
                closeBazaarGui();
                sleepOrStop(100);
                continue;
            }
            if (heartbeatNow - lastHeartbeatAt > 30_000L) {
                lastHeartbeatAt = heartbeatNow;
                watchdogStrikes++;
                log("WATCHDOG(wait): screen=" + ScreenUtil.currentScreen() + " paused=" + paused
                        + " onSkyBlock=" + ScreenUtil.onSkyBlock() + " combat=" + ScreenUtil.inCombatOrDead()
                        + " market=" + (market != null ? market.products.size() : -1)
                        + " ownsGui=" + traderOwnsGui + " stop=" + stopRequested);
                if (watchdogStrikes >= 3) {
                    log("WATCHDOG: self-healing - closing the menu and resetting flags");
                    closeOwnGuiOnly();
                    traderOwnsGui = false;
                    SignInput.clear();
                    flowStartAt = 0;
                    watchdogStrikes = 0;
                }
            }
            if (paused) {
                sleepOrStop(500);
                continue;
            }
            if (!ScreenUtil.onSkyBlock()) {
                sleepOrStop(1000);
                continue;
            }
            if (ScreenUtil.inCombatOrDead()) {
                sleepOrStop(1000);
                continue;
            }
            ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
            if (traderOwnsGui) {
                traderOwnsGui = false;
                ScreenUtil.closeAndWait(250);
                continue;
            }
            if (screen == ScreenUtil.BzScreen.BAZAAR || screen == ScreenUtil.BzScreen.PRODUCT
                    || screen == ScreenUtil.BzScreen.SEARCH || screen == ScreenUtil.BzScreen.ORDERS
                    || screen == ScreenUtil.BzScreen.ORDER_OPTIONS || screen == ScreenUtil.BzScreen.BUY_AMOUNT
                    || screen == ScreenUtil.BzScreen.BUY_PRICE || screen == ScreenUtil.BzScreen.BUY_CONFIRM
                    || screen == ScreenUtil.BzScreen.SELL_AMOUNT || screen == ScreenUtil.BzScreen.SELL_PRICE
                    || screen == ScreenUtil.BzScreen.SELL_CONFIRM || screen == ScreenUtil.BzScreen.SIGN) {
                log("Closed a leftover Bazaar menu: " + screen);
                ScreenUtil.closeAndWait(250);
                continue;
            }
            if (screen == ScreenUtil.BzScreen.NONE) {
                return true;
            }
            sleepOrStop(250);
        }
        return false;
    }

    private void refreshMarketIfStale() {
        long age = System.currentTimeMillis() - marketFetchedAt;
        if (market != null && age < cfg.apiRefreshSeconds * 1000L) {
            return;
        }
        beat();
        MarketData fresh = BazaarApi.fetch();
        beat();
        if (fresh != null && !fresh.products.isEmpty()) {
            market = fresh;
            marketFetchedAt = System.currentTimeMillis();
            com.exkor.flipbot.flipper.data.UndercutTracker.update(fresh);
        }
    }

    private void sendCommand(String command) {
        checkStop();
        if (isAhPriority()) {
            return;
        }
        long now = System.currentTimeMillis();
        long wait = lastChatAt + CHAT_MIN_INTERVAL_MS - now;
        if (wait > 0) {
            sleepOrStop(wait + 150);
        }
        ScreenUtil.onClient(() -> {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client.player != null && client.getConnection() != null) {
                client.getConnection().sendCommand(command);
            }
        });
        lastChatAt = System.currentTimeMillis();
    }

    private void click(int slot, int button) {
        checkStop();
        if (isAhPriority()) {
            return;
        }
        if (stuck()) {
            log("Stuck - forcing the menu closed (Esc)");
            cleanupGui();
            return;
        }
        long now = System.currentTimeMillis();
        long wait = lastClickAt + CLICK_MIN_INTERVAL_MS - now;
        if (wait > 0) {
            sleepOrStop(wait + 100);
        }
        String name = ScreenUtil.slotName(slot);
        log("Clicking slot " + slot + " (button " + button + ") Item: " + name);
        ScreenUtil.clickSlot(slot, button);
        lastClickAt = System.currentTimeMillis();
    }

    private void clickDelay() {
        checkStop();
        long now = System.currentTimeMillis();
        long wait = lastClickAt + CLICK_MIN_INTERVAL_MS - now;
        if (wait > 0) {
            jitterSleepOrStop(wait + 100, 150);
        }
        lastClickAt = System.currentTimeMillis();
    }

    private boolean screenGone() {
        ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
        if (screen == ScreenUtil.BzScreen.NONE) {
            traderOwnsGui = false;
            log("Menu closed from outside - aborting this step");
            return true;
        }
        return false;
    }

    private boolean clickByName(String text, int button, long timeoutMs, String what) {
        checkStop();
        if (cfg.practiceMode && (text.equals("Cancel") || text.startsWith("Flip Order"))) {
            log("PRACTICE: click refused (" + what + ")");
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            if (isAhPriority()) {
                return false;
            }
            if (stuck() || screenGone()) {
                cleanupGui();
                return false;
            }
            clickDelay();
            if (ScreenUtil.clickIfNameContains(text, button)) {
                log("Click verified: " + what);
                return true;
            }
            sleepOrStop(400);
        }
        log("Click target not found: " + what);
        return false;
    }

    private boolean clickExactName(String text, int button, long timeoutMs, String what) {
        checkStop();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            if (isAhPriority()) {
                return false;
            }
            if (stuck() || screenGone()) {
                cleanupGui();
                return false;
            }
            clickDelay();
            if (ScreenUtil.clickIfNameExact(text, button)) {
                log("Click verified (exact): " + what);
                return true;
            }
            sleepOrStop(400);
        }
        log("Exact click target not found: " + what);
        return false;
    }

    private boolean clickSign(long timeoutMs, String what) {
        checkStop();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            if (isAhPriority()) {
                return false;
            }
            if (stuck() || screenGone()) {
                cleanupGui();
                return false;
            }
            clickDelay();
            if (ScreenUtil.clickSignSlot(0)) {
                log("Click verified: " + what);
                return true;
            }
            sleepOrStop(400);
        }
        log("Sign not found: " + what);
        return false;
    }

    private boolean clickOrder(String displayName, boolean sellSide, int button, long timeoutMs, String what) {
        checkStop();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            if (isAhPriority()) {
                return false;
            }
            if (stuck() || screenGone()) {
                cleanupGui();
                return false;
            }
            clickDelay();
            if (ScreenUtil.clickOrderEntry(displayName, sellSide, button)) {
                log("Click verified: " + what);
                return true;
            }
            sleepOrStop(400);
        }
        log("Order entry not found: " + what);
        return false;
    }

    private boolean clickConfirmAtomic(String acceptContains, long timeoutMs) {
        checkStop();
        if (cfg.practiceMode) {
            log("PRACTICE: confirm click refused (" + acceptContains + ")");
            return false;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            if (isAhPriority()) {
                return false;
            }
            if (stuck() || screenGone()) {
                cleanupGui();
                return false;
            }
            clickDelay();
            long clickAt = System.currentTimeMillis();
            if (ScreenUtil.clickConfirmItem(acceptContains)) {
                lastConfirmClickAt = clickAt;
                log("Click verified: confirm (" + acceptContains + ")");
                return true;
            }
            sleepOrStop(400);
        }
        log("Confirm button not found: " + acceptContains);
        return false;
    }

    private boolean openBazaar() {
        if (isAhPriority()) {
            return false;
        }
        ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
        if (screen == ScreenUtil.BzScreen.NONE) {
            long now = System.currentTimeMillis();
            if (now - lastBazaarFailAt < 15_000L) {
                return false;
            }
            sendCommand("bz");
            if (waitForScreenCancel(ScreenUtil.BzScreen.BAZAAR, 10_000)) {
                traderOwnsGui = true;
                flowStartAt = System.currentTimeMillis();
                sleepOrStop(400);
                return true;
            }
            if (waitForScreenCancel(ScreenUtil.BzScreen.PRODUCT, 3000)) {
                traderOwnsGui = true;
                flowStartAt = System.currentTimeMillis();
                sleepOrStop(400);
                return true;
            }
            if (isAhPriority()) {
                return false;
            }
            lastBazaarFailAt = now;
            chat("\u00a76[Flipper] \u00a7cCould not open Bazaar (Booster Cookie active?).");
            return false;
        }
        if (screen == ScreenUtil.BzScreen.BAZAAR || screen == ScreenUtil.BzScreen.PRODUCT
                || screen == ScreenUtil.BzScreen.SEARCH) {
            traderOwnsGui = true;
            return true;
        }
        if (screen == ScreenUtil.BzScreen.ORDERS || screen == ScreenUtil.BzScreen.ORDER_OPTIONS) {
            ScreenUtil.closeCurrentScreen();
            sleepOrStop(600);
            return openBazaar();
        }
        log("Another screen is open - waiting for it to close: " + screen);
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            sleepOrStop(1000);
            screen = ScreenUtil.currentScreen();
            if (screen == ScreenUtil.BzScreen.NONE) {
                return openBazaar();
            }
            if (screen == ScreenUtil.BzScreen.BAZAAR || screen == ScreenUtil.BzScreen.PRODUCT
                    || screen == ScreenUtil.BzScreen.SEARCH) {
                traderOwnsGui = true;
                return true;
            }
            if (screen == ScreenUtil.BzScreen.ORDERS || screen == ScreenUtil.BzScreen.ORDER_OPTIONS) {
                ScreenUtil.closeCurrentScreen();
                sleepOrStop(600);
            }
        }
        log("Another screen stayed open");
        return false;
    }

    private boolean openOrdersGui() {
        if (isAhPriority()) {
            return false;
        }
        ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
        if (screen == ScreenUtil.BzScreen.NONE) {
            long now = System.currentTimeMillis();
            if (now - lastOrdersFailAt < 15_000L) {
                return false;
            }
            sendCommand("managebazaarorders");
            if (waitForScreenCancel(ScreenUtil.BzScreen.ORDERS, 12_000)) {
                lastOrdersFailAt = 0;
                traderOwnsGui = true;
                flowStartAt = System.currentTimeMillis();
                sleepOrStop(400);
                return true;
            }
            if (isAhPriority()) {
                return false;
            }
            lastOrdersFailAt = now;
            chat("\u00a76[Flipper] \u00a7cCould not open Orders GUI.");
            return false;
        }
        if (screen == ScreenUtil.BzScreen.ORDERS) {
            traderOwnsGui = true;
            return true;
        }
        if (screen == ScreenUtil.BzScreen.BAZAAR || screen == ScreenUtil.BzScreen.PRODUCT
                || screen == ScreenUtil.BzScreen.SEARCH || screen == ScreenUtil.BzScreen.ORDER_OPTIONS) {
            ScreenUtil.closeCurrentScreen();
            sleepOrStop(600);
            return openOrdersGui();
        }
        log("Another screen is open - waiting for it to close: " + screen);
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            sleepOrStop(1000);
            screen = ScreenUtil.currentScreen();
            if (screen == ScreenUtil.BzScreen.NONE) {
                return openOrdersGui();
            }
            if (screen == ScreenUtil.BzScreen.ORDERS) {
                traderOwnsGui = true;
                return true;
            }
        }
        log("Another screen stayed open");
        return false;
    }

    private boolean searchProduct(String displayName, String productId) {
        String remembered = cfg.searchNameOverrides.get(displayName);
        if (remembered != null) {
            log("Saved search name: " + remembered);
            if (searchProductOnce(remembered, productId)) {
                return true;
            }
        }
        if (searchProductOnce(displayName, productId)) {
            return true;
        }
        String cleaned = displayName.replaceAll("[^\\p{L}\\p{N}\\s]", "").trim();
        if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase(displayName)) {
            log("2nd search attempt without special characters: " + cleaned);
            if (searchProductOnce(cleaned, productId)) {
                cfg.searchNameOverrides.put(displayName, cleaned);
                cfg.save();
                log("Search name remembered: " + displayName + " -> " + cleaned);
                return true;
            }
        }
        return false;
    }

    private boolean productPageMatches(String productId, String fallbackName) {
        String actual = ScreenUtil.productName();
        if (actual == null || actual.isBlank()) {
            log("Product name unreadable - aborting to be safe");
            return false;
        }
        String expected = productId != null ? ItemNames.displayName(productId) : fallbackName;
        if (!ScreenUtil.sameName(actual, expected)) {
            log("Wrong product page: expected='" + expected + "' actual='" + actual + "'");
            return false;
        }
        return true;
    }

    private boolean actualProductPriceSane(String productId, double buyPrice, double sellPrice) {
        BazaarPriceHistory.Snapshot history = BazaarPriceHistory.lookup(productId);
        if (cfg.requireHistoricalPrice) {
            if (history == null || history.volume < cfg.minHistoryVolume) {
                log("Weekly avg check: not enough history for " + productId
                        + " volume=" + (history != null ? history.volume : -1));
                return false;
            }
            double reference = history.referencePrice();
            if (reference <= 0) {
                log("Weekly avg check: invalid reference for " + productId);
                return false;
            }
            double buyDeviation = Math.abs(buyPrice - reference) / reference * 100.0;
            double sellDeviation = Math.abs(sellPrice - reference) / reference * 100.0;
            if (buyDeviation > cfg.maxHistoricalDeviationPercent
                    || sellDeviation > cfg.maxHistoricalDeviationPercent) {
                log(String.format(java.util.Locale.US,
                        "Weekly-Avg-Check FAIL %s: ref=%.1f buy=%.1f(%.1f%%) sell=%.1f(%.1f%%) max=%.1f%%",
                        productId, reference, buyPrice, buyDeviation, sellPrice, sellDeviation,
                        cfg.maxHistoricalDeviationPercent));
                return false;
            }
            log(String.format(java.util.Locale.US,
                    "Weekly-Avg-Check PASS %s: ref=%.1f buy=%.1f(%.1f%%) sell=%.1f(%.1f%%)",
                    productId, reference, buyPrice, buyDeviation, sellPrice, sellDeviation));
        }

        MarketData current = market;
        MarketData.Product actual = current != null ? current.products.get(productId) : null;
        if (actual == null) {
            log("Live price check: product missing: " + productId);
            return false;
        }
        double liveBuyReference = actual.avgSellPrice > 0 ? actual.avgSellPrice : actual.bestAsk;
        double liveSellReference = actual.avgBuyPrice > 0 ? actual.avgBuyPrice : actual.bestBid;
        if (liveBuyReference > 0) {
            double deviation = Math.abs(buyPrice - liveBuyReference) / liveBuyReference * 100.0;
            if (deviation > cfg.maxHistoricalDeviationPercent) {
                log(String.format(java.util.Locale.US,
                        "Live price check FAIL %s buy: ref=%.1f buy=%.1f (%.1f%%)",
                        productId, liveBuyReference, buyPrice, deviation));
                return false;
            }
        }
        if (liveSellReference > 0) {
            double deviation = Math.abs(sellPrice - liveSellReference) / liveSellReference * 100.0;
            if (deviation > cfg.maxHistoricalDeviationPercent) {
                log(String.format(java.util.Locale.US,
                        "Live price check FAIL %s sell: ref=%.1f sell=%.1f (%.1f%%)",
                        productId, liveSellReference, sellPrice, deviation));
                return false;
            }
        }
        return true;
    }

    private boolean searchProductOnce(String displayName, String productId) {
        boolean signSeen = false;
        for (int attempt = 0; attempt < 2 && !signSeen && !stopRequested; attempt++) {
            setSign(displayName);
            if (!clickByName("Search", 0, 4000, "Search-Button")) {
                log("Search slot not found. " + ScreenUtil.screenDebugInfo());
                return false;
            }
            long signDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < signDeadline) {
                ScreenUtil.BzScreen current = ScreenUtil.currentScreen();
                if (current == ScreenUtil.BzScreen.PRODUCT) {
                    if (productPageMatches(productId, displayName)) {
                        return true;
                    }
                    cleanupGui();
                    return false;
                }
                if (current == ScreenUtil.BzScreen.SIGN) {
                    signSeen = true;
                    break;
                }
                sleepOrStop(120);
            }
        }
        if (!signSeen) {
            log("Search sign did not open. " + ScreenUtil.screenDebugInfo());
            return false;
        }
        long deadline = System.currentTimeMillis() + 3500;
        int clicks = 0;
        while (System.currentTimeMillis() < deadline) {
            ScreenUtil.BzScreen current = ScreenUtil.currentScreen();
            if (current == ScreenUtil.BzScreen.PRODUCT) {
                if (productPageMatches(productId, displayName)) {
                    return true;
                }
                cleanupGui();
                return false;
            }
            if (stuck()) {
                cleanupGui();
                return false;
            }
            if (clicks < 2 && current != ScreenUtil.BzScreen.NONE
                    && current != ScreenUtil.BzScreen.SIGN
                    && current != ScreenUtil.BzScreen.UNKNOWN) {
                long clickStart = System.currentTimeMillis();
                if (clickExactName(displayName, 0, 2500, "Search result: " + displayName)) {
                    clicks++;
                    sleepOrStop(700);
                    for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(clickStart)) {
                        if (entry.text.toLowerCase().contains("mutation")) {
                            if (productId != null && !cfg.blacklist.contains(productId)) {
                                cfg.blacklist.add(productId);
                                cfg.save();
                            }
                            chat("\u00a76[Flipper] \u00a7cNo Bazaar item - permanently blacklisted: \u00a7f" + displayName);
                            cleanupGui();
                            return false;
                        }
                    }
                    sleepOrStop(600);
                    continue;
                }
            }
            sleepOrStop(200);
        }
        log("Product page not reached after the search. " + ScreenUtil.screenDebugInfo());
        return false;
    }

    private boolean placeBuyOrder(Trade trade, FlipAnalyzer.FlipPlan plan) {
        checkStop();
        if (BazaarDailyLimit.reached()) {
            return false;
        }
        beat();
        lastFailureItemSpecific = false;
        log("Placing buy order: " + plan.qty + "x " + plan.displayName + " @ " + plan.buyPrice);
        if (!openBazaar()) {
            log("openBazaar failed");
            return false;
        }
        if (!searchProduct(plan.displayName, plan.productId)) {
            log("searchProduct failed: " + plan.displayName);
            // With the menu gone, this says nothing about the item.
            lastFailureItemSpecific = !isAhPriority()
                    && ScreenUtil.currentScreen() != ScreenUtil.BzScreen.NONE;
            cleanupGui();
            return false;
        }
        if (ScreenUtil.currentScreen() != ScreenUtil.BzScreen.PRODUCT) {
            log("Not on the product page before the buy button. " + ScreenUtil.screenDebugInfo());
            cleanupGui();
            return false;
        }
        if (!productPageMatches(plan.productId, plan.displayName)) {
            chat("\u00a76[Flipper] \u00a7cWrong product detected - purchase cancelled: \u00a7f" + plan.displayName);
            lastFailureItemSpecific = true;
            cleanupGui();
            return false;
        }
        String actualId = ItemNames.productId(ScreenUtil.productName());
        if (!actualProductPriceSane(actualId, plan.buyPrice, plan.sellPrice)) {
            chat("\u00a76[Flipper] \u00a7cWeekly average check failed - purchase cancelled: \u00a7f" + plan.displayName);
            lastFailureItemSpecific = true;
            cleanupGui();
            return false;
        }
        if (!clickByName("Create Buy Order", 0, 5000, "Create Buy Order")) {
            log("Create Buy Order button not found");
            cleanupGui();
            return false;
        }
        if (!waitForScreenCancel(ScreenUtil.BzScreen.BUY_AMOUNT, 6000)) {
            log("BUY_AMOUNT screen not reached");
            cleanupGui();
            return false;
        }
        setSign(String.valueOf(plan.qty));
        if (!clickSign(5000, "Amount sign")) {
            log("Amount sign not found");
            cleanupGui();
            return false;
        }
        if (!waitForScreenCancel(ScreenUtil.BzScreen.BUY_PRICE, 6000)) {
            log("BUY_PRICE screen not reached");
            cleanupGui();
            return false;
        }
        sleepOrStop(ScreenUtil.jitter(400));
        setSign(ScreenUtil.formatPrice(plan.buyPrice));
        if (!clickSign(5000, "Price sign")) {
            log("Price sign not found");
            cleanupGui();
            return false;
        }
        if (!waitForScreenCancel(ScreenUtil.BzScreen.BUY_CONFIRM, 6000)) {
            log("BUY_CONFIRM screen not reached");
            cleanupGui();
            return false;
        }
        sleepOrStop(ScreenUtil.jitter(400));
        if (cfg.practiceMode) {
            // Practice: the menus worked up to the last click; back out without placing the order.
            log("PRACTICE: confirm screen reached for " + plan.qty + "x " + plan.displayName
                    + " @ " + plan.buyPrice + " - backing out");
            beat();
            cleanupGui();
            return true;
        }
        if (!clickConfirmAtomic("Buy Order", 8000)) {
            log("Confirm button not found");
            cleanupGui();
            return false;
        }
        if (!waitForConfirmChat("Buy Order Setup!", 12_000)) {
            log("No 'Buy Order Setup!' confirmation in chat");
            cleanupGui();
            return false;
        }
        trade.cost = plan.qty * plan.buyPrice;
        trade.buyPrice = plan.buyPrice;
        trade.sellPrice = plan.sellPrice;
        beat();
        cleanupGui();
        return true;
    }

    private boolean placeNewBuyOrders(MarketData data, long purse) {
        if (waitingForBatchToSell()) {
            return false;
        }
        if (BazaarDailyLimit.reached()) {
            long now = System.currentTimeMillis();
            if (now - lastDailyLimitLogAt > 60_000L) {
                lastDailyLimitLogAt = now;
                chat("\u00a76[Flipper] \u00a7cDaily Bazaar limit reached \u00a77("
                        + BazaarDailyLimit.summary()
                        + "). No new buy orders until 00:00 UTC.");
            }
            return false;
        }
        if (orderLimitReached) {
            log("Order limit reached - waiting for claim/cancel, no new buys");
            return false;
        }
        if (BazaarLimits.sessionCapReached(cfg)) {
            if (BazaarLimits.announceCap()) {
                chat("§6[Flipper] §eSession limit "
                        + ScreenUtil.formatCoins(cfg.maxCoinsPerSessionMillions * 1_000_000.0)
                        + " in sell offers reached - no new buy orders, only selling.");
            }
            return false;
        }
        PracticeBook practice = cfg.practiceMode ? com.exkor.flipbot.flipper.FlipperMod.practice() : null;
        double bankroll = Math.max(0, purse - cfg.reserveCoins
                - (practice != null ? practice.investedCoins() : 0));
        double investRoom = investRoom();
        if (investRoom <= 0) {
            if (cfg.tradeWithProfitOnly) {
                double profit = com.exkor.flipbot.flipper.FlipperMod.profitMade();
                long now = System.currentTimeMillis();
                if (now - lastBudgetChatAt > 10 * 60_000L) {
                    lastBudgetChatAt = now;
                    chat(profit <= 0
                            ? "§6[Flipper] §7No profit made yet - with §fTrade only with profit§7 on, the bot has nothing to trade with."
                            : "§6[Flipper] §7All §f" + ScreenUtil.formatCoins(profit)
                                    + "§7 of profit is in orders - waiting for them to sell.");
                }
                logLimit("Profit budget used up (" + ScreenUtil.formatCoins(profit) + ") - no new buys");
            } else {
                logLimit("Max invested reached ("
                        + ScreenUtil.formatCoins(cfg.maxInvestedMillions * 1_000_000.0) + ") - no new buys");
            }
            return false;
        }
        bankroll = Math.min(bankroll, investRoom);
        if (bankroll <= 0) {
            long now = System.currentTimeMillis();
            if (now - lastLowBankrollAt > 10 * 60 * 1000L) {
                lastLowBankrollAt = now;
                chat("\u00a76[Flipper] \u00a7cNot enough coins \u00a77\u00b7 purse \u00a7f" + ScreenUtil.formatCoins(purse) + " \u00a77\u00b7 reserve \u00a7f" + ScreenUtil.formatCoins(cfg.reserveCoins));
            }
            return false;
        }
        int open = practice != null ? practice.openCount() : lastScannedOrderCount;
        int availableSlots = Math.max(0, cfg.maxOrders - open);
        if (availableSlots <= 0) {
            return false;
        }
        Set<String> existing = new HashSet<>();
        for (String id : itemBlacklist.keySet()) {
            if (isBlacklisted(id)) {
                existing.add(id);
            }
        }
        existing.addAll(scannedOrderProductIds);
        if (practice != null) {
            existing.addAll(practice.openProductIds());
        }
        List<FlipAnalyzer.FlipPlan> plans;
        if (BazaarLimits.itemModeActive(cfg)) {
            // Item mode: no analysis, just buy the listed items (selling runs as usual).
            plans = itemModePlans(data, bankroll, availableSlots, existing);
        } else {
            existing.addAll(BazaarLimits.excludedByItemMode(cfg, data));
            plans = FlipAnalyzer.compute(data, cfg, bankroll, existing, availableSlots, this::isStopRequested, 3000L);
        }
        cachePlans(plans);
        log("New buys: " + plans.size() + " candidates, bankroll=" + bankroll + ", Slots=" + availableSlots);
        if (plans.isEmpty()) {
            explainNoPlans(bankroll);
        }
        int placed = 0;
        double plannedSpend = 0;
        for (FlipAnalyzer.FlipPlan plan : plans) {
            if (placed >= cfg.maxNewOrdersPerCycle || stopRequested) {
                break;
            }
            double cost = plan.qty * plan.buyPrice;
            if (plannedSpend + cost > investRoom) {
                continue; // would go over "max invested"
            }
            plannedSpend += cost;
            if (practice != null) {
                // Practice: a throwaway Trade that never enters the ledger.
                Trade dry = new Trade();
                dry.productId = plan.productId;
                dry.displayName = plan.displayName;
                dry.qty = plan.qty;
                if (placeBuyOrder(dry, plan)) {
                    practice.recordBuy(plan.productId, plan.displayName, plan.buyPrice, plan.qty,
                            plan.coinsPerHour, System.currentTimeMillis());
                    com.exkor.flipbot.flipper.FlipperMod.savePractice();
                    chat("§6[Flipper] §d[Practice] §aBuy Order: §f" + plan.qty + "x "
                            + plan.displayName + " §7@ §f" + ScreenUtil.formatPrice(plan.buyPrice)
                            + " §8(not placed)");
                    rememberPlacedOrder(plan);
                    placed++;
                } else {
                    if (!onBuyOrderFailed(plan)) {
                        break;
                    }
                    chat("§6[Flipper] §d[Practice] §cMenu walk failed: §f" + plan.displayName
                            + " §7(blacklisted " + (int) Math.max(1.0, cfg.blacklistMinutes) + "m)");
                    failPause("Practice buy walk failed: " + plan.displayName);
                    break;
                }
                continue;
            }
            Trade trade = new Trade();
            trade.productId = plan.productId;
            trade.displayName = plan.displayName;
            trade.qty = plan.qty;
            trade.buyPrice = plan.buyPrice;
            trade.sellPrice = plan.sellPrice;
            trade.createdAt = System.currentTimeMillis();
            ledger.add(trade);
            if (placeBuyOrder(trade, plan)) {
                trade.state = Trade.State.BUY_OPEN;
                ledger.update(trade);
                chat("\u00a76[Flipper] \u00a7aBuy Order: \u00a7f" + plan.qty + "x " + plan.displayName + " \u00a77@ \u00a7f" + ScreenUtil.formatPrice(plan.buyPrice));
                log(String.format(java.util.Locale.US,
                        "Formel %s: B=%.1f S=%.1f m=%.2f v=%.2f/h u=%.2f/%.2f c=%.3f q=%d "
                                + "P=%.0f r=%.2f e=%.4f Lim/h=%.0f λ=%.4f Score=%.0f",
                        plan.displayName, plan.buyPrice, plan.sellPrice, plan.profitPerUnit,
                        plan.hourlyVolume, plan.uBuy, plan.uSell, plan.c, plan.qty,
                        plan.coinsPerHour, plan.r, plan.e, plan.limitPerHour, plan.lambda, plan.score));
                rememberPlacedOrder(plan);
                placed++;
            } else {
                trade.state = Trade.State.ABANDONED;
                ledger.update(trade);
                if (orderLimitReached) {
                    chat("\u00a76[Flipper] \u00a7eOrder limit reached - no new orders until claim/cancel.");
                    break;
                }
                if (!onBuyOrderFailed(plan)) {
                    break;
                }
                chat("\u00a76[Flipper] \u00a7cBuy order failed: \u00a7f" + plan.displayName + " \u00a77(blacklisted " + (int) Math.max(1.0, cfg.blacklistMinutes) + "m)");
                failPause("Buy order failed: " + plan.displayName);
                break;
            }
        }
        return placed > 0;
    }

    // ------------------------------------------------------------------ craft flipper

    private long lastCraftChatAt;
    private long craftBlockedUntil;
    private boolean craftMenuLogged;

    /** Whether a GUI buy order belongs to the craft batch in progress (never flipped, cancelled or sold). */
    private boolean craftHeld(String itemName) {
        return com.exkor.flipbot.craft.CraftBatch.holds(ItemNames.productId(itemName));
    }

    private Trade craftOrderFor(String itemName) {
        com.exkor.flipbot.craft.CraftBatch batch = com.exkor.flipbot.craft.CraftBatch.current();
        if (batch == null) {
            return null;
        }
        for (Trade t : ledger.all()) {
            if (t.craftIngredient && batch.id.equals(t.craftId)
                    && (t.state == Trade.State.BUY_OPEN || t.state == Trade.State.FILLED)
                    && ScreenUtil.sameName(t.displayName, itemName)) {
                return t;
            }
        }
        return null;
    }

    private void craftChat(String message) {
        long now = System.currentTimeMillis();
        if (now - lastCraftChatAt > 10 * 60_000L) {
            lastCraftChatAt = now;
            chat("§6[Crafts] " + message);
        }
    }

    /**
     * One craft flipper step per cycle: start a batch (unlocked recipes only), wait for its ingredient orders,
     * craft, and hand the result to the normal sell path. Returns true when it used the menus this cycle.
     */
    private boolean craftStep(MarketData data, long purse) {
        if (!cfg.craftFlipping || stopRequested || System.currentTimeMillis() < craftBlockedUntil) {
            return false;
        }
        if (cfg.practiceMode) {
            return practiceCraftStep(data, purse);
        }
        com.exkor.flipbot.craft.CraftBatch batch = com.exkor.flipbot.craft.CraftBatch.current();
        if (batch == null) {
            return startCraftBatch(data, purse);
        }
        if (batch.state == com.exkor.flipbot.craft.CraftBatch.State.BUYING) {
            boolean allIn = true;
            for (Trade t : ledger.all()) {
                if (!t.craftIngredient || !batch.id.equals(t.craftId)) {
                    continue;
                }
                if (t.state == Trade.State.CANCELLED || t.state == Trade.State.ABANDONED) {
                    abandonCraftBatch(batch, "the " + t.displayName + " order was cancelled");
                    return false;
                }
                if (t.claimedQty < t.qty) {
                    allIn = false;
                }
            }
            if (!allIn) {
                return false;
            }
            batch.state = com.exkor.flipbot.craft.CraftBatch.State.CRAFTING;
            com.exkor.flipbot.craft.CraftBatch.set(batch);
        }
        return craftBatchNow(batch, data);
    }

    /** Picks the best unlocked craft the budget allows and places its ingredient buy orders. */
    private boolean startCraftBatch(MarketData data, long purse) {
        if (waitingForBatchToSell() || BazaarDailyLimit.reached() || orderLimitReached) {
            return false;
        }
        if (com.exkor.flipbot.craft.Collections.tiers() == null) {
            craftChat("§7Collections not read yet - open the Crafts tab and click §fRead collections§7.");
            return false;
        }
        List<com.exkor.flipbot.craft.CraftAnalyzer.Plan> plans = craftPlans(data, purse);
        if (plans.isEmpty()) {
            return false;
        }
        com.exkor.flipbot.craft.CraftAnalyzer.Plan plan = plans.get(0);
        com.exkor.flipbot.craft.Recipe recipe = plan.recipe();
        // The unlock check, once more right before any coin is spent.
        if (com.exkor.flipbot.craft.Collections.status(recipe, com.exkor.flipbot.craft.Collections.tiers())
                != com.exkor.flipbot.craft.Collections.Status.UNLOCKED) {
            return false;
        }
        Map<String, Double> prices = new java.util.LinkedHashMap<>();
        for (String id : recipe.ingredients().keySet()) {
            prices.put(id, Math.round((data.products.get(id).bestBid + 0.1) * 10.0) / 10.0);
        }
        com.exkor.flipbot.craft.CraftBatch batch = com.exkor.flipbot.craft.CraftBatch.create(plan, prices,
                plan.batchCrafts(), System.currentTimeMillis());
        com.exkor.flipbot.craft.CraftBatch.set(batch);
        chat("§6[Crafts] §aStarting: §f" + batch.crafts + "× " + ItemNames.displayName(batch.output)
                + " §7· ingredients " + ScreenUtil.formatCoins(batch.totalCost)
                + " §7· expected §a+" + ScreenUtil.formatCoins(batch.expectedProfit));
        for (Map.Entry<String, Integer> e : recipe.ingredients().entrySet()) {
            String id = e.getKey();
            Trade trade = new Trade();
            trade.productId = id;
            trade.displayName = ItemNames.displayName(id);
            trade.qty = batch.needed(id);
            trade.buyPrice = prices.get(id);
            trade.cost = trade.qty * trade.buyPrice;
            trade.craftId = batch.id;
            trade.craftIngredient = true;
            trade.createdAt = System.currentTimeMillis();
            ledger.add(trade);
            if (!placeBuyOrder(trade, ingredientPlan(trade, data))) {
                trade.state = Trade.State.ABANDONED;
                ledger.update(trade);
                abandonCraftBatch(batch, "the buy order for " + trade.displayName + " failed");
                craftBlockedUntil = System.currentTimeMillis() + 10 * 60_000L;
                return true;
            }
            trade.state = Trade.State.BUY_OPEN;
            ledger.update(trade);
            chat("§6[Crafts] §aBuy Order: §f" + trade.qty + "x " + trade.displayName + " §7@ §f"
                    + ScreenUtil.formatPrice(trade.buyPrice));
        }
        return true;
    }

    /** Profitable crafts whose recipe is unlocked and whose items have no other open order. */
    private List<com.exkor.flipbot.craft.CraftAnalyzer.Plan> craftPlans(MarketData data, long purse) {
        List<com.exkor.flipbot.craft.Recipe> recipes = com.exkor.flipbot.craft.RecipeRepo.recipes();
        if (recipes.isEmpty()) {
            com.exkor.flipbot.craft.RecipeRepo.ensure(data.products.keySet());
            recipes = com.exkor.flipbot.craft.RecipeRepo.recipes();
        }
        double budget = Math.min(Math.max(0, purse - cfg.reserveCoins), investRoom());
        if (budget <= 0) {
            craftChat("§7No budget for a craft batch right now.");
            return List.of();
        }
        int free = ScreenUtil.freeInventorySlots() - 2; // room for the result
        int open = cfg.practiceMode ? com.exkor.flipbot.flipper.FlipperMod.practice().openCount() : lastScannedOrderCount;
        int slots = cfg.maxOrders - open;
        Set<String> busy = new HashSet<>(scannedOrderProductIds);
        if (cfg.practiceMode) {
            busy.addAll(com.exkor.flipbot.flipper.FlipperMod.practice().openProductIds());
        }
        Map<String, Integer> tiers = com.exkor.flipbot.craft.Collections.tiers();
        List<com.exkor.flipbot.craft.CraftAnalyzer.Plan> out = new ArrayList<>();
        for (com.exkor.flipbot.craft.CraftAnalyzer.Plan p : com.exkor.flipbot.craft.CraftAnalyzer.plans(data, recipes, cfg, budget, Math.max(0, free))) {
            com.exkor.flipbot.craft.Recipe r = p.recipe();
            if (p.batchCrafts() < 1 || r.ingredients().size() > slots) {
                continue;
            }
            if (com.exkor.flipbot.craft.Collections.status(r, tiers) != com.exkor.flipbot.craft.Collections.Status.UNLOCKED) {
                continue;
            }
            if (busy.contains(r.output()) || r.ingredients().keySet().stream().anyMatch(busy::contains)
                    || isBlacklisted(r.output()) || r.ingredients().keySet().stream().anyMatch(this::isBlacklisted)) {
                continue;
            }
            if (r.ingredients().values().stream().anyMatch(n -> (long) n * p.batchCrafts() > 71_680)) {
                continue; // over Hypixel's per-order maximum
            }
            out.add(p);
        }
        if (out.isEmpty()) {
            craftChat("§7No unlocked craft pays right now §8(the Crafts tab shows all of them)§7.");
        }
        return out;
    }

    private FlipAnalyzer.FlipPlan ingredientPlan(Trade trade, MarketData data) {
        FlipAnalyzer.FlipPlan plan = new FlipAnalyzer.FlipPlan();
        plan.productId = trade.productId;
        plan.displayName = trade.displayName;
        plan.qty = trade.qty;
        plan.buyPrice = trade.buyPrice;
        MarketData.Product p = data.products.get(trade.productId);
        plan.sellPrice = p != null && p.bestAsk > 0 ? Math.round((p.bestAsk - 0.1) * 10.0) / 10.0 : trade.buyPrice;
        return plan;
    }

    /** Gives up a batch: its remaining orders go back to normal flipping, bought items stay in the inventory. */
    private void abandonCraftBatch(com.exkor.flipbot.craft.CraftBatch batch, String why) {
        for (Trade t : ledger.all()) {
            if (batch.id.equals(t.craftId) && t.craftIngredient) {
                t.craftIngredient = false;
                t.craftId = null;
                if (t.state == Trade.State.BUY_OPEN) {
                    t.qty = Math.max(0, t.qty - t.claimedQty);
                    t.cost = t.qty * t.buyPrice;
                    t.sellPrice = 0;
                } else if (t.state != Trade.State.CANCELLED && t.state != Trade.State.ABANDONED) {
                    t.state = Trade.State.ABANDONED;
                }
                ledger.update(t);
            }
        }
        com.exkor.flipbot.craft.CraftBatch.set(null);
        chat("§6[Crafts] §cBatch stopped: §7" + why + ". Items already bought stay in your inventory.");
    }

    /**
     * A claimed-in ingredient order: left-click claims its items into the inventory (never the flip button).
     * Returns false when the claim did not go through.
     */
    private boolean claimCraftIngredient(ScreenUtil.GuiOrder target, Trade trade) {
        manualClaimFlow = true;
        boolean clicked = ScreenUtil.clickOrderSlotByIndex(target.slot, target.itemName, false, 0);
        manualClaimFlow = false;
        if (!clicked) {
            return false;
        }
        long claimed = waitForClaimedItems(3000);
        if (claimed <= 0) {
            return false;
        }
        trade.claimedQty += claimed;
        if (trade.claimedQty >= trade.qty) {
            trade.state = Trade.State.DONE;
        }
        ledger.update(trade);
        chat("§6[Crafts] §aClaimed: §f" + claimed + "x " + trade.displayName + " §7(" + Math.min(trade.claimedQty, trade.qty)
                + "/" + trade.qty + ")");
        return true;
    }

    /** Count of an item in the player inventory, by SkyBlock id. */
    private static long countById(String productId) {
        Long n = ScreenUtil.sync(() -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) {
                return 0L;
            }
            long total = 0;
            for (int i = 0; i < 36; i++) {
                net.minecraft.world.item.ItemStack stack = mc.player.getInventory().getItem(i);
                if (!stack.isEmpty() && productId.equals(com.exkor.flipbot.flipper.data.SkyblockId.of(stack))) {
                    total += stack.getCount();
                }
            }
            return total;
        });
        return n == null ? 0 : n;
    }

    /** All ingredients are in: craft with the recipe menu's Supercraft, then list the result. */
    private boolean craftBatchNow(com.exkor.flipbot.craft.CraftBatch batch, MarketData data) {
        long possible = batch.crafts;
        for (Map.Entry<String, Integer> e : batch.perCraft.entrySet()) {
            possible = Math.min(possible, countById(e.getKey()) / e.getValue());
        }
        if (possible <= 0) {
            abandonCraftBatch(batch, "the ingredients are not in your inventory");
            return false;
        }
        long before = countById(batch.output);
        int slot = openSupercraft(batch.output);
        if (slot < 0) {
            abandonCraftBatch(batch, "the Supercraft button wasn't found (menu logged to config/flipbot/craft-menus.log)");
            craftBlockedUntil = System.currentTimeMillis() + 30 * 60_000L;
            return true;
        }
        long target = before + possible * batch.outputCount;
        for (int i = 0; i < possible && !stopRequested; i++) {
            long was = countById(batch.output);
            click(slot, 0);
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end && countById(batch.output) <= was) {
                sleepOrStop(150);
            }
            if (countById(batch.output) <= was) {
                logCraftMenu("no result after Supercraft click " + (i + 1) + " for " + batch.output);
                break;
            }
            if (countById(batch.output) >= target) {
                break;
            }
            sleepOrStop(ScreenUtil.jitter(350));
        }
        cleanupGui();
        long made = Math.max(0, countById(batch.output) - before);
        if (made <= 0) {
            abandonCraftBatch(batch, "crafting didn't produce anything (menu logged to config/flipbot/craft-menus.log)");
            craftBlockedUntil = System.currentTimeMillis() + 30 * 60_000L;
            return true;
        }
        double crafts = made / (double) batch.outputCount;
        for (Trade t : ledger.all()) {
            if (batch.id.equals(t.craftId) && t.craftIngredient && t.state == Trade.State.BUY_OPEN) {
                t.state = Trade.State.DONE; // claimed in full by now
                ledger.update(t);
            }
        }
        Trade result = new Trade();
        result.productId = batch.output;
        result.displayName = ItemNames.displayName(batch.output);
        result.qty = made;
        result.cost = batch.totalCost * Math.min(1.0, crafts / batch.crafts);
        result.buyPrice = result.cost / made;
        result.craftId = batch.id;
        result.state = Trade.State.CLAIMED;
        result.createdAt = System.currentTimeMillis();
        ledger.add(result);
        com.exkor.flipbot.craft.CraftBatch.set(null);
        chat("§6[Crafts] §aCrafted §f" + made + "x " + result.displayName + " §7· cost "
                + ScreenUtil.formatCoins(result.cost) + " · listing it");
        createSellOfferFromInventory(result, data);
        return true;
    }

    /** Opens /viewrecipe for the item and returns the Supercraft slot, or -1 (the menu is then logged). */
    private int openSupercraft(String output) {
        cleanupGui();
        sendCommand("viewrecipe " + output);
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            sleepOrStop(200);
            Integer slot = ScreenUtil.sync(() -> {
                if (!(net.minecraft.client.Minecraft.getInstance().gui.screen()
                        instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> c)
                        || !(c.getMenu() instanceof net.minecraft.world.inventory.ChestMenu chest)) {
                    return null;
                }
                int pickaxe = -1;
                for (int i = 0; i < chest.getContainer().getContainerSize(); i++) {
                    net.minecraft.world.item.ItemStack st = chest.getContainer().getItem(i);
                    if (st.isEmpty()) {
                        continue;
                    }
                    if (ScreenUtil.cleanName(st.getHoverName().getString()).toLowerCase(java.util.Locale.ROOT)
                            .contains("supercraft")) {
                        return i;
                    }
                    // The recipe menu's Supercraft button is a golden pickaxe below the crafting table.
                    if (pickaxe < 0 && st.is(net.minecraft.world.item.Items.GOLDEN_PICKAXE)) {
                        pickaxe = i;
                    }
                }
                return pickaxe;
            });
            if (slot != null && slot >= 0) {
                if (!craftMenuLogged) {
                    craftMenuLogged = true;
                    logCraftMenu("recipe menu for " + output + " (Supercraft = slot " + slot + ")");
                }
                sleepOrStop(ScreenUtil.jitter(300));
                return slot;
            }
        }
        logCraftMenu("no Supercraft button for " + output);
        cleanupGui();
        return -1;
    }

    /** Writes what the open menu shows (names and descriptions) to craft-menus.log, for tuning. */
    private void logCraftMenu(String why) {
        String dump = ScreenUtil.sync(() -> {
            StringBuilder sb = new StringBuilder();
            var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
            sb.append("screen: ").append(screen == null ? "none" : ScreenUtil.cleanName(screen.getTitle().getString())).append('\n');
            if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> c
                    && c.getMenu() instanceof net.minecraft.world.inventory.ChestMenu chest) {
                for (int i = 0; i < chest.getContainer().getContainerSize(); i++) {
                    net.minecraft.world.item.ItemStack st = chest.getContainer().getItem(i);
                    if (st.isEmpty()) {
                        continue;
                    }
                    sb.append("  ").append(i).append(": ").append(ScreenUtil.cleanName(st.getHoverName().getString()));
                    var lore = st.get(net.minecraft.core.component.DataComponents.LORE);
                    if (lore != null) {
                        for (Component line : lore.lines()) {
                            sb.append(" | ").append(ScreenUtil.cleanName(line.getString()));
                        }
                    }
                    sb.append('\n');
                }
            }
            return sb.toString();
        });
        try {
            java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("flipbot").resolve("craft-menus.log");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, java.time.LocalDateTime.now() + "  " + why + "\n" + dump,
                    java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
        log("Craft: " + why);
    }

    /**
     * Practice: walks the real menus (each ingredient's buy order up to the confirm screen, then the recipe menu's
     * Supercraft button), places nothing, and simulates the batch in the practice book.
     */
    private boolean practiceCraftStep(MarketData data, long purse) {
        PracticeBook book = com.exkor.flipbot.flipper.FlipperMod.practice();
        if (book.openCraft() != null || waitingForBatchToSell()) {
            return false;
        }
        if (com.exkor.flipbot.craft.Collections.tiers() == null) {
            craftChat("§7Collections not read yet - open the Crafts tab and click §fRead collections§7.");
            return false;
        }
        List<com.exkor.flipbot.craft.CraftAnalyzer.Plan> plans = craftPlans(data, purse);
        if (plans.isEmpty()) {
            return false;
        }
        com.exkor.flipbot.craft.CraftAnalyzer.Plan plan = plans.get(0);
        com.exkor.flipbot.craft.Recipe recipe = plan.recipe();
        if (com.exkor.flipbot.craft.Collections.status(recipe, com.exkor.flipbot.craft.Collections.tiers())
                != com.exkor.flipbot.craft.Collections.Status.UNLOCKED) {
            return false;
        }
        Map<String, Double> prices = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : recipe.ingredients().entrySet()) {
            String id = e.getKey();
            prices.put(id, Math.round((data.products.get(id).bestBid + 0.1) * 10.0) / 10.0);
            Trade dry = new Trade();
            dry.productId = id;
            dry.displayName = ItemNames.displayName(id);
            dry.qty = (long) e.getValue() * plan.batchCrafts();
            dry.buyPrice = prices.get(id);
            if (!placeBuyOrder(dry, ingredientPlan(dry, data))) {
                chat("§6[Crafts] §d[Practice] §cMenu walk failed: §f" + dry.displayName);
                craftBlockedUntil = System.currentTimeMillis() + 10 * 60_000L;
                return true;
            }
        }
        int slot = openSupercraft(recipe.output());
        cleanupGui();
        if (slot < 0) {
            chat("§6[Crafts] §d[Practice] §cNo Supercraft button in /viewrecipe " + recipe.output()
                    + " §8(logged to config/flipbot/craft-menus.log)");
            craftBlockedUntil = System.currentTimeMillis() + 30 * 60_000L;
            return true;
        }
        book.recordCraft(recipe.output(), ItemNames.displayName(recipe.output()), recipe.outputCount(),
                plan.batchCrafts(), recipe.ingredients(), prices, System.currentTimeMillis());
        com.exkor.flipbot.flipper.FlipperMod.savePractice();
        chat("§6[Crafts] §d[Practice] §aBatch: §f" + plan.batchCrafts() + "× " + ItemNames.displayName(recipe.output())
                + " §7· expected §a+" + ScreenUtil.formatCoins(plan.batchProfit()) + " §8(nothing bought)");
        return true;
    }

    private long lastBudgetChatAt;
    private long lastBatchWaitChatAt;
    private long lastFloorChatAt;

    /** "Finish each batch first": no new buy orders while bought items are still unsold. */
    private boolean waitingForBatchToSell() {
        if (!cfg.finishBatchFirst) {
            return false;
        }
        int unsold = cfg.practiceMode
                ? com.exkor.flipbot.flipper.ProfitRules.unsoldPractice(com.exkor.flipbot.flipper.FlipperMod.practice())
                : com.exkor.flipbot.flipper.ProfitRules.unsoldTrades(ledger.all());
        if (unsold == 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastBatchWaitChatAt > 10 * 60_000L) {
            lastBatchWaitChatAt = now;
            chat("§6[Flipper] §7Waiting for §f" + unsold + "§7 listed item"
                    + (unsold == 1 ? "" : "s") + " to sell before buying more §8(finish each batch first)");
        }
        logLimit("Finish batch first: " + unsold + " unsold - no new buys");
        return true;
    }

    /** Lowest sell price for this trade's items, 0 when the rule is off or the cost is unknown. */
    private double profitFloor(Trade trade) {
        if (!cfg.onlySellAtProfit || trade == null) {
            return 0;
        }
        return com.exkor.flipbot.flipper.ProfitRules.breakEven(trade.buyPrice, cfg.taxPercent);
    }

    private long lastNoPlansChatAt;

    /** Says in chat (at most every 10 min) why no buy order is placed. */
    private void explainNoPlans(double bankroll) {
        long now = System.currentTimeMillis();
        if (now - lastNoPlansChatAt < 10 * 60_000L) {
            return;
        }
        lastNoPlansChatAt = now;
        double perItem = bankroll * cfg.maxBankFractionPerItem;
        if (cfg.maxCoinsPerItem > 0) {
            perItem = Math.min(perItem, cfg.maxCoinsPerItem);
        }
        String why = perItem < cfg.minUnitCoins
                ? "the budget per item (" + ScreenUtil.formatCoins(perItem) + ") is below the minimum unit price ("
                        + ScreenUtil.formatCoins(cfg.minUnitCoins) + "). Raise the invest cap or lower minUnitCoins."
                : "no item passes the filters right now at " + ScreenUtil.formatCoins(perItem)
                        + " per item. The bot keeps checking.";
        chat("§6[Flipper] §eNo buy orders: §7" + why);
    }

    private static String formatHourly(double perHour) {
        if (perHour >= 1000) {
            return ScreenUtil.formatCoins(perHour);
        }
        return String.format(java.util.Locale.US, "%.1f", perHour);
    }

    private double buyOrderExpectedProfit(ScreenUtil.GuiOrder order) {
        MarketData.Product product = productFor(order.itemName);
        if (product == null || product.bestAsk <= 0 || order.unitPrice <= 0) {
            return -1;
        }
        double sellPrice = Math.round((product.bestAsk - 0.1) * 10.0) / 10.0;
        double perUnit = sellPrice * (1.0 - cfg.taxPercent / 100.0) - order.unitPrice;
        long remaining = Math.max(1, order.amount - order.filled);
        return perUnit * remaining;
    }

    /** Coins that may still go into new orders: the cap minus what is already in the Bazaar (MAX_VALUE without a cap). */
    private double investRoom() {
        double cap = com.exkor.flipbot.flipper.Bankroll.cap(cfg, com.exkor.flipbot.flipper.FlipperMod.profitMade());
        if (cap < 0) {
            return Double.MAX_VALUE;
        }
        if (cfg.practiceMode) {
            return cap - com.exkor.flipbot.flipper.FlipperMod.practice().investedCoins();
        }
        return cap - BazaarLimits.invested(lastScannedBuyOrders, lastScannedSellOffers);
    }

    private void logLimit(String text) {
        long now = System.currentTimeMillis();
        if (now - lastLimitLogAt > 60_000L) {
            lastLimitLogAt = now;
            log(text);
        }
    }

    /**
     * Item mode: one buy order per listed item that has no open order yet, at the best buy
     * order + 0.1, to be sold at the best offer - 0.1 like any other flip. No scoring, volume or
     * margin filters: the only thing checked is that the spread pays after tax.
     */
    private List<FlipAnalyzer.FlipPlan> itemModePlans(MarketData data, double bankroll, int slots,
                                                      Set<String> skip) {
        List<FlipAnalyzer.FlipPlan> plans = new ArrayList<>();
        List<String> products = BazaarLimits.itemModeProducts(cfg, data);
        if (products.isEmpty()) {
            log("Item mode: no listed item found on the Bazaar: " + cfg.onlyItems);
            return plans;
        }
        double budget = bankroll * cfg.maxBankFractionPerItem;
        if (cfg.maxCoinsPerItem > 0) {
            budget = Math.min(budget, cfg.maxCoinsPerItem);
        }
        int freeSlots = ScreenUtil.freeInventorySlots();
        double taxFactor = 1.0 - cfg.taxPercent / 100.0;
        for (String productId : products) {
            if (plans.size() >= slots) {
                break;
            }
            String name = ItemNames.displayName(productId);
            if (skip.contains(productId)) {
                log("Item mode: " + name + " already has an order / is blocked");
                continue;
            }
            MarketData.Product product = data.products.get(productId);
            if (product == null || product.bestBid <= 0 || product.bestAsk <= 0) {
                log("Item mode: no prices for " + name);
                continue;
            }
            double buy = Math.round((product.bestBid + 0.1) * 10.0) / 10.0;
            double sell = Math.round((product.bestAsk - 0.1) * 10.0) / 10.0;
            double profit = sell * taxFactor - buy;
            if (profit <= 0) {
                log(String.format(java.util.Locale.US,
                        "Item mode: %s not worth it right now (buy %.1f, sell %.1f, after tax %.1f)",
                        name, buy, sell, sell * taxFactor));
                continue;
            }
            double hourly = Math.max(product.sellMovingWeek, product.buyMovingWeek) / 168.0;
            long qty = (long) Math.floor(budget / buy);
            qty = Math.min(qty, 71_680L);
            qty = Math.min(qty, (long) Math.max(1, freeSlots) * 64L);
            if (hourly > 0) {
                qty = Math.min(qty, Math.max(1L, (long) Math.ceil(hourly * 2.0)));
            }
            if (qty < 1) {
                log("Item mode: not enough budget for " + name + " (" + ScreenUtil.formatCoins(buy) + ")");
                continue;
            }
            FlipAnalyzer.FlipPlan plan = new FlipAnalyzer.FlipPlan();
            plan.productId = productId;
            plan.displayName = name;
            plan.buyPrice = buy;
            plan.sellPrice = sell;
            plan.profitPerUnit = profit;
            plan.marginPercent = profit / buy * 100.0;
            plan.hourlyVolume = hourly;
            plan.qty = qty;
            plan.stake = qty * buy;
            plan.score = profit * qty;
            plans.add(plan);
            log(String.format(java.util.Locale.US, "Item mode: buy %dx %s @ %.1f, sell @ %.1f (+%.1f each)",
                    qty, name, buy, sell, profit));
        }
        return plans;
    }

    private void replaceWorstOrderIfWorthwhile(MarketData data, long purse) {
        if (waitingForBatchToSell()) {
            return;
        }
        if (BazaarDailyLimit.reached() || BazaarLimits.sessionCapReached(cfg)
                || BazaarLimits.itemModeActive(cfg)) {
            return;
        }
        int open = lastScannedOrderCount;
        int availableSlots = Math.max(0, cfg.maxOrders - open);
        if (availableSlots > 0 || orderLimitReached || lastScannedBuyOrders.isEmpty()) {
            return;
        }
        ScreenUtil.GuiOrder worst = null;
        double worstScore = Double.MAX_VALUE;
        for (ScreenUtil.GuiOrder order : lastScannedBuyOrders) {
            if (!BazaarLimits.itemModeAllowsOrder(cfg, order.itemName) || craftHeld(order.itemName)) {
                continue; // item mode: orders of other items are never cancelled
            }
            double score = buyOrderExpectedProfit(order);
            if (score < worstScore) {
                worstScore = score;
                worst = order;
            }
        }
        if (worst == null) {
            return;
        }
        double bankroll = Math.max(0, purse - cfg.reserveCoins);
        if (bankroll <= 0) {
            return;
        }
        Set<String> existing = new HashSet<>();
        for (String id : itemBlacklist.keySet()) {
            if (isBlacklisted(id)) {
                existing.add(id);
            }
        }
        existing.addAll(scannedOrderProductIds);
        existing.addAll(BazaarLimits.excludedByItemMode(cfg, data));
        // the worst order is cancelled, its coins are free again for the new one
        double worstValue = Math.max(0, worst.unitPrice) * Math.max(0, worst.amount - worst.filled);
        bankroll = Math.min(bankroll, investRoom() + worstValue);
        if (bankroll <= 0) {
            return;
        }
        List<FlipAnalyzer.FlipPlan> plans = FlipAnalyzer.compute(data, cfg, bankroll, existing, 1, this::isStopRequested, 3000L);
        cachePlans(plans);
        if (plans.isEmpty()) {
            return;
        }
        FlipAnalyzer.FlipPlan best = plans.get(0);
        double newScore = best.profitPerUnit * best.qty;
        boolean worthIt = worstScore <= 0 ? newScore > 0 : newScore >= worstScore * 1.2;
        if (!worthIt) {
            log("Replacement not worth it: new=" + ScreenUtil.formatCoins(newScore)
                    + " alt=" + ScreenUtil.formatCoins(worstScore) + " (" + worst.itemName + ")");
            return;
        }
        log("Replacing the weakest buy order: " + worst.itemName + " (" + ScreenUtil.formatCoins(worstScore)
                + ") with " + best.displayName + " (" + ScreenUtil.formatCoins(newScore) + ")");
        Trade base = ensureTradeFor(worst);
        if (!cancelBuyOrder(base, "replaced", data)) {
            failPause("Replacement cancel failed: " + worst.itemName);
            return;
        }
        chat("\u00a76[Flipper] \u00a7cBuy order replaced: \u00a7f" + worst.itemName + " \u00a77(" + ScreenUtil.formatCoins(worstScore) + ")");
        Trade trade = new Trade();
        trade.productId = best.productId;
        trade.displayName = best.displayName;
        trade.qty = best.qty;
        trade.buyPrice = best.buyPrice;
        trade.sellPrice = best.sellPrice;
        trade.createdAt = System.currentTimeMillis();
        ledger.add(trade);
        if (placeBuyOrder(trade, best)) {
            trade.state = Trade.State.BUY_OPEN;
            ledger.update(trade);
            rememberPlacedOrder(best);
            chat("\u00a76[Flipper] \u00a7aNew Buy Order: \u00a7f" + best.qty + "x " + best.displayName + " \u00a77@ \u00a7f" + ScreenUtil.formatPrice(best.buyPrice));
        } else {
            trade.state = Trade.State.ABANDONED;
            ledger.update(trade);
            if (orderLimitReached) {
                chat("\u00a76[Flipper] \u00a7eOrder limit reached - replacement cancelled.");
                return;
            }
            if (!onBuyOrderFailed(best)) {
                return;
            }
            chat("\u00a76[Flipper] \u00a7cReplacement buy failed: \u00a7f" + best.displayName + " \u00a77(blacklisted " + (int) Math.max(1.0, cfg.blacklistMinutes) + "m)");
        }
    }

    private boolean prioritizeExistingOrders(MarketData data) {
        long now = System.currentTimeMillis();
        boolean force = now - lastFullScanAt >= 60_000L;
        if (!force && now - lastOrdersScanAt < SCAN_MIN_INTERVAL_MS) {
            return ordersBusy;
        }
        if (!force && !needsOrderScan(data)) {
            return ordersBusy;
        }
        recentFillChat = false;
        if (!openOrdersGui()) {
            return true;
        }
        waitForOrderGuiStable(4000);
        if (ScreenUtil.currentScreen() != ScreenUtil.BzScreen.ORDERS) {
            // Menu closed during the wait (AH priority ...): "no orders found" here
            // made the bot place the same buy orders a second time.
            log("Orders menu no longer open - scan discarded");
            cleanupGui();
            ordersBusy = true;
            return true;
        }
        List<ScreenUtil.GuiOrder> orders = ScreenUtil.scanOrders();
        if (orders.isEmpty() && lastScannedOrderCount > 0) {
            // Items may just not have arrived yet.
            sleepOrStop(800);
            if (ScreenUtil.currentScreen() != ScreenUtil.BzScreen.ORDERS) {
                cleanupGui();
                ordersBusy = true;
                return true;
            }
            orders = ScreenUtil.scanOrders();
        }
        lastScannedOrders = List.copyOf(orders);
        cleanupGui();
        beat();
        lastOrdersScanAt = System.currentTimeMillis();
        lastFullScanAt = System.currentTimeMillis();
        lastScannedSnapshot = data.lastUpdated;
        lastScanHadOrders = !orders.isEmpty();
        scannedOrderProductIds.clear();
        if (orders.isEmpty()) {
            ownSellPrices.clear();
            lastScannedOrderCount = 0;
            if (orderLimitReached) {
                log("Order limit cleared: scan shows no orders");
                orderLimitReached = false;
            }
            ordersBusy = false;
            return false;
        }
        log("Order-Scan: " + orders.size() + " orders found");
        lastScannedOrderCount = orders.size();
        if (orderLimitReached && orders.size() < cfg.maxOrders) {
            log("Order limit cleared: scan shows only " + orders.size() + " Orders");
            orderLimitReached = false;
        }
        lastScannedBuyOrders.clear();
        lastScannedSellOffers.clear();
        ownSellPrices.clear();
        for (ScreenUtil.GuiOrder order : orders) {
            if (!order.sellSide) {
                lastScannedBuyOrders.add(order);
            } else {
                lastScannedSellOffers.add(order);
                if (order.amount <= 0 || order.filled < order.amount) {
                    rememberOwnSellPrice(order.itemName, order.unitPrice);
                }
            }
        }
        productByName.clear();
        for (MarketData.Product product : data.products.values()) {
            productByName.putIfAbsent(ItemNames.nameKey(ItemNames.displayName(product.productId)), product);
        }

        boolean busy = false;
        Map<String, List<ScreenUtil.GuiOrder>> sellGroups = new java.util.LinkedHashMap<>();
        for (ScreenUtil.GuiOrder order : orders) {
            if (order.sellSide) {
                sellGroups.computeIfAbsent(order.itemName.toLowerCase(), k -> new ArrayList<>()).add(order);
            }
        }
        for (List<ScreenUtil.GuiOrder> group : sellGroups.values()) {
            String itemName = group.get(0).itemName;
            if (!BazaarLimits.itemModeAllowsOrder(cfg, itemName)) {
                // Item mode: not in the list - only claim finished coins, never cancel or reprice.
                for (ScreenUtil.GuiOrder order : group) {
                    if (order.coinsClaimable && order.claimable > 0) {
                        busy |= !processSellEntry(order, data);
                    }
                }
                if (stopRequested) {
                    ordersBusy = true;
                    return true;
                }
                continue;
            }
            busy |= !handleSellGroup(group, data);
            if (stopRequested) {
                ordersBusy = true;
                return true;
            }
        }
        Map<String, List<ScreenUtil.GuiOrder>> buyGroups = new java.util.LinkedHashMap<>();
        List<ScreenUtil.GuiOrder> buyOrders = new ArrayList<>();
        for (ScreenUtil.GuiOrder order : orders) {
            if (!order.sellSide) {
                buyOrders.add(order);
                buyGroups.computeIfAbsent(ScreenUtil.cleanSpecials(order.itemName).toLowerCase(),
                        k -> new ArrayList<>()).add(order);
            }
        }
        List<Trade> batchCancel = new ArrayList<>();
        Set<ScreenUtil.GuiOrder> handled = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<ScreenUtil.GuiOrder> group : buyGroups.values()) {
            if (group.size() <= 1 || !BazaarLimits.itemModeAllowsOrder(cfg, group.get(0).itemName) || craftHeld(group.get(0).itemName)) {
                continue;
            }
            group.sort(Comparator.comparingDouble(o -> o.unitPrice));
            log("Several buy orders for " + group.get(0).itemName + " (" + group.size()
                    + ") - keeping the best, cancelling the rest");
            for (int i = 1; i < group.size(); i++) {
                ScreenUtil.GuiOrder extra = group.get(i);
                handled.add(extra);
                Trade trade = ensureTradeFor(extra);
                batchCancel.add(trade);
            }
        }
        for (ScreenUtil.GuiOrder order : buyOrders) {
            String productId = ItemNames.productId(order.itemName);
            scannedOrderProductIds.add(productId);
            if (handled.contains(order)) {
                continue;
            }
            boolean mayCancel = BazaarLimits.itemModeAllowsOrder(cfg, order.itemName) && !craftHeld(order.itemName);
            MarketData.Product product = productFor(order.itemName);
            boolean outdated = mayCancel && product != null && product.bestBid > order.unitPrice + 0.05;
            boolean unprofitable = mayCancel && isBuyOrderUnprofitable(order);
            boolean fullyFilled = order.filled >= order.amount && order.amount > 0;
            boolean claimable = order.claimable > 0;
            if ((outdated || unprofitable) && !claimable && !fullyFilled) {
                handled.add(order);
                Trade trade = ensureTradeFor(order);
                batchCancel.add(trade);
            } else if (fullyFilled || claimable || outdated || unprofitable) {
                busy |= !processBuyEntry(order, data);
            }
            if (stopRequested) {
                ordersBusy = true;
                return true;
            }
        }
        if (!batchCancel.isEmpty() && !stopRequested) {
            int cancelled = cancelBuyOrdersBatch(batchCancel, "Batch", data);
            if (cancelled < batchCancel.size()) {
                busy = true;
            }
        }
        ordersBusy = busy;
        return busy;
    }

    private boolean isBuyOrderUnprofitable(ScreenUtil.GuiOrder order) {
        MarketData.Product product = productFor(order.itemName);
        if (product == null || product.bestAsk <= 0 || order.unitPrice <= 0) {
            return false;
        }
        double sellPrice = Math.round((product.bestAsk - 0.1) * 10.0) / 10.0;
        double profitPerUnit = sellPrice * (1.0 - cfg.taxPercent / 100.0) - order.unitPrice;
        double margin = profitPerUnit / order.unitPrice * 100.0;
        if (margin < cfg.minBuyOrderMarginPercent) {
            return true;
        }
        long remaining = Math.max(1, order.amount - order.filled);
        return profitPerUnit * remaining < 200_000;
    }

    private int cancelBuyOrdersBatch(List<Trade> trades, String reason, MarketData data) {
        if (trades.isEmpty()) {
            return 0;
        }
        if (!openOrdersGui()) {
            return 0;
        }
        waitForOrderGuiStable(2500);
        int cancelled = 0;
        for (Trade trade : trades) {
            if (stopRequested) {
                break;
            }
            if (ScreenUtil.freeInventorySlots() < 4) {
                log("Inventory almost full - leaving the menu, the rest later");
                break;
            }
            int slot = findOrderSlotRetry(trade.displayName, false, 2500);
            if (slot < 0) {
                continue;
            }
            trade.notFoundChecks = 0;
            ledger.update(trade);
            manualClaimFlow = true;
            if (!clickOrder(trade.displayName, false, 0, 4000, "Batch-Cancel (Claim-Check): " + trade.displayName)) {
                manualClaimFlow = false;
                continue;
            }
            long claimedNow = waitForClaimedItems(2500);
            manualClaimFlow = false;
            if (claimedNow > 0) {
                Trade batch = new Trade();
                batch.productId = trade.productId;
                batch.displayName = trade.displayName;
                batch.qty = claimedNow;
                batch.buyPrice = trade.buyPrice;
                batch.sellPrice = trade.sellPrice;
                batch.cost = claimedNow * trade.buyPrice;
                batch.state = Trade.State.CLAIMED;
                batch.createdAt = System.currentTimeMillis();
                ledger.add(batch);
                trade.qty = Math.max(0, trade.qty - claimedNow);
                trade.cost = trade.qty * trade.buyPrice;
                ledger.update(trade);
                chat("\u00a76[Flipper] \u00a7aClaimed: \u00a7f" + claimedNow + "x " + trade.displayName);
                if (ScreenUtil.freeInventorySlots() < 4) {
                    log("Inventory full after the claim - leaving the menu");
                    cleanupGui();
                    return cancelled;
                }
            }
            if (ScreenUtil.currentScreen() != ScreenUtil.BzScreen.ORDER_OPTIONS
                    && !waitForScreenCancel(ScreenUtil.BzScreen.ORDER_OPTIONS, 2000)) {
                if (!clickOrder(trade.displayName, false, 1, 4000, "Batch cancel (right click): " + trade.displayName)) {
                    continue;
                }
                if (!waitForScreenCancel(ScreenUtil.BzScreen.ORDER_OPTIONS, 2500)) {
                    continue;
                }
            }
            boolean done = false;
            for (int attempt = 0; attempt < 2 && !done && !stopRequested; attempt++) {
                if (!clickByName("Cancel", 0, 3000, "Cancel-Button (Batch)")) {
                    break;
                }
                done = waitForCancelled(3000);
            }
            if (done) {
                trade.state = Trade.State.CANCELLED;
                ledger.update(trade);
                cancelled++;
                chat("\u00a76[Flipper] \u00a7cBuy order cancelled: \u00a7f" + trade.displayName + " \u00a77(" + reason + ")");
            }
            ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
            if (screen != ScreenUtil.BzScreen.ORDERS) {
                ScreenUtil.closeCurrentScreen();
                jitterSleepOrStop(400, 200);
            }
        }
        cleanupGui();
        return cancelled;
    }

    /**
     * All our sell offers of one item. Offers are merged only as far as their items fit in the inventory (merging
     * cancels them all at once). They are re-listed when someone undercuts all of them, one at a time.
     */
    private boolean handleSellGroup(List<ScreenUtil.GuiOrder> group, MarketData data) {
        checkStop();
        String itemName = group.get(0).itemName;
        boolean ok = claimSellCoins(group);
        if (stopRequested) {
            return false;
        }
        List<ScreenUtil.GuiOrder> open = new ArrayList<>();
        double ourBest = Double.MAX_VALUE;
        for (ScreenUtil.GuiOrder order : group) {
            if (order.amount > 0 && order.filled >= order.amount) {
                continue;
            }
            open.add(order);
            ourBest = Math.min(ourBest, order.unitPrice);
        }
        if (open.isEmpty()) {
            return ok;
        }
        MarketData.Product product = productFor(itemName);
        double bestAsk = product != null ? product.bestAsk : -1;
        boolean undercut = bestAsk > 0 && bestAsk < ourBest - 0.05;
        log("Sell-Check: " + itemName + " offers=" + group.size() + " undercut=" + undercut
                + " bestAsk=" + bestAsk + " ours=" + ourBest);
        // smallest first: they fit the inventory best
        open.sort(Comparator.comparingLong(ScreenUtil.GuiOrder::remaining));
        boolean merged = false;
        if (open.size() > 1) {
            List<ScreenUtil.GuiOrder> fit = mergeableOffers(open, itemName);
            if (fit.size() >= 2) {
                if (!mergeSellOffers(fit, itemName, data)) {
                    return false;
                }
                merged = true;
                open.removeIf(order -> fit.stream().anyMatch(f -> f == order));
            } else {
                logInventory("Sell offers for " + itemName + " don't fit in the inventory together ("
                        + ScreenUtil.freeInventorySlots() + " free slots) - they stay separate");
            }
            if (stopRequested) {
                return false;
            }
        }
        if (!undercut || open.isEmpty()) {
            return ok;
        }
        Long last = sellRepriceCooldown.get(itemName.toLowerCase());
        if (!merged && last != null && System.currentTimeMillis() - last < 5_000L) {
            log("Reprice-Cooldown (busy): " + itemName);
            return false;
        }
        for (ScreenUtil.GuiOrder order : open) {
            // An offer already at break-even stays put instead of chasing the price down.
            double floor = profitFloor(findLedgerTrade(order.itemName, true, order.amount));
            double target = Math.round((bestAsk - 0.1) * 10.0) / 10.0;
            if (floor > 0 && target < floor && order.unitPrice <= floor + 0.05) {
                log("Never sell at a loss: " + itemName + " stays at " + order.unitPrice
                        + " (break-even " + floor + ", best offer " + bestAsk + ")");
                long now = System.currentTimeMillis();
                if (now - lastFloorChatAt > 10 * 60_000L) {
                    lastFloorChatAt = now;
                    chat("§6[Flipper] §7Holding §f" + itemName + "§7 at §f"
                            + ScreenUtil.formatPrice(order.unitPrice) + "§7 - the market ("
                            + ScreenUtil.formatPrice(bestAsk) + ") is below what it cost.");
                }
                continue;
            }
            if (!inventoryRoomFor(itemName, order.remaining())) {
                logInventory("No inventory space for " + order.remaining() + "x " + itemName
                        + " - sell offer stays for now");
                break;
            }
            if (!relistSellOffer(order)) {
                return false;
            }
            if (stopRequested) {
                return false;
            }
        }
        return ok;
    }

    /**
     * The offers (smallest first) whose items together fit into the free inventory slots,
     * one slot to spare. Merging cancels them all at once, so 30 + 11 Summoning Eyes with 25
     * free slots are not merged; 2 + 5 + 11 are.
     */
    private List<ScreenUtil.GuiOrder> mergeableOffers(List<ScreenUtil.GuiOrder> sortedOpen, String itemName) {
        int free = ScreenUtil.freeInventorySlots() - 1;
        List<ScreenUtil.GuiOrder> fit = new ArrayList<>();
        long total = 0;
        for (ScreenUtil.GuiOrder order : sortedOpen) {
            long next = total + order.remaining();
            if (order.remaining() <= 0 || slotsNeeded(itemName, next) > free) {
                break;
            }
            fit.add(order);
            total = next;
        }
        return fit;
    }

    /**
     * Cancels these offers and lists all their items as one offer: fewer orders in the
     * Manage Orders menu. If the best offer is still one of ours (an offer that was not
     * merged), the new one takes the same price.
     */
    private boolean mergeSellOffers(List<ScreenUtil.GuiOrder> offers, String itemName, MarketData data) {
        long total = offers.stream().mapToLong(ScreenUtil.GuiOrder::remaining).sum();
        log("Running " + offers.size() + " sell offers for " + itemName + " together (" + total
                + "x, " + ScreenUtil.freeInventorySlots() + " free slots)");
        Trade merged = null;
        for (ScreenUtil.GuiOrder order : offers) {
            Trade base = ensureTradeFor(order);
            cancelSellOffer(base, order);
            if (base.state != Trade.State.CLAIMED) {
                break; // cancel failed: list what came back so far
            }
            if (merged == null) {
                merged = base;
            } else if (base != merged) {
                base.qty = 0;
                base.cost = 0;
                base.state = Trade.State.ABANDONED;
                ledger.update(base);
            }
            if (stopRequested) {
                return false;
            }
        }
        if (merged == null) {
            return false;
        }
        merged.qty = ScreenUtil.countItemsInInventory(merged.displayName);
        if (merged.qty <= 0) {
            log("Merge: no items in the inventory: " + merged.displayName);
            return false;
        }
        merged.cost = merged.qty * merged.buyPrice;
        merged.state = Trade.State.CLAIMED;
        ledger.update(merged);
        refreshMarketIfStale();
        MarketData fresh = market != null ? market : data;
        if (!createSellOfferFromInventory(merged, fresh)) {
            return false;
        }
        sellRepriceCooldown.put(itemName.toLowerCase(), System.currentTimeMillis());
        chat("§6[Flipper] §aMerged sell offers: §f" + merged.qty + "x " + merged.displayName
                + " §7@ §f" + ScreenUtil.formatPrice(merged.sellPrice));
        return true;
    }

    /** Claims the coins of (partly) sold offers in the group. */
    private boolean claimSellCoins(List<ScreenUtil.GuiOrder> group) {
        String itemName = group.get(0).itemName;
        for (ScreenUtil.GuiOrder order : group) {
            if (!order.coinsClaimable || order.claimable <= 0) {
                continue;
            }
            if (!openOrdersGui()) {
                return false;
            }
            waitForOrderGuiStable(2500);
            List<ScreenUtil.GuiOrder> fresh = ScreenUtil.scanOrders();
            ScreenUtil.GuiOrder target = null;
            for (ScreenUtil.GuiOrder entry : fresh) {
                if (entry.sellSide && entry.coinsClaimable && entry.claimable > 0
                        && ScreenUtil.fuzzyItemMatch(entry.itemName, itemName)) {
                    target = entry;
                    break;
                }
            }
            if (target != null) {
                ScreenUtil.clickOrderSlotByIndex(target.slot, target.itemName, true, 0);
                if (waitForChatCancel("Claimed", 3000)) {
                    Trade trade = findLedgerTrade(itemName, true, order.amount);
                    if (trade != null) {
                        trade.state = Trade.State.DONE;
                        markProductSuccess(trade.productId);
                        ledger.update(trade);
                    } else {
                        chat("§6[Flipper] §aCoins claimed: §f" + itemName);
                    }
                }
            }
            cleanupGui();
            if (stopRequested) {
                return false;
            }
        }
        return true;
    }

    /** Cancels this sell offer and lists its items again at the current best price. */
    private boolean relistSellOffer(ScreenUtil.GuiOrder order) {
        log("Sell offer undercut - relisting: " + order.itemName + " @ " + order.unitPrice);
        Trade base = ensureTradeFor(order);
        cancelSellOffer(base, order);
        if (base.state != Trade.State.CLAIMED || stopRequested) {
            return false;
        }
        if (order.remaining() > 0) {
            base.qty = order.remaining();
            base.cost = base.qty * base.buyPrice;
        }
        refreshMarketIfStale();
        MarketData fresh = market;
        boolean listed = fresh != null && createSellOfferFromInventory(base, fresh);
        if (!listed) {
            log("Relisting failed - items stay in the inventory: " + order.itemName);
            return false;
        }
        base.outbidChecks = 0;
        base.lastRelistedAt = System.currentTimeMillis();
        ledger.update(base);
        sellRepriceCooldown.put(order.itemName.toLowerCase(), System.currentTimeMillis());
        chat("§6[Flipper] §aSell offer updated: §f" + base.qty + "x " + base.displayName
                + " §7@ §f" + ScreenUtil.formatPrice(base.sellPrice));
        return true;
    }

    private boolean processSellEntry(ScreenUtil.GuiOrder order, MarketData data) {
        BotLock.actionDelay(cfg.actionDelayMs);
        if (!openOrdersGui()) {
            return false;
        }
        waitForOrderGuiStable(2500);
        List<ScreenUtil.GuiOrder> freshOrders = ScreenUtil.scanOrders();
        ScreenUtil.GuiOrder target = null;
        for (ScreenUtil.GuiOrder entry : freshOrders) {
            if (entry.sellSide && ScreenUtil.fuzzyItemMatch(entry.itemName, order.itemName)
                    && entry.coinsClaimable && entry.claimable > 0) {
                target = entry;
                break;
            }
        }
        if (target == null) {
            for (ScreenUtil.GuiOrder entry : freshOrders) {
                if (entry.sellSide && ScreenUtil.fuzzyItemMatch(entry.itemName, order.itemName)) {
                    target = entry;
                    break;
                }
            }
        }
        boolean clicked;
        if (target != null) {
            clicked = ScreenUtil.clickOrderSlotByIndex(target.slot, target.itemName, true, 0);
            log(clicked ? "Clicking slot " + target.slot + " (gezielt): " + target.itemName
                    : "Click failed: " + target.itemName);
        } else {
            clicked = clickOrder(order.itemName, true, 0, 5000, "Coin-Check: " + order.itemName);
        }
        if (!clicked) {
            cleanupGui();
            return false;
        }
        boolean claimed = waitForChatCancel("Claimed", 3000);
        if (claimed) {
            Trade trade = findLedgerTrade(order.itemName, true, order.amount);
            if (trade != null) {
                trade.state = Trade.State.DONE;
                markProductSuccess(trade.productId);
                ledger.update(trade);
            } else {
                chat("\u00a76[Flipper] \u00a7aCoins claimed: \u00a7f" + order.itemName);
            }
            cleanupGui();
            return true;
        }
        ScreenUtil.BzScreen screen = ScreenUtil.currentScreen();
        if (screen != ScreenUtil.BzScreen.ORDERS) {
            ScreenUtil.closeCurrentScreen();
            jitterSleepOrStop(400, 200);
        }
        MarketData.Product product = productFor(order.itemName);
        boolean outdated = product != null && product.bestAsk > 0 && product.bestAsk < order.unitPrice - 0.05;
        if (!outdated || !BazaarLimits.itemModeAllowsOrder(cfg, order.itemName)) {
            cleanupGui();
            return true;
        }
        if (!inventoryRoomFor(order.itemName, order.remaining())) {
            logInventory("No inventory space for " + order.remaining() + "x " + order.itemName
                    + " - sell offer stays for now");
            cleanupGui();
            return true;
        }
        cleanupGui();
        return relistSellOffer(order);
    }

    private boolean claimEntryItems(ScreenUtil.GuiOrder target, ScreenUtil.GuiOrder order, MarketData data) {
        manualClaimFlow = true;
        boolean clicked = ScreenUtil.clickOrderSlotByIndex(target.slot, target.itemName, false, 0);
        log(clicked ? "Clicking slot " + target.slot + " (gezielt Claim): " + target.itemName
                : "Claim click failed: " + target.itemName);
        manualClaimFlow = false;
        if (!clicked) {
            return false;
        }
        long claimed = waitForClaimedItems(3000);
        if (claimed <= 0) {
            return false;
        }
        Trade batch = new Trade();
        batch.productId = ItemNames.productId(order.itemName);
        batch.displayName = order.itemName;
        batch.qty = claimed;
        batch.buyPrice = order.unitPrice;
        batch.sellPrice = 0;
        batch.cost = claimed * order.unitPrice;
        batch.state = Trade.State.CLAIMED;
        batch.createdAt = System.currentTimeMillis();
        ledger.add(batch);
        chat("\u00a76[Flipper] \u00a7aClaimed: \u00a7f" + claimed + "x " + order.itemName);
        if (data != null) {
            createSellOfferFromInventory(batch, data);
        }
        return true;
    }

    private boolean processBuyEntry(ScreenUtil.GuiOrder order, MarketData data) {
        BotLock.actionDelay(cfg.actionDelayMs);
        if (!openOrdersGui()) {
            return false;
        }
        waitForOrderGuiStable(2500);
        long start = System.currentTimeMillis();
        List<ScreenUtil.GuiOrder> fresh = ScreenUtil.scanOrders();
        ScreenUtil.GuiOrder target = null;
        for (ScreenUtil.GuiOrder entry : fresh) {
            if (!entry.sellSide && ScreenUtil.fuzzyItemMatch(entry.itemName, order.itemName)) {
                target = entry;
                break;
            }
        }
        if (target == null) {
            log("Buy order gone - skipping: " + order.itemName);
            cleanupGui();
            return true;
        }
        Trade craftOrder = craftOrderFor(order.itemName);
        if (craftOrder != null) {
            // Craft ingredient: claim into the inventory, never flip, cancel or sell.
            if (target.claimable > 0 || (target.amount > 0 && target.filled >= target.amount)) {
                claimCraftIngredient(target, craftOrder);
            }
            cleanupGui();
            return true;
        }
        MarketData.Product product = productFor(order.itemName);
        // Item mode: orders of items that are not listed are only claimed, never cancelled.
        boolean mayCancel = BazaarLimits.itemModeAllowsOrder(cfg, order.itemName) && !craftHeld(order.itemName);
        boolean outdated = mayCancel && product != null && product.bestBid > order.unitPrice + 0.05;
        boolean unprofitable = mayCancel && isBuyOrderUnprofitable(order);
        boolean fullyFilled = target.filled >= target.amount && target.amount > 0;
        boolean partiallyFilled = target.filled > 0 && !fullyFilled;
        boolean claimable = target.claimable > 0;
        if (!fullyFilled && !partiallyFilled && !outdated && !unprofitable && !claimable) {
            log("Buy order healthy - nothing to do: " + order.itemName);
            cleanupGui();
            return true;
        }
        if (!fullyFilled && !outdated && !unprofitable && (claimable || partiallyFilled)) {
            log("Buy order partly filled - claiming the filled part: " + order.itemName);
            claimEntryItems(target, order, data);
            cleanupGui();
            return true;
        }
        if (fullyFilled && !outdated && !unprofitable) {
            manualClaimFlow = true;
            boolean clicked = ScreenUtil.clickOrderSlotByIndex(target.slot, target.itemName, false, 1);
            log(clicked ? "Clicking slot " + target.slot + " (gezielt Flip): " + target.itemName
                    : "Flip click failed: " + target.itemName);
            if (!clicked) {
                manualClaimFlow = false;
                cleanupGui();
                return false;
            }
            manualClaimFlow = false;
            long claimedByClick = claimedItemsSince(start);
            if (claimedByClick > 0) {
                log("Click got " + claimedByClick + "x claimed - items in the inventory: " + order.itemName);
                cleanupGui();
                Trade batch = new Trade();
                batch.productId = ItemNames.productId(order.itemName);
                batch.displayName = order.itemName;
                batch.qty = claimedByClick;
                batch.buyPrice = order.unitPrice;
                batch.sellPrice = 0;
                batch.cost = claimedByClick * order.unitPrice;
                batch.state = Trade.State.CLAIMED;
                batch.createdAt = System.currentTimeMillis();
                ledger.add(batch);
                createSellOfferFromInventory(batch, data);
                return true;
            }
            if (waitForScreenCancel(ScreenUtil.BzScreen.ORDER_OPTIONS, 3000)) {
                if (clickByName("Flip Order", 0, 3000, "Flip Order")) {
                    double targetSell = 0;
                    if (product != null && product.bestAsk > 0) {
                        targetSell = Math.round((product.bestAsk - 0.1) * 10.0) / 10.0;
                    }
                    if (targetSell <= 0) {
                        cleanupGui();
                        return false;
                    }
                    setSign(ScreenUtil.formatPrice(targetSell));
                    boolean flipped = waitForFlipChat(12_000);
                    if (flipped) {
                        Trade trade = ensureTradeFor(order);
                        trade.sellPrice = targetSell;
                        trade.revenue = trade.qty * targetSell * (1.0 - cfg.taxPercent / 100.0);
                        trade.state = Trade.State.SELL_OPEN;
                        trade.listedAt = System.currentTimeMillis();
                        ledger.update(trade);
                        chat("\u00a76[Flipper] \u00a7aSell offer created (flip): \u00a7f" + order.itemName + " \u00a77@ \u00a7f" + ScreenUtil.formatPrice(targetSell));
                        cleanupGui();
                        return true;
                    }
                    failPause("Flip failed: " + order.itemName);
                    cleanupGui();
                    return false;
                }
                ScreenUtil.closeCurrentScreen();
                jitterSleepOrStop(400, 200);
            }
        }
        String reason = outdated ? "outbid" : "not profitable";
        Trade base = ensureTradeFor(order);
        boolean cancelled = cancelBuyOrder(base, reason, data);
        if (cancelled) {
            chat("\u00a76[Flipper] \u00a7cBuy order cancelled: \u00a7f" + order.itemName + " \u00a77(" + reason + ")");
            return true;
        }
        return false;
    }

    private void waitForOrderGuiStable(long timeoutMs) {
        checkStop();
        long deadline = System.currentTimeMillis() + timeoutMs;
        String previous = ScreenUtil.containerSignature();
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            jitterSleepOrStop(300, 200);
            String current = ScreenUtil.containerSignature();
            if (current != null && current.equals(previous)) {
                return;
            }
            previous = current;
        }
    }

    private boolean needsOrderScan(MarketData data) {
        if (recentFillChat) {
            return true;
        }
        if (!ledger.inState(Trade.State.CLAIMED).isEmpty() || !ledger.inState(Trade.State.FILLED).isEmpty()
                || !ledger.inState(Trade.State.SOLD).isEmpty() || !ledger.inState(Trade.State.SELL_OPEN).isEmpty()) {
            return true;
        }
        if (lastScanHadOrders) {
            return true;
        }
        return data.lastUpdated != lastScannedSnapshot;
    }

    private MarketData.Product productFor(String itemName) {
        if (market != null) {
            MarketData.Product direct = market.products.get(ItemNames.productId(itemName));
            if (direct != null) {
                return direct;
            }
        }
        String key = ItemNames.nameKey(itemName);
        return productByName.get(key);
    }

    private Trade findLedgerTrade(String itemName, boolean sellSide, long amount) {
        Trade best = null;
        long bestDiff = Long.MAX_VALUE;
        for (Trade trade : ledger.all()) {
            // The menu drops item-name symbols ("❈ Perfect Amethyst Gemstone" vs " Perfect Amethyst Gemstone").
            if (!trade.isOpen() || !ScreenUtil.sameName(trade.displayName, itemName)) {
                continue;
            }
            boolean tradeIsSell = trade.state == Trade.State.SELL_OPEN
                    || trade.state == Trade.State.SOLD || trade.state == Trade.State.CLAIMED;
            if (tradeIsSell != sellSide) {
                continue;
            }
            long diff = amount <= 0 ? 0 : Math.abs(trade.qty - amount);
            if (best == null || diff < bestDiff) {
                best = trade;
                bestDiff = diff;
            }
        }
        return best;
    }

    private Trade ensureTradeFor(ScreenUtil.GuiOrder order) {
        Trade matched = findLedgerTrade(order.itemName, order.sellSide, order.amount);
        if (matched != null) {
            return matched;
        }
        Trade trade = new Trade();
        trade.productId = ItemNames.productId(order.itemName);
        trade.displayName = order.itemName;
        trade.qty = order.amount;
        trade.buyPrice = order.sellSide ? 0 : order.unitPrice;
        trade.sellPrice = order.sellSide ? order.unitPrice : 0;
        trade.cost = order.sellSide ? 0 : order.amount * order.unitPrice;
        trade.createdAt = System.currentTimeMillis();
        trade.state = order.sellSide ? Trade.State.SELL_OPEN
                : (order.amount > 0 && order.filled >= order.amount ? Trade.State.FILLED : Trade.State.BUY_OPEN);
        ledger.add(trade);
        log("New trade from the order scan: " + order.itemName + " (" + trade.state + ")");
        return trade;
    }

    private int findOrderSlotRetry(String displayName, boolean sellSide, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            int slot = ScreenUtil.findOrderSlot(displayName, sellSide);
            if (slot >= 0) {
                return slot;
            }
            sleepOrStop(400);
        }
        return -1;
    }

    private long waitForClaimedItems(long timeoutMs) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        int noneStreak = 0;
        while (System.currentTimeMillis() < deadline) {
            beat();
            for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
                if (entry.event != null && entry.event.type == BazaarChatEvent.Type.CLAIMED_ITEMS
                        && entry.event.qty > 0) {
                    return entry.event.qty;
                }
            }
            if (stuck()) {
                return 0;
            }
            ScreenUtil.BzScreen current = ScreenUtil.currentScreen();
            if (current == ScreenUtil.BzScreen.NONE) {
                noneStreak++;
                if (noneStreak > 18) {
                    traderOwnsGui = false;
                    log("Menu closed from outside while waiting for the claim");
                    return 0;
                }
            } else {
                noneStreak = 0;
                if (current != ScreenUtil.BzScreen.ORDERS && current != ScreenUtil.BzScreen.ORDER_OPTIONS
                        && current != ScreenUtil.BzScreen.SIGN) {
                    log("Screen left the orders menu while waiting for the claim: " + current
                            + " " + ScreenUtil.screenDebugInfo());
                    return 0;
                }
            }
            sleepOrStop(150);
        }
        return 0;
    }

    private long claimedItemsSince(long start) {
        for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
            if (entry.event != null && entry.event.type == BazaarChatEvent.Type.CLAIMED_ITEMS
                    && entry.event.qty > 0) {
                return entry.event.qty;
            }
        }
        return 0;
    }

    private boolean inventoryFullSince(long start) {
        for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
            if (entry.event != null && entry.event.type == BazaarChatEvent.Type.INVENTORY_FULL) {
                return true;
            }
        }
        return false;
    }


    private boolean waitForFlipChat(long timeoutMs) {
        checkStop();
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            beat();
            for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
                if (entry.text.contains("Order Flipped!") || entry.text.contains("Sell Offer Setup!")) {
                    return true;
                }
            }
            if (stuck()) {
                return false;
            }
            if (ScreenUtil.currentScreen() == ScreenUtil.BzScreen.SELL_CONFIRM) {
                if (clickConfirmAtomic("Sell Offer", 4000)) {
                    continue;
                }
            }
            sleepOrStop(150);
        }
        for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
            if (entry.text.contains("Order Flipped!") || entry.text.contains("Sell Offer Setup!")) {
                return true;
            }
        }
        return false;
    }


    private void handleMissingOrder(Trade trade, MarketData data) {
        trade.notFoundChecks++;
        ledger.update(trade);
        if (trade.notFoundChecks >= 5) {
            ledger.remove(trade);
            log("Order no longer exists - trade removed: " + trade.displayName);
        }
        cleanupGui();
    }

    private boolean waitForCancelled(long timeoutMs) {
        checkStop();
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        long lastConfirmClick = 0;
        int foreignStreak = 0;
        while (System.currentTimeMillis() < deadline) {
            beat();
            if (cancelledChatSince(start)) {
                return true;
            }
            if (stuck()) {
                return false;
            }
            ScreenUtil.BzScreen current = ScreenUtil.currentScreen();
            if (current == ScreenUtil.BzScreen.BAZAAR || current == ScreenUtil.BzScreen.PRODUCT
                    || current == ScreenUtil.BzScreen.SEARCH || current == ScreenUtil.BzScreen.BUY_AMOUNT
                    || current == ScreenUtil.BzScreen.BUY_PRICE || current == ScreenUtil.BzScreen.BUY_CONFIRM
                    || current == ScreenUtil.BzScreen.SELL_AMOUNT || current == ScreenUtil.BzScreen.SELL_PRICE
                    || current == ScreenUtil.BzScreen.SELL_CONFIRM || current == ScreenUtil.BzScreen.SIGN) {
                foreignStreak++;
                if (foreignStreak > 20) {
                    log("Screen left the orders flow while waiting for the cancel");
                    return false;
                }
            } else {
                foreignStreak = 0;
            }
            String title = ScreenUtil.screenTitle();
            if (title != null && title.toLowerCase().contains("confirm")) {
                long now = System.currentTimeMillis();
                if (now - lastConfirmClick > 1500) {
                    int confirm = ScreenUtil.findButton("Confirm");
                    if (confirm >= 0) {
                        click(confirm, 0);
                        lastConfirmClick = now;
                    }
                }
            }
            sleepOrStop(150);
        }
        return cancelledChatSince(start);
    }

    /** A player writing "cancelled", or an auction line, is no cancelled order. */
    private boolean cancelledChatSince(long start) {
        for (BazaarChatListener.ChatEntry entry : BazaarChatListener.since(start)) {
            if (BazaarChatEvent.isBazaarLine(entry.text)
                    && entry.text.toLowerCase().contains("cancelled")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cancels a buy order. It can keep filling meanwhile: every click claims what filled since, and those claims are
     * listed as one sell offer at the end. If the inventory fills up, the claimed items are listed first.
     */
    private boolean cancelBuyOrder(Trade trade, String reason, MarketData data) {
        checkStop();
        Trade claimed = null;
        for (int round = 0; round < 4 && !stopRequested; round++) {
            if (!openOrdersGui()) {
                listClaimed(claimed, data);
                return false;
            }
            int slot = findOrderSlotRetry(trade.displayName, false, 4000);
            if (slot < 0) {
                handleMissingOrder(trade, data);
                listClaimed(claimed, data);
                return false;
            }
            trade.notFoundChecks = 0;
            ledger.update(trade);
            manualClaimFlow = true;
            long clickAt = System.currentTimeMillis();
            if (!clickOrder(trade.displayName, false, 0, 5000, "Order-Eintrag (Cancel): " + trade.displayName)) {
                manualClaimFlow = false;
                break;
            }
            long claimedNow = waitForClaimedItems(3000);
            manualClaimFlow = false;
            if (claimedNow > 0) {
                log("Cancel-Flow: " + claimedNow + "x claimed: " + trade.displayName);
                claimed = addClaimed(claimed, trade, claimedNow);
                if (inventoryFullSince(clickAt) || ScreenUtil.freeInventorySlots() < 2) {
                    log("Inventory full while claiming - selling first, cancelling later: " + trade.displayName);
                    cleanupGui();
                    listClaimed(claimed, data);
                    return false;
                }
                cleanupGui();
                continue;
            }
            if (ScreenUtil.currentScreen() == ScreenUtil.BzScreen.ORDER_OPTIONS
                    || waitForScreenCancel(ScreenUtil.BzScreen.ORDER_OPTIONS, 2000)) {
                boolean done = confirmCancel(trade, reason);
                listClaimed(claimed, data);
                return done;
            }
            if (!clickOrder(trade.displayName, false, 1, 5000, "Order entry right click (cancel): " + trade.displayName)) {
                break;
            }
            if (waitForClaimedItems(3000) > 0) {
                continue;
            }
            if (ScreenUtil.currentScreen() == ScreenUtil.BzScreen.ORDER_OPTIONS
                    || waitForScreenCancel(ScreenUtil.BzScreen.ORDER_OPTIONS, 3000)) {
                boolean done = confirmCancel(trade, reason);
                listClaimed(claimed, data);
                return done;
            }
            break;
        }
        cleanupGui();
        if (claimed != null) {
            // It kept filling while we tried to cancel: sell what we have, cancel next pass.
            listClaimed(claimed, data);
            return false;
        }
        failPause("Cancel flow unclear: " + trade.displayName);
        return false;
    }

    /** Adds a claim from {@code order} to the collected batch (one ledger trade). */
    private Trade addClaimed(Trade batch, Trade order, long qty) {
        if (batch == null) {
            batch = new Trade();
            batch.productId = order.productId;
            batch.displayName = order.displayName;
            batch.buyPrice = order.buyPrice;
            batch.sellPrice = order.sellPrice;
            batch.state = Trade.State.CLAIMED;
            batch.createdAt = System.currentTimeMillis();
            batch.qty = qty;
            batch.cost = qty * order.buyPrice;
            ledger.add(batch);
        } else {
            batch.qty += qty;
            batch.cost = batch.qty * batch.buyPrice;
            ledger.update(batch);
        }
        order.qty = Math.max(0, order.qty - qty);
        order.cost = order.qty * order.buyPrice;
        ledger.update(order);
        chat("§6[Flipper] §aClaimed: §f" + qty + "x " + order.displayName);
        return batch;
    }

    private void listClaimed(Trade batch, MarketData data) {
        if (batch != null && data != null && !stopRequested) {
            cleanupGui();
            createSellOfferFromInventory(batch, data);
        }
    }

    private boolean confirmCancel(Trade trade, String reason) {
        boolean cancelled = false;
        for (int attempt = 0; attempt < 3 && !cancelled && !stopRequested; attempt++) {
            if (!clickByName("Cancel", 0, 4000, "Cancel-Button")) {
                break;
            }
            cancelled = waitForCancelled(3000);
            if (!cancelled) {
                log("Cancel click may not have gone through - retrying (" + attempt + ")");
            }
        }
        if (cancelled) {
            trade.state = Trade.State.CANCELLED;
            ledger.update(trade);
            chat("\u00a76[Flipper] \u00a7cBuy order cancelled: \u00a7f" + trade.displayName + " \u00a77(" + ScreenUtil.formatCoins(trade.cost) + ")");
            cleanupGui();
            return true;
        }
        failPause("Cancel not confirmed: " + trade.displayName);
        cleanupGui();
        return false;
    }

    /**
     * Cancels a sell offer of this item. With {@code exact}, the offer with that price and amount
     * is clicked (an item can now have several offers); otherwise the first one.
     */
    private void cancelSellOffer(Trade trade, ScreenUtil.GuiOrder exact) {
        checkStop();
        if (!openOrdersGui()) {
            return;
        }
        waitForOrderGuiStable(2500);
        int slot = findOrderSlotRetry(trade.displayName, true, 4000);
        if (slot < 0) {
            handleMissingOrder(trade, null);
            return;
        }
        trade.notFoundChecks = 0;
        ledger.update(trade);
        ScreenUtil.GuiOrder target = null;
        if (exact != null) {
            for (ScreenUtil.GuiOrder entry : ScreenUtil.scanOrders()) {
                if (entry.sellSide && entry.amount == exact.amount
                        && Math.abs(entry.unitPrice - exact.unitPrice) < 0.05
                        && ScreenUtil.fuzzyItemMatch(entry.itemName, exact.itemName)) {
                    target = entry;
                    break;
                }
            }
        }
        boolean clicked = target != null
                ? ScreenUtil.clickOrderSlotByIndex(target.slot, target.itemName, true, 0)
                : clickOrder(trade.displayName, true, 0, 5000, "Sell-Eintrag (Cancel): " + trade.displayName);
        if (!clicked) {
            failPause("Order entry not clickable (sell cancel): " + trade.displayName);
            return;
        }
        if (!waitForScreenCancel(ScreenUtil.BzScreen.ORDER_OPTIONS, 3000)) {
            if (!clickOrder(trade.displayName, true, 1, 5000, "Sell entry right click: " + trade.displayName)) {
                failPause("Order options not reachable (sell cancel): " + trade.displayName);
                return;
            }
            if (!waitForScreenCancel(ScreenUtil.BzScreen.ORDER_OPTIONS, 3000)) {
                failPause("Order options not reachable (sell cancel): " + trade.displayName);
                return;
            }
        }
        boolean cancelled = false;
        for (int attempt = 0; attempt < 3 && !cancelled && !stopRequested; attempt++) {
            if (!clickByName("Cancel", 0, 4000, "Cancel-Button (Sell)")) {
                break;
            }
            cancelled = waitForCancelled(3000);
        }
        if (cancelled) {
            // Hypixel keeps a cancelled offer on the daily limit, so it is not subtracted.
            trade.state = Trade.State.CLAIMED;
            ledger.update(trade);
            List<Double> prices = ownSellPrices.get(sellKey(trade.displayName));
            if (prices != null && exact != null) {
                prices.removeIf(price -> Math.abs(price - exact.unitPrice) < 0.05);
            }
            chat("\u00a76[Flipper] \u00a77Sell offer cancelled for repricing: \u00a7f" + trade.displayName);
        } else {
            failPause("Sell cancel not confirmed: " + trade.displayName);
        }
        cleanupGui();
    }

    private boolean createSellOfferFromInventory(Trade trade, MarketData data) {
        checkStop();
        if (sellPendingSince == 0) {
            sellPendingSince = System.currentTimeMillis();
        }
        refreshMarketIfStale();
        MarketData fresh = market != null ? market : data;
        if (!openBazaar()) {
            return false;
        }
        if (!searchProduct(trade.displayName, trade.productId)) {
            ;
            failPause("Item not found (sell): " + trade.displayName);
            return false;
        }
        if (ScreenUtil.currentScreen() != ScreenUtil.BzScreen.PRODUCT) {
            failPause("Not on the product page (sell): " + trade.displayName);
            return false;
        }
        log("Product page (sell): " + trade.displayName + " " + ScreenUtil.screenDebugInfo());
        boolean sellClicked = clickByName("Create Sell Offer", 0, 5000, "Create Sell Offer");
        if (!sellClicked) {
            sellClicked = clickByName("Sell Offer", 0, 3000, "Sell Offer");
        }
        if (!sellClicked) {
            sellClicked = clickByName("Create Offer", 0, 3000, "Create Offer");
        }
        if (!sellClicked) {
            sellClicked = clickByName("Create Sell", 0, 3000, "Create Sell");
        }
        if (!sellClicked) {
            String slot16 = ScreenUtil.slotName(16);
            if (slot16 != null && !slot16.toLowerCase().contains("search")
                    && !slot16.toLowerCase().contains("close") && !slot16.toLowerCase().contains("go back")
                    && !slot16.toLowerCase().contains("instantly")
                    && !slot16.toLowerCase().contains("create buy")) {
                log("Fallback: clicking slot 16 (sell) for " + trade.displayName + " Item: " + slot16);
                clickDelay();
                if (!isAhPriority()) {
                    ScreenUtil.clickSlot(16, 0);
                    sellClicked = true;
                }
            }
        }
        if (!sellClicked) {
            failPause("Sell button not found: " + trade.displayName);
            return false;
        }
        long inInventory = ScreenUtil.countItemsInInventory(trade.displayName);
        if (inInventory <= 0) {
            log("No items in the inventory for " + trade.displayName + ". Inventory names: "
                    + ScreenUtil.inventoryItemNames());
            failPause("No items in the inventory: " + trade.displayName);
            return false;
        }
        learnStacking(trade.displayName);
        long qty = trade.qty > 0 ? Math.min(trade.qty, inInventory) : inInventory;
        trade.qty = qty;
        trade.cost = qty * trade.buyPrice;
        ledger.update(trade);
        if (!waitForScreenCancel(ScreenUtil.BzScreen.SELL_PRICE, 4000)) {
            if (waitForScreenCancel(ScreenUtil.BzScreen.SELL_AMOUNT, 4000)) {
                setSign(String.valueOf(qty));
                if (!clickSign(5000, "Amount sign (sell)")) {
                    failPause("Amount sign not found (sell): " + trade.displayName);
                    return false;
                }
                if (!waitForScreenCancel(ScreenUtil.BzScreen.SELL_PRICE, 6000)) {
                    failPause("SELL_PRICE not reached: " + trade.displayName);
                    return false;
                }
            } else {
                failPause("Sell price page not reached: " + trade.displayName);
                return false;
            }
        }
        sleepOrStop(ScreenUtil.jitter(400));
        log("SELL_PRICE page for: " + trade.displayName + " " + ScreenUtil.screenDebugInfo());
        double targetSell = trade.sellPrice;
        MarketData.Product product = fresh.products.get(trade.productId);
        if (product == null) {
            product = productFor(trade.displayName);
        }
        if (product != null && product.bestAsk > 0) {
            double freshPrice = Math.round((product.bestAsk - 0.1) * 10.0) / 10.0;
            if (freshPrice > 0) {
                targetSell = freshPrice;
            }
        }
        // "Same as Best Offer" shows the best offer's unit price. If that is our own offer,
        // take the same price: undercutting ourselves only lowered the price step by step
        // and left several offers at different prices that later got merged.
        double bestOffer = ScreenUtil.buttonUnitPrice("Same as Best Offer");
        if (bestOffer > 0 && ownsSellPrice(trade.displayName, bestOffer)
                && clickExactName("Same as Best Offer", 0, 3000, "Same as Best Offer")) {
            log("Best offer is already ours (" + ScreenUtil.formatPrice(bestOffer)
                    + ") - same price instead of -0.1: " + trade.displayName);
            return confirmSellOffer(trade, bestOffer);
        }
        if (bestOffer > 0) {
            targetSell = Math.round((bestOffer - 0.1) * 10.0) / 10.0;
        }
        double floor = profitFloor(trade);
        if (floor > 0 && targetSell < floor) {
            // The market is below what this cost after tax: list at break-even.
            log("Never sell at a loss: best offer " + targetSell + " is below break-even " + floor
                    + " - listing at " + floor + ": " + trade.displayName);
            setSign(ScreenUtil.formatPrice(floor));
            if (!clickSign(5000, "Price sign (sell)")) {
                failPause("Price sign not found (sell): " + trade.displayName);
                return false;
            }
            return confirmSellOffer(trade, floor);
        }
        boolean usedUndercut = clickByName("Best Offer -0.1", 0, 3000, "Best Offer -0.1");
        if (!usedUndercut) {
            usedUndercut = clickByName("Below", 0, 3000, "Below");
        }
        if (!usedUndercut) {
            usedUndercut = clickByName("0.1 Coins Less", 0, 3000, "0.1 Coins Less");
        }
        if (usedUndercut) {
            log("Using the -0.1 preset for: " + trade.displayName);
        } else {
            boolean usedPreset = clickByName("Best Offer", 0, 3000, "Best Offer");
            if (!usedPreset) {
                usedPreset = clickByName("Top Offer", 0, 3000, "Top Offer");
            }
            if (!usedPreset) {
                usedPreset = clickByName("Match", 0, 3000, "Match");
            }
            if (!usedPreset) {
                usedPreset = clickByName("Best", 0, 3000, "Best");
            }
            if (usedPreset) {
                log("Using the price preset for: " + trade.displayName);
                targetSell = Math.round((targetSell + 0.1) * 10.0) / 10.0;
            } else {
                log("No price preset found - using the sign: " + trade.displayName);
                setSign(ScreenUtil.formatPrice(targetSell));
                if (!clickSign(5000, "Price sign (sell)")) {
                    failPause("Price sign not found (sell): " + trade.displayName);
                    return false;
                }
            }
        }
        return confirmSellOffer(trade, targetSell);
    }

    /** Sell price chosen: confirm the offer and wait for Hypixel's "Sell Offer Setup!". */
    private boolean confirmSellOffer(Trade trade, double targetSell) {
        if (!waitForScreenCancel(ScreenUtil.BzScreen.SELL_CONFIRM, 6000)) {
            failPause("SELL_CONFIRM not reached: " + trade.displayName);
            return false;
        }
        sleepOrStop(ScreenUtil.jitter(400));
        if (!clickConfirmAtomic("Sell Offer", 8000)) {
            failPause("Sell confirm button not found: " + trade.displayName);
            return false;
        }
        if (!waitForConfirmChat("Sell Offer Setup!", 12_000)) {
            if (orderCooldownActive()) {
                // Not our fault and not stuck: the items stay in the inventory and are
                // listed once the cooldown is over.
                log("Bazaar cooldown - sell offer later: " + trade.displayName);
                cleanupGui();
                return false;
            }
            failPause("No 'Sell Offer Setup!' confirmation: " + trade.displayName);
            return false;
        }
        rememberOwnSellPrice(trade.displayName, targetSell);
        trade.sellPrice = targetSell;
        trade.revenue = trade.qty * targetSell * (1.0 - cfg.taxPercent / 100.0);
        trade.state = Trade.State.SELL_OPEN;
        trade.listedAt = System.currentTimeMillis();
        ledger.update(trade);
        chat("\u00a76[Flipper] \u00a7aSell offer created: \u00a7f" + trade.qty + "x " + trade.displayName + " \u00a77@ \u00a7f" + ScreenUtil.formatPrice(targetSell));
        cleanupGui();
        return true;
    }

    /**
     * Inventory full (Hypixel said so, or at most one free slot): everything else waits. Every Bazaar item in the
     * inventory gets a sell offer, then the item stash is picked up so its items can be listed next cycle.
     */
    private boolean clearFullInventory(MarketData data) {
        long now = System.currentTimeMillis();
        boolean signal = inventoryFullAt > 0 && now - inventoryFullAt < 15 * 60_000L;
        int free = ScreenUtil.freeInventorySlots();
        if ((!signal && free > 1) || now < inventoryClearBlockedUntil || data == null) {
            if (!signal && free > 1) {
                inventoryClearPasses = 0;
            }
            return false;
        }
        if (BazaarDailyLimit.sellLimitReached()) {
            logInventory("Inventory full, but the daily sell limit is reached - can't list anything");
            inventoryClearBlockedUntil = now + 10 * 60_000L;
            return false;
        }
        if (inventoryClearPasses >= 3) {
            chat("\u00a76[Flipper] \u00a7cInventory still full after 3 tries \u00a77- back to normal flipping for 10 min.");
            inventoryClearPasses = 0;
            inventoryFullAt = 0;
            inventoryClearBlockedUntil = now + 10 * 60_000L;
            return false;
        }
        List<Trade> items = bazaarItemsInInventory(data);
        if (items.isEmpty()) {
            if (stashPending) {
                pickupStash();
                return true;
            }
            logInventory("Inventory full, but no Bazaar items in it (free: " + free + ")");
            inventoryFullAt = 0;
            inventoryClearBlockedUntil = now + 5 * 60_000L;
            return false;
        }
        inventoryClearPasses++;
        chat("\u00a76[Flipper] \u00a7eInventory full \u00a77- selling " + items.size()
                + " Bazaar item(s) from the inventory first");
        int listed = 0;
        for (Trade trade : items) {
            checkStop();
            if (isAhPriority()) {
                return true;
            }
            if (createSellOfferFromInventory(trade, data)) {
                listed++;
            }
        }
        if (stashPending) {
            pickupStash();
        } else if (ScreenUtil.freeInventorySlots() > 1) {
            inventoryFullAt = 0;
        }
        if (listed == items.size()) {
            inventoryClearPasses = 0;
        }
        return true;
    }

    private void logInventory(String text) {
        long now = System.currentTimeMillis();
        if (now - lastInventoryLogAt > 60_000L) {
            lastInventoryLogAt = now;
            log(text);
        }
    }

    /** Items that did not fit went to Hypixel's item stash: fetch them to sell them too. */
    private void pickupStash() {
        stashPending = false;
        cleanupGui();
        log("Picking up the item stash (/pickupstash)");
        sendCommand("pickupstash");
        sleepOrStop(1500);
        inventoryFullAt = System.currentTimeMillis();
    }

    /** Every Bazaar product in the player inventory, as trades ready for a sell offer. */
    private List<Trade> bazaarItemsInInventory(MarketData data) {
        Map<String, Long> counts = ScreenUtil.sync(() -> {
            Map<String, Long> found = new java.util.LinkedHashMap<>();
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client.player == null) {
                return found;
            }
            for (int i = 0; i < 36; i++) {
                net.minecraft.world.item.ItemStack stack = client.player.getInventory().getItem(i);
                String id = bazaarProductId(stack, data);
                if (id != null) {
                    found.merge(id, (long) stack.getCount(), Long::sum);
                }
            }
            return found;
        });
        List<Trade> result = new ArrayList<>();
        if (counts == null) {
            return result;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (com.exkor.flipbot.craft.CraftBatch.holds(entry.getKey())) {
                continue; // ingredients waiting to be crafted
            }
            MarketData.Product product = data.products.get(entry.getKey());
            if (product == null || product.bestAsk <= 0) {
                continue;
            }
            String name = ItemNames.displayName(entry.getKey());
            Trade trade = ledger.firstMatching(name, Trade.State.FILLED, Trade.State.CLAIMED);
            if (trade == null) {
                trade = new Trade();
                trade.productId = entry.getKey();
                trade.displayName = name;
                trade.buyPrice = product.bestBid;
                trade.state = Trade.State.CLAIMED;
                trade.createdAt = now;
                trade.qty = entry.getValue();
                trade.cost = trade.qty * trade.buyPrice;
                ledger.add(trade);
            } else {
                trade.qty = entry.getValue();
            }
            trade.sellPrice = product.bestAsk;
            result.add(trade);
        }
        return result;
    }

    /** SkyBlock id of the stack if the Bazaar trades it (enchanted books by their enchant). */
    private static String bazaarProductId(net.minecraft.world.item.ItemStack stack, MarketData data) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String tag = com.exkor.flipbot.flipper.data.SkyblockId.of(stack);
        if (tag != null && !tag.isBlank() && data.products.containsKey(tag)) {
            return tag;
        }
        String name = ScreenUtil.cleanName(stack.getHoverName().getString());
        if ("ENCHANTED_BOOK".equals(tag)) {
            var lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                name = ScreenUtil.cleanName(lore.lines().getFirst().getString());
            }
        }
        String byName = ItemNames.productId(name);
        return byName != null && data.products.containsKey(byName) ? byName : null;
    }

    private void maybeReconcile() {
        List<Trade> sellable = ledger.inState(Trade.State.CLAIMED);
        if (!sellable.isEmpty() && !stopRequested) {
            refreshMarketIfStale();
            MarketData data = market;
            for (Trade trade : sellable) {
                beat();
                long nowMs = System.currentTimeMillis();
                if (nowMs - trade.lastRelistedAt < CLAIM_LIST_RETRY_MS) {
                    continue;
                }
                trade.lastRelistedAt = nowMs;
                ledger.update(trade);
                if (trade.craftIngredient) {
                    continue; // held for a craft
                }
                if (ScreenUtil.countItemsInInventory(trade.displayName) <= 0) {
                    ;
                    trade.state = Trade.State.ABANDONED;
                    ledger.update(trade);
                    continue;
                }
                if (data != null) {
                    createSellOfferFromInventory(trade, data);
                }
                if (stopRequested) {
                    return;
                }
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastReconciliationAt < RECONCILE_INTERVAL_MS) {
            return;
        }
        List<ScreenUtil.GuiOrder> orders = lastScannedOrders;
        if (now - lastOrdersScanAt > 30_000L) {
            if (!openOrdersGui()) {
                return;
            }
            waitForOrderGuiStable(2500);
            orders = ScreenUtil.scanOrders();
            lastScannedOrders = List.copyOf(orders);
            lastOrdersScanAt = System.currentTimeMillis();
            cleanupGui();
        }
        if (orders == null) {
            orders = List.of();
        }
        Set<ScreenUtil.GuiOrder> used = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Trade trade : ledger.all()) {
            beat();
            if (!trade.isOpen() || trade.state == Trade.State.PLANNED || trade.state == Trade.State.CLAIMED) {
                continue;
            }
            if (trade.createdAt > lastOrdersScanAt) {
                continue;
            }
            boolean sellSide = trade.state == Trade.State.SELL_OPEN || trade.state == Trade.State.SOLD;
            ScreenUtil.GuiOrder match = findMatchingOrder(orders, trade.displayName, sellSide, used);
            if (match == null) {
                trade.notFoundChecks++;
                ledger.update(trade);
                if (trade.notFoundChecks >= 5) {
                    ledger.remove(trade);
                    ;
                }
                continue;
            }
            used.add(match);
            trade.notFoundChecks = 0;
            trade.filledQty = match.filled;
            if (trade.state == Trade.State.BUY_OPEN && !trade.craftIngredient && match.filled >= match.amount && match.amount > 0) {
                trade.state = Trade.State.FILLED;
                trade.filledAt = System.currentTimeMillis();
                chat("\u00a76[Flipper] \u00a7aBuy order filled: \u00a7f" + trade.qty + "x " + trade.displayName);
            }
            if (trade.state == Trade.State.SELL_OPEN && match.filled >= match.amount && match.amount > 0) {
                trade.state = Trade.State.SOLD;
                trade.soldAt = System.currentTimeMillis();
                markProductSuccess(trade.productId);
                chat("\u00a76[Flipper] \u00a7aSell offer sold: \u00a7f" + trade.displayName + " \u00a77Revenue \u00a7f" + ScreenUtil.formatCoins(trade.revenue));
            }
            ledger.update(trade);
        }
        cleanupGui();
        lastReconciliationAt = System.currentTimeMillis();
    }

    private ScreenUtil.GuiOrder findMatchingOrder(List<ScreenUtil.GuiOrder> orders, String displayName,
            boolean sellSide, Set<ScreenUtil.GuiOrder> used) {
        for (ScreenUtil.GuiOrder order : orders) {
            if (used.contains(order) || order.sellSide != sellSide || order.itemName == null) {
                continue;
            }
            if (order.itemName.equalsIgnoreCase(displayName)
                    || ScreenUtil.fuzzyItemMatch(order.itemName, displayName)) {
                return order;
            }
        }
        return null;
    }

    public synchronized void onChatEvent(BazaarChatEvent event) {
        if (event == null) {
            return;
        }
        if (event.type == BazaarChatEvent.Type.DAILY_SELL_LIMIT) {
            // "You reached the daily limit in items value that you may sell on the bazaar!"
            if (state == State.RUNNING) {
                stopBecause("Daily Bazaar sell limit reached");
            }
            return;
        }
        switch (event.type) {
            case ORDER_LIMIT, INVENTORY_FULL, DAILY_BUY_LIMIT, COOLDOWN, OTHER -> {
            }
            default -> troubleSince = 0; // a Bazaar action went through
        }
        if (event.type == BazaarChatEvent.Type.SELL_SETUP || event.type == BazaarChatEvent.Type.FLIPPED) {
            sellPendingSince = 0;
        }
        try {
            switch (event.type) {
                case BUY_SETUP -> {
                    Trade trade = ledger.firstMatching(event.item, Trade.State.PLANNED);
                    if (trade != null) {
                        trade.state = Trade.State.BUY_OPEN;
                        trade.createdAt = System.currentTimeMillis();
                        if (event.price > 0) {
                            trade.cost = event.price;
                        }
                        ledger.update(trade);
                    }
                }
                case SELL_SETUP, FLIPPED -> {
                    Trade trade = ledger.firstMatching(event.item, Trade.State.FILLED, Trade.State.CLAIMED);
                    if (trade != null) {
                        trade.state = Trade.State.SELL_OPEN;
                        trade.listedAt = System.currentTimeMillis();
                        if (event.price > 0) {
                            trade.revenue = event.price * (1.0 - cfg.taxPercent / 100.0);
                        }
                        ledger.update(trade);
                    }
                }
                case BUY_FILLED -> {
                    Trade trade = ledger.firstMatching(event.item, Trade.State.BUY_OPEN);
                    if (trade != null && trade.craftIngredient) {
                        recentFillChat = true; // claimed by the next order scan, never flipped
                        if (event.qty > 0) {
                            trade.filledQty = event.qty;
                        }
                        ledger.update(trade);
                        break;
                    }
                    if (trade != null) {
                        trade.state = Trade.State.FILLED;
                        trade.filledAt = System.currentTimeMillis();
                        if (event.qty > 0) {
                            trade.filledQty = event.qty;
                        }
                        ledger.update(trade);
                        chat("\u00a76[Flipper] \u00a7aBuy order filled: \u00a7f" + trade.filledQty + "x " + trade.displayName);
                    } else {
                        recentFillChat = true;
                        log("Filled order found (no ledger match): " + event.item);
                    }
                }
                case SELL_FILLED -> {
                    Trade trade = ledger.firstMatching(event.item, Trade.State.SELL_OPEN);
                    if (trade != null) {
                        trade.state = Trade.State.SOLD;
                        trade.soldAt = System.currentTimeMillis();
                        markProductSuccess(trade.productId);
                        ledger.update(trade);
                        chat("\u00a76[Flipper] \u00a7aSell offer sold: \u00a7f" + trade.displayName + " \u00a77Revenue \u00a7f" + ScreenUtil.formatCoins(trade.revenue));
                    } else {
                        recentFillChat = true;
                        log("Sold order found (no ledger match): " + event.item);
                    }
                }
                case CLAIMED_COINS -> {
                    orderLimitReached = false;
                    Trade trade = null;
                    if (event.item != null && !event.item.isBlank()) {
                        trade = ledger.firstMatching(event.item, Trade.State.SOLD);
                    }
                    if (trade == null) {
                        List<Trade> sold = ledger.inState(Trade.State.SOLD);
                        if (!sold.isEmpty()) {
                            trade = sold.get(0);
                        }
                    }
                    if (trade != null) {
                        trade.state = Trade.State.DONE;
                        markProductSuccess(trade.productId);
                        ledger.update(trade);
                    }
                }
                case CLAIMED_ITEMS -> {
                    orderLimitReached = false;
                    if (manualClaimFlow) {
                        break;
                    }
                    Trade trade = ledger.firstMatching(event.item, Trade.State.FILLED);
                    if (trade != null && trade.craftIngredient) {
                        break;
                    }
                    if (trade != null) {
                        trade.state = Trade.State.CLAIMED;
                        ledger.update(trade);
                    }
                }
                case CANCELLED, EXPIRED -> {
                    orderLimitReached = false;
                    Trade trade = event.side == BazaarChatEvent.Side.SELL
                            ? ledger.firstMatching(event.item, Trade.State.SELL_OPEN)
                            : event.side == BazaarChatEvent.Side.BUY
                            ? ledger.firstMatching(event.item, Trade.State.BUY_OPEN, Trade.State.FILLED)
                            : ledger.firstMatching(event.item, Trade.State.BUY_OPEN, Trade.State.SELL_OPEN, Trade.State.FILLED);
                    if (trade != null) {
                        boolean wasBuy = trade.state == Trade.State.BUY_OPEN;
                        trade.state = wasBuy ? Trade.State.CANCELLED : Trade.State.CLAIMED;
                        ledger.update(trade);
                        if (wasBuy) {
                            chat("\u00a76[Flipper] \u00a7cBuy order cancelled: \u00a7f" + trade.displayName);
                        }
                    }
                }
                case INVENTORY_FULL -> {
                    inventoryFullAt = System.currentTimeMillis();
                    if (event.raw != null && event.raw.toLowerCase().contains("stash")) {
                        stashPending = true;
                    }
                    log("Inventory full according to chat: " + event.raw);
                }
                case COOLDOWN -> {
                    long now = System.currentTimeMillis();
                    boolean fresh = now >= orderCooldownUntil;
                    orderCooldownAt = now;
                    // "for up to 1 minute": wait the full minute plus a little.
                    orderCooldownUntil = now + 65_000L;
                    log("Bazaar-Cooldown: " + event.raw);
                    if (fresh) {
                        chat("§6[Flipper] §eBazaar order cooldown §7- waiting 1 min, then it goes on.");
                    }
                }
                case ORDER_LIMIT -> {
                    orderLimitReached = true;
                    lastScannedOrderCount = Math.max(lastScannedOrderCount, cfg.maxOrders);
                    log("Order limit reached (max " + cfg.maxOrders + " orders) - waiting for claim/cancel");
                }
                default -> {
                }
            }
        } catch (Exception e) {
            System.out.println("[Flipper] chat event error: " + e.getMessage());
        }
    }
}







