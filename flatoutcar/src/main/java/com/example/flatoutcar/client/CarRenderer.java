package com.example.flatoutcar.client;

import com.example.flatoutcar.CarEntity;
import com.example.flatoutcar.FlatoutCarMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

public class CarRenderer extends EntityRenderer<CarEntity> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(FlatoutCarMod.MOD_ID, "car"), "main");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(FlatoutCarMod.MOD_ID, "textures/entity/car.png");

    private final ModelPart root;

    public CarRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.root = context.bakeLayer(LAYER);
        this.shadowRadius = 1.0F;
    }

    /** Модель из кубов. Значения texOffs должны совпадать с генератором текстуры (128x128). */
    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition rootDef = mesh.getRoot();
        PartDefinition car = rootDef.addOrReplaceChild("car", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-12F, -10F, -20F, 24F, 6F, 40F)      // кузов
                .texOffs(0, 48).addBox(-10F, -16F, -4F, 20F, 6F, 16F)      // кабина
                .texOffs(72, 48).addBox(-9F, -16F, -6F, 18F, 6F, 2F)       // лобовое стекло
                .texOffs(0, 72).addBox(-12F, -18F, 16F, 24F, 2F, 4F)       // спойлер
                .texOffs(0, 84).addBox(-10F, -16F, 17F, 2F, 6F, 2F)        // стойка
                .texOffs(0, 84).addBox(8F, -16F, 17F, 2F, 6F, 2F),         // стойка
                PartPose.offset(0F, 24F, 0F));

        CubeListBuilder wheel = CubeListBuilder.create().texOffs(60, 72).addBox(-2F, -4F, -4F, 4F, 8F, 8F);
        car.addOrReplaceChild("wheel_fl", wheel, PartPose.offset(-13F, -4F, -13F));
        car.addOrReplaceChild("wheel_fr", wheel, PartPose.offset(13F, -4F, -13F));
        car.addOrReplaceChild("wheel_rl", wheel, PartPose.offset(-13F, -4F, 13F));
        car.addOrReplaceChild("wheel_rr", wheel, PartPose.offset(13F, -4F, 13F));
        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public void render(CarEntity car, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - entityYaw));
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.501F, 0.0F);
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        this.root.render(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY, -1);
        poseStack.popPose();
        super.render(car, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(CarEntity car) {
        return TEXTURE;
    }
}
