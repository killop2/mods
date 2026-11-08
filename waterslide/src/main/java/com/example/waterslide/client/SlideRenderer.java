package com.example.waterslide.client;

import com.example.waterslide.SlideAnchorBlockEntity;
import com.example.waterslide.SlideInstance;
import com.example.waterslide.WaterSlideMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Draws the whole slide from its anchor block entity: one pre-built mesh, rotated to the slide's facing.
 * Light is sampled at ~100 probe points and refreshed twice a second, not per vertex per frame.
 */
public class SlideRenderer implements BlockEntityRenderer<SlideAnchorBlockEntity> {
    private static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath(WaterSlideMod.MODID, "textures/entity/slide.png");
    private static final ResourceLocation WATER = ResourceLocation.fromNamespaceAndPath(WaterSlideMod.MODID, "textures/entity/slide_water.png");

    @Override
    public void render(SlideAnchorBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        SlideInstance slide = be.instance();
        if (level == null || slide == null) return;
        SlideMesh mesh = SlideMesh.get(slide.type);

        long time = level.getGameTime();
        if (be.lights == null || time - be.lightTick >= 10 || time < be.lightTick) {
            int[] lights = new int[mesh.probes.size()];
            for (int i = 0; i < lights.length; i++) lights[i] = LevelRenderer.getLightColor(level, slide.worldBlock(mesh.probes.get(i)));
            be.lights = lights;
            be.lightTick = time;
        }

        pose.pushPose();
        pose.translate(0.5D, 0.0D, 0.5D);
        pose.mulPose(Axis.YP.rotationDegrees(-slide.facing.toYRot()));
        PoseStack.Pose p = pose.last();
        emit(buffers.getBuffer(RenderType.entityCutoutNoCull(TEX)), p, mesh.solid, be.lights, 0.0F, 255);
        int frame = (int) ((time + partialTick) / 3.0F) & 3;
        emit(buffers.getBuffer(RenderType.entityTranslucent(WATER)), p, mesh.water, be.lights, frame * 0.25F, 200);
        if (slide.geo.spec.isCarnival()) {
            CarnivalDecor.render(be, slide, slide.geo, partialTick, pose, buffers, slide.facing.toYRot());
        }
        pose.popPose();
    }

    private static void emit(VertexConsumer vc, PoseStack.Pose p, float[] d, int[] lights, float uOffset, int alpha) {
        for (int i = 0; i < d.length; i += SlideMesh.STRIDE) {
            vc.addVertex(p, d[i], d[i + 1], d[i + 2])
                    .setColor(d[i + 9], d[i + 10], d[i + 11], alpha / 255.0F)
                    .setUv(d[i + 3] + uOffset, d[i + 4])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(lights[(int) d[i + 8]])
                    .setNormal(p, d[i + 5], d[i + 6], d[i + 7]);
        }
    }

    @Override
    public boolean shouldRenderOffScreen(SlideAnchorBlockEntity be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 192;
    }

    @Override
    public AABB getRenderBoundingBox(SlideAnchorBlockEntity be) {
        SlideInstance slide = be.instance();
        if (slide == null) return AABB.ofSize(be.getBlockPos().getCenter(), 48, 48, 48);
        // the Dark Carnival arch, gantries and stands reach a few blocks past the trough
        return slide.box.inflate(slide.geo.spec.isCarnival() ? 4.5D : 1.0D);
    }
}
