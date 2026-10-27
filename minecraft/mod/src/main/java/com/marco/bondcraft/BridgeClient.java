package com.marco.bondcraft;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class BridgeClient {
    void send(String event, Map<String, Object> data) {
        if (!BondCraftConfig.ENABLED.get()) {
            return;
        }

        JsonObject payload = HapticSettingsStore.payloadFor(event, data, Minecraft.getInstance().getUser().getName());
        if (payload == null) {
            return;
        }

        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        String url = BondCraftConfig.BRIDGE_URL.get();
        if (BondCraftConfig.VERBOSE_LOGGING.get()) {
            BondCraft.LOGGER.info("BondCraft event {} -> {}", event, payload);
        }

        CompletableFuture.runAsync(() -> post(url, bytes, event));
    }

    private void post(String url, byte[] bytes, String event) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(250);
            connection.setReadTimeout(350);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Content-Length", Integer.toString(bytes.length));
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                BondCraft.LOGGER.debug("BondCraft companion returned HTTP {} for {}", status, event);
            }
        } catch (Exception ex) {
            BondCraft.LOGGER.debug("BondCraft could not send event {} to companion: {}", event, ex.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
