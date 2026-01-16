package com.marsphobos.minecraftdclink.config;

import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class FileConfig {
    public static String apiBaseUrl = "https://mc-auth.marsphobos.com";
    public static String apiKey = "";
    public static int apiTimeoutSeconds = 5;
    public static int checkIntervalSeconds = 10;
    public static int messageIntervalSeconds = 30;
    public static int statusIntervalSeconds = 30;
    public static String instructionMessage = "Please register your account in the #auth channel of the Discord server.";

    private FileConfig() {
    }

    public static void load(Logger logger) {
        Path configDir = Paths.get("config");
        Path configFile = configDir.resolve("minecraftdclink.properties");
        Properties properties = new Properties();

        try {
            Files.createDirectories(configDir);

            if (Files.exists(configFile)) {
                try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(configFile))) {
                    properties.load(in);
                }
            }

            apiBaseUrl = properties.getProperty("api.baseUrl", apiBaseUrl);
            apiKey = properties.getProperty("api.key", apiKey);
            apiTimeoutSeconds = parseInt(properties.getProperty("api.timeoutSeconds"), apiTimeoutSeconds);
            checkIntervalSeconds = parseInt(properties.getProperty("behavior.checkIntervalSeconds"), checkIntervalSeconds);
            messageIntervalSeconds = parseInt(properties.getProperty("behavior.messageIntervalSeconds"), messageIntervalSeconds);
            statusIntervalSeconds = parseInt(properties.getProperty("behavior.statusIntervalSeconds"), statusIntervalSeconds);
            instructionMessage = properties.getProperty("behavior.instructionMessage", instructionMessage);

            properties.setProperty("api.baseUrl", apiBaseUrl);
            properties.setProperty("api.key", apiKey);
            properties.setProperty("api.timeoutSeconds", Integer.toString(apiTimeoutSeconds));
            properties.setProperty("behavior.checkIntervalSeconds", Integer.toString(checkIntervalSeconds));
            properties.setProperty("behavior.messageIntervalSeconds", Integer.toString(messageIntervalSeconds));
            properties.setProperty("behavior.statusIntervalSeconds", Integer.toString(statusIntervalSeconds));
            properties.setProperty("behavior.instructionMessage", instructionMessage);

            try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(configFile))) {
                properties.store(out, "MinecraftDCLink configuration");
            }
        } catch (IOException e) {
            logger.error("Failed to load configuration.", e);
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
