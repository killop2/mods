package com.example.huggynpc.client;

import com.example.huggynpc.HuggyEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Renderer of Verity, the pixel ball (VerityModel); a small round shadow. */
public class VerityRenderer extends MobRenderer<HuggyEntity, VerityModel<HuggyEntity>> {
    public VerityRenderer(EntityRendererProvider.Context context) {
        super(context, new VerityModel<>(), 0.6F);
    }

    @Override
    public ResourceLocation getTextureLocation(HuggyEntity entity) {
        return VerityMesh.TEXTURE;
    }
}
