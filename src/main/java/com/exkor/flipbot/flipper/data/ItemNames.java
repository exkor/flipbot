package com.exkor.flipbot.flipper.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class ItemNames {
    private static final Map<String, String> PRODUCT_TO_NAME = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> NAME_TO_PRODUCT = new java.util.concurrent.ConcurrentHashMap<>();
    /** Current in-game names from the NEU repo, where they differ from the bundled list (which is older). */
    private static final Map<String, String> REPO_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    public static String nameKey(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replaceAll("[^\\p{L}\\p{N}\\s]", " ").toLowerCase().trim();
        String[] words = cleaned.split("\\s+");
        java.util.Arrays.sort(words);
        return String.join(" ", words);
    }

    public static void load() {
        PRODUCT_TO_NAME.clear();
        REPO_NAMES.clear();
        NAME_TO_PRODUCT.clear();
        try (InputStream in = ItemNames.class.getResourceAsStream("/assets/flipbot/bazaar-resources.json")) {
            if (in == null) {
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject conversions = root.getAsJsonObject("bazaarConversions");
            if (conversions == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : conversions.entrySet()) {
                String name = entry.getValue().getAsString();
                PRODUCT_TO_NAME.put(entry.getKey(), name);
                NAME_TO_PRODUCT.putIfAbsent(name.toLowerCase(), entry.getKey());
                NAME_TO_PRODUCT.putIfAbsent(nameKey(name), entry.getKey());
                if (entry.getKey().startsWith("SHARD_") && !name.toLowerCase().endsWith(" shard")) {
                    // The bundled list has shards as "Salmon"; in game they are "Salmon Shard".
                    String current = name + " Shard";
                    REPO_NAMES.put(entry.getKey(), current);
                    NAME_TO_PRODUCT.put(current.toLowerCase(), entry.getKey());
                    NAME_TO_PRODUCT.put(nameKey(current), entry.getKey());
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Every known Bazaar product: product id -> display name. */
    public static Map<String, String> allProducts() {
        if (PRODUCT_TO_NAME.isEmpty()) {
            load();
        }
        return java.util.Collections.unmodifiableMap(PRODUCT_TO_NAME);
    }

    /**
     * Takes the current names from the NEU repo (id -> name with colour codes). A name that differs only in symbols
     * keeps the bundled one; the old name stays as an alias, so either one is found.
     */
    public static void setRepoNames(Map<String, String> names) {
        if (PRODUCT_TO_NAME.isEmpty()) {
            load();
        }
        for (Map.Entry<String, String> e : names.entrySet()) {
            String clean = cleanRepoName(e.getValue());
            if (clean.isEmpty()) {
                continue;
            }
            String old = PRODUCT_TO_NAME.get(e.getKey());
            if (old != null && nameKey(old).equals(nameKey(clean))) {
                continue;
            }
            REPO_NAMES.put(e.getKey(), clean);
            NAME_TO_PRODUCT.put(clean.toLowerCase(), e.getKey());
            NAME_TO_PRODUCT.put(nameKey(clean), e.getKey());
        }
    }

    /** "§fEnchanted Gold Ingot" -> "Enchanted Gold Ingot"; drops colour codes and private-use glyphs. */
    static String cleanRepoName(String raw) {
        return raw == null ? "" : raw.replaceAll("§.", "").replaceAll("\\p{Co}", "").trim().replaceAll("\\s+", " ");
    }

    /** Every name this item may show under: the current one first, then the bundled one. */
    public static java.util.List<String> aliases(String productId) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(displayName(productId));
        String old = PRODUCT_TO_NAME.get(productId);
        if (old != null) {
            out.add(old);
        }
        return java.util.List.copyOf(out);
    }

    public static String displayName(String productId) {
        String repo = REPO_NAMES.get(productId);
        if (repo != null) {
            return repo;
        }
        String mapped = PRODUCT_TO_NAME.get(productId);
        if (mapped != null) {
            return mapped;
        }
        return guessName(productId);
    }

    private static final java.util.regex.Pattern ENCHANT_ID =
            java.util.regex.Pattern.compile("^ENCHANTMENT_(?:ULTIMATE_)?(.+)_(\\d+)$");

    /**
     * A name for an id no list knows, in Hypixel's patterns: SHARD_BLACK_WIDOW -> "Black Widow Shard",
     * ENCHANTMENT_ULTIMATE_WISE_5 -> "Wise V", anything else title-cased.
     */
    static String guessName(String productId) {
        if (productId.startsWith("SHARD_")) {
            return titleCase(productId.substring(6)) + " Shard";
        }
        java.util.regex.Matcher m = ENCHANT_ID.matcher(productId);
        if (m.matches()) {
            return titleCase(m.group(1)) + " " + roman(Integer.parseInt(m.group(2)));
        }
        return titleCase(productId);
    }

    private static String titleCase(String id) {
        StringBuilder sb = new StringBuilder();
        for (String word : id.toLowerCase().split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    static String roman(int n) {
        if (n <= 0 || n > 39) {
            return String.valueOf(n);
        }
        String[] tens = {"", "X", "XX", "XXX"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        return tens[n / 10] + ones[n % 10];
    }

    /** Lets the guessed names of these Bazaar ids resolve back to their id (order names, inventory items). */
    public static void registerGuesses(java.util.Collection<String> bazaarIds) {
        if (PRODUCT_TO_NAME.isEmpty()) {
            load();
        }
        for (String id : bazaarIds) {
            if (PRODUCT_TO_NAME.containsKey(id) || REPO_NAMES.containsKey(id)) {
                continue;
            }
            String name = guessName(id);
            NAME_TO_PRODUCT.putIfAbsent(name.toLowerCase(), id);
            NAME_TO_PRODUCT.putIfAbsent(nameKey(name), id);
        }
    }

    public static String productId(String displayName) {
        if (displayName == null) {
            return null;
        }
        String id = NAME_TO_PRODUCT.get(displayName.toLowerCase());
        if (id != null) {
            return id;
        }
        id = NAME_TO_PRODUCT.get(nameKey(displayName));
        if (id != null) {
            return id;
        }
        return displayName.toUpperCase().replace(' ', '_');
    }
}
