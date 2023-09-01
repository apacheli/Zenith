package dev.shadowsoffire.apotheosis.adventure.affix.effect;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.shadowsoffire.apotheosis.Apoth.Affixes;
import dev.shadowsoffire.apotheosis.adventure.affix.Affix;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.adventure.affix.AffixType;
import dev.shadowsoffire.apotheosis.adventure.affix.socket.gem.bonus.GemBonus;
import dev.shadowsoffire.apotheosis.adventure.loot.LootCategory;
import dev.shadowsoffire.apotheosis.adventure.loot.LootRarity;
import dev.shadowsoffire.placebo.json.PSerializer;
import dev.shadowsoffire.placebo.util.StepFunction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Loot Pinata
 */
public class FestiveAffix extends Affix {

    public static Codec<FestiveAffix> CODEC = RecordCodecBuilder.create(inst -> inst
        .group(
            GemBonus.VALUES_CODEC.fieldOf("values").forGetter(a -> a.values))
        .apply(inst, FestiveAffix::new));

    public static final PSerializer<FestiveAffix> SERIALIZER = PSerializer.fromCodec("Festive Affix", CODEC);

    protected final Map<LootRarity, StepFunction> values;

    public FestiveAffix(Map<LootRarity, StepFunction> values) {
        super(AffixType.ABILITY);
        this.values = values;
    }

    @Override
    public void addInformation(ItemStack stack, LootRarity rarity, float level, Consumer<Component> list) {
        list.accept(Component.translatable("affix." + this.getId() + ".desc", fmt(100 * this.getTrueLevel(rarity, level))));
    }

    @Override
    public boolean canApplyTo(ItemStack stack, LootCategory cat, LootRarity rarity) {
        return cat.isLightWeapon() && this.values.containsKey(rarity);
    }

    private float getTrueLevel(LootRarity rarity, float level) {
        return this.values.get(rarity).get(level);
    }

    private static String MARKER = "apoth.equipment";

    // EventPriority.LOW
    public void markEquipment(LivingEntity entity, DamageSource source) {
        if (entity instanceof Player || entity.getCustomData().getBoolean("apoth.no_pinata")) return;
        entity.getAllSlots().forEach(i -> {
            if (!i.isEmpty()) i.getOrCreateTag().putBoolean(MARKER, true);
        });
    }

    // EventPriority.LOW
    public void drops(LivingEntity target, DamageSource source, Collection<ItemEntity> drops) {
        if (target instanceof Player || target.getCustomData().getBoolean("apoth.no_pinata")) return;
        if (source.getEntity() instanceof Player player && !drops.isEmpty()) {
            AffixInstance inst = AffixHelper.getAffixes(player.getMainHandItem()).get(Affixes.FESTIVE);
            if (inst != null && inst.isValid() && player.level().random.nextFloat() < this.getTrueLevel(inst.rarity().get(), inst.level())) {
                player.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 4.0F,
                    (1.0F + (player.level().random.nextFloat() - player.level().random.nextFloat()) * 0.2F) * 0.7F);
                ((ServerLevel) player.level()).sendParticles(ParticleTypes.EXPLOSION_EMITTER, target.getX(), target.getY(), target.getZ(), 2, 1.0D, 0.0D, 0.0D, 0);
                for (ItemEntity item : drops) {
                    if (item.getItem().hasTag() && item.getItem().getTag().contains(MARKER)) continue;
                    for (int i = 0; i < 20; i++) {
                        drops.add(new ItemEntity(player.level(), item.getX(), item.getY(), item.getZ(), item.getItem().copy()));
                    }
                }
                for (ItemEntity item : drops) {
                    if (!item.getItem().getItem().canBeDepleted()) {
                        item.setPos(target.getX(), target.getY(), target.getZ());
                        item.setDeltaMovement(-0.3 + target.level().random.nextDouble() * 0.6, 0.3 + target.level().random.nextDouble() * 0.3, -0.3 + target.level().random.nextDouble() * 0.6);
                    }
                }
            }
        }
    }

    // Lowest prio + receive cancelled
    public void removeMarker(Collection<ItemEntity> drops) {
        drops.stream().forEach(ent -> {
            ItemStack s = ent.getItem();
            if (s.hasTag() && s.getTag().contains(MARKER)) {
                s.getTag().remove(MARKER);
                if (s.getTag().isEmpty()) s.setTag(null);
            }
            ent.setItem(s);
        });
    }

    @Override
    public PSerializer<? extends Affix> getSerializer() {
        return SERIALIZER;
    }
}