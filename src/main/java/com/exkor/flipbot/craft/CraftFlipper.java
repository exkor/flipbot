package com.exkor.flipbot.craft;

import com.exkor.flipbot.flipper.Bankroll;
import com.exkor.flipbot.flipper.FlipperMod;
import com.exkor.flipbot.flipper.data.BazaarApi;
import com.exkor.flipbot.flipper.data.MarketData;
import com.exkor.flipbot.flipper.trading.ScreenUtil;
import com.exkor.flipbot.flipper.trading.Trader;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Craft flipper, stage 1: finds profitable Bazaar crafts and shows which recipes are unlocked, from the player's
 * collection tiers (scanned once, then kept current from chat). Nothing is bought yet.
 */
public final class CraftFlipper {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile List<CraftAnalyzer.Plan> plans = List.of();
    private static volatile long updatedAt;
    private static volatile String status = "";
    private static volatile double budget;
    private static long onSkyBlockSince;
    private static boolean autoScanTried;

    private CraftFlipper() {}

    public static List<CraftAnalyzer.Plan> plans() { return plans; }
    public static long updatedAt() { return updatedAt; }
    public static String status() { return status; }
    public static double budget() { return budget; }
    public static boolean busy() { return BUSY.get() || CollectionScanner.running(); }

    /** One line about the batch in progress (practice or live), or null when none is running. */
    public static String activeSummary() {
        if (FlipperMod.CONFIG.practiceMode) {
            var c = FlipperMod.practice().openCraft();
            if (c == null) return null;
            if (c.selling) {
                return String.format(java.util.Locale.ROOT, "Practice: selling %d/%d %s", (long) Math.floor(c.sold), c.units, c.name);
            }
            double have = 0, need = 0;
            for (var e : c.need.entrySet()) {
                need += e.getValue();
                have += Math.min(e.getValue(), c.filled.getOrDefault(e.getKey(), 0.0));
            }
            return String.format(java.util.Locale.ROOT, "Practice: buying ingredients for %d× %s (%.0f%%)",
                    c.crafts, c.name, need > 0 ? have / need * 100 : 0);
        }
        CraftBatch b = CraftBatch.current();
        if (b == null) return null;
        String name = com.exkor.flipbot.flipper.data.ItemNames.displayName(b.output);
        return b.state == CraftBatch.State.CRAFTING ? "Crafting " + b.crafts + "× " + name
                : "Buying ingredients for " + b.crafts + "× " + name;
    }

    public static Collections.Status unlock(Recipe r) {
        return Collections.status(r, Collections.tiers());
    }

    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || message == null) return;
            for (String line : ScreenUtil.cleanName(message.getString()).split("\n")) {
                if (line.contains("COLLECTION LEVEL UP")) Collections.onChat(line, RecipeRepo.recipes());
            }
        });
    }

    /**
     * The first time on SkyBlock without a collection scan: scan once, when nothing else is going on (no menu
     * open, Bazaar bot stopped, 30 s after arriving).
     */
    public static void tick(Minecraft mc) {
        if (autoScanTried || mc.player == null) return;
        if (!ScreenUtil.onSkyBlock()) {
            onSkyBlockSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (onSkyBlockSince == 0) onSkyBlockSince = now;
        if (now - onSkyBlockSince < 30_000 || mc.gui.screen() != null) return;
        Trader trader = Trader.get();
        if (trader != null && trader.isRunning()) return;
        autoScanTried = true;
        if (Collections.tiers() == null) CollectionScanner.scanAsync(null);
    }

    /** Coins one batch may use: the profit budget, or the bankroll when profit-only is off. */
    private static double budgetNow() {
        if (FlipperMod.CONFIG.tradeWithProfitOnly) return Math.max(0, FlipperMod.profitMade());
        long purse = ScreenUtil.lastKnownPurse();
        return purse >= 0 ? Bankroll.of(FlipperMod.CONFIG, purse, FlipperMod.profitMade()) : 100_000_000.0;
    }

    /** Recomputes the craft list in the background (downloads recipes the first time). */
    public static void refresh() {
        if (!BUSY.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("flipbot-crafts").start(() -> {
            try {
                status = "Fetching prices…";
                MarketData data = BazaarApi.fetch();
                if (data == null || data.products.isEmpty()) {
                    status = "Bazaar API not reachable";
                    return;
                }
                if (RecipeRepo.recipes().isEmpty()) status = "Downloading recipes (once a week, ~20 MB)…";
                RecipeRepo.ensure(data.products.keySet());
                if (RecipeRepo.recipes().isEmpty()) {
                    status = RecipeRepo.status();
                    return;
                }
                budget = budgetNow();
                Integer free = ScreenUtil.sync(ScreenUtil::freeInventorySlots);
                plans = CraftAnalyzer.plans(data, RecipeRepo.recipes(), FlipperMod.CONFIG, budget,
                        free == null || free <= 0 ? 30 : free);
                updatedAt = System.currentTimeMillis();
                status = plans.isEmpty()
                        ? "No profitable crafts right now"
                        : plans.size() + " profitable crafts" + (budget <= 0 ? " · no profit made yet, so no budget" : "");
            } catch (Exception e) {
                status = "Failed: " + e.getMessage();
            } finally {
                BUSY.set(false);
            }
        });
    }
}
