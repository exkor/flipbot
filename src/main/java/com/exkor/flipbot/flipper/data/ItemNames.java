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
        String[] words = productId.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return sb.toString().trim();
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
