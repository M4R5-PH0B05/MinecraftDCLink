package com.marsphobos.minecraftdclink;

import com.marsphobos.minecraftdclink.config.ModConfigs;
import com.marsphobos.minecraftdclink.freeze.FreezeManager;
import com.marsphobos.minecraftdclink.http.RegistrationClient;
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
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.event.entity.living.LivingHurtEvent;
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
    private MinecraftServer server;

    public MinecraftDCLink() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ModConfigs.SPEC);

        dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setName("MinecraftDCLink-DB");
            thread.setDaemon(true);
            return thread;
        });
        registrationClient = new RegistrationClient(LOGGER);
        freezeManager = new FreezeManager(LOGGER, registrationClient, dbExecutor);

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
    }

    private void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        freezeManager.setServer(server);
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
    }

    private void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        freezeManager.handlePlayerQuit(player);
    }

    private void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
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
                    player.sendSystemMessage(Component.literal(ModConfigs.INSTRUCTION_MESSAGE.get())
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

    private void onPlayerDamage(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (freezeManager.isFrozen(player.getUUID())) {
            event.setCanceled(true);
        }
    }
}
