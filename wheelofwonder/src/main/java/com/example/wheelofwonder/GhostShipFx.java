package com.example.wheelofwonder;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side particles of the ghost ship: fog creeping over the ground round the target, dark storm clouds
 * boiling above, sea water and mist dripping off the hull, ghost fire in the lanterns, drifting souls.
 */
final class GhostShipFx {
    private GhostShipFx() {
    }

    static void tick(GhostShipEntity e) {
        Level level = e.level();
        RandomSource r = e.getRandom();
        double t = e.tickCount;
        float there = GhostShipEntity.presence(t);
        Vec3 a = e.anchor();
        boolean fog = t < GhostShipEntity.VANISH_END - 10;
        // ground fog round the target, thickest while the ship is there
        if (fog) {
            int n = 3 + (int) (5 * Math.min(1.0D, t / 40.0D));
            for (int i = 0; i < n; i++) {
                double ang = r.nextDouble() * Math.PI * 2.0D, rad = 2.0D + r.nextDouble() * 12.0D;
                level.addParticle(ParticleTypes.CLOUD, a.x + Math.cos(ang) * rad, a.y + 0.2D + r.nextDouble() * 1.2D, a.z + Math.sin(ang) * rad,
                        -Math.sin(ang) * 0.02D, 0.0D, Math.cos(ang) * 0.02D);
            }
            // the storm overhead
            for (int i = 0; i < 2; i++) {
                double ang = r.nextDouble() * Math.PI * 2.0D, rad = r.nextDouble() * 14.0D;
                level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, a.x + Math.cos(ang) * rad, a.y + 18.0D + r.nextDouble() * 4.0D, a.z + Math.sin(ang) * rad,
                        0.0D, 0.01D, 0.0D);
            }
            if (r.nextFloat() < 0.5F) {
                level.addParticle(ParticleTypes.FALLING_WATER, a.x + r.nextGaussian() * 8.0D, a.y + 14.0D, a.z + r.nextGaussian() * 8.0D, 0.0D, 0.0D, 0.0D);
            }
        }
        // the shark pool: spray off the fins, bubbles
        float w = GhostShipEntity.water(t);
        if (w > 0.2F) {
            for (int k = 0; k < GhostShipEntity.SHARKS; k++) {
                Vec3 s = e.shark(k, t).add(0.0D, 0.5D, 0.0D);
                if (r.nextFloat() < 0.5F) level.addParticle(ParticleTypes.SPLASH, s.x, e.waterY() + 0.1D, s.z, 0.0D, 0.05D, 0.0D);
            }
            if (r.nextFloat() < 0.6F) {
                double ang = r.nextDouble() * Math.PI * 2.0D, rad = r.nextDouble() * GhostShipEntity.WATER_R * w;
                level.addParticle(ParticleTypes.BUBBLE_POP, a.x + Math.cos(ang) * rad, e.waterY() + 0.05D, a.z + Math.sin(ang) * rad, 0.0D, 0.02D, 0.0D);
            }
        }
        if (there <= 0.05F) return;
        // the ship itself: water and mist pouring off the hull, ghost fire in the lanterns, souls drifting
        for (int i = 0; i < 2; i++) {
            Vec3 p = e.toWorld(new Vec3((r.nextDouble() - 0.5D) * 4.4D, 0.3D, (r.nextDouble() - 0.5D) * 13.0D), t);
            level.addParticle(r.nextBoolean() ? ParticleTypes.FALLING_WATER : ParticleTypes.DRIPPING_WATER, p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
        }
        if (r.nextFloat() < 0.6F * there) {
            Vec3 p = e.toWorld(new Vec3((r.nextDouble() - 0.5D) * 5.0D, r.nextDouble() * 1.5D, (r.nextDouble() - 0.5D) * 15.0D), t);
            level.addParticle(ParticleTypes.CLOUD, p.x, p.y, p.z, 0.0D, -0.02D, 0.0D);
        }
        double[][] lanterns = {{-2.3D, 3.4D, 5.5D}, {2.3D, 3.4D, 5.5D}, {-2.0D, 4.6D, -6.6D}, {2.0D, 4.6D, -6.6D}};
        for (double[] l : lanterns) {
            if (r.nextFloat() > 0.35F * there) continue;
            Vec3 p = e.toWorld(new Vec3(l[0], l[1], l[2]), t);
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 0.0D, 0.01D, 0.0D);
        }
        if (r.nextFloat() < 0.25F * there) {
            Vec3 p = e.toWorld(new Vec3((r.nextDouble() - 0.5D) * 4.0D, 2.5D + r.nextDouble() * 6.0D, (r.nextDouble() - 0.5D) * 12.0D), t);
            level.addParticle(ParticleTypes.SOUL, p.x, p.y, p.z, 0.0D, 0.03D, 0.0D);
        }
        // while it sails in, a wake of mist behind it
        if (t < GhostShipEntity.SAIL_END && t > GhostShipEntity.APPEAR) {
            Vec3 p = e.toWorld(new Vec3((r.nextDouble() - 0.5D) * 3.0D, 0.5D, -7.5D), t);
            level.addParticle(ParticleTypes.CLOUD, p.x, p.y, p.z, 0.0D, 0.0D, 0.0D);
        }
    }
}
