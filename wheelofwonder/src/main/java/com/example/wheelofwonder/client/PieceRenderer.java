package com.example.wheelofwonder.client;

import com.example.wheelofwonder.WheelEntity;
import com.example.wheelofwonder.WheelPieceEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** Tumbling debris of the exploded wheel. */
public class PieceRenderer extends EntityRenderer<WheelPieceEntity> {
    public PieceRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.15F;
    }

    @Override
    public ResourceLocation getTextureLocation(WheelPieceEntity e) {
        return WheelRenderer.WHITE;
    }

    @Override
    public void render(WheelPieceEntity e, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        float t = e.tickCount + partial;
        int id = e.getId();
        // stop tumbling once it lies on the ground
        float tumble = e.onGround() ? 0.0F : 1.0F;
        pose.pushPose();
        pose.translate(0.0F, 0.15F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees((id * 47) % 360 + t * 13.0F * tumble));
        pose.mulPose(Axis.XP.rotationDegrees((id * 91) % 360 + t * (9 + id % 7) * tumble));
        PoseStack.Pose p = pose.last();
        int col = 0xFF000000 | WheelEntity.BULB_COLORS[e.colorIndex() % WheelEntity.BULB_COLORS.length];
        switch (e.kind()) {
            case WheelPieceEntity.BULB -> {
                VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
                Draw.cube(c, p, 0, 0, 0, 0.08F, col, LightTexture.FULL_BRIGHT);
                Draw.box(c, p, -0.06F, -0.16F, -0.06F, 0.06F, -0.08F, 0.06F, 0xFFA87418, light);
                pose.popPose();
                if (e.tickCount < 60) {
                    pose.pushPose();
                    pose.translate(0.0F, 0.15F, 0.0F);
                    pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
                    float fade = 1.0F - e.tickCount / 60.0F;
                    Draw.halo(buffers.getBuffer(ShaderCompat.glow(WheelRenderer.GLOW)), pose.last(), 0.35F, Draw.shade(col, 0.7F * fade) | 0xFF000000);
                    pose.popPose();
                }
                return;
            }
            case WheelPieceEntity.SHARD -> {
                VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.FACE));
                // a random patch of the spiral face
                float u = (id % 4) * 0.2F + 0.1F, v = ((id / 4) % 4) * 0.2F + 0.1F;
                Draw.quadZ(c, p, -0.28F, -0.28F, 0.28F, 0.28F, 0.04F, true, 0xFFFFFFFF, u, v, u + 0.2F, v + 0.2F, light);
                Draw.quadZ(c, p, -0.28F, -0.28F, 0.28F, 0.28F, -0.04F, false, 0xFFFFFFFF, u, v, u + 0.2F, v + 0.2F, light);
            }
            case WheelPieceEntity.HULL -> {
                VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
                int hull = e.colorIndex() % 2 == 0 ? RocketModel.NAVY : RocketModel.RED;
                Draw.box(c, p, -0.26F, -0.04F, -0.18F, 0.26F, 0.04F, 0.18F, hull, light);
                Draw.box(c, p, -0.26F, 0.04F, -0.03F, 0.26F, 0.06F, 0.03F, 0xFFE8B33A, light);
            }
            case WheelPieceEntity.FIN -> {
                VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
                Draw.box(c, p, -0.03F, -0.2F, -0.16F, 0.03F, 0.2F, 0.16F, RocketModel.NAVY, light);
                Draw.box(c, p, -0.035F, -0.05F, -0.12F, 0.035F, 0.05F, 0.1F, RocketModel.RED, light);
            }
            case WheelPieceEntity.ROCK -> {
                // a lump of the meteor: charred rock, one face still glowing (fading out)
                VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
                float s = 0.16F + (id % 5) * 0.03F;
                float glow = Math.max(0.0F, 1.0F - e.tickCount / 90.0F);
                Draw.box(c, p, -s, -s * 0.8F, -s * 0.9F, s, s * 0.8F, s * 0.9F, 0xFF3A3034, light);
                Draw.box(c, p, -s * 0.7F, s * 0.8F, -s * 0.6F, s * 0.5F, s * 1.05F, s * 0.5F, 0xFF2A2226, light);
                int hot = 0xFF000000 | ((int) (80 + 175 * glow) << 16) | ((int) (30 + 70 * glow) << 8) | 12;
                Draw.box(c, p, -s * 0.6F, -s * 0.5F, s * 0.9F, s * 0.6F, s * 0.5F, s * 0.95F, hot, e.colorIndex() == 1 || glow > 0.5F ? LightTexture.FULL_BRIGHT : light);
            }
            case WheelPieceEntity.NOSE -> {
                VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
                Draw.box(c, p, -0.16F, -0.12F, -0.16F, 0.16F, 0.08F, 0.16F, 0xFF121018, light);
                Draw.box(c, p, -0.08F, 0.08F, -0.08F, 0.08F, 0.24F, 0.08F, 0xFF121018, light);
            }
            default -> {
                VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(WheelRenderer.WHITE));
                Draw.box(c, p, -0.3F, -0.1F, -0.18F, 0.3F, 0.1F, 0.18F, (id % 2 == 0) ? 0xFFE8B33A : 0xFFA87418, light);
                Draw.cube(c, p, 0.0F, 0.13F, 0.0F, 0.05F, col, LightTexture.FULL_BRIGHT);
            }
        }
        pose.popPose();
        super.render(e, yaw, partial, pose, buffers, light);
    }
}
