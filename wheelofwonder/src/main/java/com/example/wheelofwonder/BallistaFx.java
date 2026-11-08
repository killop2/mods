package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Client-side ballista particles: a few per tick at most, vanilla types only. */
final class BallistaFx {
    private BallistaFx() {
    }

    static void ambient(BallistaEntity b) {
        Level level = b.level();
        RandomSource r = b.getRandom();
        int t = b.tickCount;
        int st = b.state();
        // the jack-o'-lantern on the crossbar smoulders with purple sparks
        if (t % 12 == 0) {
            Vec3 p = b.toWorld(new Vec3(0.0D, 1.86D, 0.54D));
            level.addParticle(ParticleTypes.WITCH, p.x, p.y, p.z, 0.0D, 0.02D, 0.0D);
        }
        if (t % 23 == 0) {
            Vec3 p = b.toWorld(new Vec3((r.nextDouble() - 0.5D) * 1.2D, 0.55D, (r.nextDouble() - 0.5D) * 2.6D - 0.25D));
            level.addParticle(WheelEntity.dust(WheelEntity.BULB_COLORS[r.nextInt(WheelEntity.BULB_COLORS.length)], 0.7F), p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
        }
        if (st == BallistaEntity.LOADED) {
            // tension: sparks crackle around the bucket, smoke from the twisted ropes, notes from the bells
            Vec3 s = b.seat(b.armAngle(0.0F));
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, s.x + (r.nextDouble() - 0.5D) * 0.9D, s.y + r.nextDouble() * 0.6D,
                    s.z + (r.nextDouble() - 0.5D) * 0.9D, 0.0D, 0.03D, 0.0D);
            if (t % 3 == 0) {
                Vec3 k = b.toWorld(new Vec3((r.nextBoolean() ? 1 : -1) * 0.35D, BallistaEntity.PIVOT_Y, BallistaEntity.PIVOT_Z));
                level.addParticle(ParticleTypes.SMOKE, k.x, k.y, k.z, 0.0D, 0.03D, 0.0D);
            }
            if (t % 6 == 0) level.addParticle(ParticleTypes.NOTE, s.x, s.y + 1.4D, s.z, r.nextDouble(), 0.0D, 0.0D);
        }
    }

    /** The arm starts its swing: a puff from the torsion ropes and soul fire from the wheels. */
    static void fire(BallistaEntity b) {
        Level level = b.level();
        RandomSource r = b.getRandom();
        for (int i = 0; i < 10; i++) {
            Vec3 k = b.toWorld(new Vec3((r.nextDouble() - 0.5D) * 0.8D, BallistaEntity.PIVOT_Y, BallistaEntity.PIVOT_Z));
            level.addParticle(ParticleTypes.LARGE_SMOKE, k.x, k.y, k.z, (r.nextDouble() - 0.5D) * 0.1D, 0.05D, (r.nextDouble() - 0.5D) * 0.1D);
        }
        for (double[] w : new double[][]{{0.8D, 0.95D}, {-0.8D, 0.95D}, {0.8D, -1.45D}, {-0.8D, -1.45D}}) {
            Vec3 p = b.toWorld(new Vec3(w[0], 0.1D, w[1]));
            for (int i = 0; i < 3; i++) {
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, (r.nextDouble() - 0.5D) * 0.08D, 0.04D, (r.nextDouble() - 0.5D) * 0.08D);
            }
        }
    }
}
