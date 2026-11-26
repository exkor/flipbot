package com.exkor.flipbot.craft;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Crafting recipes from the NotEnoughUpdates community repo, kept to those whose result and ingredients all trade on
 * the Bazaar. Cached in config/flipbot/recipes.json and refreshed once a week (one 22 MB download).
 */
public final class RecipeRepo {
    private static final String ZIP = "https://codeload.github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/zip/refs/heads/master";
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final Gson GSON = new Gson();

    private record Cache(long savedAt, List<Recipe> recipes, Map<String, String> names) {}

    private static volatile List<Recipe> recipes = List.of();
    private static volatile long loadedAt;
    private static volatile String status = "not loaded";

    private RecipeRepo() {}

    public static List<Recipe> recipes() {
        return recipes;
    }

    public static String status() {
        return status;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("flipbot").resolve("recipes.json");
    }

    /** Loads the cache, downloading the repo when there is none or it is a week old. Blocking: call off-thread. */
    public static synchronized void ensure(Set<String> bazaarIds) {
        if (!recipes.isEmpty() && System.currentTimeMillis() - loadedAt < MAX_AGE_MS) return;
        try {
            Path path = file();
            if (Files.exists(path)) {
                Cache c = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), new TypeToken<Cache>() {}.getType());
                if (c != null && c.recipes() != null && c.names() != null && System.currentTimeMillis() - c.savedAt() < MAX_AGE_MS) {
                    com.exkor.flipbot.flipper.data.ItemNames.setRepoNames(c.names());
                    recipes = List.copyOf(c.recipes());
                    loadedAt = c.savedAt();
                    status = recipes.size() + " recipes";
                    return;
                }
            }
        } catch (Exception e) {
            status = "cache unreadable, downloading";
        }
        try {
            status = "downloading recipes…";
            Map<String, String> names = new HashMap<>();
            List<Recipe> fresh = download(bazaarIds, names);
            com.exkor.flipbot.flipper.data.ItemNames.setRepoNames(names);
            recipes = List.copyOf(fresh);
            loadedAt = System.currentTimeMillis();
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(new Cache(loadedAt, fresh, names)), StandardCharsets.UTF_8);
            status = fresh.size() + " recipes";
        } catch (Exception e) {
            status = "recipe download failed: " + e.getMessage();
        }
    }

    private static List<Recipe> download(Set<String> bazaarIds, Map<String, String> names) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(ZIP)).timeout(Duration.ofMinutes(3))
                .header("User-Agent", "flipbot/0.1").GET().build();
        HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new IOException("HTTP " + res.statusCode());
        }
        try (ZipInputStream zip = new ZipInputStream(res.body())) {
            return fromZip(zip, bazaarIds, names);
        }
    }

    static List<Recipe> fromZip(ZipInputStream zip, Set<String> bazaarIds) throws IOException {
        return fromZip(zip, bazaarIds, new HashMap<>());
    }

    /** Recipes in a repo zip whose result and ingredients are all Bazaar products; fills in every Bazaar item's name. */
    static List<Recipe> fromZip(ZipInputStream zip, Set<String> bazaarIds, Map<String, String> names) throws IOException {
        List<Recipe> out = new ArrayList<>();
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            String name = e.getName();
            int slash = name.lastIndexOf('/');
            if (e.isDirectory() || !name.endsWith(".json") || !name.substring(0, Math.max(0, slash)).endsWith("/items")) continue;
            String id = Recipe.bazaarId(name.substring(slash + 1, name.length() - 5));
            if (!bazaarIds.contains(id)) continue;
            try {
                JsonObject item = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (item.has("displayname")) names.put(id, item.get("displayname").getAsString());
                for (Recipe r : Recipe.parse(item)) {
                    if (bazaarIds.containsAll(r.ingredients().keySet()) && !r.ingredients().containsKey(r.output())) {
                        out.add(r);
                    }
                }
            } catch (RuntimeException ignored) {
                // a malformed item file: skip it
            }
        }
        return out;
    }
}
