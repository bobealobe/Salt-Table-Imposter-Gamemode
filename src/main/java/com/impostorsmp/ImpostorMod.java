package com.impostorsmp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public class ImpostorMod implements ModInitializer {
	public static final String MOD_ID = "impostorsmp";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static GameController controller;

	/** The running game, or null while no world is open. */
	public static GameController controller() {
		return controller;
	}

	@Override
	public void onInitialize() {
		ImpostorLog.setSink(LOGGER::warn);

		ImpostorCommands.register();
		ChatRules.register();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			controller = new GameController(server, FabricLoader.getInstance().getConfigDir());
			controller.onServerStarted();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (controller != null) {
				controller.save();
				controller = null;
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (controller != null) {
				controller.tick();
			}
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			controller == null || controller.allowDamage(entity, source));

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (controller != null) {
				controller.onDeath(entity, source);
			}
		});

		ServerPlayerEvents.JOIN.register(player -> {
			if (controller != null) {
				controller.onPlayerReady(player);
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (controller != null) {
				controller.onPlayerReady(newPlayer);
			}
		});

		LOGGER.info("Impostor SMP loaded.");
	}
}
