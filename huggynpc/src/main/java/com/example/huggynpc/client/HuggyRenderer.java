package com.example.huggynpc.client;

import com.example.huggynpc.HuggyEntity;
import com.example.huggynpc.HuggyNpcMod;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Renderer of one NPC model: its skinned mesh (models/entity/{id}.skin) and texture (textures/entity/{id}.png). */
public class HuggyRenderer extends MobRenderer<HuggyEntity, HuggyModel<HuggyEntity>> {
    private final ResourceLocation texture;

    public HuggyRenderer(EntityRendererProvider.Context context, HuggyNpcMod.NpcModel model) {
        super(context, new HuggyModel<>(ResourceLocation.fromNamespaceAndPath(HuggyNpcMod.MOD_ID, "models/entity/" + model.id() + ".skin"),
                        model.id().equals("doll_girl"), model.id().equals("lily")),
                Math.min(1.0F, model.width() * 0.6F));
        this.texture = ResourceLocation.parse(model.texture());
    }

    @Override
    public ResourceLocation getTextureLocation(HuggyEntity entity) {
        return this.texture;
    }
}
