package com.exkor.flipbot.craft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A crafting-table recipe in Bazaar product ids. {@code requirement} is the repo's unlock text, e.g.
 * "Requires: Gold Ingot IV"; the in-game check decides whether it is really unlocked.
 */
public record Recipe(String output, int outputCount, Map<String, Integer> ingredients, String requirement) {

    private static final String[] GRID = {"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"};

    /** Repo item id to Bazaar product id: "INK_SACK-3" is "INK_SACK:3" on the Bazaar. */
    public static String bazaarId(String repoId) {
        int dash = repoId.lastIndexOf('-');
        if (dash > 0 && dash < repoId.length() - 1 && repoId.substring(dash + 1).chars().allMatch(Character::isDigit)) {
            return repoId.substring(0, dash) + ":" + repoId.substring(dash + 1);
        }
        return repoId;
    }

    /** Bazaar product id to the repo's file name. */
    public static String repoId(String bazaarId) {
        return bazaarId.replace(':', '-');
    }

    /** The crafting recipes in one repo item file (forge, NPC and other recipe types are skipped). */
    public static List<Recipe> parse(JsonObject item) {
        List<Recipe> out = new ArrayList<>();
        if (!item.has("internalname")) return out;
        String output = bazaarId(item.get("internalname").getAsString());
        String requirement = item.has("crafttext") ? item.get("crafttext").getAsString().trim() : "";
        if (item.has("recipe") && item.get("recipe").isJsonObject()) {
            Recipe r = grid(output, item.getAsJsonObject("recipe"), requirement);
            if (r != null) out.add(r);
        }
        if (item.has("recipes") && item.get("recipes").isJsonArray()) {
            JsonArray list = item.getAsJsonArray("recipes");
            for (JsonElement e : list) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String type = o.has("type") ? o.get("type").getAsString() : "crafting";
                if (!type.equals("crafting")) continue;
                Recipe r = grid(output, o, requirement);
                if (r != null) out.add(r);
            }
        }
        return out;
    }

    private static Recipe grid(String output, JsonObject o, String requirement) {
        Map<String, Integer> ingredients = new LinkedHashMap<>();
        for (String cell : GRID) {
            if (!o.has(cell) || o.get(cell).isJsonNull()) continue;
            String v = o.get(cell).getAsString().trim();
            if (v.isEmpty()) continue;
            int colon = v.lastIndexOf(':');
            String id = colon > 0 ? v.substring(0, colon) : v;
            int qty = 1;
            if (colon > 0) {
                try {
                    qty = Integer.parseInt(v.substring(colon + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            ingredients.merge(bazaarId(id), qty, Integer::sum);
        }
        if (ingredients.isEmpty()) return null;
        int count = o.has("count") ? Math.max(1, o.get("count").getAsInt()) : 1;
        return new Recipe(output, count, ingredients, requirement);
    }
}
