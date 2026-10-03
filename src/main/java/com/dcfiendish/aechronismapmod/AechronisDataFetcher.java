package com.dcfiendish.aechronismapmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AechronisDataFetcher {

    private static final String TOWNS_URL     = "https://map.crusalis.net/nodes/towns.json";
    private static final String WORLD_URL     = "https://map.crusalis.net/nodes/world.json";
    private static final String MAP_REFERER   = "https://map.crusalis.net/";
    private static final String GIST_URL      = "https://gist.githubusercontent.com/DCFiendish/a0989e75d3d6dadb9a2af6254232a350/raw/nation_colors.json";

    public AechronisMapData mapData;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Aechronis-Fetcher");
        t.setDaemon(true);
        return t;
    });

    // Nothing below fires until onJoinCrusalis() is called — this mod must not phone
    // home to Crusalis's infrastructure for players who never connect there (thousands
    // of installs mostly playing elsewhere would otherwise generate constant unwanted
    // background traffic against a specific third party's server).
    private volatile boolean oneTimeDataFetched = false;
    private volatile ScheduledFuture<?> townsPollFuture;

    /**
     * Called by AechronisMapMod's JOIN handler once a Crusalis connection is confirmed.
     * Idempotent and safe to call on every join (including backend/proxy transfers that
     * re-fire JOIN without an intervening DISCONNECT): the one-time fetches (gist colors,
     * world geometry — static-ish, no need to refresh on reconnect) only ever run
     * once per client session, and the recurring towns.json poll is only (re)started if
     * it isn't already running.
     */
    public synchronized void onJoinCrusalis() {
        if (!oneTimeDataFetched) {
            oneTimeDataFetched = true;
            scheduler.schedule(this::fetchGistColors, 0, TimeUnit.SECONDS);
            scheduler.schedule(this::fetchWorldAndTerritories, 2, TimeUnit.SECONDS);
        }
        if (townsPollFuture == null || townsPollFuture.isCancelled()) {
            townsPollFuture = scheduler.scheduleAtFixedRate(this::fetchTownsJson, 5, 60, TimeUnit.SECONDS);
        }
    }

    /**
     * Called on leaving Crusalis (disconnect, or JOIN resolving to a different/no
     * server) — cancels the recurring towns.json poll so the mod goes fully quiet
     * until the player reconnects. One-time data stays cached, not cleared.
     */
    public synchronized void onLeaveCrusalis() {
        if (townsPollFuture != null) {
            townsPollFuture.cancel(false);
            townsPollFuture = null;
        }
    }

    private void fetchGistColors() {
        try {
            System.out.println("[Crusalis] Fetching nation color overrides from Gist...");
            String json = fetch(GIST_URL);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            mapData.loadGistColors(obj);
            System.out.println("[Crusalis] Gist colors loaded.");
        } catch (Exception e) {
            System.out.println("[Crusalis] Gist fetch error: " + e.getMessage());
        }
    }

    // world.json drives borders and nation fills and is fetched once; without a retry a
    // single transient failure leaves them empty for the whole session while labels (fed
    // by the separate towns poll) still work.
    private static final long WORLD_FETCH_RETRY_DELAY_SECONDS = 10;

    private void fetchWorldAndTerritories() {
        try {
            System.out.println("[Crusalis] Fetching world.json and towns.json for territory data...");
            String worldStr = fetch(WORLD_URL);
            String townsStr = fetch(TOWNS_URL);
            JsonObject worldJson = JsonParser.parseString(worldStr).getAsJsonObject();
            JsonObject townsJson = JsonParser.parseString(townsStr).getAsJsonObject();
            mapData.loadWorldData(worldJson);
            mapData.loadTownsData(townsJson, townsStr);
            System.out.println("[Crusalis] World and territory data loaded.");
        } catch (Throwable e) {
            // Throwable, not Exception: world.json is ~16MB, so an OutOfMemoryError while
            // parsing is real and must still trigger the retry.
            System.out.println("[Crusalis] World fetch error: " + e.getMessage() +
                    " - retrying in " + WORLD_FETCH_RETRY_DELAY_SECONDS + "s.");
            scheduler.schedule(this::fetchWorldAndTerritories, WORLD_FETCH_RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void fetchTownsJson() {
        try {
            String json = fetch(TOWNS_URL);
            JsonObject townsJson = JsonParser.parseString(json).getAsJsonObject();
            mapData.loadTownsData(townsJson, json);
        } catch (Throwable e) { // an escaped Throwable would cancel the recurring poll
            System.out.println("[Crusalis] Towns fetch error: " + e.getMessage());
        }
    }

    private String fetch(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        var conn = url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "AechronisMapMod/1.0");
        if (urlStr.startsWith("https://map.crusalis.net/")) {
            conn.setRequestProperty("Referer", MAP_REFERER);
        }
        try (InputStream is = conn.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}