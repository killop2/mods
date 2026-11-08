package com.example.wheelofwonder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Telekinesis Orb: right click an NPC to lift it into the air as a ragdoll and steer it with your gaze, spin it,
 * swing it round you in a circle, and let go (it flies with the speed of your last swing, or just falls). The clicks
 * are caught on the client (TelekinesisClient); TelekinesisServer moves the body.
 */
public class TelekinesisOrbItem extends Item {
    public TelekinesisOrbItem(Properties props) {
        super(props);
    }

    public static boolean isOrb(ItemStack stack) {
        return stack.getItem() instanceof TelekinesisOrbItem;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wheelofwonder.telekinesis_orb.desc").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable("item.wheelofwonder.telekinesis_orb.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.telekinesis_orb.desc3").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.telekinesis_orb.desc4").withStyle(ChatFormatting.GRAY));
    }
}
