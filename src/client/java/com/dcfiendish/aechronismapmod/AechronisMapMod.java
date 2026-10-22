package com.dcfiendish.aechronismapmod;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class AechronisMapMod implements ClientModInitializer {
	/** Dev client (gradlew runClient). Test-only switches are ignored everywhere else. */
	static final boolean DEV = FabricLoader.getInstance().isDevelopmentEnvironment();

	public static AechronisMapData mapData;
	private static AechronisDataFetcher fetcher;

	@Override
	public void onInitializeClient() {
		System.out.println("[Crusalis] Initializing...");

		// Register config
		AutoConfig.register(AechronisConfig.class, GsonConfigSerializer::new);

		// Create data objects
		mapData = new AechronisMapData();
		fetcher = new AechronisDataFetcher();
		fetcher.mapData = mapData;
		AechronisRenderer.init(mapData);

		// Register chat listener
		new AechronisChatListener(mapData).register();

		// Settings screen without Mod Menu: rebindable key (default O) under Controls > Crusalis Map.
		KeyMapping.Category keyCategory = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aechronismapmod", "main"));
		KeyMapping openSettings = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.aechronismapmod.open_settings", InputConstants.KEY_O, keyCategory));
		// Unbound by default (bind it under Controls > Crusalis Map): steps the resource filter.
		KeyMapping cycleFilter = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.aechronismapmod.cycle_filter", InputConstants.UNKNOWN.getValue(), keyCategory));

		// Custom PNG icons: loaded once the texture manager exists, reloaded on every settings save.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> AechronisIcons.reload());
		AutoConfig.getConfigHolder(AechronisConfig.class).registerSaveListener((holder, config) -> {
			Minecraft.getInstance().execute(() -> {
				AechronisIcons.reload();
				AechronisRenderer.invalidate();
			});
			return InteractionResult.SUCCESS;
		});

		// Purges and cache rebuilds run here, independent of toggles, dimension or open screens.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openSettings.consumeClick()) {
				client.setScreen(AutoConfig.getConfigScreen(AechronisConfig.class, client.screen).get());
			}
			while (cycleFilter.consumeClick()) cycleResourceFilter(client);
			AechronisIcons.tick(mapData);
			AechronisRenderer.tick();
		});

		// Re-run on EVERY join, including proxy transfers (e.g. lobby -> main server).
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			// Only activate on the actual Crusalis server — skip singleplayer and any other server entirely.
			// Every early-return path must deactivate the overlay, or leaving Crusalis (e.g. quitting to
			// singleplayer, or to any other server) keeps drawing the previous session's data.
			var serverData = client.getCurrentServer();
			String serverAddress = serverData != null ? serverData.ip : null;
			boolean onCrusalis = isCrusalisAddress(serverAddress);
			// Dev/testing only: draw live Crusalis data in any world (e.g. the dev client's singleplayer).
			// Ignored outside the dev environment, so a release jar only ever activates on Crusalis.
			if (!onCrusalis && DEV && Boolean.getBoolean("crusalis.devForceActive")) onCrusalis = true;
			if (!onCrusalis) {
				System.out.println("[Crusalis] Not connected to Crusalis (address=" + serverAddress + "), mod inactive.");
				AechronisRenderer.setActive(false);
				fetcher.onLeaveCrusalis();
				return;
			}

			AechronisRenderer.setActive(true);
			System.out.println("[Crusalis] Overlay enabled.");
			fetcher.onJoinCrusalis();
		});

		// Disconnecting (quit to title, kicked, connection lost) does not fire another JOIN.
		// The overlay holds no GPU resources of its own any more (Xaero's buffers carry the
		// vertices), so it can be switched off right away.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			AechronisRenderer.setActive(false);
			fetcher.onLeaveCrusalis();
		});

		System.out.println("[Crusalis] Initialized!");
	}

	/**
	 * crusalis.net or any subdomain of it (play.crusalis.net, ...), port ignored. A plain
	 * contains() would also match hosts like crusalis.net.example.com.
	 */
	static boolean isCrusalisAddress(String address) {
		if (address == null) return false;
		String host = ServerAddress.parseString(address.trim()).getHost().toLowerCase(java.util.Locale.ROOT);
		if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
		return host.equals("crusalis.net") || host.endsWith(".crusalis.net");
	}

	/** All -> each node type in the data (alphabetical) -> All. Shown on the action bar. */
	private static void cycleResourceFilter(Minecraft client) {
		AechronisConfig cfg = AechronisConfig.get();
		java.util.List<String> types = AechronisRenderer.resourceTypes();
		int next = types.indexOf(cfg.resourceFilter.trim().toLowerCase(java.util.Locale.ROOT)) + 1;
		cfg.resourceFilter = next < types.size() ? types.get(next) : "";
		if (client.player != null) {
			client.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
					"Crusalis resource filter: " + (cfg.resourceFilter.isEmpty() ? "All" : cfg.resourceFilter)), true);
		}
	}
}
