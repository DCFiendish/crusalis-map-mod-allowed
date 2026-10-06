package com.dcfiendish.aechronismapmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AechronisDataFetcher {

    // The site moved to /map/ in October 2026: geometry.json replaces nodes/world.json
    // (same territories/nodes layout) and the social API replaces nodes/towns.json
    // (flat UUID-keyed arrays, converted back by CrusalisSocial).
    private static final String SOCIAL_URL    = "https://map.crusalis.net/api-proxy/v1/map/social";
    private static final String WORLD_URL     = "https://map.crusalis.net/map/map/geometry.json";
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
    private boolean gistFetched = false;
    private boolean worldLoaded = false;
    private ScheduledFuture<?> worldFetchFuture;
    private ScheduledFuture<?> townsPollFuture;

    /**
     * Called by AechronisMapMod's JOIN handler once a Crusalis connection is confirmed.
     * Idempotent and safe to call on every join (including backend/proxy transfers that
     * re-fire JOIN without an intervening DISCONNECT): the one-time fetches (gist colors,
     * world geometry — static-ish, no need to refresh on reconnect) only ever run
     * once per client session, and the recurring social poll is only (re)started if
     * it isn't already running.
     */
    public synchronized void onJoinCrusalis() {
        if (!gistFetched) {
            gistFetched = true;
            scheduler.schedule(this::fetchGistColors, 0, TimeUnit.SECONDS);
        }
        // Also resumes a world fetch whose retries stopped when the player left Crusalis.
        if (!worldLoaded && (worldFetchFuture == null || worldFetchFuture.isDone())) {
            worldFetchFuture = scheduler.schedule(this::fetchWorldAndTerritories, 2, TimeUnit.SECONDS);
        }
        if (townsPollFuture == null || townsPollFuture.isCancelled()) {
            townsPollFuture = scheduler.scheduleAtFixedRate(this::fetchTownsJson, 5, 60, TimeUnit.SECONDS);
        }
    }

    /**
     * Called on leaving Crusalis (disconnect, or JOIN resolving to a different/no
     * server) — cancels the recurring social poll so the mod goes fully quiet
     * until the player reconnects. One-time data stays cached, not cleared.
     */
    public synchronized void onLeaveCrusalis() {
        if (townsPollFuture != null) {
            townsPollFuture.cancel(false);
            townsPollFuture = null;
        }
        if (worldFetchFuture != null) {
            worldFetchFuture.cancel(false);
            worldFetchFuture = null;
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

    // geometry.json drives borders and nation fills and is fetched once; without a retry a
    // single transient failure leaves them empty for the whole session while labels (fed
    // by the separate towns poll) still work.
    private static final long WORLD_FETCH_RETRY_DELAY_SECONDS = 10;

    private void fetchWorldAndTerritories() {
        try {
            System.out.println("[Crusalis] Fetching geometry.json and social data for territory data...");
            String worldStr = fetch(WORLD_URL);
            Response social = request(SOCIAL_URL, null);
            if (social.status != 200) throw new java.io.IOException("social HTTP " + social.status);
            JsonObject worldJson = JsonParser.parseString(worldStr).getAsJsonObject();
            worldStr = null; // ~11MB; let it go before the parse below allocates more
            JsonObject socialJson = JsonParser.parseString(social.body).getAsJsonObject();
            CrusalisSocial.applyNameOverrides(worldJson, socialJson);
            mapData.loadWorldData(worldJson);
            mapData.loadTownsData(CrusalisSocial.toLegacyTowns(socialJson), social.body);
            synchronized (this) {
                worldLoaded = true;
                socialEtag = social.etag;
            }
            System.out.println("[Crusalis] World and territory data loaded.");
        } catch (Throwable e) {
            // Throwable, not Exception: geometry.json is ~11MB, so an OutOfMemoryError while
            // parsing is real and must still trigger the retry.
            // Retry only while still on Crusalis; otherwise the next join starts it again.
            synchronized (this) {
                boolean onCrusalis = townsPollFuture != null;
                System.out.println("[Crusalis] World fetch error: " + e.getMessage() + (onCrusalis
                        ? " - retrying in " + WORLD_FETCH_RETRY_DELAY_SECONDS + "s." : " - will retry on next join."));
                worldFetchFuture = onCrusalis
                        ? scheduler.schedule(this::fetchWorldAndTerritories, WORLD_FETCH_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                        : null;
            }
        }
    }

    // The social endpoint answers If-None-Match with 304 and rate limits with 429 +
    // Retry-After; the site itself backs off on 429, so the poll does too.
    private volatile String socialEtag;
    private volatile long socialBackoffUntilMs;
    private static final long DEFAULT_RETRY_AFTER_MS = 60_000;

    private void fetchTownsJson() {
        try {
            if (System.currentTimeMillis() < socialBackoffUntilMs) return;
            Response res = request(SOCIAL_URL, socialEtag);
            if (res.status == 304) return;
            if (res.status == 429) {
                socialBackoffUntilMs = System.currentTimeMillis() + res.retryAfterMs;
                System.out.println("[Crusalis] Social data rate limited, waiting " + res.retryAfterMs / 1000 + "s.");
                return;
            }
            if (res.status != 200) throw new java.io.IOException("HTTP " + res.status);
            JsonObject social = JsonParser.parseString(res.body).getAsJsonObject();
            mapData.loadTownsData(CrusalisSocial.toLegacyTowns(social), res.body);
            socialEtag = res.etag;
        } catch (Throwable e) { // an escaped Throwable would cancel the recurring poll
            System.out.println("[Crusalis] Towns fetch error: " + e.getMessage());
        }
    }

    private record Response(int status, String body, String etag, long retryAfterMs) {}

    private Response request(String urlStr, String ifNoneMatch) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "AechronisMapMod/1.0");
        conn.setRequestProperty("Referer", MAP_REFERER);
        if (ifNoneMatch != null) conn.setRequestProperty("If-None-Match", ifNoneMatch);
        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                long retryAfterMs = DEFAULT_RETRY_AFTER_MS;
                String ra = conn.getHeaderField("Retry-After");
                if (ra != null) {
                    try { retryAfterMs = Math.max(1, Long.parseLong(ra.trim())) * 1000; } catch (NumberFormatException ignored) {}
                }
                return new Response(status, null, null, retryAfterMs);
            }
            return new Response(status, read(conn.getInputStream()), conn.getHeaderField("ETag"), 0);
        } finally {
            conn.disconnect();
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
        return read(conn.getInputStream());
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream is = in;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}