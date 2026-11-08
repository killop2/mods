package com.example.flatoutcar.client;

import com.example.flatoutcar.CarEntity;
import com.example.flatoutcar.FlatoutCarMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Рисует low-poly меш машины (assets/flatoutcar/meshes/car.json).
 * Цвета берутся из вершин, текстура — просто белый квадрат.
 * Колёса крутятся по пройденному пути, передние поворачиваются вместе с рулём.
 */
public class CarRenderer extends EntityRenderer<CarEntity> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(FlatoutCarMod.MOD_ID, "textures/entity/car_paint.png");
    private static final float MAX_STEER_DEG = 28.0F;

    private final CarMesh mesh;

    public CarRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.mesh = CarMesh.load(context.getResourceManager());
        this.shadowRadius = 0.9F;
    }

    @Override
    public void render(CarEntity car, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        // Меш смотрит в +Z; yaw 0 у сущности тоже смотрит в +Z.
        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw));
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        float spin = car.getWheelSpin(partialTick);
        float steer = car.getSteerVisual(partialTick) * MAX_STEER_DEG;
        int overlay = OverlayTexture.NO_OVERLAY;

        for (CarMesh.Part part : this.mesh.parts) {
            if (part.role == CarMesh.Role.BODY) {
                part.render(poseStack.last(), vc, packedLight, overlay);
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(part.px, part.py, part.pz);
            if (part.role == CarMesh.Role.WHEEL_FRONT) {
                poseStack.mulPose(Axis.YP.rotationDegrees(steer));
            }
            poseStack.mulPose(Axis.XP.rotation(spin));
            part.render(poseStack.last(), vc, packedLight, overlay);
            poseStack.popPose();
        }
        poseStack.popPose();
        super.render(car, Mth.wrapDegrees(entityYaw), partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CarEntity car) {
        return TEXTURE;
    }
}
