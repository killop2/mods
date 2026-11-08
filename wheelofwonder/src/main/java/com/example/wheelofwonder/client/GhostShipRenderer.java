package com.example.wheelofwonder.client;

import com.example.wheelofwonder.GhostShipEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * The ghost ship and its dead crew. The hull: rotten black planks bellying out, moss and barnacles low down, seams
 * glowing ghost green, a raised stern deck with lit windows, a skull for a figurehead, three masts with torn grey
 * sails, shrouds, cannons in their ports and lanterns burning with ghost fire; a torn black flag with a skull. The
 * crew: skeleton sailors in bandanas with sabres and pistols, and their captain in a long coat and tricorn. Ropes,
 * the bound prisoner's coils, cannonballs with their muzzle flashes, and the storm cloud over the place.
 * It fades in and out of the fog (drawn see-through while not fully there). Three passes: solid, see-through, glow.
 */
public class GhostShipRenderer extends EntityRenderer<GhostShipEntity> {
    private static final int HULL = 0x2C2A25, HULL2 = 0x37332C, MOSS = 0x3C5642, DECKW = 0x4A4236, RAILC = 0x3E362C, MAST = 0x2E261F;
    private static final int SEAM = 0x5CFFB0, WINDOW = 0xB8FF9A, BONE = 0xC9D1BE, GOLD = 0x7A6630, IRON = 0x1E1E22, ROPE = 0x6B5638;
    private static final int CLOTH = 0x4C5A4E, CLOTH2 = 0x353F38, BANDANA = 0x5A1E1E, COAT = 0x1E2422, HAT = 0x141616, SAIL = 0xA6B09C;
    private static final int FULL = LightTexture.FULL_BRIGHT;
    private static final double[][] MASTS = {{3.6D, 9.0D}, {0.0D, 11.5D}, {-4.6D, 8.0D}};
    private static final double[][] LANTERNS = {{-2.3D, 3.4D, 5.5D}, {2.3D, 3.4D, 5.5D}, {-2.0D, 4.6D, -6.6D}, {2.0D, 4.6D, -6.6D}};

    private float alpha = 1.0F;

    public GhostShipRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(GhostShipEntity e) {
        return WheelRenderer.WHITE;
    }

    @Override
    public boolean shouldRender(GhostShipEntity e, net.minecraft.client.renderer.culling.Frustum f, double x, double y, double z) {
        return true;
    }

    private int col(int rgb) {
        return ((int) (Mth.clamp(this.alpha, 0.0F, 1.0F) * 255.0F) << 24) | (rgb & 0xFFFFFF);
    }

    /** Half the hull's width at z (towards the bow it narrows to a point, the stern stays broad). */
    private static double halfWidth(double z) {
        if (z > 2.0D) {
            double u = (z - 2.0D) / 5.0D;
            return 2.2D * (1.0D - 0.82D * u * u);
        }
        if (z < -5.0D) return 2.2D - (-5.0D - z) * 0.18D;
        return 2.2D;
    }

    /** The keel line: the hull's bottom rises towards the bow and a little at the stern. */
    private static double bottom(double z) {
        if (z > 3.0D) return (z - 3.0D) * 0.28D;
        if (z < -5.0D) return (-5.0D - z) * 0.25D;
        return 0.0D;
    }

    @Override
    public void render(GhostShipEntity e, float entityYaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        double t = e.tickCount + partial;
        this.alpha = GhostShipEntity.presence(t);
        Vec3 base = e.getPosition(partial);
        float time = (float) t;
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 7), LightTexture.sky(light));

        // ---- pass 1: the solid things (see-through while the ship is still forming in the fog)
        boolean ghostly = this.alpha < 0.995F;
        RenderType solidType = ghostly ? RenderType.beaconBeam(WheelRenderer.WHITE, true) : RenderType.entityCutoutNoCull(WheelRenderer.WHITE);
        if (this.alpha > 0.01F) {
            VertexConsumer c = buffers.getBuffer(solidType);
            pose.pushPose();
            Vec3 o = e.origin(t);
            pose.translate(o.x - base.x, o.y - base.y, o.z - base.z);
            pose.mulPose(Axis.YP.rotation((float) -e.yawRad(t)));
            pose.mulPose(Axis.ZP.rotation((float) GhostShipEntity.roll(t)));
            drawHull(c, pose, lit, time);
            pose.popPose();
            // the crew, the ropes, the prisoner's bonds, the cannonballs (world positions)
            drawCrew(e, c, pose, base, t, lit, time);
            drawRopes(e, c, pose, base, t, lit);
            drawBalls(e, c, pose, base, t, lit);
            drawSharks(e, c, pose, base, t, lit, time);
        }

        // ---- pass 2: sails and the storm cloud (always see-through)
        VertexConsumer tr = buffers.getBuffer(RenderType.beaconBeam(WheelRenderer.WHITE, true));
        if (this.alpha > 0.01F) {
            pose.pushPose();
            Vec3 o = e.origin(t);
            pose.translate(o.x - base.x, o.y - base.y, o.z - base.z);
            pose.mulPose(Axis.YP.rotation((float) -e.yawRad(t)));
            pose.mulPose(Axis.ZP.rotation((float) GhostShipEntity.roll(t)));
            drawSails(tr, pose, time);
            pose.popPose();
        }
        drawWater(e, tr, pose, base, t, time);
        drawStorm(e, tr, pose, base, t);

        // ---- pass 3: glows
        boolean shaders = ShaderCompat.shadersOn();
        RenderType gt = shaders ? RenderType.beaconBeam(ShaderCompat.GLOW_SOFT, true) : ShaderCompat.glow(WheelRenderer.GLOW);
        VertexConsumer glow = buffers.getBuffer(gt);
        Quaternionf cam = this.entityRenderDispatcher.cameraOrientation();
        float fl = 0.85F + 0.15F * Mth.sin(time * 0.7F);
        if (this.alpha > 0.01F) {
            for (double[] l : LANTERNS) halo(pose, glow, cam, e.toWorld(new Vec3(l[0], l[1], l[2]), t).subtract(base), 0.7F * fl, 0x40FFC8, 0.6F * this.alpha);
            for (int w = -1; w <= 1; w++) halo(pose, glow, cam, e.toWorld(new Vec3(w * 0.9D, 2.7D, -7.2D), t).subtract(base), 0.6F, 0xB8FF9A, 0.45F * this.alpha);
            halo(pose, glow, cam, e.toWorld(new Vec3(0.0D, 0.8D, 0.0D), t).subtract(base), 6.0F, 0x1E8F6A, 0.18F * this.alpha);
            halo(pose, glow, cam, e.toWorld(new Vec3(0.0D, 2.0D, 7.7D), t).subtract(base), 0.4F * fl, 0x40FFC8, 0.8F * this.alpha);
            for (int i = 0; i < GhostShipEntity.CREW; i++) {
                Vec3 s = e.sailor(i, t);
                halo(pose, glow, cam, s.add(0.0D, 1.62D, 0.0D).subtract(base), 0.22F * fl, 0x40FFC8, 0.8F * this.alpha);
            }
            halo(pose, glow, cam, e.captain(t).add(0.0D, 1.85D, 0.0D).subtract(base), 0.26F * fl, 0x40FFC8, 0.8F * this.alpha);
        }
        for (int k = 0; k < GhostShipEntity.CANNONS; k++) {
            double since = t - (GhostShipEntity.CANNON0 + k * GhostShipEntity.CANNON_STEP);
            if (since >= 0.0D && since < 4.0D) {
                float f = (float) (1.0D - since / 4.0D);
                halo(pose, glow, cam, e.gunport(k, t).subtract(base), 2.2F * f, 0xFF9A30, 0.9F * f);
            }
            double hit = since - GhostShipEntity.BALL_FLIGHT;
            if (hit >= 0.0D && hit < 5.0D) {
                float f = (float) (1.0D - hit / 5.0D);
                Vec3 p = impact(e, k);
                halo(pose, glow, cam, p.add(0.0D, 0.6D, 0.0D).subtract(base), 3.0F * f, 0x58FFB8, 0.8F * f);
            }
        }
        super.render(e, entityYaw, partial, pose, buffers, light);
    }

    private static void halo(PoseStack pose, VertexConsumer glow, Quaternionf cam, Vec3 rel, float size, int rgb, float strength) {
        if (strength <= 0.01F) return;
        pose.pushPose();
        pose.translate(rel.x, rel.y, rel.z);
        pose.mulPose(cam);
        Draw.halo(glow, pose.last(), size, Draw.shade(0xFF000000 | rgb, strength));
        pose.popPose();
    }

    // ------------------------------------------------------------------ the hull

    private void drawHull(VertexConsumer c, PoseStack pose, int lit, float time) {
        PoseStack.Pose p = pose.last();
        // planked hull: bands bellying out towards the deck, each band a little darker or lighter
        double step = 0.5D;
        for (double z = -7.0D; z < 7.0D - 1.0E-6D; z += step) {
            double zm = z + step * 0.5D, w = halfWidth(zm), b = bottom(zm);
            int band = 0;
            for (double y = b; y < GhostShipEntity.DECK - 1.0E-6D; y += 0.45D, band++) {
                double y1 = Math.min(GhostShipEntity.DECK, y + 0.45D);
                double belly = 0.72D + 0.28D * Math.sin(Math.min(1.0D, (y1 - b) / (GhostShipEntity.DECK - b + 0.01D)) * Math.PI * 0.5D);
                double hw = w * belly;
                int colr = y < 0.8D ? MOSS : (band % 2 == 0 ? HULL : HULL2);
                Draw.box(c, p, (float) -hw, (float) y, (float) z, (float) hw, (float) y1, (float) (z + step), col(colr), lit);
            }
            // glowing seams along the planks, on both sides
            float sy = 1.05F;
            float sw = (float) (w * 0.95D) + 0.012F;
            Draw.box(c, p, -sw - 0.01F, sy, (float) z, -sw + 0.02F, sy + 0.05F, (float) (z + step), col(SEAM), FULL);
            Draw.box(c, p, sw - 0.02F, sy, (float) z, sw + 0.01F, sy + 0.05F, (float) (z + step), col(SEAM), FULL);
        }
        // the deck
        Draw.box(c, p, -2.15F, (float) GhostShipEntity.DECK - 0.1F, -6.9F, 2.15F, (float) GhostShipEntity.DECK, 4.0F, col(DECKW), lit);
        // stern deck (raised) with its lit windows, and the forecastle
        Draw.box(c, p, -2.05F, (float) GhostShipEntity.DECK, -7.0F, 2.05F, (float) GhostShipEntity.QUARTER, -4.0F, col(HULL2), lit);
        Draw.box(c, p, -2.15F, (float) GhostShipEntity.QUARTER - 0.08F, -7.1F, 2.15F, (float) GhostShipEntity.QUARTER, -3.95F, col(DECKW), lit);
        for (int w = -1; w <= 1; w++) {
            Draw.box(c, p, w * 0.9F - 0.3F, 2.45F, -7.08F, w * 0.9F + 0.3F, 2.95F, -7.02F, col(WINDOW), FULL);
        }
        Draw.box(c, p, -1.4F, (float) GhostShipEntity.DECK, 4.6F, 1.4F, 2.8F, 6.2F, col(HULL2), lit);
        // gold trim along the top of the hull
        Draw.box(c, p, -2.2F, 2.1F, -7.05F, -2.14F, 2.22F, 4.2F, col(GOLD), lit);
        Draw.box(c, p, 2.14F, 2.1F, -7.05F, 2.2F, 2.22F, 4.2F, col(GOLD), lit);
        // rails: posts and a top rail on both sides, round the stern deck too
        for (double z = -3.8D; z <= 4.2D; z += 1.0D) {
            double w = halfWidth(z) - 0.08D;
            for (int sd = -1; sd <= 1; sd += 2) {
                float x = (float) (sd * w);
                Draw.box(c, p, x - 0.05F, (float) GhostShipEntity.DECK, (float) z - 0.05F, x + 0.05F, (float) GhostShipEntity.DECK + 0.6F, (float) z + 0.05F, col(RAILC), lit);
            }
        }
        for (int sd = -1; sd <= 1; sd += 2) {
            Draw.box(c, p, sd * 2.1F - 0.06F, (float) GhostShipEntity.DECK + 0.55F, -3.9F, sd * 2.1F + 0.06F, (float) GhostShipEntity.DECK + 0.65F, 4.3F, col(RAILC), lit);
            Draw.box(c, p, sd * 2.0F - 0.06F, (float) GhostShipEntity.QUARTER + 0.5F, -7.0F, sd * 2.0F + 0.06F, (float) GhostShipEntity.QUARTER + 0.6F, -4.0F, col(RAILC), lit);
        }
        Draw.box(c, p, -2.0F, (float) GhostShipEntity.QUARTER + 0.5F, -7.06F, 2.0F, (float) GhostShipEntity.QUARTER + 0.6F, -6.94F, col(RAILC), lit);
        // gun ports and cannons on both sides
        for (int k = 0; k < 5; k++) {
            float z = -4.5F + k * 2.2F;
            for (int sd = -1; sd <= 1; sd += 2) {
                float x = (float) (sd * halfWidth(z) * 0.98D);
                Draw.box(c, p, x - 0.04F, 1.15F, z - 0.25F, x + 0.04F, 1.6F, z + 0.25F, col(0x0A0A0A), lit);
                RocketRideRenderer.beam(c, pose, x, 1.37F, z, x + sd * 0.55F, 1.37F, z, 0.2F, col(IRON), lit);
            }
        }
        // bowsprit and the skull figurehead with glowing eyes
        RocketRideRenderer.beam(c, pose, 0.0F, 2.3F, 6.5F, 0.0F, 4.4F, 10.6F, 0.2F, col(MAST), lit);
        Draw.box(c, p, -0.32F, 1.55F, 7.1F, 0.32F, 2.15F, 7.7F, col(BONE), lit);
        Draw.box(c, p, -0.22F, 1.4F, 7.25F, 0.22F, 1.56F, 7.6F, col(BONE), lit);
        Draw.box(c, p, -0.2F, 1.85F, 7.69F, -0.06F, 1.98F, 7.72F, col(SEAM), FULL);
        Draw.box(c, p, 0.06F, 1.85F, 7.69F, 0.2F, 1.98F, 7.72F, col(SEAM), FULL);
        // masts with their yards, crow's nest on the main mast, shrouds down to the rails
        for (double[] m : MASTS) {
            float z = (float) m[0], top = (float) m[1];
            float foot = z < -4.0F ? (float) GhostShipEntity.QUARTER : (float) GhostShipEntity.DECK;
            Draw.box(c, p, -0.14F, foot, z - 0.14F, 0.14F, top, z + 0.14F, col(MAST), lit);
            for (float yy : new float[]{top * 0.42F + 1.2F, top * 0.8F}) {
                float half = yy < top * 0.6F ? 2.0F : 1.5F;
                Draw.box(c, p, -half, yy - 0.07F, z - 0.07F, half, yy + 0.07F, z + 0.07F, col(MAST), lit);
            }
            for (int sd = -1; sd <= 1; sd += 2) {
                RocketRideRenderer.beam(c, pose, 0.0F, top - 0.3F, z, sd * 2.05F, (float) GhostShipEntity.DECK + 0.6F, z - 0.6F, 0.03F, col(ROPE), lit);
                RocketRideRenderer.beam(c, pose, 0.0F, top - 0.3F, z, sd * 2.05F, (float) GhostShipEntity.DECK + 0.6F, z + 0.6F, 0.03F, col(ROPE), lit);
            }
        }
        Draw.box(c, p, -0.45F, 9.3F, -0.45F, 0.45F, 9.7F, 0.45F, col(HULL2), lit);
        // stays between the mast tops and to the bowsprit
        RocketRideRenderer.beam(c, pose, 0.0F, 11.2F, 0.0F, 0.0F, 8.8F, 3.6F, 0.03F, col(ROPE), lit);
        RocketRideRenderer.beam(c, pose, 0.0F, 8.8F, 3.6F, 0.0F, 4.4F, 10.5F, 0.03F, col(ROPE), lit);
        RocketRideRenderer.beam(c, pose, 0.0F, 11.2F, 0.0F, 0.0F, 7.8F, -4.6F, 0.03F, col(ROPE), lit);
        // lanterns: iron cages with ghost fire
        for (double[] l : LANTERNS) {
            float x = (float) l[0], y = (float) l[1], z = (float) l[2];
            Draw.box(c, p, x - 0.13F, y - 0.18F, z - 0.13F, x + 0.13F, y + 0.18F, z + 0.13F, col(IRON), lit);
            Draw.box(c, p, x - 0.09F, y - 0.13F, z - 0.09F, x + 0.09F, y + 0.13F, z + 0.09F, col(0x6AFFD0), FULL);
        }
        // the torn black flag with a pale skull, fluttering at the main mast top
        for (int i = 0; i < 6; i++) {
            float z0 = -0.15F - i * 0.25F, z1 = z0 - 0.25F;
            float wave = 0.12F * Mth.sin(time * 0.35F + i * 0.9F);
            float y0 = 10.5F - (i == 5 ? 0.25F : 0.0F);
            Draw.box(c, p, -0.02F + wave, y0, z1, 0.02F + wave, 11.3F, z0, col(HAT), lit);
        }
        Draw.box(c, p, -0.04F + 0.12F * Mth.sin(time * 0.35F + 1.8F), 10.75F, -0.95F, 0.04F + 0.12F * Mth.sin(time * 0.35F + 1.8F), 11.05F, -0.65F, col(BONE), lit);
    }

    /** Torn grey sails between the yards: a grid of patches with holes and a ragged lower edge, bellying in the wind. */
    private void drawSails(VertexConsumer c, PoseStack pose, float time) {
        PoseStack.Pose p = pose.last();
        for (int mi = 0; mi < MASTS.length; mi++) {
            double z = MASTS[mi][0] + 0.12D, top = MASTS[mi][1];
            double yLow = top * 0.42D + 1.2D, yHigh = top * 0.8D;
            int nx = 8, ny = 6;
            for (int ix = 0; ix < nx; ix++) {
                for (int iy = 0; iy < ny; iy++) {
                    int h = (mi * 131 + ix * 17 + iy * 29) % 11;
                    if (h == 3 || h == 7 || (iy == 0 && h % 2 == 0)) continue;   // holes and a ragged bottom
                    double halfTop = 1.5D, halfBot = 1.9D;
                    double v0 = iy / (double) ny, v1 = (iy + 1) / (double) ny;
                    double y0 = Mth.lerp(v0, yLow, yHigh), y1 = Mth.lerp(v1, yLow, yHigh);
                    double hw0 = Mth.lerp(v0, halfBot, halfTop), hw1 = Mth.lerp(v1, halfBot, halfTop);
                    double u0 = ix / (double) nx, u1 = (ix + 1) / (double) nx;
                    float a = this.alpha * (0.55F + 0.1F * (h % 3));
                    Vec3 q00 = sailPoint(u0, v0, hw0, y0, z, time), q10 = sailPoint(u1, v0, hw0, y0, z, time);
                    Vec3 q11 = sailPoint(u1, v1, hw1, y1, z, time), q01 = sailPoint(u0, v1, hw1, y1, z, time);
                    quad2(c, p, q00, q10, q11, q01, SAIL, a);
                }
            }
        }
    }

    private static Vec3 sailPoint(double u, double v, double hw, double y, double z, float time) {
        double x = (u - 0.5D) * 2.0D * hw;
        double belly = Math.sin(u * Math.PI) * Math.sin(v * Math.PI) * 0.55D + 0.06D * Math.sin(time * 0.3D + u * 6.0D + v * 4.0D);
        return new Vec3(x, y, z + belly);
    }

    private static void quad2(VertexConsumer c, PoseStack.Pose p, Vec3 a, Vec3 b, Vec3 cc, Vec3 d, int rgb, float alpha) {
        int argb = ((int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | rgb;
        Vec3[] q = {a, b, cc, d, d, cc, b, a};
        for (Vec3 v : q) {
            c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(argb).setUv(0.5F, 0.5F)
                    .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xA000A0).setNormal(p, 0.0F, 1.0F, 0.0F);
        }
    }

    // ------------------------------------------------------------------ the crew

    private void drawCrew(GhostShipEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t, int lit, float time) {
        Vec3 a = e.anchor();
        Vec3 prisoner = t >= GhostShipEntity.HIT ? e.prisoner(t) : e.running(t);
        boolean down = GhostShipEntity.knockedDown(t);
        for (int i = 0; i < GhostShipEntity.CREW; i++) {
            Vec3 feet = e.sailor(i, t);
            // they look at the target while they go for it and while they guard it, otherwise out over the side
            Vec3 look;
            if (t >= GhostShipEntity.BOARD0 && t < GhostShipEntity.HARPOON + 18) look = a.subtract(feet);
            else if (t >= GhostShipEntity.REEL_END && t < GhostShipEntity.KICK + 10) look = prisoner.subtract(feet);
            else look = e.side(t).scale(-1.0D);
            boolean fighting = t > GhostShipEntity.SURROUND && t < GhostShipEntity.BREAK;
            boolean rope = GhostShipEntity.onRope(i, t);
            figure(c, pose, feet.subtract(base), look, lit, time + i * 7.0F, false, i % 2 == 0, fighting, rope, down ? 1.0F : 0.0F, 0.0F, false);
        }
        // the captain: his harpoon arm swings up at the running target and fires; at the bow he gives the kick
        Vec3 cap = e.captain(t);
        boolean aiming = t > GhostShipEntity.HARPOON - 14 && t < GhostShipEntity.HIT + 6;
        Vec3 look;
        if (aiming) look = e.running(Math.min(t, GhostShipEntity.HIT)).subtract(cap);
        else if (t >= GhostShipEntity.WALK0) look = e.fwd(t);
        else if (t >= GhostShipEntity.HIT && t < GhostShipEntity.REEL_END) look = prisoner.subtract(cap);
        else look = e.side(t).scale(-1.0D);
        float kick = 0.0F;
        if (t > GhostShipEntity.KICK - 6 && t < GhostShipEntity.KICK + 8) {
            double u = (t - GhostShipEntity.KICK + 6) / 14.0D;
            kick = (float) Math.sin(Math.PI * u);
        }
        figure(c, pose, cap.subtract(base), look, lit, time, true, true, t > GhostShipEntity.SURROUND && t < GhostShipEntity.BREAK, false, 0.0F, kick, aiming);
    }

    /**
     * A ghostly skeleton sailor (or the captain): bony legs and hands, a ragged shirt or the captain's long coat, a
     * skull with green burning eyes, a bandana or a tricorn; a sabre in the right hand and a pistol in the left - the
     * captain's left arm is a harpoon gun instead. down: lying on its back (0..1); kick: the right leg kicks out.
     */
    private void figure(VertexConsumer c, PoseStack pose, Vec3 rel, Vec3 look, int lit, float time, boolean captain, boolean sabre,
                        boolean fighting, boolean rope, float down, float kick, boolean aiming) {
        pose.pushPose();
        pose.translate(rel.x, rel.y, rel.z);
        double yaw = Math.atan2(look.x, look.z);
        pose.mulPose(Axis.YP.rotation((float) yaw));
        if (down > 0.0F) {
            // thrown on its back
            pose.translate(0.0F, 0.2F, 0.0F);
            pose.mulPose(Axis.XP.rotation(-1.45F * down));
        }
        float s = captain ? 1.12F : 1.0F;
        pose.scale(s, s, s);
        PoseStack.Pose p = pose.last();
        float swing = rope || down > 0.0F ? 0.0F : 0.25F * Mth.sin(time * 0.25F);
        // legs
        legOrArm(c, pose, -0.11F, 0.78F, 0.0F, swing, 0.14F, 0.78F, col(captain ? COAT : CLOTH2), lit);
        legOrArm(c, pose, 0.11F, 0.78F, 0.0F, -swing - 1.5F * kick, 0.14F, 0.78F, col(captain ? COAT : CLOTH2), lit);
        // body: shirt, or the captain's coat down to the knees
        Draw.box(c, p, -0.24F, 0.78F, -0.13F, 0.24F, 1.45F, 0.13F, col(captain ? COAT : CLOTH), lit);
        if (captain) {
            Draw.box(c, p, -0.27F, 0.35F, -0.15F, 0.27F, 0.85F, 0.15F, col(COAT), lit);
            Draw.box(c, p, -0.05F, 0.8F, 0.13F, 0.05F, 1.42F, 0.15F, col(GOLD), lit);
        }
        Draw.box(c, p, -0.25F, 0.95F, -0.14F, 0.25F, 1.02F, 0.14F, col(ROPE), lit);   // belt
        // arms: on a rope both hands up; fighting: the sabre arm hacks
        float hack = fighting ? -1.2F + 0.9F * Mth.sin(time * 0.6F) : -0.25F;
        float right = rope ? -2.9F : hack, left = rope ? -2.9F : (fighting ? -1.4F : -0.15F);
        if (down > 0.0F) right = left = -2.6F;
        if (captain) left = aiming ? -1.57F : -0.35F;
        legOrArm(c, pose, -0.31F, 1.42F, 0.0F, right, 0.12F, 0.62F, col(BONE), lit);
        if (!captain) legOrArm(c, pose, 0.31F, 1.42F, 0.0F, left, 0.12F, 0.62F, col(BONE), lit);
        if (!rope) {
            // sabre in the right hand
            pose.pushPose();
            pose.translate(-0.31F, 1.42F, 0.0F);
            pose.mulPose(Axis.XP.rotation(right));
            Draw.box(c, pose.last(), -0.03F, -0.68F, -0.03F, 0.03F, -0.58F, 0.12F, col(GOLD), lit);
            if (sabre) Draw.box(c, pose.last(), -0.015F, -0.66F, 0.1F, 0.015F, -0.6F, 0.85F, col(0x9AA6A0), lit);
            pose.popPose();
            pose.pushPose();
            pose.translate(0.31F, 1.42F, 0.0F);
            pose.mulPose(Axis.XP.rotation(left));
            if (captain) {
                // the harpoon gun in place of his left forearm: a sleeve, an iron barrel, the barbed head
                Draw.box(c, pose.last(), -0.08F, -0.45F, -0.08F, 0.08F, 0.0F, 0.08F, col(COAT), lit);
                Draw.box(c, pose.last(), -0.07F, -0.95F, -0.07F, 0.07F, -0.42F, 0.07F, col(IRON), lit);
                Draw.box(c, pose.last(), -0.09F, -0.62F, -0.09F, 0.09F, -0.55F, 0.09F, col(GOLD), lit);
                Draw.box(c, pose.last(), -0.02F, -1.15F, -0.02F, 0.02F, -0.95F, 0.02F, col(0x9AA6A0), lit);
            } else {
                Draw.box(c, pose.last(), -0.04F, -0.7F, -0.04F, 0.04F, -0.58F, 0.3F, col(IRON), lit);
            }
            pose.popPose();
        }
        // the skull with burning eyes
        Draw.box(c, p, -0.17F, 1.47F, -0.16F, 0.17F, 1.8F, 0.16F, col(BONE), lit);
        Draw.box(c, p, -0.12F, 1.42F, -0.1F, 0.12F, 1.5F, 0.14F, col(0xA9B19E), lit);
        Draw.box(c, p, -0.12F, 1.6F, 0.16F, -0.03F, 1.68F, 0.18F, col(SEAM), FULL);
        Draw.box(c, p, 0.03F, 1.6F, 0.16F, 0.12F, 1.68F, 0.18F, col(SEAM), FULL);
        if (captain) {
            // the tricorn
            Draw.box(c, p, -0.32F, 1.78F, -0.26F, 0.32F, 1.84F, 0.26F, col(HAT), lit);
            Draw.box(c, p, -0.2F, 1.84F, -0.17F, 0.2F, 2.02F, 0.17F, col(HAT), lit);
            Draw.box(c, p, -0.33F, 1.84F, -0.04F, -0.26F, 1.95F, 0.04F, col(HAT), lit);
            Draw.box(c, p, 0.26F, 1.84F, -0.04F, 0.33F, 1.95F, 0.04F, col(HAT), lit);
            Draw.box(c, p, -0.03F, 1.84F, -0.28F, 0.03F, 1.95F, -0.22F, col(HAT), lit);
            Draw.box(c, p, 0.12F, 1.95F, -0.05F, 0.16F, 2.25F, 0.0F, col(0xB8B8A8), lit);   // a grey feather
        } else {
            Draw.box(c, p, -0.18F, 1.72F, -0.17F, 0.18F, 1.82F, 0.17F, col(BANDANA), lit);
            Draw.box(c, p, -0.05F, 1.6F, -0.24F, 0.05F, 1.76F, -0.16F, col(BANDANA), lit);
        }
        pose.popPose();
    }

    /** An arm or a leg hanging from (x, y, z), turned forward by rot about X. */
    private static void legOrArm(VertexConsumer c, PoseStack pose, float x, float y, float z, float rot, float w, float len, int argb, int lit) {
        pose.pushPose();
        pose.translate(x, y, z);
        pose.mulPose(Axis.XP.rotation(rot));
        Draw.box(c, pose.last(), -w * 0.5F, -len, -w * 0.5F, w * 0.5F, 0.0F, w * 0.5F, argb, lit);
        pose.popPose();
    }

    // ------------------------------------------------------------------ ropes, the hook, bonds, cannonballs, sharks, water, the storm

    private void drawRopes(GhostShipEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t, int lit) {
        int rope = col(ROPE);
        for (int i = 0; i < GhostShipEntity.CREW; i++) {
            if (!GhostShipEntity.onRope(i, t)) continue;
            Vec3 rail = e.toWorld(new Vec3(-GhostShipEntity.RAIL, GhostShipEntity.DECK + 0.6D, GhostShipEntity.home(i).z), t);
            Vec3 hand = e.sailor(i, t).add(0.0D, 2.0D, 0.0D);
            line(c, pose, rail.subtract(base), hand.subtract(base), 0.05F, rope, lit);
        }
        // the harpoon: the barbed hook flying out on its chain, then the chain reeling the prisoner in
        if (t > GhostShipEntity.HARPOON && t < GhostShipEntity.REEL_END) {
            Vec3 muzzle = e.harpoonMuzzle(t);
            Vec3 hook = t < GhostShipEntity.HIT ? e.hook(t) : body(e, t);
            chain(c, pose, muzzle.subtract(base), hook.subtract(base), lit);
            Vec3 d = hook.subtract(muzzle);
            Vec3 dir = d.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, -1.0D, 0.0D) : d.normalize();
            Vec3 h = hook.subtract(base);
            Vec3 sideV = dir.cross(new Vec3(0.0D, 1.0D, 0.0D));
            sideV = sideV.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : sideV.normalize();
            line(c, pose, h.subtract(dir.scale(0.3D)), h.add(dir.scale(0.15D)), 0.07F, col(IRON), lit);
            line(c, pose, h.add(dir.scale(0.15D)), h.subtract(dir.scale(0.1D)).add(sideV.scale(0.22D)), 0.05F, col(0x9AA6A0), lit);
            line(c, pose, h.add(dir.scale(0.15D)), h.subtract(dir.scale(0.1D)).subtract(sideV.scale(0.22D)), 0.05F, col(0x9AA6A0), lit);
        }
        // bound to the main mast: coils round the mast and the body
        if (t >= GhostShipEntity.REEL_END - 4 && t < GhostShipEntity.WALK0) {
            float grow = (float) Math.min(1.0D, (t - GhostShipEntity.REEL_END + 4) / 14.0D);
            Vec3 b = body(e, t);
            for (double dy : new double[]{0.45D, 0.05D, -0.55D}) coil(c, pose, b.add(0.0D, dy, 0.0D).subtract(base), 0.32F * grow + 0.05F, rope, lit);
            for (double y : new double[]{0.6D, 1.1D, 1.6D}) coil(c, pose, e.toWorld(new Vec3(0.0D, GhostShipEntity.DECK + y, 0.0D), t).subtract(base), 0.2F * grow + 0.05F, rope, lit);
        }
        // hands still tied while it is driven out to the bow
        if (t >= GhostShipEntity.WALK0 && t < GhostShipEntity.KICK) coil(c, pose, body(e, t).add(0.0D, 0.25D, 0.0D).subtract(base), 0.3F, rope, lit);
    }

    /** The prisoner's body as drawn (the ragdoll's torso), or where it is held. */
    private static Vec3 body(GhostShipEntity e, double t) {
        Vec3 held = e.prisoner(t);
        Vec3 drawn = RagdollView.stableTorso();
        return drawn != null && drawn.distanceTo(held) < 3.0D ? drawn : held;
    }

    /** An iron chain from a to b: dark links along a slight sag. */
    private void chain(VertexConsumer c, PoseStack pose, Vec3 a, Vec3 b, int lit) {
        double len = a.distanceTo(b);
        int n = Math.max(2, (int) (len / 0.35D));
        Vec3 prev = a;
        for (int i = 1; i <= n; i++) {
            double f = i / (double) n;
            Vec3 q = a.lerp(b, f).subtract(0.0D, Math.sin(Math.PI * f) * Math.min(1.2D, len * 0.06D), 0.0D);
            line(c, pose, prev, q, i % 2 == 0 ? 0.07F : 0.05F, col(i % 2 == 0 ? IRON : 0x3A3A40), lit);
            prev = q;
        }
    }

    private static void line(VertexConsumer c, PoseStack pose, Vec3 a, Vec3 b, float w, int argb, int lit) {
        RocketRideRenderer.beam(c, pose, (float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z, w, argb, lit);
    }

    private static void coil(VertexConsumer c, PoseStack pose, Vec3 centre, float r, int argb, int lit) {
        int n = 10;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2.0D / n, a1 = (i + 1) * Math.PI * 2.0D / n;
            Vec3 p0 = centre.add(Math.cos(a0) * r, 0.0D, Math.sin(a0) * r), p1 = centre.add(Math.cos(a1) * r, 0.0D, Math.sin(a1) * r);
            line(c, pose, p0, p1, 0.06F, argb, lit);
        }
    }

    private static Vec3 impact(GhostShipEntity e, int k) {
        Vec3 p = e.impactXZ(k);
        double g = groundY(e, p);
        return new Vec3(p.x, g, p.z);
    }

    private static double groundY(GhostShipEntity e, Vec3 p) {
        net.minecraft.core.BlockPos.MutableBlockPos bp = new net.minecraft.core.BlockPos.MutableBlockPos();
        double from = e.anchor().y + 4.0D;
        for (int by = Mth.floor(from); by >= Mth.floor(from) - 16; by--) {
            bp.set(Mth.floor(p.x), by, Mth.floor(p.z));
            var s = e.level().getBlockState(bp).getCollisionShape(e.level(), bp);
            if (!s.isEmpty()) return by + s.max(net.minecraft.core.Direction.Axis.Y);
        }
        return e.anchor().y;
    }

    /** Cannonballs in flight: from the gun port in an arc to their spot round the target. */
    private void drawBalls(GhostShipEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t, int lit) {
        for (int k = 0; k < GhostShipEntity.CANNONS; k++) {
            double u = (t - (GhostShipEntity.CANNON0 + k * GhostShipEntity.CANNON_STEP)) / GhostShipEntity.BALL_FLIGHT;
            if (u < 0.0D || u > 1.0D) continue;
            Vec3 from = e.gunport(k, t), to = impact(e, k);
            Vec3 p = from.lerp(to, u).add(0.0D, Math.sin(Math.PI * u) * 2.5D, 0.0D).subtract(base);
            Draw.cube(c, pose.last(), (float) p.x, (float) p.y, (float) p.z, 0.16F, col(IRON), lit);
        }
    }

    /**
     * The sharks: grey-blue backs and pale bellies, a tall dorsal fin, pectoral fins and a tail beating side to
     * side; they circle just under the surface with their fins out of the water. One of them leaps out with its
     * jaws wide open, takes the prisoner and dives back in.
     */
    private void drawSharks(GhostShipEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t, int lit, float time) {
        float w = GhostShipEntity.water(t);
        if (w <= 0.02F) return;
        int catchT = e.catchTick();
        boolean leaping = t > catchT - GhostShipEntity.LEAP - 6 && t < catchT + GhostShipEntity.DIVE + 8;
        for (int k = 0; k < GhostShipEntity.SHARKS; k++) {
            if (k == 0 && leaping) continue;
            Vec3 p = e.shark(k, t);
            shark(c, pose, p.subtract(base), e.sharkDir(k, t), w, time + k * 5.0F, lit, 0.0F);
        }
        if (t > catchT - GhostShipEntity.LEAP && t < catchT + GhostShipEntity.DIVE) {
            Vec3 mouth = e.sharkMouth(t);
            Vec3 d = e.sharkMouth(t + 0.5D).subtract(e.sharkMouth(t - 0.5D));
            Vec3 dir = d.lengthSqr() < 1.0E-6D ? e.fwd(GhostShipEntity.KICK) : d.normalize();
            // the jaws are at the front: the body trails behind them
            Vec3 centre = mouth.subtract(dir.scale(1.3D));
            float open = (float) Mth.clamp(1.0D - Math.abs(t - catchT) / 8.0D, 0.0D, 1.0D);
            shark(c, pose, centre.subtract(base), dir, 1.0F, time, lit, Math.max(open, t < catchT ? 1.0F : 0.4F));
        }
    }

    /** One shark, centred at rel, swimming along dir (any direction: it can point up out of the water). */
    private void shark(VertexConsumer c, PoseStack pose, Vec3 rel, Vec3 dir, float scale, float time, int lit, float jaws) {
        pose.pushPose();
        pose.translate(rel.x, rel.y, rel.z);
        pose.mulPose(new Quaternionf().rotationTo(0.0F, 0.0F, 1.0F, (float) dir.x, (float) dir.y, (float) dir.z));
        pose.scale(scale * 1.25F, scale * 1.25F, scale * 1.25F);
        PoseStack.Pose p = pose.last();
        int back = 0xFF3A4652, belly = 0xFFB8BEC2, fin = 0xFF2E3842;
        float wag = 0.35F * Mth.sin(time * 0.5F);
        // body: thick in the middle, tapering to the snout and the tail
        Draw.box(c, p, -0.32F, -0.28F, -0.6F, 0.32F, 0.3F, 0.6F, back, lit);
        Draw.box(c, p, -0.28F, -0.34F, -0.55F, 0.28F, -0.28F, 0.55F, belly, lit);
        Draw.box(c, p, -0.24F, -0.22F, 0.6F, 0.24F, 0.2F, 1.05F, back, lit);
        Draw.box(c, p, -0.2F, -0.28F, 0.6F, 0.2F, -0.22F, 1.0F, belly, lit);
        Draw.box(c, p, -0.14F, -0.12F, 1.05F, 0.14F, 0.12F, 1.35F, back, lit);
        // the jaws: the lower one drops open, rows of white teeth
        if (jaws > 0.0F) {
            pose.pushPose();
            pose.translate(0.0F, -0.22F, 0.65F);
            pose.mulPose(Axis.XP.rotation(0.7F * jaws));
            Draw.box(c, pose.last(), -0.2F, -0.08F, 0.0F, 0.2F, 0.0F, 0.6F, belly, lit);
            for (int i = 0; i < 4; i++) Draw.box(c, pose.last(), -0.17F + i * 0.1F, 0.0F, 0.45F, -0.12F + i * 0.1F, 0.06F, 0.55F, 0xFFF4F2EA, lit);
            pose.popPose();
            for (int i = 0; i < 4; i++) Draw.box(c, p, -0.17F + i * 0.1F, -0.24F, 1.1F, -0.12F + i * 0.1F, -0.17F, 1.2F, 0xFFF4F2EA, lit);
        }
        // eyes
        Draw.box(c, p, -0.15F, 0.0F, 1.0F, -0.1F, 0.06F, 1.06F, 0xFF0A0A0A, lit);
        Draw.box(c, p, 0.1F, 0.0F, 1.0F, 0.15F, 0.06F, 1.06F, 0xFF0A0A0A, lit);
        // dorsal fin (out of the water), pectoral fins
        RocketRideRenderer.beam(c, pose, 0.0F, 0.28F, 0.25F, 0.0F, 0.95F, -0.15F, 0.08F, fin, lit);
        RocketRideRenderer.beam(c, pose, 0.0F, 0.28F, 0.0F, 0.0F, 0.75F, -0.3F, 0.06F, fin, lit);
        RocketRideRenderer.beam(c, pose, -0.3F, -0.2F, 0.5F, -0.8F, -0.45F, 0.15F, 0.06F, fin, lit);
        RocketRideRenderer.beam(c, pose, 0.3F, -0.2F, 0.5F, 0.8F, -0.45F, 0.15F, 0.06F, fin, lit);
        // the tail, beating
        pose.pushPose();
        pose.translate(0.0F, 0.0F, -0.6F);
        pose.mulPose(Axis.YP.rotation(wag));
        Draw.box(c, pose.last(), -0.2F, -0.18F, -0.55F, 0.2F, 0.18F, 0.0F, back, lit);
        Draw.box(c, pose.last(), -0.1F, -0.1F, -0.9F, 0.1F, 0.1F, -0.55F, back, lit);
        RocketRideRenderer.beam(c, pose, 0.0F, 0.0F, -0.85F, 0.0F, 0.6F, -1.2F, 0.07F, fin, lit);
        RocketRideRenderer.beam(c, pose, 0.0F, 0.0F, -0.85F, 0.0F, -0.45F, -1.15F, 0.07F, fin, lit);
        pose.popPose();
        pose.popPose();
    }

    /**
     * The shark pool: dark green-blue water spreading out over the ground, rings of ripples running across it and a
     * pale band of foam at its edge; it drains away again at the end.
     */
    private void drawWater(GhostShipEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t, float time) {
        float w = GhostShipEntity.water(t);
        if (w <= 0.01F) return;
        Vec3 a = e.anchor();
        Vec3 o = new Vec3(a.x, e.waterY(), a.z).subtract(base);
        double r = GhostShipEntity.WATER_R * (0.15D + 0.85D * w);
        PoseStack.Pose p = pose.last();
        int segs = 48;
        for (int i = 0; i < segs; i++) {
            double a0 = i * Math.PI * 2.0D / segs, a1 = (i + 1) * Math.PI * 2.0D / segs;
            double wob0 = 0.06D * Math.sin(a0 * 5.0D + time * 0.07D), wob1 = 0.06D * Math.sin(a1 * 5.0D + time * 0.07D);
            Vec3 q0 = o.add(Math.cos(a0) * r * (1.0D + wob0), 0.0D, Math.sin(a0) * r * (1.0D + wob0));
            Vec3 q1 = o.add(Math.cos(a1) * r * (1.0D + wob1), 0.0D, Math.sin(a1) * r * (1.0D + wob1));
            quad2(c, p, o, q0, q1, o, 0x0E3A44, 0.78F * w);
            // foam at the edge
            Vec3 f0 = o.add(Math.cos(a0) * r * (1.0D + wob0) * 0.94D, 0.02D, Math.sin(a0) * r * (1.0D + wob0) * 0.94D);
            Vec3 f1 = o.add(Math.cos(a1) * r * (1.0D + wob1) * 0.94D, 0.02D, Math.sin(a1) * r * (1.0D + wob1) * 0.94D);
            quad2(c, p, f0, q0.add(0.0D, 0.02D, 0.0D), q1.add(0.0D, 0.02D, 0.0D), f1, 0xCFE6E8, 0.55F * w);
        }
        // ripples running outwards
        for (int k = 0; k < 4; k++) {
            double rr = ((time * 0.06D + k * 0.25D) % 1.0D) * r * 0.9D;
            float ra = (float) (0.35D * (1.0D - rr / r)) * w;
            for (int i = 0; i < segs; i++) {
                double a0 = i * Math.PI * 2.0D / segs, a1 = (i + 1) * Math.PI * 2.0D / segs;
                Vec3 i0 = o.add(Math.cos(a0) * rr, 0.03D, Math.sin(a0) * rr), i1 = o.add(Math.cos(a1) * rr, 0.03D, Math.sin(a1) * rr);
                Vec3 o0 = o.add(Math.cos(a0) * (rr + 0.12D), 0.03D, Math.sin(a0) * (rr + 0.12D)), o1 = o.add(Math.cos(a1) * (rr + 0.12D), 0.03D, Math.sin(a1) * (rr + 0.12D));
                quad2(c, p, i0, o0, o1, i1, 0x6FA8B0, ra);
            }
        }
    }

    /**
     * The storm cloud over the place: dark ragged layers turning slowly, thickening as the ship comes and thinning
     * when it goes; lit from inside when lightning strikes.
     */
    private void drawStorm(GhostShipEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t) {
        double grow = Math.min(1.0D, t / 30.0D), fade = t > GhostShipEntity.VANISH0 ? 1.0D - (t - GhostShipEntity.VANISH0) / (GhostShipEntity.VANISH_END - GhostShipEntity.VANISH0) : 1.0D;
        float a = (float) (grow * Math.max(0.0D, fade));
        if (a <= 0.01F) return;
        boolean flash = (t > 6 && t < 9) || (t > 26 && t < 29) || (t > 58 && t < 61);
        Vec3 centre = e.anchor().add(0.0D, 19.0D, 0.0D).subtract(base);
        PoseStack.Pose p = pose.last();
        for (int layer = 0; layer < 3; layer++) {
            double y = centre.y - layer * 1.1D, rot = t * 0.004D * (layer % 2 == 0 ? 1 : -1) + layer;
            int n = 28;
            int rgb = flash ? 0x7A8A90 : (layer == 0 ? 0x1E2226 : layer == 1 ? 0x2A3034 : 0x353C40);
            for (int i = 0; i < n; i++) {
                double a0 = i * Math.PI * 2.0D / n + rot, a1 = (i + 1) * Math.PI * 2.0D / n + rot;
                double r0 = (16.0D - layer * 3.0D) * (0.8D + 0.2D * Math.sin(i * 2.3D + layer)), r1 = (16.0D - layer * 3.0D) * (0.8D + 0.2D * Math.sin((i + 1) * 2.3D + layer));
                Vec3 o = new Vec3(centre.x, y, centre.z);
                Vec3 q0 = o.add(Math.cos(a0) * r0, 0.0D, Math.sin(a0) * r0), q1 = o.add(Math.cos(a1) * r1, 0.0D, Math.sin(a1) * r1);
                quad2(c, p, o, q0, q1, o, rgb, a * (0.55F - layer * 0.1F));
            }
        }
    }
}
