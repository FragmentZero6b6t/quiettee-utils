package com.quiettee.utils.util;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.world.GameMode;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class ExplosionReductions {
    private static final Object2IntMap<RegistryEntry<Enchantment>> enchantmentsScratch = new Object2IntOpenHashMap<>();

    private final LivingEntity target;
    private final boolean immune;
    private final float armor, toughness;
    private final int protection;
    private final float resistanceFactor;

    private ExplosionReductions(LivingEntity target) {
        this.target = target;

        immune = target instanceof PlayerEntity player && EntityUtils.getGameMode(player) == GameMode.CREATIVE && !(player instanceof FakePlayerEntity);
        armor = (float) Math.floor(target.getAttributeValue(EntityAttributes.ARMOR));
        toughness = (float) target.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);

        StatusEffectInstance resistance = target.getStatusEffect(StatusEffects.RESISTANCE);
        resistanceFactor = resistance == null ? 1 : (1 - (resistance.getAmplifier() + 1) * 0.2f);

        int damageProtection = 0;
        if (!explosionSource().isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            for (EquipmentSlot slot : AttributeModifierSlot.ARMOR) {
                ItemStack stack = target.getEquippedStack(slot);
                Utils.getEnchantments(stack, enchantmentsScratch);

                int prot = Utils.getEnchantmentLevel(enchantmentsScratch, Enchantments.PROTECTION);
                if (prot > 0) damageProtection += prot;

                int blast = Utils.getEnchantmentLevel(enchantmentsScratch, Enchantments.BLAST_PROTECTION);
                if (blast > 0) damageProtection += 2 * blast;
            }
        }
        protection = damageProtection;
    }

    public static ExplosionReductions of(LivingEntity target) {
        return new ExplosionReductions(target);
    }

    private static DamageSource explosionSource() {
        return mc.world.getDamageSources().explosion(null);
    }

    public float apply(float damage) {
        if (immune) return 0;

        DamageSource source = explosionSource();

        if (source.isScaledWithDifficulty()) {
            switch (mc.world.getDifficulty()) {
                case EASY -> damage = Math.min(damage / 2 + 1, damage);
                case HARD -> damage *= 1.5f;
                default -> { }
            }
        }

        damage = DamageUtil.getDamageLeft(target, damage, source, armor, toughness);
        damage = Math.max(damage * resistanceFactor, 0);
        damage = DamageUtil.getInflictedDamage(damage, protection);

        return Math.max(damage, 0);
    }
}
