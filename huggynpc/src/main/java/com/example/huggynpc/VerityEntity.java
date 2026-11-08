package com.example.huggynpc;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

/**
 * Verity, the pixel ball. Like the other models: Custom NPCs borrows it for the look and the hitbox (a ball the size
 * of a head); the spawn-egg fallback stands and looks around. What it does near other NPCs (jumping onto their
 * heads) is VerityBrain, for both kinds.
 */
public class VerityEntity extends HuggyEntity {
    public VerityEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }
}
