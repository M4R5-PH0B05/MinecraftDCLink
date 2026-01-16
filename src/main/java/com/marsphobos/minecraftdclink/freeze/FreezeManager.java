package com.marsphobos.minecraftdclink.freeze;

import com.marsphobos.minecraftdclink.config.FileConfig;
import com.marsphobos.minecraftdclink.http.RegistrationClient;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public class FreezeManager {
    private static final double TELEPORT_EPSILON_SQR = 0.0025D;

    private final Logger logger;
    private final RegistrationClient registrationClient;
    private final ExecutorService executor;
    private final Consumer<ServerPlayer> onUnfreeze;
    private final Map<UUID, FrozenPlayer> frozenPlayers = new ConcurrentHashMap<>();
    private MinecraftServer server;

    public FreezeManager(Logger logger, RegistrationClient registrationClient, ExecutorService executor, Consumer<ServerPlayer> onUnfreeze) {
        this.logger = logger;
        this.registrationClient = registrationClient;
        this.executor = executor;
        this.onUnfreeze = onUnfreeze;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public boolean isFrozen(UUID playerId) {
        return frozenPlayers.containsKey(playerId);
    }

    public void handlePlayerJoin(ServerPlayer player) {
        freeze(player);
        scheduleRegistrationCheck(player, true);
    }

    public void handlePlayerQuit(ServerPlayer player) {
        frozenPlayers.remove(player.getUUID());
    }

    public void handlePlayerTick(ServerPlayer player) {
        FrozenPlayer frozen = frozenPlayers.get(player.getUUID());
        if (frozen == null) {
            return;
        }

        Vec3 currentPos = player.position();
        Vec3 frozenPos = frozen.getPosition();
        if (currentPos.distanceToSqr(frozenPos) > TELEPORT_EPSILON_SQR) {
            teleportPlayer(player, frozen);
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;

        long now = System.currentTimeMillis();
        long checkIntervalMs = FileConfig.checkIntervalSeconds * 1000L;
        if (now - frozen.getLastCheckMillis() >= checkIntervalMs) {
            frozen.setLastCheckMillis(now);
            scheduleRegistrationCheck(player, false);
        }

        long messageIntervalMs = FileConfig.messageIntervalSeconds * 1000L;
        if (now - frozen.getLastMessageMillis() >= messageIntervalMs) {
            frozen.setLastMessageMillis(now);
            sendInstruction(player);
        }
    }

    private void teleportPlayer(ServerPlayer player, FrozenPlayer frozen) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(frozen.getDimension());
        if (level == null) {
            return;
        }
        Vec3 pos = frozen.getPosition();
        player.teleportTo(level, pos.x, pos.y, pos.z, frozen.getYaw(), frozen.getPitch());
    }

    private void freeze(ServerPlayer player) {
        FrozenPlayer frozen = new FrozenPlayer(
                player.level().dimension(),
                player.position(),
                player.getYRot(),
                player.getXRot()
        );
        long now = System.currentTimeMillis();
        frozen.setLastCheckMillis(now);
        frozen.setLastMessageMillis(now);
        frozenPlayers.put(player.getUUID(), frozen);
    }

    private void unfreeze(ServerPlayer player) {
        frozenPlayers.remove(player.getUUID());
        player.sendSystemMessage(Component.literal("Your account has been registered. You can move now.")
                .withStyle(ChatFormatting.GREEN));
        if (onUnfreeze != null) {
            onUnfreeze.accept(player);
        }
    }

    private void sendInstruction(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(FileConfig.instructionMessage)
                .withStyle(ChatFormatting.RED));
    }

    private void scheduleRegistrationCheck(ServerPlayer player, boolean immediateMessage) {
        if (server == null) {
            return;
        }
        UUID playerId = player.getUUID();
        CompletableFuture
                .supplyAsync(() -> registrationClient.isRegistered(playerId), executor)
                .thenAcceptAsync(registered -> {
                    ServerPlayer current = server.getPlayerList().getPlayer(playerId);
                    if (current == null) {
                        return;
                    }
                    if (registered) {
                        unfreeze(current);
                    } else {
                        if (frozenPlayers.get(playerId) == null) {
                            freeze(current);
                        }
                        if (immediateMessage) {
                            sendInstruction(current);
                        }
                    }
                }, server);
    }
}
