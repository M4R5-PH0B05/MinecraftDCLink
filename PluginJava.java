package com.marsphobos.minecraftdclink.roles;

import com.marsphobos.minecraftdclink.http.RegistrationClient;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public class RoleManager {
    private static final String TEAM_PREFIX = "dclink_";
    private final Logger logger;
    private final RegistrationClient registrationClient;
    private final ExecutorService executor;
    private final Map<UUID, RegistrationClient.RoleInfo> roleCache = new ConcurrentHashMap<>();
    private MinecraftServer server;

    public RoleManager(Logger logger, RegistrationClient registrationClient, ExecutorService executor) {
        this.logger = logger;
        this.registrationClient = registrationClient;
        this.executor = executor;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public void scheduleUpdate(ServerPlayer player) {
        if (server == null) {
            return;
        }
        UUID playerId = player.getUUID();
        executor.execute(() -> {
            RegistrationClient.RoleInfo roleInfo = registrationClient.getRoleInfo(playerId);
            server.execute(() -> applyRole(playerId, roleInfo));
        });
    }

    public Component getPrefixComponent(UUID playerId) {
        RegistrationClient.RoleInfo roleInfo = roleCache.get(playerId);
        if (roleInfo == null || roleInfo.roleName().isBlank()) {
            return null;
        }
        Style style = Style.EMPTY;
        if (roleInfo.color() != 0) {
            style = style.withColor(TextColor.fromRgb(roleInfo.color()));
        }
        return Component.literal("[" + roleInfo.roleName() + "] ").withStyle(style);
    }

    public Style getNameStyle(UUID playerId) {
        RegistrationClient.RoleInfo roleInfo = roleCache.get(playerId);
        if (roleInfo == null || roleInfo.color() == 0) {
            return Style.EMPTY;
        }
        return Style.EMPTY.withColor(TextColor.fromRgb(roleInfo.color()));
    }

    private void applyRole(UUID playerId, RegistrationClient.RoleInfo roleInfo) {
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return;
        }

        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam currentTeam = scoreboard.getPlayersTeam(player.getScoreboardName());
        if (currentTeam != null && currentTeam.getName().startsWith(TEAM_PREFIX)) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), currentTeam);
        }

        if (roleInfo == null) {
            roleCache.remove(playerId);
            return;
        }
        roleCache.put(playerId, roleInfo);

        String teamName = TEAM_PREFIX + Integer.toHexString(roleInfo.roleName().hashCode());
        PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
        }

        String prefixText = "[" + roleInfo.roleName() + "] ";
        Style style = Style.EMPTY;
        if (roleInfo.color() != 0) {
            style = style.withColor(TextColor.fromRgb(roleInfo.color()));
        }
        team.setPlayerPrefix(Component.literal(prefixText).withStyle(style));

        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
    }
}
