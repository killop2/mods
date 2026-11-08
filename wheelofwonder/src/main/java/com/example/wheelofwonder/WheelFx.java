package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side particles (each client makes its own, nothing is sent over the network):
 * sparkles from the bulbs, confetti, notes, spooky soul-fire under the levitating wheel, and the explosion burst.
 * Only vanilla particle types, so nothing can clash with other mods.
 */
public final class WheelFx {
    private WheelFx() {
    }

    static void ambient(WheelEntity w) {
        Level level = w.level();
        RandomSource r = w.getRandom();
        int phase = w.phase();
        if (phase == WheelEntity.BLOWN) return;
        int t = w.tickCount;
        float R = WheelEntity.RADIUS;

        // sparkle from a random bulb on either face
        for (int i = 0; i < 2; i++) {
            int b = r.nextInt(16);
            double a = b * Math.PI * 2.0D / 16.0D;
            double side = r.nextBoolean() ? 1.0D : -1.0D;
            Vec3 p = w.clientPoint(Math.cos(a) * R * 0.93D, Math.sin(a) * R * 0.93D, side * (WheelEntity.THICK * 0.5D + 0.1D));
            int col = WheelEntity.BULB_COLORS[b % WheelEntity.BULB_COLORS.length];
            level.addParticle(WheelEntity.dust(col, 0.8F), p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
        }
        if (phase == WheelEntity.ROLL) {
            if (t % 3 == 0) {
                Vec3 p = w.position();
                level.addParticle(ParticleTypes.FIREWORK, p.x, p.y + 0.1D, p.z, (r.nextDouble() - 0.5D) * 0.2D, 0.15D, (r.nextDouble() - 0.5D) * 0.2D);
            }
            return;
        }

        Vec3 c = w.center();
        // levitation: soul-fire jets under the wheel (the Halloween part)
        if (t % 2 == 0) {
            Vec3 bottom = c.subtract(0.0D, R + 0.1D, 0.0D);
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, bottom.x + (r.nextDouble() - 0.5D) * 0.6D, bottom.y,
                    bottom.z + (r.nextDouble() - 0.5D) * 0.6D, 0.0D, -0.06D, 0.0D);
        }
        if (t % 9 == 0) {
            level.addParticle(ParticleTypes.SOUL, c.x + (r.nextDouble() - 0.5D) * 2.0D, c.y - R, c.z + (r.nextDouble() - 0.5D) * 2.0D, 0.0D, 0.03D, 0.0D);
        }
        // carnival: confetti and notes
        if (t % 2 == 0) {
            int col = WheelEntity.BULB_COLORS[r.nextInt(WheelEntity.BULB_COLORS.length)];
            level.addParticle(WheelEntity.dust(col, 1.1F), c.x + (r.nextDouble() - 0.5D) * 4.0D, c.y + R + 0.5D + r.nextDouble(),
                    c.z + (r.nextDouble() - 0.5D) * 4.0D, 0.0D, -0.05D, 0.0D);
        }
        if (t % 8 == 0) {
            level.addParticle(ParticleTypes.NOTE, c.x + (r.nextDouble() - 0.5D) * 1.5D, c.y + R + 0.4D, c.z + (r.nextDouble() - 0.5D) * 1.5D,
                    r.nextDouble(), 0.0D, 0.0D);
        }
        if (t % 6 == 0) {
            level.addParticle(ParticleTypes.WITCH, c.x + (r.nextDouble() - 0.5D) * 3.0D, c.y + (r.nextDouble() - 0.5D) * 3.0D,
                    c.z + (r.nextDouble() - 0.5D) * 3.0D, 0.0D, 0.0D, 0.0D);
        }
        // countdown: a red ring of embers around the rim, denser near the end
        int fuse = w.fuse();
        if (phase == WheelEntity.HOVER && fuse >= 0 && fuse <= 40) {
            int n = 2 + (40 - fuse) / 6;
            for (int i = 0; i < n; i++) {
                double a = r.nextDouble() * Math.PI * 2.0D;
                Vec3 p = w.clientPoint(Math.cos(a) * (R + 0.15D), Math.sin(a) * (R + 0.15D), (r.nextDouble() - 0.5D) * 0.5D);
                level.addParticle(WheelEntity.dust(0xFF2020, 1.3F), p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
                if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.SMALL_FLAME, p.x, p.y, p.z, 0.0D, 0.02D, 0.0D);
            }
        }
    }

    static void explosion(WheelEntity w) {
        Level level = w.level();
        RandomSource r = w.getRandom();
        Vec3 c = w.center();
        level.addParticle(ParticleTypes.FLASH, c.x, c.y, c.z, 0.0D, 0.0D, 0.0D);
        for (int i = 0; i < 90; i++) {
            Vec3 v = randomDir(r).scale(0.35D + r.nextDouble() * 0.45D);
            level.addParticle(ParticleTypes.FIREWORK, c.x, c.y, c.z, v.x, v.y, v.z);
        }
        for (int i = 0; i < 160; i++) {
            Vec3 v = randomDir(r).scale(3.0D + r.nextDouble() * 6.0D);
            int col = WheelEntity.BULB_COLORS[i % WheelEntity.BULB_COLORS.length];
            level.addParticle(WheelEntity.dust(col, 1.4F), c.x + v.x * 0.1D, c.y + v.y * 0.1D, c.z + v.z * 0.1D, v.x, v.y, v.z);
        }
        for (int i = 0; i < 50; i++) {
            Vec3 v = randomDir(r).scale(0.4D + r.nextDouble() * 0.5D);
            level.addParticle(ParticleTypes.TOTEM_OF_UNDYING, c.x, c.y, c.z, v.x, v.y + 0.2D, v.z);
        }
        for (int i = 0; i < 30; i++) {
            Vec3 v = randomDir(r).scale(0.1D + r.nextDouble() * 0.2D);
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y, c.z, v.x, v.y, v.z);
            level.addParticle(ParticleTypes.LARGE_SMOKE, c.x + v.x * 5, c.y + v.y * 5, c.z + v.z * 5, v.x * 0.3D, 0.05D, v.z * 0.3D);
        }
        for (int i = 0; i < 12; i++) {
            Vec3 v = randomDir(r).scale(0.15D);
            level.addParticle(ParticleTypes.SOUL, c.x, c.y, c.z, v.x, v.y + 0.1D, v.z);
        }
    }

    private static Vec3 randomDir(RandomSource r) {
        Vec3 v = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
        return v.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 1.0D, 0.0D) : v.normalize();
    }
}
