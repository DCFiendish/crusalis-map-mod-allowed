package com.dcfiendish.aechronismapmod;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class AechronisMapMod implements ClientModInitializer {

	public static AechronisMapData mapData;
	private static AechronisDataFetcher fetcher;
	private static AechronisRenderer renderer;
	private static boolean rendererRegistered = false;

	@Override
	public void onInitializeClient() {
		System.out.println("[Crusalis] Initializing...");

		// Register config
		AutoConfig.register(AechronisConfig.class, GsonConfigSerializer::new);

		// Create data objects
		mapData = new AechronisMapData();
		fetcher = new AechronisDataFetcher();
		fetcher.mapData = mapData;

		// Register chat listener
		new AechronisChatListener(mapData).register();

		// Re-run on EVERY join, including proxy transfers (e.g. lobby -> main server),
		// since XaeroPlus may treat a backend transfer as a new map-world and drop
		// previously-registered draw features. We re-enable (not just enable-once)
		// so the draw features get freshly re-registered every time.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {

			// Only activate on the actual Crusalis server — skip singleplayer and any other server entirely.
			// IMPORTANT: every early-return path below must explicitly disable an already-enabled
			// renderer, not just skip re-enabling it. Otherwise leaving Crusalis (e.g. quitting to
			// singleplayer, or to any other server) leaves the PREVIOUS session's renderer running —
			// its draw features stay registered in AechronisRenderer.ourFeatures and the mixin keeps
			// rendering them unconditionally every frame regardless of what world you're actually in.
			// (This was the actual cause of the nation overlay showing up in singleplayer.)
			var serverData = client.getCurrentServer();
			String serverAddress = serverData != null ? serverData.ip : null;
			if (serverAddress == null || !serverAddress.toLowerCase().contains("167.235.177.45")) {
				System.out.println("[Crusalis] Not connected to Crusalis (address=" + serverAddress + "), mod inactive.");
				if (rendererRegistered) {
					renderer.disable();
					System.out.println("[Crusalis] Renderer disabled (left Crusalis).");
				}
				fetcher.onLeaveCrusalis();
				return;
			}

			if (!rendererRegistered) {
				// First time ever this session: create and add the module once.
				renderer = new AechronisRenderer(mapData);
				xaeroplus.module.ModuleManager.addModule(renderer);
				renderer.enable();
				rendererRegistered = true;
				System.out.println("[Crusalis] Renderer created and enabled.");
			} else {
				// Subsequent joins (proxy transfers etc.) — force a fresh re-registration
				// of draw features by disabling then re-enabling the same module instance.
				renderer.disable();
				renderer.enable();
				System.out.println("[Crusalis] Renderer re-enabled (fresh registration).");
			}
			fetcher.onJoinCrusalis();
		});

		// Disconnecting (quit to title, kicked, connection lost) does NOT fire another
		// JOIN event. We deliberately do NOT call renderer.disable() here, even though
		// that means AechronisRenderer.ourFeatures (and therefore the Crusalis-only
		// fairplay bypass in AechronisDrawManagerMixin) can stay "live" for a few extra
		// frames until the next JOIN corrects it — this used to call renderer.disable()
		// immediately on disconnect, but that closes each DrawFeature (releasing
		// XaeroPlus's own GL-backed resources) at a moment that isn't guaranteed to be
		// safe relative to the old world/GL context's own teardown, which produced a
		// reproducible native crash (Windows exit 0xC0000409 / STATUS_STACK_BUFFER_OVERRUN,
		// no Java exception) specifically when disconnecting from Crusalis. The JOIN
		// handler's existing "not connected to Crusalis" branch already calls
		// renderer.disable() safely — by that point a full new connection has been
		// established, well past the old GL context's teardown window. A brief stale
		// overlay/bypass is a far smaller cost than a client crash.
		//
		// Safe to call here regardless: onLeaveCrusalis() is pure Java scheduler state
		// (cancels a ScheduledFuture), no GL/native interaction at all.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			fetcher.onLeaveCrusalis();
		});

		System.out.println("[Crusalis] Initialized!");
	}
}