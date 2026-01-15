package com.marsphobos.minecraftdclink.freeze;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class FrozenPlayer {
    private final ResourceKey<Level> dimension;
    private final Vec3 position;
    private final float yaw;
    private final float pitch;
    private long lastCheckMillis;
    private long lastMessageMillis;

    public FrozenPlayer(ResourceKey<Level> dimension, Vec3 position, float yaw, float pitch) {
        this.dimension = dimension;
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public Vec3 getPosition() {
        return position;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public long getLastCheckMillis() {
        return lastCheckMillis;
    }

    public void setLastCheckMillis(long lastCheckMillis) {
        this.lastCheckMillis = lastCheckMillis;
    }

    public long getLastMessageMillis() {
        return lastMessageMillis;
    }

    public void setLastMessageMillis(long lastMessageMillis) {
        this.lastMessageMillis = lastMessageMillis;
    }
}
