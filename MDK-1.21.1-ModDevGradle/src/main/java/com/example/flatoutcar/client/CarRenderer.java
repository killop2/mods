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

/**
 * Рисует меш машины (assets/flatoutcar/meshes/car.json) с текстурой bmw_m3.png.
 * Колёса крутятся по пройденному пути, передние колёса и руль поворачиваются вместе с рулением.
 */
public class CarRenderer extends EntityRenderer<CarEntity> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(FlatoutCarMod.MOD_ID, "textures/entity/bmw_m3.png");
    private static final float MAX_STEER_DEG = 28.0F;
    private static final float STEERING_WHEEL_DEG = 120.0F;

    private final CarMesh mesh;

    public CarRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.mesh = CarMesh.load(context.getResourceManager());
        this.shadowRadius = 1.6F;
    }

    @Override
    public void render(CarEntity car, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        // Меш смотрит в +Z; yaw 0 у сущности тоже смотрит в +Z.
        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw));
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        float spin = car.getWheelSpin(partialTick);
        float steer = car.getSteerVisual(partialTick);
        int overlay = OverlayTexture.NO_OVERLAY;

        for (CarMesh.Part part : this.mesh.parts) {
            if (part.role == CarMesh.Role.BODY) {
                part.render(poseStack.last(), vc, packedLight, overlay);
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(part.px, part.py, part.pz);
            switch (part.role) {
                case WHEEL_FRONT -> {
                    poseStack.mulPose(Axis.YP.rotationDegrees(steer * MAX_STEER_DEG));
                    poseStack.mulPose(Axis.XP.rotation(spin));
                }
                case WHEEL_REAR -> poseStack.mulPose(Axis.XP.rotation(spin));
                case STEERING -> poseStack.mulPose(Axis.ZP.rotationDegrees(-steer * STEERING_WHEEL_DEG));
                default -> {
                }
            }
            part.render(poseStack.last(), vc, packedLight, overlay);
            poseStack.popPose();
        }
        poseStack.popPose();
        super.render(car, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CarEntity car) {
        return TEXTURE;
    }
}
