package com.exkor.flipbot.craft;

import com.exkor.flipbot.Chat;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player's collection tiers: scanned once from the Collections menu, cached per player in
 * config/flipbot/collections.json, and kept up to date from "COLLECTION LEVEL UP" chat lines. A recipe counts as
 * unlocked only when its requirement is a collection tier the player has reached.
 */
public final class Collections {
    public enum Status { UNLOCKED, LOCKED, UNKNOWN }

    /** A recipe's requirement: a collection and tier, or null when it is something else (slayer, HotM ...). */
    public record Requirement(String collection, int tier) {}

    static final Pattern REQUIRES = Pattern.compile("^Requires:?\\s+(.+?)\\s+([IVXLC]+)$");
    static final Pattern NAME_TIER = Pattern.compile("^(.+?)\\s+([IVXLC]+)$");
    static final Pattern LEVEL_UP = Pattern.compile("COLLECTION LEVEL UP\\s+(.+?)\\s+(?:[IVXLC]+\\s*[➜>-]+\\s*)?([IVXLC]+)\\s*$");

    private record Store(Map<String, Profile> players) {}
    private record Profile(long scannedAt, Map<String, Integer> tiers) {}

    private static final Gson GSON = new Gson();
    private static Map<String, Profile> players = new HashMap<>();
    private static boolean loaded;

    private Collections() {}

    // ------------------------------------------------------------------ parsing (pure)

    public static int roman(String s) {
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; case 'C' -> 100;
                default -> 0;
            };
            if (v == 0) return 0;
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    /** "Requires: Gold Ingot IV" -> Gold Ingot, 4. Empty text -> no requirement (null tier 0). Other kinds -> null. */
    public static Requirement requirement(String text) {
        if (text == null || text.isBlank()) return new Requirement("", 0);
        Matcher m = REQUIRES.matcher(text.trim());
        if (!m.matches()) return null;
        int tier = roman(m.group(2));
        return tier > 0 ? new Requirement(m.group(1).trim(), tier) : null;
    }

    /** A collection item's name in the menu: "Gold Ingot VII" -> 7, "Gold Ingot" (nothing reached yet) -> 0. */
    public static Map.Entry<String, Integer> menuEntry(String name) {
        Matcher m = NAME_TIER.matcher(name.trim());
        if (m.matches() && roman(m.group(2)) > 0) return Map.entry(m.group(1).trim(), roman(m.group(2)));
        return Map.entry(name.trim(), 0);
    }

    /** "COLLECTION LEVEL UP Gold Ingot VII" (also "... VI ➜ VII") -> Gold Ingot, 7; null for other lines. */
    public static Map.Entry<String, Integer> levelUp(String line) {
        Matcher m = LEVEL_UP.matcher(line.trim());
        if (!m.find()) return null;
        int tier = roman(m.group(2));
        return tier > 0 ? Map.entry(m.group(1).trim(), tier) : null;
    }

    /** Whether a recipe is unlocked for these tiers (null tiers = not scanned yet). */
    public static Status status(Recipe r, Map<String, Integer> tiers) {
        Requirement req = requirement(r.requirement());
        if (req == null) return Status.UNKNOWN;          // slayer, HotM, museum ...: not checked
        if (req.tier() == 0) return Status.UNKNOWN;       // none listed: the repo leaves out e.g. slayer gates
        if (tiers == null) return Status.UNKNOWN;         // collections not scanned yet
        Integer have = tiers.get(key(req.collection()));
        if (have == null) return Status.UNKNOWN;          // name not found in the scan
        return have >= req.tier() ? Status.UNLOCKED : Status.LOCKED;
    }

    static String key(String collection) {
        return collection.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ cache

    private static String player() {
        try {
            return Minecraft.getInstance().getUser().getName();
        } catch (Exception e) {
            return "?";
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("flipbot").resolve("collections.json");
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(file())) {
                Store s = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<Store>() {}.getType());
                if (s != null && s.players() != null) players = new HashMap<>(s.players());
            }
        } catch (Exception e) {
            System.out.println("[FlipBot] collections.json unreadable: " + e.getMessage());
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(new Store(players)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[FlipBot] collections.json not saved: " + e.getMessage());
        }
    }

    /** This player's tiers (collection name in lower case -> tier), or null when never scanned. */
    public static synchronized Map<String, Integer> tiers() {
        load();
        Profile p = players.get(player());
        return p == null ? null : java.util.Collections.unmodifiableMap(p.tiers());
    }

    public static synchronized long scannedAt() {
        load();
        Profile p = players.get(player());
        return p == null ? 0 : p.scannedAt();
    }

    /** Stores a full scan. */
    public static synchronized void storeScan(Map<String, Integer> scanned) {
        load();
        Map<String, Integer> tiers = new HashMap<>();
        scanned.forEach((name, tier) -> tiers.put(key(name), tier));
        players.put(player(), new Profile(System.currentTimeMillis(), tiers));
        save();
    }

    /** A "COLLECTION LEVEL UP" chat line: update the tier and log the recipes it unlocks. */
    public static void onChat(String line, List<Recipe> recipes) {
        Map.Entry<String, Integer> up = levelUp(line);
        if (up == null) return;
        List<String> unlockedNow = new ArrayList<>();
        synchronized (Collections.class) {
            load();
            Profile p = players.get(player());
            if (p == null) return; // not scanned yet: the first scan picks it up
            Map<String, Integer> tiers = new HashMap<>(p.tiers());
            Integer before = tiers.get(key(up.getKey()));
            if (before != null && before >= up.getValue()) return;
            tiers.put(key(up.getKey()), up.getValue());
            players.put(player(), new Profile(p.scannedAt(), tiers));
            save();
            for (Recipe r : recipes) {
                Requirement req = requirement(r.requirement());
                if (req != null && req.tier() == up.getValue() && key(req.collection()).equals(key(up.getKey()))) {
                    unlockedNow.add(r.output());
                }
            }
        }
        String what = up.getKey() + " " + line.trim().replaceAll(".*\\s", "");
        Chat.message("§7Collection §f" + what + "§7 reached" + (unlockedNow.isEmpty() ? "."
                : " - unlocked recipes: §a" + String.join(", ", unlockedNow)));
        try {
            Path log = FabricLoader.getInstance().getConfigDir().resolve("flipbot").resolve("unlocks.log");
            Files.writeString(log, LocalDateTime.now() + "  " + what + " -> " + unlockedNow + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
