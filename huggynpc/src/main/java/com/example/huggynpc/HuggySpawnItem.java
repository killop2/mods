package com.example.huggynpc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;

/**
 * Spawn egg of one NPC model. With Custom NPCs installed it places a real Custom NPC that looks like the model
 * (listed with the other NPCs, editable with the NPC Wand); without Custom NPCs it spawns the plain model mob.
 */
public class HuggySpawnItem extends DeferredSpawnEggItem {
    private final HuggyNpcMod.NpcModel model;

    public HuggySpawnItem(HuggyNpcMod.NpcModel model, Item.Properties props) {
        super(model.type(), model.eggColor(), model.eggSpots(), props);
        this.model = model;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!CnpcSpawner.available()) return super.useOn(ctx);
        if (!(ctx.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        BlockPos clicked = ctx.getClickedPos();
        BlockPos pos = level.getBlockState(clicked).getCollisionShape(level, clicked).isEmpty() ? clicked : clicked.relative(ctx.getClickedFace());
        Player player = ctx.getPlayer();
        float yaw = player != null ? player.getYRot() + 180.0F : 0.0F;
        Entity npc = CnpcSpawner.spawn(level, pos, yaw, this.model);
        if (npc == null) return super.useOn(ctx);
        level.gameEvent(player, GameEvent.ENTITY_PLACE, pos);
        if (player == null || !player.getAbilities().instabuild) ctx.getItemInHand().shrink(1);
        return InteractionResult.CONSUME;
    }
}
