package com.example.wheelofwonder.client;

import com.example.wheelofwonder.RocketEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

/** The flying rocket car: the same model as on the ride, nose along its flight, with a burning exhaust. */
public class RocketRenderer extends EntityRenderer<RocketEntity> {
    public RocketRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.5F;
    }

    @Override
    public ResourceLocation getTextureLocation(RocketEntity e) {
        return RocketModel.SKIN;
    }

    @Override
    public void render(RocketEntity e, float entityYaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        Vector3f d = e.dir();
        float time = e.tickCount + partial;
        int lit = LightTexture.pack(Math.max(LightTexture.block(light), 8), LightTexture.sky(light));
        RocketModel.place(pose, 0.0D, 0.0D, 0.0D, d.x, d.y, d.z, RocketModel.SCALE);
        RocketModel.solid(buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE)), pose.last(), lit, true);
        RocketModel.skin(buffers.getBuffer(RenderType.entityCutoutNoCull(RocketModel.SKIN)), pose.last(), lit);
        pose.popPose();

        // exhaust: an orange core and a violet outer flame while the engine burns, then a dull ember
        VertexConsumer glow = buffers.getBuffer(ShaderCompat.glow(WheelRenderer.GLOW));
        float back = -RocketModel.TAIL_Y * RocketModel.SCALE + 0.15F;
        boolean burn = e.thrusting();
        float fl = RocketModel.flicker(time, e.getId());
        for (int i = 0; i < (burn ? 3 : 1); i++) {
            float off = back + i * 0.35F;
            pose.pushPose();
            pose.translate(-d.x * off, -d.y * off, -d.z * off);
            pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
            int col = i == 0 ? 0xFFFFB040 : (i == 1 ? 0xFFFF5A10 : 0xFFA040FF);
            float size = burn ? (0.55F - i * 0.1F) * fl : 0.25F;
            Draw.halo(glow, pose.last(), size, Draw.shade(col, burn ? 0.8F - i * 0.2F : 0.35F) | 0xFF000000);
            pose.popPose();
        }
        super.render(e, entityYaw, partial, pose, buffers, light);
    }
}
