package com.dcfiendish.aechronismapmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import xaero.common.HudMod;
import xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Dev-only self-test (-Dcrusalis.autotest=true, with -Dcrusalis.devForceActive=true for live
 * data; `gradlew runClient -Pautotest`). Once the live Crusalis data has loaded it parks the
 * player (spectator) in a nation-held chunk, adds a fake occupied marker
 * next to it, screenshots the minimap in every rotation/shape combination and the world map at
 * several zooms, repeats one minimap + world map shot in the Nether (must be empty), then quits.
 * Screenshots land in run/screenshots/autotest_*.png.
 */
public class DevAutoTest implements ClientModInitializer {
    private record Step(int waitTicks, Runnable action) {}

    private final Queue<Step> steps = new ArrayDeque<>();
    private int wait;

    @Override
    public void onInitializeClient() {
        // Runs commands (gamemode, tp): never outside the dev client.
        if (!AechronisMapMod.DEV || !Boolean.getBoolean("crusalis.autotest")) return;
        Minecraft mc = Minecraft.getInstance();
        checkAddresses();

        step(100, () -> {
            mc.options.pauseOnLostFocus = false; // the dev window rarely has focus
            command("gamemode spectator @a");
            command("time set day");
        });
        // Live Crusalis data loads over ~5 s (devForceActive); then park the player on the
        // first nation's label, with a fake occupation marker next to it.
        step(400, () -> {
            AechronisMapData d = AechronisMapMod.mapData;
            // A nation-held chunk, so fills, borders and labels are all around.
            long chunk = d.buildAlphaCache(255).keySet().iterator().nextLong();
            int x = (net.minecraft.world.level.ChunkPos.getX(chunk) << 4) + 8;
            int z = (net.minecraft.world.level.ChunkPos.getZ(chunk) << 4) + 8;
            System.out.printf("[AutoTest] data: nations=%d nodes=%d towns=%d borders=%d target=%d,%d%n",
                    d.nationLabelInfos.size(), d.nodeLabelInfos.size(), d.townLabelInfos.size(),
                    d.nodeBorderLines.size(), x, z);
            d.territoryDiagonals.keySet().stream().findFirst().ifPresent(tid -> {
                d.capturedTerritoryIds.add(tid);
                d.territoryDiagonalColors.put(tid, 0x00FFFF);
                d.territoryDiagonalNations.put(tid, "Test");
            });
            command("tp @a " + (x + 0.5) + " 200 " + (z + 0.5) + " 30 60");
            AechronisConfig.get().showChunkGrid = true;
            target[0] = x;
            target[1] = z;
        });
        step(300, () -> {});
        for (boolean north : new boolean[]{true, false}) {
            for (int shape : new int[]{0, 1}) {
                step(10, () -> {
                    cfg(MinimapProfiledConfigOptions.NORTH_LOCKED, north);
                    cfg(MinimapProfiledConfigOptions.SHAPE, shape);
                });
                step(30, () -> shot("mm_" + (north ? "north" : "rotating") + "_" + (shape == 0 ? "square" : "circle")));
            }
        }
        // Fair-play mode (what Crusalis enforces): Xaero switches it on when a chat message
        // carries its code. The overlay must keep drawing; cave mode/radar stay Xaero's call.
        step(10, () -> command("tellraw @a {\"text\":\"\u00a7f\u00a7a\u00a7i\u00a7r\u00a7x\u00a7a\u00a7e\u00a7r\u00a7o\"}"));
        step(40, () -> {
            System.out.println("[AutoTest] fair-play active: " + xaero.common.HudMod.INSTANCE.isFairPlay());
            shot("fairplay_mm");
        });
        worldMap(1, "fairplay_wm");
        for (double zoom : new double[]{0.125, 0.25, 1, 4, 16}) {
            worldMap(zoom, "wm_zoom_" + zoom);
        }
        // Resource filter (normally cycled with its keybind): only wheat nodes.
        step(5, () -> AechronisConfig.get().resourceFilter = "wheat");
        worldMap(0.25, "wm_filter_wheat");
        step(5, () -> AechronisConfig.get().resourceFilter = "");
        // Settings screen (normally the O key).
        step(10, () -> mc.setScreen(me.shedaniel.autoconfig.AutoConfig.getConfigScreen(AechronisConfig.class, null).get()));
        step(40, () -> shot("settings"));
        step(5, () -> mc.setScreen(null));
        // Capture colors by relation: the occupied diagonal turns green (own), blue (ally),
        // red (other) as the dev player's nation is faked into each relation with the occupier.
        step(5, () -> AechronisConfig.get().relationCaptureColors = true);
        for (String relation : new String[]{"own", "ally", "enemy"}) {
            step(5, () -> fakeRelation(relation));
            worldMap(1, "wm_capture_" + relation);
        }
        step(5, () -> AechronisConfig.get().relationCaptureColors = false);
        // Nether: both maps must be empty of Crusalis data (the chunk grid still shows).
        step(10, () -> command("execute in minecraft:the_nether run tp @a " + target[0] + " 100 " + target[1]));
        step(200, () -> shot("nether_mm"));
        worldMap(1, "nether_wm");
        // Disconnect and rejoin from the Overworld test spot: the overlay must come back
        // (one towns poll, no duplicates).
        step(20, () -> command("execute in minecraft:overworld run tp @a " + (target[0] + 0.5) + " 120 " + (target[1] + 0.5) + " 30 20"));
        step(40, () -> {
            System.out.println("[AutoTest] disconnecting");
            rejoin = true;
            // Queued, not run inside this tick handler: disconnect pumps nested ticks itself.
            mc.execute(() -> mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("autotest")));
        });
        step(200, () -> System.out.println("[AutoTest] rejoined: active=" + AechronisRenderer.isActive()));
        step(20, () -> shot("rejoin_mm"));
        // -Pautotest=stay: go back to the Overworld test spot and leave the client open.
        if (Boolean.getBoolean("crusalis.autotest.stay")) {
            step(20, () -> command("execute in minecraft:overworld run tp @a " + target[0] + " 200 " + target[1]));
        } else {
            step(20, mc::stop);
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (rejoin && client.level == null && client.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                rejoin = false;
                System.out.println("[AutoTest] rejoining");
                client.createWorldOpenFlows().openWorld("HookSpike", () -> {});
            }
            if (client.player == null || client.getSingleplayerServer() == null || steps.isEmpty()) return;
            if (wait-- > 0) return;
            Step step = steps.poll();
            step.action().run();
            wait = steps.isEmpty() ? 0 : steps.peek().waitTicks();
        });
        wait = steps.peek().waitTicks();
    }

    private final int[] target = new int[2];
    private boolean rejoin;

    /** Puts the dev player's nation in the given relation to the fake "Test" occupier. */
    private void fakeRelation(String relation) {
        AechronisMapData d = AechronisMapMod.mapData;
        var nations = new java.util.HashMap<>(d.playerNationMap);
        nations.put(Minecraft.getInstance().player.getGameProfile().name(), relation.equals("own") ? "Test" : "AutoTestNation");
        d.playerNationMap = nations;
        AechronisConfig.get().allyNations = relation.equals("ally") ? "test" : "";
    }

    /** isCrusalisAddress must accept crusalis.net and its subdomains only. */
    private static void checkAddresses() {
        String[] yes = {"crusalis.net", "play.crusalis.net", "crusalis.net:25565", "CRUSALIS.NET", "crusalis.net."};
        String[] no = {"crusalis.net.evil.com", "notcrusalis.net", "203.0.113.7", null, ""};
        boolean ok = true;
        for (String a : yes) ok &= AechronisMapMod.isCrusalisAddress(a);
        for (String a : no) ok &= !AechronisMapMod.isCrusalisAddress(a);
        System.out.println("[AutoTest] address gate: " + (ok ? "OK" : "FAIL"));
    }

    private void worldMap(double zoom, String name) {
        Minecraft mc = Minecraft.getInstance();
        step(10, () -> {
            setStatic(GuiMap.class, "destScale", zoom);
            mc.setScreen(new GuiMap(null, null, WorldMapSession.getCurrentSession().getMapProcessor(), mc.getCameraEntity()));
        });
        step(100, () -> shot(name));
        step(5, () -> mc.setScreen(null));
    }

    private static void command(String cmd) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd));
    }

    private void step(int waitTicks, Runnable action) {
        steps.add(new Step(waitTicks, action));
    }

    private static <T> void cfg(xaero.lib.common.config.option.ConfigOption<T> option, T value) {
        HudMod.INSTANCE.getHudConfigs().getClientConfigManager().getCurrentProfile().set(option, value);
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "autotest_" + name + ".png", mc.getMainRenderTarget(), 1,
                msg -> System.out.println("[AutoTest] " + msg.getString()));
    }

    private static void setStatic(Class<?> owner, String field, Object value) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
