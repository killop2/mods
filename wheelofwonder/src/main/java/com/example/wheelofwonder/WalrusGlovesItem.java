package com.example.wheelofwonder;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.List;

/**
 * Walrus PUNCH! boxing gloves, worn on both hands while held. A normal punch hits harder than a bare fist; the
 * signature combo (left click on an NPC, when not on cooldown) is run by PunchServer / client PunchController.
 */
public class WalrusGlovesItem extends Item {
    public WalrusGlovesItem(Properties props) {
        super(props.attributes(ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID, 6.0D, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID, -1.2D, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build()));
    }

    public static boolean isGloves(ItemStack stack) {
        return stack.getItem() instanceof WalrusGlovesItem;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wheelofwonder.walrus_punch.desc").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.wheelofwonder.walrus_punch.desc2").withStyle(ChatFormatting.GRAY));
    }
}
