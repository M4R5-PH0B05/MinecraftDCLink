package com.marsphobos.minecraftdclink.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModConfigs {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> API_BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> API_KEY;
    public static final ModConfigSpec.IntValue API_TIMEOUT_SECONDS;

    public static final ModConfigSpec.IntValue CHECK_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue MESSAGE_INTERVAL_SECONDS;
    public static final ModConfigSpec.ConfigValue<String> INSTRUCTION_MESSAGE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("api");
        API_BASE_URL = builder.define("baseUrl", "https://mc-auth.marsphobos.com");
        API_KEY = builder.define("key", "");
        API_TIMEOUT_SECONDS = builder.defineInRange("timeoutSeconds", 5, 1, 30);
        builder.pop();

        builder.push("behavior");
        CHECK_INTERVAL_SECONDS = builder.defineInRange("checkIntervalSeconds", 10, 2, 3600);
        MESSAGE_INTERVAL_SECONDS = builder.defineInRange("messageIntervalSeconds", 30, 5, 3600);
        INSTRUCTION_MESSAGE = builder.define("instructionMessage",
                "Please register your account in the #auth channel of the Discord server.");
        builder.pop();

        SPEC = builder.build();
    }

    private ModConfigs() {
    }
}
