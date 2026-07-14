package com.dcfiendish.aechronismapmod;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AechronisChatListener {

    // Only node-level (whole-territory) flips remain. Per-chunk war tracking
    // (chunk captured/liberated, attack-start/under-attack, defended) was removed
    // per server admin ruling — the map may only change node by node, not chunk by
    // chunk. Territory captures/liberations flip an entire node at once, which is
    // allowed, and chat-triggering them still updates the map ahead of dynmap.
    //
    // Group 1 is the acting PLAYER'S username, not a town — confirmed against the
    // plugin source (FlagWar.kt: `Message.broadcast("[War] ${attacker?.name} captured
    // territory ...")`, where attacker is a Resident, i.e. a player). Resolved to a
    // nation via AechronisMapData.playerNationMap, not townNationMap.
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

        Matcher m;

        // Territory captured — flips the whole node to the capturing player's nation color.
        m = TERRITORY_CAPTURED.matcher(text);
        if (m.find()) {
            String capturingPlayer = extractPlayerName(m.group(1).trim());
            mapData.captureTerritory(m.group(2), capturingPlayer);
            return;
        }

        // Territory liberated — clears the occupied marker and flips the base color
        // back to the liberating (original-owner) nation. See liberateTerritory()'s
        // javadoc: this used to reuse captureTerritory(), which incorrectly marked the
        // territory OCCUPIED on a successful defense.
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