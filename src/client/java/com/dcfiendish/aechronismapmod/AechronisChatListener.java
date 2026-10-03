package com.dcfiendish.aechronismapmod;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AechronisChatListener {

    // Only node-level (whole-territory) flips. Per-chunk war tracking (chunk
    // captured/liberated/defended, attack-start/under-attack) is deliberately absent
    // from this build per a server admin ruling: the map may only change node by node,
    // never chunk by chunk. Territory captures/liberations flip an entire node at once,
    // which is allowed, and chat-triggering them still updates the map ahead of dynmap.
    //
    // Group 1 is always the acting PLAYER'S username, not a town — confirmed against
    // the plugin source (FlagWar.kt / NodesWorldListener.kt: every [War] broadcast
    // interpolates ${attacker?.name} / ${event.player.name}, i.e. a player, never a
    // town). Resolved to a nation via AechronisMapData.playerNationMap, not
    // townNationMap, everywhere below.
    private static final Pattern TERRITORY_CAPTURED = Pattern.compile(
            "\\[War\\] (.+?) captured territory \\(id=(\\d+)\\)"
    );
    private static final Pattern TERRITORY_LIBERATED = Pattern.compile(
            "\\[War\\] (.+?) liberated territory \\(id=(\\d+)\\)"
    );

    private final AechronisMapData mapData;

    public AechronisChatListener(AechronisMapData mapData) {
        this.mapData = mapData;
    }

    public void register() {
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                handleMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleMessage(message);
        });
    }

    private void handleMessage(Component message) {
        String text = message.getString().replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", "");
        AechronisWarCapture.logChatLine(text); // no-op unless AechronisWarCapture.ENABLED
        if (!text.contains("[War]")) return;
        // This listener registers unconditionally on every server the client connects
        // to, not just Crusalis \u2014 reuse the same "is the Crusalis-gated renderer
        // actually active" signal AechronisDrawManagerMixin uses, so a coincidental
        // [War]-shaped line on an unrelated server (different plugin, different
        // context) never touches mapData at all.
        if (!AechronisRenderer.isActive()) return;

        try {
            dispatch(text);
        } catch (Exception e) {
            // A malformed/unexpected match (regex matched but content didn't parse as
            // expected, a future Nodes plugin format change, etc.) must never propagate
            // an uncaught exception through Fabric's chat event pipeline.
            System.out.println("[Crusalis] Chat handler error on line: " + text + " (" + e + ")");
        }
    }

    private void dispatch(String text) {
        Matcher m;

        // Territory captured — flips the whole node to the capturing player's nation color.
        m = TERRITORY_CAPTURED.matcher(text);
        if (m.find()) {
            String capturingPlayer = extractPlayerName(m.group(1).trim());
            mapData.captureTerritory(m.group(2), capturingPlayer);
            return;
        }

        // Territory liberated — original owner (or ally) reclaimed it; clear the
        // occupied marker and flip the base color immediately (see liberateTerritory
        // javadoc — this is NOT a new capture, so it must not set the diagonal).
        m = TERRITORY_LIBERATED.matcher(text);
        if (m.find()) {
            String liberatingPlayer = extractPlayerName(m.group(1).trim());
            mapData.liberateTerritory(m.group(2), liberatingPlayer);
        }
    }

    // Defensive bracket-strip in case a nickname/prefix plugin ever wraps the name
    // (e.g. "[Tag] Name") — the vanilla broadcast today is a bare username.
    private static String extractPlayerName(String name) {
        if (name.startsWith("[") && name.contains("]"))
            return name.substring(1, name.indexOf("]")).trim();
        return name;
    }
}