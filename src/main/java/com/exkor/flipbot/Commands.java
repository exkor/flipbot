package com.exkor.flipbot;

import com.exkor.flipbot.flipper.FlipperConfig;
import com.exkor.flipbot.flipper.FlipperMod;
import com.exkor.flipbot.flipper.analysis.FlipAnalyzer;
import com.exkor.flipbot.flipper.data.BazaarApi;
import com.exkor.flipbot.flipper.data.MarketData;
import com.exkor.flipbot.flipper.trading.ScreenUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** The /flipbot command tree. Bot commands (start, stop, practice, report) are added with the bots. */
final class Commands {
    /** Plan with this much when the purse can't be read. */
    private static final double FALLBACK_BANKROLL = 100_000_000.0;
    private static final AtomicBoolean SCANNING = new AtomicBoolean();

    private Commands() {}

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommands.literal("flipbot")
                .executes(ctx -> run(com.exkor.flipbot.ui.FlipBotScreen::open))
                .then(ClientCommands.literal("menu").executes(ctx -> run(com.exkor.flipbot.ui.FlipBotScreen::open)))
                .then(ClientCommands.literal("status").executes(ctx -> status()))
                .then(ClientCommands.literal("start").executes(ctx -> run(FlipperMod::start)))
                .then(ClientCommands.literal("stop").executes(ctx -> run(FlipperMod::stop)))
                .then(ClientCommands.literal("pause").executes(ctx -> run(() -> FlipperMod.setPaused(true))))
                .then(ClientCommands.literal("resume").executes(ctx -> run(() -> FlipperMod.setPaused(false))))
                .then(ClientCommands.literal("report").executes(ctx -> run(FlipperMod::report)))
                .then(ClientCommands.literal("practice")
                        .executes(ctx -> practiceInfo())
                        .then(ClientCommands.literal("on").executes(ctx -> practice(true, false)))
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> practice(false, false))
                                .then(ClientCommands.literal("confirm").executes(ctx -> practice(false, true))))
                        .then(ClientCommands.literal("reset").executes(ctx -> {
                            FlipperMod.resetPractice();
                            Chat.message("§dPractice book cleared §7(practice.jsonl keeps the history).");
                            return 1;
                        })))
                .then(ClientCommands.literal("flips")
                        .executes(ctx -> flips(-1))
                        .then(ClientCommands.argument("bankrollMillions", DoubleArgumentType.doubleArg(1))
                                .executes(ctx -> flips(DoubleArgumentType.getDouble(ctx, "bankrollMillions")))))
                .then(ClientCommands.literal("set")
                        .executes(ctx -> listKeys())
                        .then(ClientCommands.argument("key", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    ConfigSetter.keys(FlipperMod.CONFIG).forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .then(ClientCommands.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> set(StringArgumentType.getString(ctx, "key"),
                                                StringArgumentType.getString(ctx, "value")))))));
    }


    private static int run(Runnable action) {
        action.run();
        return 1;
    }

    private static int practiceInfo() {
        Chat.message("§7Practice mode is " + (FlipperMod.CONFIG.practiceMode ? "§dON" : "§cOFF (live orders)")
                + "§7. Use §f/flipbot practice on|off|reset§7, §f/flipbot report§7 for results.");
        return 1;
    }

    /** Going live needs the bot stopped and an invest cap (or an explicit confirm), per the rollout plan. */
    private static int practice(boolean on, boolean confirmed) {
        String refused = FlipperMod.setPracticeMode(on, confirmed);
        if (refused != null) {
            Chat.message("§c" + refused + (FlipperMod.isRunning() ? " §7(/flipbot stop)"
                    : " §7e.g. §f/flipbot set maxInvestedMillions 10§7, or §f/flipbot practice off confirm§7."));
            return 0;
        }
        Chat.message(on ? "§dPractice mode ON§7: no real orders."
                : "§c§lPractice mode OFF§r§c: the next /flipbot start places REAL orders.");
        return 1;
    }

    private static int status() {
        FlipperMod.status();
        Chat.message("§7Fetching Bazaar...");
        Thread.startVirtualThread(() -> {
            MarketData data = BazaarApi.fetch();
            if (data == null || data.products.isEmpty()) {
                Chat.message("§cBazaar API: no products loaded (see log).");
            } else {
                FlipperMod.setMarketSnapshot(data);
                Chat.message("§aBazaar API: §f" + data.products.size() + "§a products loaded.");
            }
        });
        return 1;
    }

    /** The analyzer's top 10 in the order the bot would buy them (score). bankrollMillions < 0 = real purse. */
    private static int flips(double bankrollMillions) {
        if (!SCANNING.compareAndSet(false, true)) {
            Chat.message("§eA scan is already running.");
            return 0;
        }
        Chat.message("§7Scanning the Bazaar (the first scan fetches price history, about a minute)...");
        Thread.startVirtualThread(() -> {
            try {
                FlipperConfig cfg = FlipperMod.CONFIG;
                MarketData data = BazaarApi.fetch();
                if (data == null || data.products.isEmpty()) {
                    Chat.message("§cBazaar API not reachable.");
                    return;
                }
                FlipperMod.setMarketSnapshot(data);
                double bankroll;
                String source;
                if (bankrollMillions > 0) {
                    bankroll = bankrollMillions * 1_000_000.0;
                    source = "given";
                } else {
                    long purse = ScreenUtil.readPurse();
                    boolean live = purse >= 0;
                    if (!live) {
                        purse = ScreenUtil.lastKnownPurse();
                    }
                    bankroll = purse >= 0 ? com.exkor.flipbot.flipper.Bankroll.of(cfg, purse, com.exkor.flipbot.flipper.FlipperMod.profitMade()) : FALLBACK_BANKROLL;
                    source = purse < 0 ? "purse unknown, fallback"
                            : (live ? "purse" : "last known purse")
                            + (com.exkor.flipbot.flipper.Bankroll.hasCap(cfg) ? " - reserve, capped" : " - reserve");
                }
                if (bankroll <= 0) {
                    Chat.message("§cPurse is below the reserve (" + ScreenUtil.formatCoins(cfg.reserveCoins) + ").");
                    return;
                }
                List<FlipAnalyzer.FlipPlan> plans = FlipAnalyzer.candidates(data, cfg, bankroll, null, null, 0);
                Chat.message("§6Top flips §7(" + plans.size() + " candidates, bankroll "
                        + ScreenUtil.formatCoins(bankroll) + " " + source + ", "
                        + FlipAnalyzer.freeSlots.getAsInt() + " free slots, lambda "
                        + String.format(Locale.ROOT, "%.6f", plans.isEmpty() ? 0 : plans.get(0).lambda) + "):");
                int rank = 1;
                for (FlipAnalyzer.FlipPlan p : plans.subList(0, Math.min(10, plans.size()))) {
                    Chat.message(String.format(Locale.ROOT,
                            "§e%d. §f%s §7buy §f%s §7sell §f%s §7(%.1f%%) §a~%s/h",
                            rank++, p.productId, ScreenUtil.formatCoins(p.buyPrice), ScreenUtil.formatCoins(p.sellPrice),
                            p.marginPercent, ScreenUtil.formatCoins(p.coinsPerHour)));
                }
            } catch (RuntimeException e) {
                FlipBotClient.LOG.error("flips scan failed", e);
                Chat.message("§cScan failed: " + e.getMessage());
            } finally {
                SCANNING.set(false);
            }
        });
        return 1;
    }

    private static int listKeys() {
        Chat.message("§7Settings: §f" + String.join(", ", ConfigSetter.keys(FlipperMod.CONFIG)));
        return 1;
    }

    private static int set(String key, String value) {
        try {
            String stored = ConfigSetter.set(FlipperMod.CONFIG, key, value);
            FlipperMod.CONFIG.save();
            Chat.message("§a" + key + " = §f" + stored);
            return 1;
        } catch (IllegalArgumentException e) {
            Chat.message("§c" + e.getMessage());
            return 0;
        }
    }
}
