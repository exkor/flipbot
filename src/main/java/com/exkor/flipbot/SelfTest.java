package com.exkor.flipbot;

import com.exkor.flipbot.flipper.FlipperMod;
import com.exkor.flipbot.flipper.analysis.FlipAnalyzer;
import com.exkor.flipbot.flipper.data.BazaarApi;
import com.exkor.flipbot.flipper.data.MarketData;
import com.exkor.flipbot.flipper.trading.BazaarDailyLimit;
import com.exkor.flipbot.profit.ProfitTracker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

/**
 * Dev-only in-game self-test, enabled with -Dflipbot.selftest=true (gradlew runClient -Pselftest).
 * Once in a world it checks mod loading and the mixin, runs the /flipbot commands, feeds a Bazaar chat
 * line through the real chat path, runs the analyzer on live data, logs "SELFTEST ..." lines and quits.
 */
final class SelfTest {
    static final boolean ENABLED = Boolean.getBoolean("flipbot.selftest");
    private static int ticksInWorld;
    private static boolean started;
    private static int failures;

    private SelfTest() {}

    static void onTick(Minecraft client) {
        if (!ENABLED || started || client.player == null || client.level == null) return;
        if (++ticksInWorld < 100) return; // let the world settle
        started = true;
        Thread.startVirtualThread(() -> run(client));
    }

    private static void run(Minecraft client) {
        if (Boolean.getBoolean("flipbot.selftest.menuOnly")) {
            // Quick mode (gradlew runClient -Pselftest -PmenuOnly): only screenshot the menu, about a minute in all.
            try {
                MarketData data = BazaarApi.fetch();
                if (data != null) FlipperMod.setMarketSnapshot(data);
                menuScreenshots(client);
            } catch (Throwable t) {
                failures++;
                FlipBotClient.LOG.error("SELFTEST crashed", t);
            } finally {
                log(failures == 0 ? "RESULT PASS" : "RESULT FAIL (" + failures + ")");
                client.execute(client::stop);
            }
            return;
        }
        try {
            log("start");
            check("flipbot loaded", FabricLoader.getInstance().isModLoaded("flipbot"));
            check("kittyaddon loaded", FabricLoader.getInstance().isModLoaded("kittyaddon"));
            check("sign mixin applied to LocalPlayer", Arrays.stream(LocalPlayer.class.getDeclaredMethods())
                    .anyMatch(m -> m.getName().contains("flipperFillSign")));

            // Commands, through the same path as typing them in chat.
            onMain(client, () -> client.player.connection.sendCommand("flipbot status"));
            onMain(client, () -> client.player.connection.sendCommand("flipbot set actionDelayMs 1000"));

            // A Bazaar chat line through the game's chat handler -> listener -> trackers.
            long netBefore = ProfitTracker.net(ProfitTracker.BAZAAR);
            long buyBefore = BazaarDailyLimit.buyCoins();
            onMain(client, () -> client.gui.chatListener().handleSystemMessage(
                    Component.literal("[Bazaar] Buy Order Setup! 5x Stone for 500 coins."), false));
            Thread.sleep(500);
            long netDelta = ProfitTracker.net(ProfitTracker.BAZAAR) - netBefore;
            long buyDelta = BazaarDailyLimit.buyCoins() - buyBefore;
            check("profit tracker debited 500 (got " + netDelta + ")", netDelta == -500);
            check("daily buy limit counted 500 (got " + buyDelta + ")", buyDelta == 500);
            // Undo it so the dev profile stays at zero.
            onMain(client, () -> client.gui.chatListener().handleSystemMessage(
                    Component.literal("[Bazaar] Cancelled! Refunded 500 coins from cancelling Buy Order!"), false));
            Thread.sleep(500);
            check("refund restored profit", ProfitTracker.net(ProfitTracker.BAZAAR) == netBefore);

            // Safety: practice is the default, can't go live without a cap, can't start off SkyBlock.
            check("practice mode is the default", FlipperMod.CONFIG.practiceMode);
            double cap = FlipperMod.CONFIG.maxInvestedMillions;
            boolean fromPurse = FlipperMod.CONFIG.investFromPurse;
            check("bankroll follows the purse by default", fromPurse);
            FlipperMod.CONFIG.maxInvestedMillions = 0;
            FlipperMod.CONFIG.investFromPurse = false;
            onMain(client, () -> client.player.connection.sendCommand("flipbot practice off"));
            check("practice off refused with no limit at all", FlipperMod.CONFIG.practiceMode);
            FlipperMod.CONFIG.investFromPurse = true;
            onMain(client, () -> client.player.connection.sendCommand("flipbot practice off"));
            check("practice off allowed when the bankroll follows the purse", !FlipperMod.CONFIG.practiceMode);
            onMain(client, () -> client.player.connection.sendCommand("flipbot practice on"));
            check("practice back on", FlipperMod.CONFIG.practiceMode);
            FlipperMod.CONFIG.maxInvestedMillions = cap;
            FlipperMod.CONFIG.investFromPurse = fromPurse;
            onMain(client, () -> client.player.connection.sendCommand("flipbot start"));
            Thread.sleep(500);
            check("bot refuses to start outside SkyBlock", !FlipperMod.isRunning());
            onMain(client, () -> client.player.connection.sendCommand("flipbot report"));
            check("no HUD line while stopped", FlipperMod.hudLine() == null);

            liveAnalyzer();

            menuScreenshots(client);
        } catch (Throwable t) {
            failures++;
            FlipBotClient.LOG.error("SELFTEST crashed", t);
        } finally {
            log(failures == 0 ? "RESULT PASS" : "RESULT FAIL (" + failures + ")");
            client.execute(client::stop);
        }
    }

    /** Live Bazaar data, saved so flipcompare.ps1 can rank the exact same response; 500M bankroll and 20 slots like the script. */
    private static void liveAnalyzer() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/skyblock/bazaar"))
                        .header("User-Agent", "flipbot/0.1").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Path out = FabricLoader.getInstance().getGameDir().resolve("flipbot-selftest-bazaar.json");
        Files.writeString(out, response.body(), StandardCharsets.UTF_8);
        MarketData data = BazaarApi.parse(response.body());
        check("live Bazaar parsed", data != null && data.products.size() > 1000);
        FlipperMod.setMarketSnapshot(data);
        IntSupplier realSlots = FlipAnalyzer.freeSlots;
        FlipAnalyzer.freeSlots = () -> 20;
        try {
            List<FlipAnalyzer.FlipPlan> plans = FlipAnalyzer.candidates(data, FlipperMod.CONFIG, 500e6, null, null, 0);
            log(String.format(Locale.ROOT, "live candidates %d lambda %.6f saved %s", plans.size(),
                    plans.isEmpty() ? 0 : plans.get(0).lambda, out));
            for (int i = 0; i < Math.min(10, plans.size()); i++) {
                log("live top " + (i + 1) + " " + plans.get(i).productId);
            }
            check("live analyzer found candidates", !plans.isEmpty());
        } finally {
            FlipAnalyzer.freeSlots = realSlots;
        }
    }


    /** Opens the menu (via the real command), shows every page with demo practice data and screenshots it. */
    private static void menuScreenshots(Minecraft client) throws Exception {
        MarketData market = FlipperMod.marketSnapshot();
        com.exkor.flipbot.flipper.practice.PracticeBook book = FlipperMod.resetPractice();
        long now = System.currentTimeMillis();
        book.tick(market, 1.25, 2, now - 40 * 60_000L);
        String[] demo = {"PERFECT_ONYX_GEM", "WITHER_BLOOD", "DEEP_SEA_ORB", "FLAWLESS_RUBY_GEM", "SEVERED_HAND"};
        for (int i = 0; i < demo.length; i++) {
            MarketData.Product p = market.products.get(demo[i]);
            if (p == null) continue;
            book.recordBuy(p.productId, com.exkor.flipbot.flipper.data.ItemNames.displayName(p.productId),
                    Math.round((p.bestBid + 0.1) * 10) / 10.0, 4 + i, 6_000_000 + i * 1_500_000, now - 40 * 60_000L);
        }
        for (int m = 35; m >= 0; m -= 5) book.tick(market, 1.25, 2, now - m * 60_000L);

        onMain(client, () -> client.player.connection.sendCommand("flipbot"));
        Thread.sleep(1500);
        check("/flipbot opens the menu", client.gui.screen() instanceof com.exkor.flipbot.ui.FlipBotScreen);
        if (client.gui.screen() instanceof com.exkor.flipbot.ui.FlipBotScreen screen) {
            for (com.exkor.flipbot.ui.FlipBotScreen.Page page : com.exkor.flipbot.ui.FlipBotScreen.Page.values()) {
                onMain(client, () -> screen.showPage(page));
                Thread.sleep(2500);
                if (page == com.exkor.flipbot.ui.FlipBotScreen.Page.FLIPS) {
                    onMain(client, () -> net.minecraft.client.Screenshot.grab(client.gameDirectory, "flipbot_menu_flips_early.png",
                            client.gameRenderer.mainRenderTarget(), 1, msg -> log("screenshot early flips, page=" + screen.getCurrentPage())));
                    Thread.sleep(Boolean.getBoolean("flipbot.selftest.menuOnly") ? 6_000 : 22_000);
                }
                if (page == com.exkor.flipbot.ui.FlipBotScreen.Page.CRAFTS) {
                    long end = System.currentTimeMillis() + 60_000;
                    while (System.currentTimeMillis() < end && com.exkor.flipbot.craft.CraftFlipper.busy()) Thread.sleep(500);
                    log("crafts: " + com.exkor.flipbot.craft.CraftFlipper.status());
                    Thread.sleep(1000);
                }
                if (client.gui.screen() != screen) {
                    // Something replaced the menu (keyboard input reaching the test window): put it back.
                    log("menu was replaced by " + client.gui.screen() + " - reopening it");
                    onMain(client, () -> client.gui.setScreen(screen));
                    Thread.sleep(1500);
                }
                log("page before screenshot: " + screen.getCurrentPage() + " screen=" + client.gui.screen());
                String name = "flipbot_menu_" + page.name().toLowerCase(Locale.ROOT) + ".png";
                onMain(client, () -> net.minecraft.client.Screenshot.grab(client.gameDirectory, name,
                        client.gameRenderer.mainRenderTarget(), 1, msg -> log("screenshot " + name)));
                Thread.sleep(800);
            }
            onMain(client, () -> client.gui.setScreen(null));
        }
        FlipperMod.resetPractice();
    }

    private static void onMain(Minecraft client, Runnable action) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        client.execute(() -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get();
        Thread.sleep(300);
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        log((ok ? "ok   " : "FAIL ") + name);
    }

    private static void log(String text) {
        FlipBotClient.LOG.info("SELFTEST {}", text);
    }
}
