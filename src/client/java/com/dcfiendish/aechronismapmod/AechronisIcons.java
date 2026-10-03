package com.dcfiendish.aechronismapmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Map icons, looked up per node type with this precedence:
 *   1. the player's own PNG in config/aechronismapmod/icons/ (node_&lt;type&gt;.png),
 *   2. the map.crusalis.net resource icon, downloaded once into icons/cache/,
 *   3. the vanilla item texture of the same name (e.g. minecraft:item/wheat), for when the
 *      site is unreachable,
 *   4. the player's node_default.png, else no icon (the label still shows).
 * Towns and nation capitals only use the player's town.png / nation.png.
 *
 * The site icons are not bundled: they're fetched at runtime from the same host the
 * mod already reads world.json from, so whatever art the site uses stays its own.
 */
public final class AechronisIcons {
    public record Icon(Identifier texture) {}

    private static final String SITE = "https://map.crusalis.net/";
    private static final String[] SITE_ICON_LISTS = {"nodes/resource_icons.json", "nodes/resource_icons_custom.json"};
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("aechronismapmod").resolve("icons");
    private static final Path CACHE = DIR.resolve("cache");
    private static final String README = """
            Crusalis Map custom icons
            =========================
            Drop PNG files in this folder to replace the map icons. Your own files always win.

              node_<type>.png   a resource node type, named as in the Crusalis data, e.g.
                                node_wheat.png, node_potatos.png, node_animal farm.png,
                                node_ultra-mega-node.png, node_oil.png
              node_default.png  any node type that has no icon at all
              town.png          towns
              nation.png        nation capitals (nation name label)

            Without your own file, nodes use the same icon as map.crusalis.net (downloaded once
            into the cache folder; delete it to re-download), or the vanilla item texture
            if the site can't be reached.

            Recommended size: 16x16 or 32x32 (square, transparent background).
            Icons reload when you save the Crusalis Map settings (default key: O).
            Toggle, size and resource filter: Crusalis Map settings > Icons.
            """;

    private static volatile Map<String, Icon> user = Map.of();   // file name (no .png) -> icon
    private static volatile Map<String, Icon> site = Map.of();   // site icon key -> icon
    private static volatile Map<String, String> typeIcons = Map.of();
    private static final Set<String> downloadsTried = new HashSet<>();
    // Icon keys come from world.json and become cache file names: no separators, no "..".
    private static final java.util.regex.Pattern SAFE_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9 _.-]{0,63}");

    private AechronisIcons() {}

    /** town / nation / node_default etc.: the player's own files only. */
    public static Icon get(String name) {
        return user.get(name);
    }

    public static Icon forNodeType(String type) {
        Icon own = user.get("node_" + type);
        if (own != null) return own;
        String key = typeIcons.get(type);
        if (key != null) {
            Icon fromSite = site.get(key);
            if (fromSite != null) return fromSite;
            Icon vanilla = vanilla(key);
            if (vanilla != null) return vanilla;
        }
        return user.get("node_default");
    }

    private static final Map<String, Icon> vanillaCache = new HashMap<>();

    private static Icon vanilla(String key) {
        return vanillaCache.computeIfAbsent(key, k -> {
            if (!k.matches("[a-z0-9_]+")) return null;
            Identifier id = Identifier.withDefaultNamespace("textures/item/" + k + ".png");
            return Minecraft.getInstance().getResourceManager().getResource(id).isPresent() ? new Icon(id) : null;
        });
    }

    /**
     * Called every client tick: when world.json brings new node types, fetch their site
     * icons in the background, then load them on the render thread.
     */
    public static void tick(AechronisMapData data) {
        Map<String, String> icons = data.nodeTypeIcons;
        if (icons == typeIcons) return;
        typeIcons = icons;
        AechronisRenderer.invalidate();
        Set<String> missing = new HashSet<>();
        synchronized (downloadsTried) {
            for (String key : icons.values()) {
                if (!SAFE_KEY.matcher(key).matches()) continue; // key becomes a file name
                if (downloadsTried.add(key) && !Files.exists(CACHE.resolve(key + ".png"))) missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            reload();
            return;
        }
        CompletableFuture.runAsync(() -> download(missing))
                .whenComplete((v, e) -> Minecraft.getInstance().execute(AechronisIcons::reload));
    }

    private static void download(Set<String> keys) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Map<String, String> paths = new HashMap<>();
        for (String list : SITE_ICON_LISTS) {
            try {
                JsonObject obj = JsonParser.parseString(get(http, list, HttpResponse.BodyHandlers.ofString())).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) paths.putIfAbsent(e.getKey(), e.getValue().getAsString());
            } catch (Exception e) {
                System.out.println("[Crusalis] Icon list " + list + " unavailable: " + e.getMessage());
            }
        }
        for (String key : keys) {
            String path = paths.get(key);
            if (path == null || !path.matches("[A-Za-z0-9_./-]+\\.png") || path.contains("..")) continue;
            try {
                byte[] png = get(http, path, HttpResponse.BodyHandlers.ofByteArray());
                Files.createDirectories(CACHE);
                Files.write(CACHE.resolve(key + ".png"), png);
            } catch (Exception e) {
                System.out.println("[Crusalis] Icon " + key + " download failed: " + e.getMessage());
            }
        }
    }

    private static <T> T get(HttpClient http, String path, HttpResponse.BodyHandler<T> body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(SITE + path))
                .header("Referer", SITE).timeout(Duration.ofSeconds(20)).build();
        HttpResponse<T> res = http.send(req, body);
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
        return res.body();
    }

    /** Reloads the player's PNGs and the cached site icons. Render thread only (texture upload). */
    public static void reload() {
        var textures = Minecraft.getInstance().getTextureManager();
        for (Icon old : user.values()) textures.release(old.texture());
        for (Icon old : site.values()) textures.release(old.texture());
        try {
            Files.createDirectories(DIR);
            Path readme = DIR.resolve("README.txt");
            if (!Files.exists(readme)) Files.writeString(readme, README);
        } catch (IOException e) {
            System.out.println("[Crusalis] Icon folder " + DIR + " unavailable: " + e.getMessage());
        }
        user = load(DIR, "user/");
        site = load(CACHE, "site/");
        vanillaCache.clear();
        AechronisRenderer.invalidate();
        System.out.println("[Crusalis] Icons: " + user.size() + " custom, " + site.size() + " from map.crusalis.net");
    }

    private static Map<String, Icon> load(Path dir, String prefix) {
        Map<String, Icon> loaded = new HashMap<>();
        if (!Files.isDirectory(dir)) return Map.of();
        var textures = Minecraft.getInstance().getTextureManager();
        try (DirectoryStream<Path> pngs = Files.newDirectoryStream(dir, "*.png")) {
            for (Path png : pngs) {
                String name = png.getFileName().toString().toLowerCase(Locale.ROOT);
                name = name.substring(0, name.length() - 4);
                try (InputStream in = Files.newInputStream(png)) {
                    NativeImage image = NativeImage.read(in);
                    Identifier id = Identifier.fromNamespaceAndPath("aechronismapmod",
                            "icons/" + prefix + name.replaceAll("[^a-z0-9_.-]", "_"));
                    String label = "Crusalis icon " + prefix + name;
                    textures.register(id, new DynamicTexture(() -> label, image));
                    loaded.put(name, new Icon(id));
                } catch (IOException | RuntimeException e) {
                    System.out.println("[Crusalis] Skipping icon " + png + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("[Crusalis] Can't read icons in " + dir + ": " + e.getMessage());
        }
        return Map.copyOf(loaded);
    }
}
