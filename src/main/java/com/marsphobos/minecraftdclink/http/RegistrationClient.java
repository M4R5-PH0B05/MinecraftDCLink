package com.marsphobos.minecraftdclink.http;

import com.marsphobos.minecraftdclink.config.FileConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegistrationClient {
    private final Logger logger;
    private final HttpClient client;

    public RegistrationClient(Logger logger) {
        this.logger = logger;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(FileConfig.apiTimeoutSeconds))
                .build();
    }

    public boolean isRegistered(UUID playerId) {
        String baseUrl = FileConfig.apiBaseUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            logger.error("API base URL is not configured.");
            return false;
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String apiKey = FileConfig.apiKey;

        URI uri;
        try {
            uri = new URI(normalized + "/v1/registration/" + playerId);
        } catch (URISyntaxException e) {
            logger.error("Invalid API base URL: {}", baseUrl, e);
            return false;
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(FileConfig.apiTimeoutSeconds))
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
            if (body == null) {
                return false;
            }
            String normalizedBody = body.replaceAll("\\s+", "");
            return normalizedBody.contains("\"registered\":true");
        } catch (IOException | InterruptedException e) {
            logger.error("Registration check failed for {}", playerId, e);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void sendPlayerEvent(UUID playerId, String playerName, String eventType) {
        String baseUrl = FileConfig.apiBaseUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            logger.error("API base URL is not configured.");
            return;
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String apiKey = FileConfig.apiKey;

        URI uri;
        try {
            uri = new URI(normalized + "/v1/mc-event");
        } catch (URISyntaxException e) {
            logger.error("Invalid API base URL: {}", baseUrl, e);
            return;
        }

        String payload = "{\"uuid\":\"" + playerId + "\",\"name\":\"" + escapeJson(playerName) + "\",\"event\":\"" + eventType + "\"}";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(FileConfig.apiTimeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("X-API-Key", apiKey);
        }

        try {
            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("MC event post failed for {} with status {}", playerId, response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            logger.error("MC event post failed for {}", playerId, e);
            Thread.currentThread().interrupt();
        }
    }

    public void sendServerStatus(long day, long timeOfDay) {
        String baseUrl = FileConfig.apiBaseUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            logger.error("API base URL is not configured.");
            return;
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String apiKey = FileConfig.apiKey;

        URI uri;
        try {
            uri = new URI(normalized + "/v1/server-status");
        } catch (URISyntaxException e) {
            logger.error("Invalid API base URL: {}", baseUrl, e);
            return;
        }

        String payload = "{\"day\":" + day + ",\"time\":" + timeOfDay + "}";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(FileConfig.apiTimeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("X-API-Key", apiKey);
        }

        try {
            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("Server status post failed with status {}", response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Server status post failed", e);
            Thread.currentThread().interrupt();
        }
    }


    public RoleInfo getRoleInfo(UUID playerId) {
        String baseUrl = FileConfig.apiBaseUrl;
        if (baseUrl == null || baseUrl.isBlank()) {
            logger.error("API base URL is not configured.");
            return null;
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String apiKey = FileConfig.apiKey;

        URI uri;
        try {
            uri = new URI(normalized + "/v1/role/" + playerId);
        } catch (URISyntaxException e) {
            logger.error("Invalid API base URL: {}", baseUrl, e);
            return null;
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(FileConfig.apiTimeoutSeconds))
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("X-API-Key", apiKey);
        }

        try {
            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("Role lookup failed for {} with status {}", playerId, response.statusCode());
                return null;
            }
            return parseRoleInfo(response.body());
        } catch (IOException | InterruptedException e) {
            logger.error("Role lookup failed for {}", playerId, e);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private RoleInfo parseRoleInfo(String body) {
        if (body == null) {
            return null;
        }
        Matcher roleMatcher = Pattern.compile("\"role\"\\s*:\\s*\"(.*?)\"").matcher(body);
        Matcher colorMatcher = Pattern.compile("\"color\"\\s*:\\s*(\\d+)").matcher(body);
        if (!roleMatcher.find()) {
            return null;
        }
        String role = roleMatcher.group(1);
        if (role == null || role.isBlank()) {
            return null;
        }
        int color = 0;
        if (colorMatcher.find()) {
            try {
                color = Integer.parseInt(colorMatcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return new RoleInfo(role, color);
    }

    public record RoleInfo(String roleName, int color) {
    }
}
