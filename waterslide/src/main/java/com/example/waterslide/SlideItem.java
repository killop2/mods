package com.example.waterslide;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** The spawner: left click (main-hand swing, handled on the server) places a slide in front of the player. */
public class SlideItem extends Item {
    private final int type;

    public SlideItem(Properties props, int type) {
        super(props);
        this.type = type;
    }

    @Override
    public boolean onEntitySwing(ItemStack stack, LivingEntity entity, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND && entity instanceof ServerPlayer player
                && player.level() instanceof ServerLevel level && !player.getCooldowns().isOnCooldown(this)) {
            player.getCooldowns().addCooldown(this, 20);
            String key = SlideManager.tryPlace(level, player, type);
            if (key.equals("message.waterslide.placed") && !player.getAbilities().instabuild) stack.shrink(1);
            player.displayClientMessage(Component.translatable(key), true);
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext ctx, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.waterslide.water_slide.tip1"));
        tip.add(Component.translatable("item.waterslide.water_slide.tip2"));
        if (SlideTypes.get(type).spec.isCarnival()) {
            tip.add(Component.translatable("item.waterslide.dark_carnival_slide.tip3").withStyle(net.minecraft.ChatFormatting.DARK_PURPLE));
        }
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }
}
