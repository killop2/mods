package com.example.wheelofwonder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Strange's Hammer ("Молот Стрэнджа"): right click an NPC and the sorcerer comes through a portal, impales it on
 * spikes out of the ground and smashes it off them with a hammer flown in through a second portal. The click is
 * caught on the client (StrangeController); StrangeServer and StrangeEntity run it.
 */
public class StrangeHammerItem extends Item {
    public StrangeHammerItem(Properties props) {
        super(props);
    }

    public static boolean isHammer(ItemStack stack) {
        return stack.getItem() instanceof StrangeHammerItem;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wheelofwonder.strange_hammer.desc").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.wheelofwonder.strange_hammer.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.strange_hammer.desc3").withStyle(ChatFormatting.GRAY));
    }
}
