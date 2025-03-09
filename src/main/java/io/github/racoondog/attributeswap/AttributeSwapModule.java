package io.github.racoondog.attributeswap;

import com.google.common.base.Predicates;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.rusherhack.client.api.events.network.EventPacket;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.utils.InventoryUtils;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.notification.NotificationType;
import org.rusherhack.core.setting.BooleanSetting;

import java.lang.reflect.Method;
import java.util.Comparator;

public class AttributeSwapModule extends ToggleableModule {
	private final BooleanSetting attributeSwap = new BooleanSetting("Attribute Swap", "Enables attribute swapping.", true);
	private final BooleanSetting alwaysShieldbreak = new BooleanSetting("Always Shieldbreak", "Prioritize breaking shields over damage.", true)
		.setVisibility(this.attributeSwap::getValue);
	private final BooleanSetting infiniteDurability = new BooleanSetting("Infinite Durability", "Enables infinite durability.", true);

	private final Method packetWriteBufMethod;

	private int swapBack = -1;

	public AttributeSwapModule() {
		super("Attribute Swap", "Attribute swapping", ModuleCategory.COMBAT);

		this.registerSettings(
			this.attributeSwap,
			this.alwaysShieldbreak,
			this.infiniteDurability
		);

		Method targetMethod = null;
		for (Method method : ServerboundInteractPacket.class.getDeclaredMethods()) {
			if (method.getReturnType() == void.class && method.getParameterCount() == 1 && method.getParameterTypes()[0] == FriendlyByteBuf.class) {
				targetMethod = method;
				break;
			}
		}
		if (targetMethod == null) {
			throw new IllegalStateException("Could not reflect required method.");
		} else {
			targetMethod.setAccessible(true);
			packetWriteBufMethod = targetMethod;
		}
	}

	@Subscribe
	private void onAttack(EventPacket.Send event) {
		if (event.getPacket() instanceof ServerboundInteractPacket interactPacket) {
			InteractPacketInfo packetInfo = readPacketInfo(interactPacket);
			if (packetInfo.isAttack()) {
				ItemStack heldStack = mc.player.getMainHandItem();
				Entity target = mc.level.getEntity(packetInfo.entityId());

				if (this.attributeSwap.getValue() && target instanceof LivingEntity livingTarget) {
					if (this.alwaysShieldbreak.getValue() && livingTarget.isBlocking()) {
						// if already holding axe, dont swap
						if (heldStack.getItem() instanceof AxeItem) {
							return;
						}

						int axe = InventoryUtils.findItemHotbar(stack -> stack.getItem() instanceof AxeItem);

						if (axe != -1) {
							swap(axe);
							return;
						}
					}

					float currentAttackDamage = getAttackDamage(livingTarget, mc.player.getWeaponItem());

					// make sure we can't already one-shot the enemy
					if (currentAttackDamage < livingTarget.getHealth() + livingTarget.getAbsorptionAmount()) {
						int weapon = InventoryUtils.findItemHotbar(Predicates.alwaysTrue(), Comparator.comparingDouble(stack -> getAttackDamage(livingTarget, stack)));

						// make sure swapping actually increases damage dealt
						if (getAttackDamage(livingTarget, mc.player.getInventory().getItem(weapon)) > currentAttackDamage) {
							swap(weapon);
							return;
						}
					}
				}

				if (this.infiniteDurability.getValue() && heldStack.isDamageableItem()) {
					int itemResult = InventoryUtils.findItemHotbar(stack -> !stack.isDamageableItem());

					if (itemResult != -1) {
						swap(itemResult);
					} else {
						this.sendNotification(NotificationType.WARNING, "Cannot use infinite durability as there are no non-damageable items in hotbar.");
					}
				}
			}
		}
	}

	@Subscribe(stage = Stage.POST)
	private void onPostAttack(EventPacket.Send event) {
		if (event.getPacket() instanceof ServerboundInteractPacket interactPacket && readPacketInfo(interactPacket).isAttack()) {
			if (swapBack != -1) {
				mc.player.getInventory().selected = swapBack;
				swapBack = -1;
			}
		}
	}

	private InteractPacketInfo readPacketInfo(ServerboundInteractPacket interactPacket) {
		try {
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			packetWriteBufMethod.invoke(interactPacket, buffer);
			int entityId = buffer.readVarInt();
			boolean isAttack = buffer.readVarInt() == 1;
			return new InteractPacketInfo(entityId, isAttack);
		} catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

	private void swap(int slot) {
		if (slot != mc.player.getInventory().selected) {
			swapBack = mc.player.getInventory().selected;
			mc.player.getInventory().selected = slot;
		}
	}

	private record InteractPacketInfo(int entityId, boolean isAttack) {}

	/**
	 * Crosby's secret sauce
	 * Big ups to you if you recognize where this comes from
	 */
	private float getAttackDamage(LivingEntity target, ItemStack weapon) {
		float damage = (float) mc.player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		DamageSource damageSource = mc.level.damageSources().playerAttack(mc.player);

		// Get enchant damage
		Object2IntMap<Holder<Enchantment>> weaponEnchants = new Object2IntOpenHashMap<>();
		for (var entry : weapon.getEnchantments().entrySet()) {
			weaponEnchants.put(entry.getKey(), entry.getIntValue());
		}
		float enchantDamage = 0f;

		int sharpness = getEnchantmentLevel(weaponEnchants, Enchantments.SHARPNESS);
		if (sharpness > 0) {
			enchantDamage += 1 + 0.5f * (sharpness - 1);
		}

		int baneOfArthropods = getEnchantmentLevel(weaponEnchants, Enchantments.BANE_OF_ARTHROPODS);
		if (baneOfArthropods > 0 && target.getType().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) {
			enchantDamage += 2.5f * baneOfArthropods;
		}

		int impaling = getEnchantmentLevel(weaponEnchants, Enchantments.IMPALING);
		if (impaling > 0 && target.getType().is(EntityTypeTags.SENSITIVE_TO_IMPALING)) {
			enchantDamage += 2.5f * impaling;
		}

		int smite = getEnchantmentLevel(weaponEnchants, Enchantments.SMITE);
		if (smite > 0 && target.getType().is(EntityTypeTags.SENSITIVE_TO_SMITE)) {
			enchantDamage += 2.5f * smite;
		}

		// Factor charge
		float charge = mc.player.getAttackStrengthScale(0.5f);
		damage *= 0.2f + charge * charge * 0.8f;
		enchantDamage *= charge;

		if (weapon.getItem() instanceof MaceItem item) {
			float bonusDamage = item.getAttackDamageBonus(target, damage, damageSource);
			if (bonusDamage > 0f) {
				int density = getEnchantmentLevel(weaponEnchants, Enchantments.DENSITY);
				if (density > 0) bonusDamage += 0.5f * mc.player.fallDistance;
				damage += bonusDamage;
			}
		}

		// Factor critical hit
		if (charge > 0.9f && mc.player.fallDistance > 0f && !mc.player.onGround() && !mc.player.onClimbable() && !mc.player.isInWater() && !mc.player.hasEffect(MobEffects.BLINDNESS) && !mc.player.isPassenger()) {
			damage *= 1.5f;
		}

		damage += enchantDamage;

		// Difficulty scaling
		if (damageSource.scalesWithDifficulty()) {
			switch (mc.level.getDifficulty()) {
				case EASY     -> damage = Math.min(damage / 2 + 1, damage);
				case HARD     -> damage *= 1.5f;
			}
		}

		// Armor reduction
		damage = CombatRules.getDamageAfterAbsorb(target, damage, damageSource, (float) Math.floor(target.getAttributeValue(Attributes.ARMOR)), (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));

		// Resistance reduction
		MobEffectInstance resistance = mc.player.getEffect(MobEffects.DAMAGE_RESISTANCE);
		if (resistance != null) {
			int lvl = resistance.getAmplifier() + 1;
			damage *= (1 - (lvl * 0.2f));
		}

		// Protection reduction
		if (!damageSource.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			int damageProtection = 0;

			for (ItemStack stack : target.getArmorAndBodyArmorSlots()) {
				Object2IntMap<Holder<Enchantment>> armorEnchants = new Object2IntOpenHashMap<>();
				for (var entry : stack.getEnchantments().entrySet()) {
					armorEnchants.put(entry.getKey(), entry.getIntValue());
				}

				int protection = getEnchantmentLevel(armorEnchants, Enchantments.PROTECTION);
				if (protection > 0) {
					damageProtection += protection;
				}

				int fireProtection = getEnchantmentLevel(armorEnchants, Enchantments.FIRE_PROTECTION);
				if (fireProtection > 0 && damageSource.is(DamageTypeTags.IS_FIRE)) {
					damageProtection += 2 * fireProtection;
				}

				int blastProtection = getEnchantmentLevel(armorEnchants, Enchantments.BLAST_PROTECTION);
				if (blastProtection > 0 && damageSource.is(DamageTypeTags.IS_EXPLOSION)) {
					damageProtection += 2 * blastProtection;
				}

				int projectileProtection = getEnchantmentLevel(armorEnchants, Enchantments.PROJECTILE_PROTECTION);
				if (projectileProtection > 0 && damageSource.is(DamageTypeTags.IS_PROJECTILE)) {
					damageProtection += 2 * projectileProtection;
				}

				int featherFalling = getEnchantmentLevel(armorEnchants, Enchantments.FEATHER_FALLING);
				if (featherFalling > 0 && damageSource.is(DamageTypeTags.IS_FALL)) {
					damageProtection += 3 * featherFalling;
				}
			}

			damage = CombatRules.getDamageAfterMagicAbsorb(damage, damageProtection);
		}

		return Math.max(damage, 0);
	}

	private int getEnchantmentLevel(Object2IntMap<Holder<Enchantment>> enchantments, ResourceKey<Enchantment> enchantment) {
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : Object2IntMaps.fastIterable(enchantments)) {
			if (entry.getKey().is(enchantment)) return entry.getIntValue();
		}
		return 0;
	}
}
