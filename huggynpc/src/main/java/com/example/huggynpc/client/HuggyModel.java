package com.example.huggynpc.client;

import com.example.huggynpc.HuggyConfig;
import com.example.huggynpc.HuggyEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;


/**
 * Renders the skinned Huggy mesh. Used two ways:
 * - by HuggyRenderer for the spawn-egg Huggy;
 * - by Custom NPCs, which calls setupAnim/renderToBuffer with a hidden HuggyEntity while drawing an NPC.
 *   The real NPC (for speed, swing, Custom NPCs animation mode) comes from RenderTracker.
 *
 * The ModelPart field below is never drawn by us: Sable: Ragdolls reads the ModelPart fields of the model and
 * their nested parts to build the ragdoll body. "body" holds the head, and for every limb or tail a chain of
 * links, as nested parts. They only hold collision boxes; what is drawn is the real mesh (see renderToBuffer).
 * No other ModelPart fields (not even static ones) may exist in this class: Sable would turn them into parts.
 */
public class HuggyModel<T extends LivingEntity> extends EntityModel<T> {
    public ModelPart body = RagdollParts.EMPTY;
    private final ResourceLocation skin;
    private RagdollParts ragdoll;
    private boolean partsBuilt;

    private LivingEntity passed;
    private float headYaw, headPitch;

    /** The doll: her eyes turn red every now and then (eye quads swapped and lit up, see SkinMesh.emitEyes). */
    private final boolean redEyes;
    /**
     * Lily: her changing cubes (bright hair strands, eyes, teeth, stars, the braids' claws) have four looks in the
     * palette rows 448/464/480/496: normal, glowing in the dark, angry (narrowed bloodshot eyes, claws out), angry in
     * the dark. In the dark they light up: the hair violet, eyes and teeth green, the stars lime.
     */
    private final boolean looks;

    public HuggyModel(ResourceLocation skin) {
        this(skin, false);
    }

    public HuggyModel(ResourceLocation skin, boolean redEyes) {
        this(skin, redEyes, false);
    }

    public HuggyModel(ResourceLocation skin, boolean redEyes, boolean looks) {
        this.skin = skin;
        this.redEyes = redEyes;
        this.looks = looks;
        // Sable looks for the parts before it calls setupAnim, so build them as soon as resources are available
        try {
            ensureRagdollParts();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        ensureRagdollParts();
        this.passed = entity;
        this.headYaw = netHeadYaw;
        this.headPitch = headPitch;
    }

    private void ensureRagdollParts() {
        if (this.partsBuilt) return;
        SkinMesh mesh = SkinMesh.get(this.skin);
        if (mesh == null) return;
        this.partsBuilt = true;
        RagdollParts r = RagdollParts.build(mesh);
        if (r == null) return;
        this.ragdoll = r;
        this.body = r.parts[0];
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int light, int overlay, int color) {
        SkinMesh mesh = SkinMesh.get(this.skin);
        if (mesh == null) return;
        ensureRagdollParts();

        // Sable draws a ragdoll one part at a time: it empties the cubes of the other parts and renders the model.
        // We only remember where each part is this frame; when the last part arrives, the whole mesh is drawn once,
        // every vertex placed by the parts it belongs to (seamless joints). If some part never arrives this frame
        // (culled), the frame shows nothing extra rather than a torn body.
        boolean[] keep = ragdollReplayParts();
        if (keep != null) {
            poseStack.pushPose();
            poseStack.translate(0.0F, 1.501F, 0.0F);
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            RagdollFrame f = RagdollFrame.of(this.passed, keep.length);
            for (int p = 0; p < keep.length; p++) if (keep[p]) f.put(p, poseStack.last(), light);
            if (f.complete()) mesh.emitMerged(f.mats, f.nmats, f.lights, buffer, overlay, color);
            poseStack.popPose();
            return;
        }

        LivingEntity e = resolve();
        if (e == null) return;

        // Performance: the skinned vertices of every NPC are kept per entity. Within one frame they are computed once
        // (the shader shadow pass reuses them); far NPCs update their animation less often; very far NPCs cast no
        // shadow at all.
        double d2 = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceToSqr(e.position());
        boolean shadowPass = ShaderCompat.inShadowPass();
        if (shadowPass && d2 > 24.0D * 24.0D) return;
        long frame = RagdollFrame.currentFrame();
        SkinCache c = SKINS.get(e);
        if (c == null || c.pos.length != mesh.vertexCount() * 3) {
            c = new SkinCache(mesh.vertexCount());
            SKINS.put(e, c);
        }
        int every = d2 > 48.0D * 48.0D ? 4 : d2 > 20.0D * 20.0D ? 2 : 1;
        // wearing Verity as its head: drawn in full without its own head
        boolean verityHead = VerityClient.merged(e.getId()) && mesh.headSegment() >= 0;
        // reduced mesh beyond 12 blocks; the shadow pass always uses it. Near NPCs skin all vertices (the reduced
        // mesh uses a subset of them), so one skinning per frame serves both the shadow and the main pass.
        boolean full = !mesh.hasLod() || d2 <= LOD_DISTANCE_SQ || verityHead;
        boolean lod = mesh.hasLod() && (shadowPass || !full) && !verityHead;
        if (c.skinnedFrame < 0 || (c.skinnedFrame != frame && frame - c.skinnedFrame >= every) || (full && !c.full)) {
            AnimController.pose(mesh, e);
            float yaw = 0F, pitch = 0F;
            if (HuggyConfig.HEAD_TRACKING.get() && AnimController.headFree(e)) {
                yaw = -Mth.clamp(this.headYaw, -70F, 70F) * Mth.DEG_TO_RAD;
                pitch = Mth.clamp(this.headPitch, -40F, 40F) * Mth.DEG_TO_RAD;
            }
            mesh.pose(mesh.poseA, yaw, pitch, c.pos, c.nrm, !full);
            c.skinnedFrame = frame;
            c.full = full;
        }

        // Undo LivingEntityRenderer's model flip (scale -1,-1,1 and 1.501 offset) and turn +Z to the front.
        poseStack.pushPose();
        poseStack.translate(0.0F, 1.501F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        if (verityHead) {
            float[] box = new float[4];
            if (mesh.emitHeadless(poseStack.last(), buffer, light, overlay, color, c.pos, c.nrm, box)) {
                // the ball goes where the head was, turned with the head (drawn after the NPC, VerityClient)
                poseStack.pushPose();
                poseStack.translate(box[0], box[1], box[2]);
                poseStack.mulPose(Axis.YP.rotation(HuggyConfig.HEAD_TRACKING.get() && AnimController.headFree(e)
                        ? -Mth.clamp(this.headYaw, -70F, 70F) * Mth.DEG_TO_RAD : 0F));
                VerityClient.putHead(e, poseStack.last(), Math.max(0.3F, box[3] * 1.05F), light);
                poseStack.popPose();
                poseStack.popPose();
                return;
            }
        }
        if (this.redEyes) mesh.enableEyeSwap(EYE_U0, EYE_V0, EYE_U1, EYE_V1, EYE_DV);
        mesh.emit(poseStack.last(), buffer, light, overlay, color, c.pos, c.nrm, lod);
        if (this.redEyes) mesh.emitEyes(poseStack.last(), buffer, light, overlay, color, c.pos, c.nrm, shadowPass ? 0F : eyeGlow(e));
        if (this.looks && mesh.hasLooks()) {
            // look slots: mood * 2 (+1 in the dark); 10 / 11 angry
            boolean dark = !shadowPass && dark(e, light), mad = angry(e);
            int slot = (mad ? 10 : AnimController.mood(e) * 2) + (dark ? 1 : 0);
            mesh.emitLooks(poseStack.last(), buffer, light, overlay, color, c.pos, c.nrm, slot, dark ? 1F : 0F);
        }
        poseStack.popPose();
    }

    /** Lily: one look's palette rows below the other. */
    private static final float LOOK_DV = 16F / 512F;
    private static final java.util.Map<LivingEntity, Integer> ANGRY_UNTIL = new java.util.WeakHashMap<>();

    /** Dark where she stands: block light and the sky's light (dimmed at night) both low. */
    private static boolean dark(LivingEntity e, int light) {
        int block = net.minecraft.client.renderer.LightTexture.block(light), sky = net.minecraft.client.renderer.LightTexture.sky(light);
        int darken = e.level() != null ? e.level().getSkyDarken() : 0;
        return Math.max(block, sky - darken) <= 6;
    }

    /** Losing her temper: while she attacks, is hurt or hunts someone, and for 3 seconds after. */
    private static boolean angry(LivingEntity e) {
        boolean now = e.swinging || e.hurtTime > 0 || (e instanceof net.minecraft.world.entity.Mob m && m.isAggressive());
        if (now) ANGRY_UNTIL.put(e, e.tickCount + 60);
        Integer until = ANGRY_UNTIL.get(e);
        return until != null && e.tickCount < until && until - e.tickCount <= 60;
    }

    // the eye cubes of the doll's palette texture (tools/GuardGen.java: rows 448..463 of 512, red twins 32 rows lower)
    private static final float EYE_U0 = 0F, EYE_V0 = 447.9F / 512F, EYE_U1 = 1F, EYE_V1 = 464.1F / 512F, EYE_DV = 32F / 512F;
    /** Every 10 s (offset per doll) the eyes go red for 3 s, fading the glow in and out over 0.75 s. */
    private static final int EYE_CYCLE = 200, EYE_ON = 120, EYE_LEN = 60, EYE_FADE = 15;

    private static float eyeGlow(LivingEntity e) {
        if (e.level() == null) return 0F;
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        float t = Math.floorMod(e.level().getGameTime() + e.getId() * 53L, EYE_CYCLE) + partial - EYE_ON;
        if (t <= 0F || t >= EYE_LEN) return 0F;
        float g = Math.min(1F, Math.min(t, EYE_LEN - t) / EYE_FADE);
        return 0.05F + 0.95F * g * g * (3F - 2F * g);
    }

    /** Skinned vertices of one entity and the frame they were computed in. */
    private static final class SkinCache {
        final float[] pos, nrm;
        long skinnedFrame = -1;
        boolean full;

        SkinCache(int vertices) {
            this.pos = new float[vertices * 3];
            this.nrm = new float[vertices * 3];
        }
    }

    private static final double LOD_DISTANCE_SQ = 12.0D * 12.0D;
    private static final java.util.Map<LivingEntity, SkinCache> SKINS = new java.util.WeakHashMap<>();

    /** @return which parts to draw when Sable is drawing a ragdoll part, or null for normal rendering. */
    private boolean[] ragdollReplayParts() {
        if (this.ragdoll == null) return null;
        ModelPart[] parts = this.ragdoll.parts;   // index = ragdoll part id of the mesh quads
        boolean[] keep = new boolean[parts.length];
        boolean hidden = false;
        for (int i = 0; i < parts.length; i++) {
            keep[i] = !parts[i].isEmpty() && parts[i].visible;
            hidden |= !keep[i];
        }
        return hidden ? keep : null;
    }

    private LivingEntity resolve() {
        LivingEntity p = this.passed;
        // the spawn-egg Huggy itself (an entity that is really in the world)
        if (p instanceof HuggyEntity && p.level() != null && p.level().getEntity(p.getId()) == p) return p;
        // Custom NPC drawn with the Huggy model: use the NPC being rendered
        LivingEntity current = RenderTracker.current();
        return current != null ? current : p;
    }
}
