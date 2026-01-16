package com.marsphobos.minecraftdclink;

import com.marsphobos.minecraftdclink.config.FileConfig;
import com.marsphobos.minecraftdclink.freeze.FreezeManager;
import com.marsphobos.minecraftdclink.http.RegistrationClient;
import com.marsphobos.minecraftdclink.roles.RoleManager;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod(MinecraftDCLink.MOD_ID)
public class MinecraftDCLink {
    public static final String MOD_ID = "minecraftdclink";
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftDCLink.class);

    private final ExecutorService dbExecutor;
    private final RegistrationClient registrationClient;
    private final FreezeManager freezeManager;
    private final RoleManager roleManager;
    private MinecraftServer server;
    private long lastStatusMillis;

    public MinecraftDCLink() {
        dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setName("MinecraftDCLink-DB");
            thread.setDaemon(true);
            return thread;
        });
        registrationClient = new RegistrationClient(LOGGER);
        roleManager = new RoleManager(LOGGER, registrationClient, dbExecutor);
        freezeManager = new FreezeManager(LOGGER, registrationClient, dbExecutor, roleManager::scheduleUpdate);

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerQuit);
        NeoForge.EVENT_BUS.addListener(this::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(this::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(this::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(this::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(this::onEntityInteractSpecific);
        NeoForge.EVENT_BUS.addListener(this::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(this::onPlayerDamage);
        NeoForge.EVENT_BUS.addListener(this::onChatMessage);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        FileConfig.load(LOGGER);
        freezeManager.setServer(server);
        roleManager.setServer(server);
        LOGGER.info("MinecraftDCLink server started.");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        dbExecutor.shutdownNow();
        LOGGER.info("MinecraftDCLink server stopping.");
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        freezeManager.handlePlayerJoin(player);
        roleManager.scheduleUpdate(player);
        String playerName = player.getGameProfile().getName();
        UUID playerId = player.getUUID();
        dbExecutor.execute(() -> registrationClient.sendPlayerEvent(playerId, playerName, "join"));
    }

    private void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        freezeManager.handlePlayerQuit(player);
        String playerName = player.getGameProfile().getName();
        UUID playerId = player.getUUID();
        dbExecutor.execute(() -> registrationClient.sendPlayerEvent(playerId, playerName, "leave"));
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        freezeManager.handlePlayerTick(player);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("register")
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    UUID uuid = player.getUUID();

                    Component uuidMessage = Component.literal("Your UUID: " + uuid)
                            .withStyle(style -> style
                                    .withColor(ChatFormatting.GOLD)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, uuid.toString()))
                                    .withHoverEvent(new HoverEvent(
                                            HoverEvent.Action.SHOW_TEXT,
                                            Component.literal("Click to copy UUID").withStyle(ChatFormatting.GREEN)
                                    )));
                    player.sendSystemMessage(uuidMessage);
                    player.sendSystemMessage(Component.literal(FileConfig.instructionMessage)
                            .withStyle(ChatFormatting.GRAY));

                    return Command.SINGLE_SUCCESS;
                });

        event.getDispatcher().register(command);
    }

    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (freezeManager.isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    private void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (freezeManager.isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    private void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (freezeManager.isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    private void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (freezeManager.isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    private void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (freezeManager.isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    private void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() == null) {
            return;
        }
        if (freezeManager.isFrozen(event.getPlayer().getUUID())) {
            event.setCanceled(true);
        }
    }

    private void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (freezeManager.isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    private void onPlayerDamage(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (freezeManager.isFrozen(player.getUUID())) {
            event.setNewDamage(0.0F);
        }
    }

    private void onChatMessage(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        Component prefix = roleManager.getPrefixComponent(player.getUUID());
        if (prefix == null) {
            return;
        }
        String username = event.getUsername();
        String rawText = event.getRawText();
        Component nameComponent = Component.literal(username).withStyle(roleManager.getNameStyle(player.getUUID()));
        Component message = Component.literal("<")
                .append(prefix)
                .append(nameComponent)
                .append(Component.literal("> " + rawText));
        event.setCanceled(true);
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (server == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long intervalMs = FileConfig.statusIntervalSeconds * 1000L;
        if (now - lastStatusMillis < intervalMs) {
            return;
        }
        lastStatusMillis = now;
        long dayTime = server.overworld().getDayTime();
        long day = dayTime / 24000L;
        long timeOfDay = dayTime % 24000L;
        dbExecutor.execute(() -> registrationClient.sendServerStatus(day, timeOfDay));
    }
}
