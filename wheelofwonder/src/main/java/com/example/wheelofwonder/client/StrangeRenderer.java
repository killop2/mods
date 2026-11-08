package com.example.wheelofwonder.client;

import com.example.wheelofwonder.StrangeEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;

/**
 * Strange's act. The sorcerer: blue tunic, belts and sash, the golden amulet with its green eye, the high collar and
 * the long red cloak flying behind him, dark hair greying at the temples, goatee; he floats. His portals: rings of
 * spinning sparks with a pale snowy world showing through. Mystic circles (rings, a seven-pointed star, runes) on
 * his hands and on the ground under the target. The spikes: black stone glowing hotter towards their points; they
 * fly to pieces at the blow. The hammer: a huge dark-steel head with gold bands, burning runes and a green gem, a
 * handle wrapped in red leather; a trail of fire behind it, a flash and rings of light at the blow.
 * Three passes: solid, see-through (magic lines, portals, the fading sorcerer), glow.
 */
public class StrangeRenderer extends EntityRenderer<StrangeEntity> {
    private static final int TUNIC = 0x1D2F72, TUNIC2 = 0x15235A, PANEL = 0x2A43A0, PANTS = 0x162045, BOOTS = 0x3A2418;
    private static final int CLOAK = 0xB0141A, CLOAK2 = 0x7A0C10, GOLD = 0xE0B22E, BELT = 0x6B3F1F, SKIN = 0xE8B48E;
    private static final int HAIR = 0x2A2026, GREY = 0xA2A2AE, EYE = 0xE6EEF6, IRIS = 0x3A78C8, GEM = 0x4CFF6A;
    private static final int STONE = 0x241C20, STONE2 = 0x3A2622, HOT = 0xC2551A, TIP = 0xFFC060;
    private static final int STEEL = 0x3B4250, STEEL_L = 0x8E99AC, LEATHER = 0x7A1A1A, RUNE = 0xFF8A1E;
    private static final int SPARK0 = 0xFF6A00, SPARK1 = 0xFFE07A, MAGIC = 0xFFA030;
    private static final int FULL = LightTexture.FULL_BRIGHT;

    private float alpha = 1.0F;

    public StrangeRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(StrangeEntity e) {
        return WheelRenderer.WHITE;
    }

    @Override
    public boolean shouldRender(StrangeEntity e, net.minecraft.client.renderer.culling.Frustum f, double x, double y, double z) {
        return true;
    }

    private int col(int rgb) {
        return ((int) (Mth.clamp(this.alpha, 0.0F, 1.0F) * 255.0F) << 24) | (rgb & 0xFFFFFF);
    }

    @Override
    public void render(StrangeEntity e, float entityYaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        double t = e.tickCount + partial;
        float time = (float) t;
        Vec3 base = e.getPosition(partial);
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 9), LightTexture.sky(light));
        float there = StrangeEntity.strangeAlpha(t);
        Pose fig = pose(e, t);

        // ---- pass 1: solid
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
        this.alpha = 1.0F;
        drawSpikes(e, c, pose, base, t, lit);
        drawHammer(e, c, pose, base, t, lit);
        if (there >= 0.995F) figure(c, pose, fig, base, time, lit);

        // ---- pass 2: see-through
        VertexConsumer tr = buffers.getBuffer(RenderType.beaconBeam(WheelRenderer.WHITE, true));
        if (there > 0.01F && there < 0.995F) {
            this.alpha = there;
            figure(tr, pose, fig, base, time, lit);
            this.alpha = 1.0F;
        }
        double dr = StrangeEntity.doorRadius(t);
        if (dr > 0.01D) portal(tr, pose, e.doorCentre().subtract(base), e.face(), dr, time, 0.0F);
        double hr = StrangeEntity.hammerPortalRadius(t);
        if (hr > 0.01D) portal(tr, pose, e.hammerPortal().subtract(base), e.hammerDir(), hr, time, 3.7F);
        drawMagic(e, tr, pose, base, t, time, fig);
        drawTrail(e, tr, pose, base, t);
        drawRings(e, tr, pose, base, t);

        // ---- pass 3: glows
        boolean shaders = ShaderCompat.shadersOn();
        RenderType gt = shaders ? RenderType.beaconBeam(ShaderCompat.GLOW_SOFT, true) : ShaderCompat.glow(WheelRenderer.GLOW);
        VertexConsumer glow = buffers.getBuffer(gt);
        Quaternionf cam = this.entityRenderDispatcher.cameraOrientation();
        float fl = 0.85F + 0.15F * Mth.sin(time * 0.9F);
        if (dr > 0.05D) portalGlow(pose, glow, cam, e.doorCentre().subtract(base), e.face(), dr, time, fl);
        if (hr > 0.05D) portalGlow(pose, glow, cam, e.hammerPortal().subtract(base), e.hammerDir(), hr, time, fl);
        if (there > 0.05F) {
            halo(pose, glow, cam, fig.local(0.0D, 1.26D, 0.17D).subtract(base), 0.28F * fl, 0x4CFF6A, 0.8F * there);
            float cast = castLevel(t);
            if (cast > 0.01F) {
                halo(pose, glow, cam, fig.rightHand.subtract(base), 0.55F * fl, 0xFF9A30, 0.8F * cast * there);
                halo(pose, glow, cam, fig.leftHand.subtract(base), 0.55F * fl, 0xFF9A30, 0.8F * cast * there);
            }
        }
        if (t > StrangeEntity.CAST0 && t < StrangeEntity.HIT + 20) {
            float g = glyphAlpha(t);
            halo(pose, glow, cam, new Vec3(e.anchor().x, e.groundY() + 0.3D, e.anchor().z).subtract(base), 3.2F, 0xFF7A1E, 0.3F * g);
        }
        if (t < StrangeEntity.HIT) {
            for (int k = 0; k < StrangeEntity.SPIKES; k++) {
                if (StrangeEntity.spikeGrow(k, t) <= 0.0D) continue;
                Vec3[] s = e.spikeNow(k, t);
                halo(pose, glow, cam, s[1].subtract(base), 0.45F * fl, 0xFFA040, 0.75F);
                halo(pose, glow, cam, e.spikeBase(k).add(0.0D, 0.5D, 0.0D).subtract(base), 0.9F, 0xFF6A10, 0.35F);
            }
        }
        float hs = StrangeEntity.hammerScale(t);
        if (hs > 0.01F) halo(pose, glow, cam, e.hammerCenter(t).subtract(base), 1.6F * hs * fl, 0xFF8A1E, 0.55F);
        double since = t - StrangeEntity.HIT;
        if (since >= 0.0D && since < 10.0D) {
            float f = (float) (1.0D - since / 10.0D);
            Vec3 at = e.impale().subtract(base);
            halo(pose, glow, cam, at, 7.0F * (0.4F + 0.6F * f), 0xFFE0A0, 0.95F * f);
            halo(pose, glow, cam, at, 3.0F, 0xFFFFFF, 0.9F * f * f);
        }
        super.render(e, entityYaw, partial, pose, buffers, light);
    }

    private static void halo(PoseStack pose, VertexConsumer glow, Quaternionf cam, Vec3 rel, float size, int rgb, float strength) {
        if (strength <= 0.01F || size <= 0.01F) return;
        pose.pushPose();
        pose.translate(rel.x, rel.y, rel.z);
        pose.mulPose(cam);
        Draw.halo(glow, pose.last(), size, Draw.shade(0xFF000000 | rgb, Math.min(1.0F, strength)));
        pose.popPose();
    }

    // ------------------------------------------------------------------ the sorcerer's pose

    /** Where he stands, the way he faces, his arms (turned forward xr / outward zr) and his hands in the world. */
    private static final class Pose {
        Vec3 feet;
        double yaw;
        float rx, rz, lx, lz;
        boolean glowHands;
        Vec3 rightHand, leftHand;

        Vec3 local(double x, double y, double z) {
            double cs = Math.cos(this.yaw), sn = Math.sin(this.yaw);
            return this.feet.add(x * cs + z * sn, y, -x * sn + z * cs);
        }

        Vec3 hand(double sx, float xr, float zr) {
            double len = 0.7D;
            // Rz * Rx * (0, -len, 0)
            double y1 = -len * Math.cos(xr), z1 = -len * Math.sin(xr);
            double x2 = -y1 * Math.sin(zr), y2 = y1 * Math.cos(zr);
            return local(sx + x2, 1.42D + y2, z1);
        }
    }

    /** How strongly his hands burn with magic (0..1). */
    private static float castLevel(double t) {
        double in = Mth.clamp((t - StrangeEntity.CAST0 + 6.0D) / 6.0D, 0.0D, 1.0D);
        double out = 1.0D - Mth.clamp((t - StrangeEntity.HIT - 10.0D) / 10.0D, 0.0D, 1.0D);
        return (float) Math.min(in, out);
    }

    private static float glyphAlpha(double t) {
        double in = Mth.clamp((t - StrangeEntity.CAST0) / 6.0D, 0.0D, 1.0D);
        double out = 1.0D - Mth.clamp((t - StrangeEntity.HIT) / 20.0D, 0.0D, 1.0D);
        return (float) Math.min(in, out);
    }

    private static float lerpA(double u, float a, float b) {
        return (float) (a + (b - a) * Mth.clamp(u, 0.0D, 1.0D));
    }

    private static double sm(double x) {
        x = Mth.clamp(x, 0.0D, 1.0D);
        return x * x * (3.0D - 2.0D * x);
    }

    private static Pose pose(StrangeEntity e, double t) {
        Pose p = new Pose();
        p.feet = e.strangeFeet(t);
        Vec3 f = e.face();
        p.yaw = Math.atan2(f.x, f.z);
        // arms out wide while he comes and goes; both hands at the target while he casts; the right one swings
        // the hammer in (up and back while the portal opens, down onto the target at the blow)
        float rx = -0.25F, rz = -0.55F, lx = -0.25F, lz = 0.55F;
        if (t >= StrangeEntity.CAST0 - 6 && t < StrangeEntity.LEAVE0 - 6) {
            double u = sm((t - StrangeEntity.CAST0 + 6.0D) / 8.0D);
            rx = lerpA(u, rx, -1.35F);
            lx = lerpA(u, lx, -1.35F);
            rz = lerpA(u, rz, -0.15F);
            lz = lerpA(u, lz, 0.15F);
            // a push at each spike
            for (int k = 0; k < StrangeEntity.SPIKES; k++) {
                double d = t - StrangeEntity.spikeTime(k);
                if (d >= 0.0D && d < 6.0D) {
                    float push = (float) Math.sin(Math.PI * d / 6.0D) * 0.3F;
                    rx -= push;
                    lx -= push;
                }
            }
            if (t >= StrangeEntity.P2_OPEN0 - 6) {
                double up = sm((t - StrangeEntity.P2_OPEN0 + 6.0D) / 14.0D);
                double down = sm((t - StrangeEntity.HAM0 - 4.0D) / (StrangeEntity.HIT - StrangeEntity.HAM0 - 4.0D));
                rx = lerpA(up, rx, -2.9F);
                rx = lerpA(down, rx, -1.1F);
                rz = lerpA(up, rz, -0.35F);
                rz = lerpA(down, rz, -0.1F);
                lx = lerpA(up, lx, -1.5F);
            }
            if (t > StrangeEntity.HIT + 10) {
                double rest = sm((t - StrangeEntity.HIT - 10.0D) / 16.0D);
                rx = lerpA(rest, rx, -0.25F);
                lx = lerpA(rest, lx, -0.25F);
                rz = lerpA(rest, rz, -0.55F);
                lz = lerpA(rest, lz, 0.55F);
            }
        }
        p.rx = rx;
        p.rz = rz;
        p.lx = lx;
        p.lz = lz;
        p.glowHands = castLevel(t) > 0.3F;
        p.rightHand = p.hand(-0.33D, rx, rz);
        p.leftHand = p.hand(0.33D, lx, lz);
        return p;
    }

    // ------------------------------------------------------------------ the sorcerer

    private void figure(VertexConsumer c, PoseStack pose, Pose fp, Vec3 base, float time, int lit) {
        pose.pushPose();
        Vec3 rel = fp.feet.subtract(base);
        pose.translate(rel.x, rel.y, rel.z);
        pose.mulPose(Axis.YP.rotation((float) fp.yaw));
        PoseStack.Pose p = pose.last();
        float dangle = 0.06F * Mth.sin(time * 0.1F);
        // legs (floating: hanging a little)
        limb(c, pose, -0.11F, 0.78F, 0.0F, 0.08F + dangle, 0.0F, 0.17F, 0.78F, PANTS, BOOTS, 0.24F, -1, lit);
        limb(c, pose, 0.11F, 0.78F, 0.0F, 0.02F - dangle, 0.0F, 0.17F, 0.78F, PANTS, BOOTS, 0.24F, -1, lit);
        // the tunic, its skirt split at the front, the lighter front panel
        Draw.box(c, p, -0.25F, 0.78F, -0.14F, 0.25F, 1.46F, 0.14F, col(TUNIC), lit);
        Draw.box(c, p, -0.12F, 0.8F, 0.14F, 0.12F, 1.44F, 0.15F, col(PANEL), lit);
        Draw.box(c, p, -0.27F, 0.36F, -0.15F, -0.02F, 0.82F, 0.15F, col(TUNIC2), lit);
        Draw.box(c, p, 0.02F, 0.36F, -0.15F, 0.27F, 0.82F, 0.15F, col(TUNIC2), lit);
        // belts: the broad one with its gold buckle, the sash across the chest, a pouch
        Draw.box(c, p, -0.26F, 0.9F, -0.155F, 0.26F, 0.99F, 0.155F, col(BELT), lit);
        Draw.box(c, p, -0.05F, 0.89F, 0.15F, 0.05F, 1.0F, 0.17F, col(GOLD), lit);
        for (int i = 0; i < 5; i++) {
            float x = 0.2F - i * 0.1F, y = 1.42F - i * 0.1F;
            Draw.box(c, p, x - 0.05F, y - 0.06F, 0.145F, x + 0.05F, y, 0.16F, col(BELT), lit);
        }
        Draw.box(c, p, 0.14F, 0.8F, 0.1F, 0.24F, 0.92F, 0.18F, col(BELT), lit);
        // the amulet: gold, with the green eye
        Draw.box(c, p, -0.01F, 1.34F, 0.14F, 0.01F, 1.46F, 0.16F, col(GOLD), lit);
        Draw.box(c, p, -0.08F, 1.18F, 0.14F, 0.08F, 1.34F, 0.18F, col(GOLD), lit);
        Draw.box(c, p, -0.04F, 1.23F, 0.18F, 0.04F, 1.29F, 0.19F, col(GEM), FULL);
        // arms: blue sleeves, gold-brown cuffs, bare hands (burning with light while he casts)
        limb(c, pose, -0.33F, 1.42F, 0.0F, fp.rx, fp.rz, 0.17F, 0.56F, TUNIC, BELT, 0.06F, fp.glowHands ? 1 : 0, lit);
        limb(c, pose, 0.33F, 1.42F, 0.0F, fp.lx, fp.lz, 0.17F, 0.56F, TUNIC, BELT, 0.06F, fp.glowHands ? 1 : 0, lit);
        Draw.box(c, p, -0.42F, 1.36F, -0.16F, -0.24F, 1.5F, 0.16F, col(TUNIC2), lit);
        Draw.box(c, p, 0.24F, 1.36F, -0.16F, 0.42F, 1.5F, 0.16F, col(TUNIC2), lit);
        // the head: face, eyes, brows, goatee; dark hair, grey at the temples
        Draw.box(c, p, -0.2F, 1.48F, -0.2F, 0.2F, 1.88F, 0.2F, col(SKIN), lit);
        Draw.box(c, p, -0.21F, 1.8F, -0.21F, 0.21F, 1.94F, 0.21F, col(HAIR), lit);
        Draw.box(c, p, -0.21F, 1.52F, -0.22F, 0.21F, 1.9F, -0.12F, col(HAIR), lit);
        Draw.box(c, p, -0.215F, 1.62F, -0.15F, -0.195F, 1.84F, 0.06F, col(GREY), lit);
        Draw.box(c, p, 0.195F, 1.62F, -0.15F, 0.215F, 1.84F, 0.06F, col(GREY), lit);
        Draw.box(c, p, -0.14F, 1.835F, 0.12F, 0.02F, 1.96F, 0.22F, col(HAIR), lit);   // the swept quiff
        Draw.box(c, p, -0.13F, 1.67F, 0.2F, -0.04F, 1.72F, 0.205F, col(EYE), lit);
        Draw.box(c, p, 0.04F, 1.67F, 0.2F, 0.13F, 1.72F, 0.205F, col(EYE), lit);
        Draw.box(c, p, -0.1F, 1.67F, 0.205F, -0.06F, 1.72F, 0.21F, col(IRIS), lit);
        Draw.box(c, p, 0.06F, 1.67F, 0.205F, 0.1F, 1.72F, 0.21F, col(IRIS), lit);
        Draw.box(c, p, -0.14F, 1.74F, 0.2F, -0.03F, 1.765F, 0.21F, col(HAIR), lit);
        Draw.box(c, p, 0.03F, 1.74F, 0.2F, 0.14F, 1.765F, 0.21F, col(HAIR), lit);
        Draw.box(c, p, -0.09F, 1.585F, 0.2F, 0.09F, 1.615F, 0.215F, col(HAIR), lit);
        Draw.box(c, p, -0.1F, 1.53F, 0.2F, -0.07F, 1.6F, 0.215F, col(HAIR), lit);
        Draw.box(c, p, 0.07F, 1.53F, 0.2F, 0.1F, 1.6F, 0.215F, col(HAIR), lit);
        Draw.box(c, p, -0.05F, 1.47F, 0.19F, 0.05F, 1.56F, 0.225F, col(HAIR), lit);
        // the high collar of the cloak round the back of the head
        Draw.box(c, p, -0.3F, 1.42F, -0.26F, 0.3F, 1.98F, -0.19F, col(CLOAK), lit);
        Draw.box(c, p, -0.36F, 1.44F, -0.22F, -0.28F, 1.92F, 0.04F, col(CLOAK), lit);
        Draw.box(c, p, 0.28F, 1.44F, -0.22F, 0.36F, 1.92F, 0.04F, col(CLOAK), lit);
        // the long cloak: strips from the shoulders down, flaring out and rippling behind him
        for (int i = 0; i < 9; i++) {
            float yTop = 1.46F - i * 0.16F, yBot = yTop - 0.17F;
            float hw = 0.32F + i * 0.028F;
            float k = i / 8.0F;
            float flap = 0.07F * Mth.sin(time * 0.18F + i * 0.7F) * k;
            float z = -0.18F - i * 0.045F - 0.06F * k - flap;
            Draw.box(c, p, -hw, yBot, z - 0.045F, hw, yTop, z, col(i == 8 ? CLOAK2 : CLOAK), lit);
            Draw.box(c, p, -hw + 0.02F, yBot, z, hw - 0.02F, yTop, z + 0.01F, col(CLOAK2), lit);
        }
        pose.popPose();
    }

    /**
     * An arm or a leg from (x, y, z): turned forward by xr, outward by zr; the main part, a band at its end (cuff /
     * boot) and, for arms (hand >= 0), a hand - glowing when hand == 1.
     */
    private void limb(VertexConsumer c, PoseStack pose, float x, float y, float z, float xr, float zr, float w, float len,
                      int main, int band, float bandLen, int hand, int lit) {
        pose.pushPose();
        pose.translate(x, y, z);
        pose.mulPose(Axis.ZP.rotation(zr));
        pose.mulPose(Axis.XP.rotation(xr));
        PoseStack.Pose p = pose.last();
        float h = w * 0.5F;
        if (hand < 0) {
            Draw.box(c, p, -h, -len + bandLen, -h, h, 0.0F, h, col(main), lit);
            Draw.box(c, p, -h - 0.01F, -len, -h - 0.01F, h + 0.01F, -len + bandLen, h + 0.02F, col(band), lit);
        } else {
            Draw.box(c, p, -h, -len, -h, h, 0.0F, h, col(main), lit);
            Draw.box(c, p, -h - 0.015F, -len - 0.02F, -h - 0.015F, h + 0.015F, -len + bandLen, h + 0.015F, col(band), lit);
            Draw.box(c, p, -0.065F, -len - 0.17F, -0.065F, 0.065F, -len - 0.02F, 0.065F, col(hand == 1 ? 0xFFD8A0 : SKIN), hand == 1 ? FULL : lit);
        }
        pose.popPose();
    }

    // ------------------------------------------------------------------ the spikes

    private void drawSpikes(StrangeEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t, int lit) {
        double since = t - StrangeEntity.HIT;
        for (int k = 0; k < StrangeEntity.SPIKES; k++) {
            if (StrangeEntity.spikeGrow(k, t) <= 0.0D) continue;
            float w = (float) StrangeEntity.spikeWidth(k);
            if (since < 0.0D) {
                Vec3[] s = e.spikeNow(k, t);
                spike(c, pose, s[0].subtract(base), s[1].subtract(base), w, lit, k * 0.9F);
                // rocky shards at its foot
                Vec3 b = e.spikeBase(k);
                double grow = StrangeEntity.spikeGrow(k, t);
                for (int j = 0; j < 3; j++) {
                    double a = j * 2.1D + k * 1.3D;
                    Vec3 foot = b.add(Math.cos(a) * w * 1.3D, 0.35D, Math.sin(a) * w * 1.3D);
                    Vec3 out = new Vec3(Math.cos(a), 1.1D, Math.sin(a)).normalize();
                    spike(c, pose, foot.subtract(base), foot.add(out.scale(0.75D * grow)).subtract(base), w * 0.45F, lit, j);
                }
            } else if (since < StrangeEntity.SHATTER) {
                shards(e, c, pose, base, k, since, w, lit);
            }
        }
    }

    /** A sharp stone spike from a to b: six faces, black at the foot, glowing hotter towards the point. */
    private void spike(VertexConsumer c, PoseStack pose, Vec3 a, Vec3 b, float w, int lit, float twist) {
        Vec3 ax = b.subtract(a);
        double len = ax.length();
        if (len < 1.0E-3D) return;
        Vec3 d = ax.scale(1.0D / len);
        Vec3 e1 = d.cross(Math.abs(d.y) > 0.9D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        Vec3 e2 = d.cross(e1).normalize();
        float[] fr = {0.0F, 0.35F, 0.62F, 0.82F, 1.0F};
        int[] cols = {STONE, STONE2, HOT, TIP};
        int sides = 6;
        PoseStack.Pose p = pose.last();
        for (int i = 0; i < fr.length - 1; i++) {
            double r0 = w * Math.pow(1.0D - fr[i], 0.9D), r1 = w * Math.pow(1.0D - fr[i + 1], 0.9D);
            Vec3 c0 = a.add(ax.scale(fr[i])), c1 = a.add(ax.scale(fr[i + 1]));
            int light = i < 2 ? lit : FULL;
            for (int s = 0; s < sides; s++) {
                double a0 = s * Math.PI * 2.0D / sides + twist + i * 0.18D, a1 = (s + 1) * Math.PI * 2.0D / sides + twist + i * 0.18D;
                double b0 = a0 + 0.18D, b1 = a1 + 0.18D;
                Vec3 n0 = e1.scale(Math.cos(a0)).add(e2.scale(Math.sin(a0))), n1 = e1.scale(Math.cos(a1)).add(e2.scale(Math.sin(a1)));
                Vec3 m0 = e1.scale(Math.cos(b0)).add(e2.scale(Math.sin(b0))), m1 = e1.scale(Math.cos(b1)).add(e2.scale(Math.sin(b1)));
                Vec3 p0 = c0.add(n0.scale(r0)), p1 = c0.add(n1.scale(r0)), p2 = c1.add(m1.scale(r1)), p3 = c1.add(m0.scale(r1));
                Vec3 nn = n0.add(n1).normalize();
                int argb = 0xFF000000 | (s % 2 == 0 ? cols[i] : Draw.shade(0xFF000000 | cols[i], 0.8F) & 0xFFFFFF);
                vtx(c, p, p0, argb, light, nn);
                vtx(c, p, p1, argb, light, nn);
                vtx(c, p, p2, argb, light, nn);
                vtx(c, p, p3, argb, light, nn);
            }
        }
    }

    private static void vtx(VertexConsumer c, PoseStack.Pose p, Vec3 v, int argb, int light, Vec3 n) {
        c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(argb).setUv(0.5F, 0.5F)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, (float) n.x, (float) n.y, (float) n.z);
    }

    /** After the blow: the spike's pieces fly outwards, tumbling, falling and shrinking away. */
    private void shards(StrangeEntity e, VertexConsumer c, PoseStack pose, Vec3 base, int k, double tau, float w, int lit) {
        Vec3 b = e.spikeBase(k), tip = e.spikeTip(k), f = e.impale();
        int n = 6;
        double shrink = 1.0D - tau / StrangeEntity.SHATTER;
        for (int j = 0; j < n; j++) {
            double fr = (j + 0.5D) / n;
            Vec3 p0 = b.lerp(tip, fr);
            Vec3 out = p0.subtract(f).multiply(1.0D, 0.0D, 1.0D);
            double hash = Math.sin(k * 12.9898D + j * 78.233D) * 43758.5453D;
            hash -= Math.floor(hash);
            if (out.lengthSqr() < 1.0E-3D) out = new Vec3(Math.cos(hash * 6.28D), 0.0D, Math.sin(hash * 6.28D));
            Vec3 v = out.normalize().scale(0.22D + 0.12D * hash).add(0.0D, 0.18D + 0.14D * ((j + k) % 3) / 2.0D, 0.0D);
            Vec3 at = p0.add(v.scale(tau)).subtract(0.0D, 0.5D * 0.045D * tau * tau, 0.0D);
            if (at.y < e.groundY() - 0.3D) continue;
            float s = (float) (w * (1.05D - fr * 0.8D) * shrink);
            if (s < 0.02F) continue;
            pose.pushPose();
            Vec3 rel = at.subtract(base);
            pose.translate(rel.x, rel.y, rel.z);
            pose.mulPose(Axis.XP.rotation((float) (tau * (0.25D + hash * 0.3D) + j)));
            pose.mulPose(Axis.ZP.rotation((float) (tau * 0.2D + k)));
            int colr = fr < 0.35D ? STONE : fr < 0.62D ? STONE2 : fr < 0.82D ? HOT : TIP;
            Draw.box(c, pose.last(), -s, -s * 1.6F, -s, s, s * 1.6F, s, 0xFF000000 | colr, fr < 0.62D ? lit : FULL);
            pose.popPose();
        }
    }

    // ------------------------------------------------------------------ the hammer

    private static Quaternionf hammerRotation(StrangeEntity e, double t) {
        Vec3 x = e.hammerDir();
        Vec3 yr = new Vec3(0.0D, 1.0D, 0.0D).subtract(x.scale(0.5D));
        Vec3 y = yr.subtract(x.scale(yr.dot(x)));
        y = y.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 1.0D, 0.0D) : y.normalize();
        Vec3 z = x.cross(y).normalize();
        Matrix3f m = new Matrix3f((float) x.x, (float) x.y, (float) x.z, (float) y.x, (float) y.y, (float) y.z, (float) z.x, (float) z.y, (float) z.z);
        Quaternionf q = new Quaternionf().setFromNormalized(m);
        q.rotateZ((float) StrangeEntity.hammerWhip(t));
        return q;
    }

    private void drawHammer(StrangeEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t, int lit) {
        float s = StrangeEntity.hammerScale(t);
        if (s <= 0.01F) return;
        Vec3 rel = e.hammerCenter(t).subtract(base);
        pose.pushPose();
        pose.translate(rel.x, rel.y, rel.z);
        pose.mulPose(hammerRotation(e, t));
        pose.scale(s, s, s);
        hammerModel(c, pose.last(), lit);
        pose.popPose();
    }

    /** The head along X (centred on the origin), the handle up along Y. */
    private static void hammerModel(VertexConsumer c, PoseStack.Pose p, int lit) {
        int steel = 0xFF000000 | STEEL, steelL = 0xFF000000 | STEEL_L, gold = 0xFF000000 | GOLD, rune = 0xFF000000 | RUNE;
        Draw.box(c, p, -0.95F, -0.55F, -0.55F, 0.95F, 0.55F, 0.55F, steel, lit);
        Draw.box(c, p, -1.1F, -0.47F, -0.47F, -0.95F, 0.47F, 0.47F, steelL, lit);
        Draw.box(c, p, 0.95F, -0.47F, -0.47F, 1.1F, 0.47F, 0.47F, steelL, lit);
        Draw.box(c, p, -0.64F, -0.61F, -0.61F, -0.48F, 0.61F, 0.61F, gold, lit);
        Draw.box(c, p, 0.48F, -0.61F, -0.61F, 0.64F, 0.61F, 0.61F, gold, lit);
        // burning runes round a green gem, on both faces; a ring of runes round the striking faces
        for (int sd = -1; sd <= 1; sd += 2) {
            float z0 = sd > 0 ? 0.55F : -0.585F, z1 = sd > 0 ? 0.585F : -0.55F;
            Draw.box(c, p, -0.32F, 0.24F, z0, 0.32F, 0.31F, z1, rune, FULL);
            Draw.box(c, p, -0.32F, -0.31F, z0, 0.32F, -0.24F, z1, rune, FULL);
            Draw.box(c, p, -0.32F, -0.24F, z0, -0.25F, 0.24F, z1, rune, FULL);
            Draw.box(c, p, 0.25F, -0.24F, z0, 0.32F, 0.24F, z1, rune, FULL);
            Draw.box(c, p, -0.04F, 0.31F, z0, 0.04F, 0.45F, z1, rune, FULL);
            Draw.box(c, p, -0.04F, -0.45F, z0, 0.04F, -0.31F, z1, rune, FULL);
            Draw.box(c, p, -0.1F, -0.1F, sd > 0 ? 0.55F : -0.61F, 0.1F, 0.1F, sd > 0 ? 0.61F : -0.55F, 0xFF000000 | GEM, FULL);
            float fx0 = sd > 0 ? 1.1F : -1.12F, fx1 = sd > 0 ? 1.12F : -1.1F;
            Draw.box(c, p, fx0, 0.3F, -0.3F, fx1, 0.36F, 0.3F, rune, FULL);
            Draw.box(c, p, fx0, -0.36F, -0.3F, fx1, -0.3F, 0.3F, rune, FULL);
            Draw.box(c, p, fx0, -0.3F, -0.36F, fx1, 0.3F, -0.3F, rune, FULL);
            Draw.box(c, p, fx0, -0.3F, 0.3F, fx1, 0.3F, 0.36F, rune, FULL);
        }
        // the handle: gold collar, red leather with gold rings, gold pommel with a small gem
        Draw.box(c, p, -0.2F, 0.55F, -0.2F, 0.2F, 0.78F, 0.2F, gold, lit);
        Draw.box(c, p, -0.12F, 0.78F, -0.12F, 0.12F, 2.9F, 0.12F, 0xFF000000 | LEATHER, lit);
        for (float y : new float[]{1.25F, 1.9F, 2.5F}) Draw.box(c, p, -0.15F, y, -0.15F, 0.15F, y + 0.08F, 0.15F, gold, lit);
        Draw.box(c, p, -0.18F, 2.9F, -0.18F, 0.18F, 3.15F, 0.18F, gold, lit);
        Draw.box(c, p, -0.07F, 3.15F, -0.07F, 0.07F, 3.22F, 0.07F, 0xFF000000 | GEM, FULL);
    }

    /** A trail of fire behind the flying hammer. */
    private void drawTrail(StrangeEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t) {
        if (t < StrangeEntity.HAM0 || t > StrangeEntity.HIT + 2) return;
        Quaternionf q = hammerRotation(e, Math.min(t, StrangeEntity.HIT));
        org.joml.Vector3f yv = q.transform(new org.joml.Vector3f(0.0F, 1.0F, 0.0F)), zv = q.transform(new org.joml.Vector3f(0.0F, 0.0F, 1.0F));
        Vec3 y = new Vec3(yv.x, yv.y, yv.z).scale(0.6D), z = new Vec3(zv.x, zv.y, zv.z).scale(0.6D);
        float fade = t > StrangeEntity.HIT ? (float) (1.0D - (t - StrangeEntity.HIT) / 2.0D) : 1.0F;
        PoseStack.Pose p = pose.last();
        Vec3 prev = e.hammerCenter(Math.min(t, StrangeEntity.HIT)).subtract(base);
        int n = 8;
        for (int i = 1; i <= n; i++) {
            Vec3 cur = e.hammerCenter(Math.max(StrangeEntity.HAM0, Math.min(t, StrangeEntity.HIT) - i * 0.7D)).subtract(base);
            float a0 = 0.55F * (1.0F - (i - 1) / (float) n) * fade, a1 = 0.55F * (1.0F - i / (float) n) * fade;
            int rgb = i < 3 ? SPARK1 : SPARK0;
            double k0 = 1.0D - (i - 1) * 0.08D, k1 = 1.0D - i * 0.08D;
            quadGrad(c, p, prev.add(y.scale(k0)), prev.subtract(y.scale(k0)), cur.subtract(y.scale(k1)), cur.add(y.scale(k1)), rgb, a0, a1);
            quadGrad(c, p, prev.add(z.scale(k0)), prev.subtract(z.scale(k0)), cur.subtract(z.scale(k1)), cur.add(z.scale(k1)), rgb, a0, a1);
            prev = cur;
        }
    }

    /** Rings of light running out from the blow, and one along the ground. */
    private void drawRings(StrangeEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t) {
        double tau = t - StrangeEntity.HIT;
        if (tau < 0.0D || tau > 16.0D) return;
        float a = (float) (0.9D * (1.0D - tau / 16.0D));
        Vec3 f = e.impale().subtract(base), hd = e.hammerDir();
        circle(c, pose.last(), f, hd, 0.6D + tau * 0.5D, 0.35F, SPARK1, a, 40, 0.0D);
        circle(c, pose.last(), f, hd, 0.3D + tau * 0.32D, 0.2F, 0xFFFFFF, a, 32, 0.0D);
        Vec3 g = new Vec3(e.anchor().x, e.groundY() + 0.08D, e.anchor().z).subtract(base);
        circle(c, pose.last(), g, new Vec3(0.0D, 1.0D, 0.0D), 1.0D + tau * 0.75D, 0.3F, SPARK0, a * 0.8F, 48, 0.0D);
    }

    // ------------------------------------------------------------------ portals and mystic circles

    /** A ring of spinning sparks (radius r, facing n) with a pale snowy world showing through it. */
    private void portal(VertexConsumer c, PoseStack pose, Vec3 ctr, Vec3 n, double r, float time, float seed) {
        Vec3[] uv = basis(n);
        Vec3 u = uv[0], v = uv[1];
        PoseStack.Pose p = pose.last();
        int segs = 40;
        // the other world: pale and bright, a little see-through
        for (int i = 0; i < segs; i++) {
            double a0 = i * Math.PI * 2.0D / segs, a1 = (i + 1) * Math.PI * 2.0D / segs;
            Vec3 q0 = ctr.add(u.scale(Math.cos(a0) * r * 0.97D)).add(v.scale(Math.sin(a0) * r * 0.97D));
            Vec3 q1 = ctr.add(u.scale(Math.cos(a1) * r * 0.97D)).add(v.scale(Math.sin(a1) * r * 0.97D));
            quadGrad(c, p, ctr, q0, q1, ctr, 0xE4EEFF, 0.42F, 0.22F);
        }
        // snow falling in it
        for (int i = 0; i < 16; i++) {
            double hx = frac(Math.sin(i * 12.9898D + seed) * 43758.5453D) * 2.0D - 1.0D;
            double hy = frac(Math.sin(i * 78.233D + seed) * 12345.678D + time * 0.012D * (1.0D + (i % 3) * 0.4D));
            double y = 1.0D - hy * 2.0D, x = hx + 0.05D * Math.sin(time * 0.1D + i);
            if (x * x + y * y > 0.85D) continue;
            Vec3 s = ctr.add(u.scale(x * r)).add(v.scale(y * r)).add(n.scale(0.02D));
            double z = 0.035D * r;
            quadGrad(c, p, s.add(u.scale(-z)).add(v.scale(-z)), s.add(u.scale(z)).add(v.scale(-z)), s.add(u.scale(z)).add(v.scale(z)), s.add(u.scale(-z)).add(v.scale(z)), 0xFFFFFF, 0.9F, 0.9F);
        }
        // the spark rings: three, turning at different speeds, flickering, with gaps
        for (int ring = 0; ring < 3; ring++) {
            double rr = r * (0.98D + 0.04D * ring);
            double spin = time * (ring == 1 ? -0.16D : 0.22D + 0.05D * ring) + seed;
            int n2 = 48;
            for (int i = 0; i < n2; i++) {
                if ((i + ring * 7 + (int) (time * 0.5F)) % 9 == 0) continue;
                double a0 = i * Math.PI * 2.0D / n2 + spin, a1 = (i + 1) * Math.PI * 2.0D / n2 + spin;
                double fl = Math.sin(i * 12.9898D + ring * 78.2D + time * 0.9D);
                fl *= fl;
                int rgb = mix(SPARK0, SPARK1, (float) fl);
                double w = (0.06D + 0.06D * fl) * Math.min(1.0D, r);
                Vec3 o0 = ctr.add(u.scale(Math.cos(a0) * (rr + w))).add(v.scale(Math.sin(a0) * (rr + w)));
                Vec3 o1 = ctr.add(u.scale(Math.cos(a1) * (rr + w))).add(v.scale(Math.sin(a1) * (rr + w)));
                Vec3 i0 = ctr.add(u.scale(Math.cos(a0) * (rr - w))).add(v.scale(Math.sin(a0) * (rr - w)));
                Vec3 i1 = ctr.add(u.scale(Math.cos(a1) * (rr - w))).add(v.scale(Math.sin(a1) * (rr - w)));
                quadGrad(c, p, i0, o0, o1, i1, rgb, 0.95F, 0.95F);
            }
        }
        // sparks streaming off along the ring
        for (int i = 0; i < 26; i++) {
            double h = frac(Math.sin(i * 41.31D + seed) * 9871.13D);
            double life = frac(time * 0.07D + h);
            double a = h * Math.PI * 2.0D + time * 0.25D + life * 1.2D;
            Vec3 radial = u.scale(Math.cos(a)).add(v.scale(Math.sin(a)));
            Vec3 tangent = u.scale(-Math.sin(a)).add(v.scale(Math.cos(a)));
            Vec3 s0 = ctr.add(radial.scale(r * (1.0D + 0.25D * life)));
            Vec3 s1 = s0.add(tangent.scale(-(0.25D + 0.4D * h) * Math.min(1.0D, r))).add(radial.scale(-0.08D));
            Vec3 wv = n.scale(0.025D);
            quadGrad(c, p, s0.add(wv), s0.subtract(wv), s1.subtract(wv), s1.add(wv), SPARK1, (float) (1.0D - life), 0.0F);
            Vec3 wr = radial.scale(0.025D);
            quadGrad(c, p, s0.add(wr), s0.subtract(wr), s1.subtract(wr), s1.add(wr), SPARK1, (float) (1.0D - life), 0.0F);
        }
    }

    private void portalGlow(PoseStack pose, VertexConsumer glow, Quaternionf cam, Vec3 ctr, Vec3 n, double r, float time, float fl) {
        Vec3[] uv = basis(n);
        halo(pose, glow, cam, ctr, (float) (r * 1.6D), 0xFF7A1E, 0.3F);
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI * 2.0D / 10.0D + time * 0.2D;
            Vec3 q = ctr.add(uv[0].scale(Math.cos(a) * r)).add(uv[1].scale(Math.sin(a) * r));
            halo(pose, glow, cam, q, (float) (0.55D * Math.min(1.0D, r)) * fl, 0xFFA040, 0.6F);
        }
    }

    /** The glyph circle on the ground and the circles on his hands. */
    private void drawMagic(StrangeEntity e, VertexConsumer c, PoseStack pose, Vec3 base, double t, float time, Pose fp) {
        float g = glyphAlpha(t);
        if (g > 0.01F) {
            double grow = sm((t - StrangeEntity.CAST0) / StrangeEntity.GLYPH_IN);
            Vec3 ctr = new Vec3(e.anchor().x, e.groundY() + 0.06D, e.anchor().z).subtract(base);
            mandala(c, pose.last(), ctr, new Vec3(1.0D, 0.0D, 0.0D), new Vec3(0.0D, 0.0D, 1.0D), 3.4D * grow, time * 0.02D, MAGIC, 0.9F * g, 0.09D);
        }
        float cast = castLevel(t) * StrangeEntity.strangeAlpha(t);
        if (cast > 0.01F) {
            Vec3 f = e.face();
            Vec3 u = new Vec3(-f.z, 0.0D, f.x), v = new Vec3(0.0D, 1.0D, 0.0D);
            double big = sm((t - StrangeEntity.P2_OPEN0 + 6.0D) / 12.0D) * (1.0D - sm((t - StrangeEntity.HIT) / 10.0D));
            mandala(c, pose.last(), fp.rightHand.add(f.scale(0.12D)).subtract(base), u, v, (0.42D + 0.3D * big) * cast, time * 0.08D, MAGIC, 0.95F * cast, 0.035D);
            mandala(c, pose.last(), fp.leftHand.add(f.scale(0.12D)).subtract(base), u, v, 0.42D * cast, -time * 0.08D, MAGIC, 0.95F * cast, 0.035D);
        }
    }

    /** A mystic circle: rings, a seven-pointed star, little circles at its points, a square cross and rune ticks. */
    private void mandala(VertexConsumer c, PoseStack.Pose p, Vec3 ctr, Vec3 u, Vec3 v, double r, double rot, int rgb, float a, double w) {
        if (r < 0.02D) return;
        Vec3 n = u.cross(v);
        circle(c, p, ctr, n, u, v, r, w, rgb, a, 48, rot);
        circle(c, p, ctr, n, u, v, r * 0.87D, w * 0.7D, rgb, a, 48, rot);
        circle(c, p, ctr, n, u, v, r * 0.42D, w * 0.7D, rgb, a, 32, rot);
        Vec3[] pts = new Vec3[7];
        for (int i = 0; i < 7; i++) {
            double ang = rot + i * Math.PI * 2.0D / 7.0D;
            pts[i] = ctr.add(u.scale(Math.cos(ang) * r * 0.84D)).add(v.scale(Math.sin(ang) * r * 0.84D));
        }
        for (int i = 0; i < 7; i++) line(c, p, pts[i], pts[(i + 3) % 7], n, w * 0.8D, rgb, a);
        for (int i = 0; i < 7; i++) circle(c, p, pts[i].subtract(ctr).scale(1.12D).add(ctr), n, u, v, r * 0.08D, w * 0.5D, rgb, a, 12, 0.0D);
        for (int sq = 0; sq < 2; sq++) {
            Vec3[] q = new Vec3[4];
            for (int j = 0; j < 4; j++) {
                double ang = -rot * 1.5D + j * Math.PI / 2.0D + sq * Math.PI / 4.0D;
                q[j] = ctr.add(u.scale(Math.cos(ang) * r * 0.42D)).add(v.scale(Math.sin(ang) * r * 0.42D));
            }
            for (int j = 0; j < 4; j++) line(c, p, q[j], q[(j + 1) % 4], n, w * 0.6D, rgb, a);
        }
        for (int i = 0; i < 28; i++) {
            double ang = rot * 0.5D + i * Math.PI * 2.0D / 28.0D;
            Vec3 dir = u.scale(Math.cos(ang)).add(v.scale(Math.sin(ang)));
            double in = i % 2 == 0 ? 0.88D : 0.92D;
            line(c, p, ctr.add(dir.scale(r * in)), ctr.add(dir.scale(r * 0.99D)), n, w * 0.5D, rgb, a);
        }
    }

    private void circle(VertexConsumer c, PoseStack.Pose p, Vec3 ctr, Vec3 n, double r, double w, int rgb, float a, int segs, double rot) {
        Vec3[] uv = basis(n);
        circle(c, p, ctr, n, uv[0], uv[1], r, w, rgb, a, segs, rot);
    }

    private void circle(VertexConsumer c, PoseStack.Pose p, Vec3 ctr, Vec3 n, Vec3 u, Vec3 v, double r, double w, int rgb, float a, int segs, double rot) {
        for (int i = 0; i < segs; i++) {
            double a0 = rot + i * Math.PI * 2.0D / segs, a1 = rot + (i + 1) * Math.PI * 2.0D / segs;
            Vec3 d0 = u.scale(Math.cos(a0)).add(v.scale(Math.sin(a0))), d1 = u.scale(Math.cos(a1)).add(v.scale(Math.sin(a1)));
            quadGrad(c, p, ctr.add(d0.scale(r - w * 0.5D)), ctr.add(d0.scale(r + w * 0.5D)), ctr.add(d1.scale(r + w * 0.5D)), ctr.add(d1.scale(r - w * 0.5D)), rgb, a, a);
        }
    }

    /** A flat line from a to b lying in the plane with normal n. */
    private void line(VertexConsumer c, PoseStack.Pose p, Vec3 a, Vec3 b, Vec3 n, double w, int rgb, float al) {
        Vec3 d = b.subtract(a);
        Vec3 side = n.cross(d);
        if (side.lengthSqr() < 1.0E-8D) return;
        side = side.normalize().scale(w * 0.5D);
        quadGrad(c, p, a.add(side), a.subtract(side), b.subtract(side), b.add(side), rgb, al, al);
    }

    /** Double-sided quad; a..b at alpha a0, c..d at alpha a1. */
    private static void quadGrad(VertexConsumer c, PoseStack.Pose p, Vec3 a, Vec3 b, Vec3 cc, Vec3 d, int rgb, float a0, float a1) {
        int c0 = ((int) (Mth.clamp(a0, 0.0F, 1.0F) * 255.0F) << 24) | (rgb & 0xFFFFFF);
        int c1 = ((int) (Mth.clamp(a1, 0.0F, 1.0F) * 255.0F) << 24) | (rgb & 0xFFFFFF);
        Vec3[] q = {a, b, cc, d, d, cc, b, a};
        int[] cs = {c0, c0, c1, c1, c1, c1, c0, c0};
        for (int i = 0; i < 8; i++) {
            Vec3 v = q[i];
            c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(cs[i]).setUv(0.5F, 0.5F)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL).setNormal(p, 0.0F, 1.0F, 0.0F);
        }
    }

    private static Vec3[] basis(Vec3 n) {
        Vec3 u = n.cross(new Vec3(0.0D, 1.0D, 0.0D));
        u = u.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : u.normalize();
        Vec3 v = u.cross(n).normalize();
        return new Vec3[]{u, v};
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
