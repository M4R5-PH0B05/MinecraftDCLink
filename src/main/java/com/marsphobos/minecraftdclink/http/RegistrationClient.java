package com.marsphobos.minecraftdclink.http;

import com.marsphobos.minecraftdclink.config.ModConfigs;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

public class RegistrationClient {
    private final Logger logger;
    private final HttpClient client;

    public RegistrationClient(Logger logger) {
        this.logger = logger;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ModConfigs.API_TIMEOUT_SECONDS.get()))
                .build();
    }

    public boolean isRegistered(UUID playerId) {
        String baseUrl = ModConfigs.API_BASE_URL.get();
        if (baseUrl == null || baseUrl.isBlank()) {
            logger.error("API base URL is not configured.");
            return false;
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String apiKey = ModConfigs.API_KEY.get();

        URI uri;
        try {
            uri = new URI(normalized + "/v1/registration/" + playerId);
        } catch (URISyntaxException e) {
            logger.error("Invalid API base URL: {}", baseUrl, e);
            return false;
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(ModConfigs.API_TIMEOUT_SECONDS.get()))
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("X-API-Key", apiKey);
        }

        try {
            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("Registration check failed for {} with status {}", playerId, response.statusCode());
                return false;
            }
            String body = response.body();
            return body != null && body.contains("\"registered\":true");
        } catch (IOException | InterruptedException e) {
            logger.error("Registration check failed for {}", playerId, e);
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
