package com.example.wheelofwonder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Chaos Meteor: right click calls a burning meteor down onto the block you look at; it rolls on, smashing through
 * trees and ice and throwing NPCs, then bursts. The click is caught on the client (ClientSetup) and sent as
 * BallistaRequest.METEOR, so aiming at an NPC never opens its dialog instead.
 */
public class ChaosMeteorItem extends Item {
    public ChaosMeteorItem(Properties props) {
        super(props);
    }

    public static boolean isMeteor(ItemStack stack) {
        return stack.getItem() instanceof ChaosMeteorItem;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wheelofwonder.chaos_meteor.desc").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.wheelofwonder.chaos_meteor.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wheelofwonder.chaos_meteor.desc3").withStyle(ChatFormatting.GRAY));
    }
}
