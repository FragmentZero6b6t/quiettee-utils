package com.quiettee.utils.modules.combat;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;

final class BoatShotDefense {
    static int drawTicks(LivingEntity target,ItemStack bow,double burst) {
        var levels=new Object2IntOpenHashMap<RegistryEntry<Enchantment>>();
        int protection=0;
        for(EquipmentSlot slot:AttributeModifierSlot.ARMOR) {
            Utils.getEnchantments(target.getEquippedStack(slot),levels);
            protection+=Utils.getEnchantmentLevel(levels,Enchantments.PROTECTION)
                +2*Utils.getEnchantmentLevel(levels,Enchantments.PROJECTILE_PROTECTION);
        }
        Utils.getEnchantments(bow,levels);
        int power=Utils.getEnchantmentLevel(levels,Enchantments.POWER);
        var resistance=target.getStatusEffect(StatusEffects.RESISTANCE);
        return BoatShotDamage.drawTicks(burst,power,new BoatShotDamage.Defense(
            target.getHealth()+target.getAbsorptionAmount(),target.getArmor(),target.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS),
            protection,resistance==null?0:resistance.getAmplifier()+1));
    }
}
