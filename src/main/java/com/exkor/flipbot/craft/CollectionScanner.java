package com.exkor.flipbot.craft;

import com.exkor.flipbot.Chat;
import com.exkor.flipbot.flipper.trading.ScreenUtil;
import com.exkor.flipbot.flipper.trading.Trader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the player's collection tiers from the in-game Collections menu (/collection): every category, every page.
 * Run once; after that, level-up chat lines keep the cache current. Writes what it saw to collection-scan.log.
 */
public final class CollectionScanner {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final Set<String> SKIP = Set.of("go back", "close", "next page", "previous page", "collections",
            "sort", "filter", "search");
    private static final String[] CATEGORIES = {"farming", "mining", "combat", "foraging", "fishing", "rift"};

    private CollectionScanner() {}

    public static boolean running() {
        return RUNNING.get();
    }

    /** Scans in the background; reports in chat. */
    public static void scanAsync(Runnable after) {
        if (!RUNNING.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("flipbot-collections").start(() -> {
            try {
                scan();
            } catch (Exception e) {
                Chat.message("§cCollection scan failed: " + e.getMessage());
            } finally {
                RUNNING.set(false);
                if (after != null) ScreenUtil.onClient(after);
            }
        });
    }

    private record Slot(int index, String name, ItemStack stack) {}

    private static void scan() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !ScreenUtil.onSkyBlock()) {
            Chat.message("§cJoin SkyBlock to scan your collections.");
            return;
        }
        Trader trader = Trader.get();
        if (trader != null && trader.isRunning()) trader.holdGuiFor(60_000);
        StringBuilder log = new StringBuilder(LocalDateTime.now() + " collection scan\n");
        Map<String, Integer> tiers = new LinkedHashMap<>();
        Chat.message("§7Reading your collections once (a few menus will open)…");
        try {
            for (String category : CATEGORIES) {
                if (!openMain()) {
                    log.append("  main Collections menu did not open: ").append(title()).append('\n');
                    break;
                }
                Slot cat = slots().stream().filter(s -> s.name.toLowerCase(Locale.ROOT).startsWith(category)).findFirst().orElse(null);
                if (cat == null) {
                    log.append("  no ").append(category).append(" entry in: ").append(names()).append('\n');
                    continue;
                }
                ScreenUtil.clickSlot(cat.index, 0);
                if (!waitFor(t -> t.contains(category), 3_000)) {
                    log.append("  ").append(category).append(" menu did not open: ").append(title()).append('\n');
                    continue;
                }
                for (int page = 0; page < 6; page++) {
                    ScreenUtil.sleep(350);
                    List<Slot> items = slots();
                    log.append("  ").append(title()).append(": ");
                    for (Slot s : items) {
                        String lower = s.name.toLowerCase(Locale.ROOT);
                        if (SKIP.stream().anyMatch(lower::startsWith) || s.stack.getItem().toString().contains("glass_pane")) continue;
                        Map.Entry<String, Integer> e = Collections.menuEntry(s.name);
                        tiers.merge(e.getKey(), e.getValue(), Math::max);
                        log.append(s.name).append(" | ");
                    }
                    log.append('\n');
                    Slot next = items.stream().filter(s -> s.name.equalsIgnoreCase("Next Page")).findFirst().orElse(null);
                    if (next == null) break;
                    String before = title() + names();
                    ScreenUtil.clickSlot(next.index, 0);
                    long end = System.currentTimeMillis() + 2_500;
                    while (System.currentTimeMillis() < end && (title() + names()).equals(before)) ScreenUtil.sleep(100);
                }
            }
        } finally {
            ScreenUtil.closeAndWait(400);
            if (trader != null) trader.releaseAhPriority();
        }
        writeLog(log.append("  -> ").append(tiers).append('\n').toString());
        if (tiers.size() < 10) {
            Chat.message("§cCollection scan read only " + tiers.size() + " collections - not saved. "
                    + "§7Details in config/flipbot/collection-scan.log");
            return;
        }
        Collections.storeScan(tiers);
        long reached = tiers.values().stream().filter(t -> t > 0).count();
        Chat.message("§aSaved " + tiers.size() + " collections §7(" + reached + " with a tier). "
                + "Level-ups are picked up from chat from now on.");
    }

    private static boolean openMain() {
        if (ScreenUtil.sync(() -> Minecraft.getInstance().gui.screen() != null) == Boolean.TRUE) ScreenUtil.closeAndWait(400);
        ScreenUtil.onClient(() -> {
            var c = Minecraft.getInstance().getConnection();
            if (c != null) c.sendCommand("collection");
        });
        return waitFor(t -> t.contains("collection"), 4_000);
    }

    private static boolean waitFor(java.util.function.Predicate<String> titleOk, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String t = title();
            if (t != null && titleOk.test(t.toLowerCase(Locale.ROOT))) return true;
            ScreenUtil.sleep(100);
        }
        return false;
    }

    private static String title() {
        return ScreenUtil.sync(() -> {
            Screen s = Minecraft.getInstance().gui.screen();
            return s == null ? null : ScreenUtil.cleanName(s.getTitle().getString());
        });
    }

    private static List<Slot> slots() {
        List<Slot> out = ScreenUtil.sync(() -> {
            List<Slot> list = new ArrayList<>();
            if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> c)) return list;
            if (!(c.getMenu() instanceof ChestMenu chest)) return list;
            for (int i = 0; i < chest.getContainer().getContainerSize(); i++) {
                ItemStack st = chest.getContainer().getItem(i);
                if (!st.isEmpty()) list.add(new Slot(i, ScreenUtil.cleanName(st.getHoverName().getString()).trim(), st));
            }
            return list;
        });
        return out == null ? List.of() : out;
    }

    private static String names() {
        return slots().stream().map(Slot::name).toList().toString();
    }

    private static void writeLog(String text) {
        try {
            Path file = FabricLoader.getInstance().getConfigDir().resolve("flipbot").resolve("collection-scan.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
