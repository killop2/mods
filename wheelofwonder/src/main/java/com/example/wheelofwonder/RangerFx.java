package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Client-side particles of the Ranger: a handful per tick, vanilla types only. */
final class RangerFx {
    private RangerFx() {
    }

    static void ambient(RangerRideEntity e) {
        Level level = e.level();
        RandomSource r = e.getRandom();
        int t = e.tickCount;
        Vec3 pivot = e.toWorld(new Vec3(0.0D, RangerRideEntity.PIVOT_Y, RangerRideEntity.ARM_Z));
        // the jester on top smoulders, notes drift from the sign
        if (t % 10 == 0) {
            Vec3 m = e.toWorld(new Vec3(0.0D, RangerRideEntity.PIVOT_Y + 2.9D, -0.2D));
            level.addParticle(ParticleTypes.WITCH, m.x + (r.nextDouble() - 0.5D) * 0.8D, m.y, m.z + (r.nextDouble() - 0.5D) * 0.8D, 0.0D, 0.02D, 0.0D);
        }
        float sp = e.speed(0.0F);
        if (t % 9 == 0 && sp > 0.05F) level.addParticle(ParticleTypes.NOTE, pivot.x, pivot.y + 2.4D, pivot.z, r.nextDouble(), 0.0D, 0.0D);
        if (sp <= 0.02F) return;
        double ang = e.viewAngle(0.0F);
        // sparks and steam from the bearing
        if (r.nextFloat() < sp) {
            double a = r.nextDouble() * Math.PI * 2.0D;
            Vec3 d = e.dirToWorld(new Vec3(Math.cos(a), Math.sin(a), 0.0D));
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, pivot.x + d.x * 0.6D, pivot.y + d.y * 0.6D, pivot.z + d.z * 0.6D, d.x * 0.15D, d.y * 0.15D + 0.05D, d.z * 0.15D);
        }
        if (t % 5 == 0) level.addParticle(ParticleTypes.CLOUD, pivot.x, pivot.y - 0.3D, pivot.z, 0.0D, 0.02D, 0.0D);
        // the gondola throws off confetti and violet fire; the mallet trails smoke
        Vec3 g = e.toWorld(RangerRideEntity.gondolaLocal(ang));
        if (r.nextFloat() < 0.3F + 0.7F * sp) {
            int col = WheelEntity.BULB_COLORS[r.nextInt(WheelEntity.BULB_COLORS.length)];
            level.addParticle(WheelEntity.dust(col, 1.1F), g.x + r.nextGaussian() * 0.5D, g.y + r.nextGaussian() * 0.5D, g.z + r.nextGaussian() * 0.4D, 0.0D, 0.0D, 0.0D);
        }
        if (sp > 0.6F && t % 2 == 0) level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, g.x, g.y - 1.0D, g.z, 0.0D, 0.0D, 0.0D);
        Vec3 mallet = e.toWorld(new Vec3(-RangerRideEntity.MALLET * Math.sin(ang), RangerRideEntity.PIVOT_Y + RangerRideEntity.MALLET * Math.cos(ang), RangerRideEntity.ARM_Z));
        if (sp > 0.4F && t % 2 == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, mallet.x, mallet.y, mallet.z, 0.0D, 0.01D, 0.0D);
        if (sp > 0.7F && r.nextFloat() < 0.3F) level.addParticle(ParticleTypes.FLAME, mallet.x, mallet.y, mallet.z, 0.0D, 0.02D, 0.0D);
        // a gust of dust when it sweeps past the ground
        if (sp > 0.8F && g.y - e.getY() < 2.2D) {
            for (int i = 0; i < 3; i++) {
                level.addParticle(ParticleTypes.POOF, g.x + r.nextGaussian() * 0.8D, e.getY() + 0.4D, g.z + r.nextGaussian() * 0.8D, r.nextGaussian() * 0.05D, 0.03D, r.nextGaussian() * 0.05D);
            }
        }
    }
}
