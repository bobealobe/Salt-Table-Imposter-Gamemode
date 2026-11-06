package com.impostorsmp.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client side: F5 does nothing. The camera is put back to first person every tick, so there is no peeking.
 * Everybody who plays needs the mod for this part; take the jar out of the mods folder to play normally.
 */
public class ImpostorClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.START_CLIENT_TICK.register(ImpostorClient::lockCamera);
		ClientTickEvents.END_CLIENT_TICK.register(ImpostorClient::lockCamera);
	}

	private static void lockCamera(Minecraft client) {
		if (client.level != null) {
			client.options.setCameraType(CameraType.FIRST_PERSON);
		}
	}
}
