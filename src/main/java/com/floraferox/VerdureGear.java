package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.DamageClass;
import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Kind;
import java.util.Map;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The 1.1 gear at work (docs/DESIGN.md, section 7): the class bonuses (the family potions' effects, the charms, the
 * armour sets), Moon's Spite, the platings' fire and frost proofing, the third-tier sets' perks, and where the genes are
 * found. A blow's class is VerdureDamage's; a summon's blow counts for its owner's bonuses.
 */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class VerdureGear {
    private VerdureGear() { }

    public static Kind kindOf(DamageClass cls) {
        for (Kind k : Kind.values()) if (k.weakness == cls) return k;
        return null;
    }

    // ---- the armour sets -------------------------------------------------------------------------------------------
    public static int platingTier(Item item) {
        for (int t = 1; t <= 3; t++) if (VerdureItems.PLATINGS[t].get() == item) return t;
        return 0;
    }

    public static int leggingsTier(Item item) {
        for (int t = 1; t <= 3; t++) if (VerdureItems.LEGGINGS[t].get() == item) return t;
        return 0;
    }

    public static int bootsTier(Item item) {
        for (int t = 1; t <= 3; t++) if (VerdureItems.BOOTS[t].get() == item) return t;
        return 0;
    }

    public static Herb helmet(Item item) {
        for (Map.Entry<Herb, net.neoforged.neoforge.registries.DeferredItem<Item>> e : VerdureItems.HELMETS.entrySet()) if (e.getValue().get() == item) return e.getKey();
        return null;
    }

    /** The full set a body wears (a class helmet with the plating, leggings (1.9) and boots of its tier), or null. */
    public static Herb set(LivingEntity body) {
        Herb h = helmet(body.getItemBySlot(EquipmentSlot.HEAD).getItem());
        if (h == null) return null;
        return platingTier(body.getItemBySlot(EquipmentSlot.CHEST).getItem()) == h.tier && leggingsTier(body.getItemBySlot(EquipmentSlot.LEGS).getItem()) == h.tier
                && bootsTier(body.getItemBySlot(EquipmentSlot.FEET).getItem()) == h.tier ? h : null;
    }

    public static int setTier(LivingEntity body, DamageClass cls) {
        Herb s = set(body);
        return s != null && s.kind.weakness == cls ? s.tier : 0;
    }

    /** How long a weapon takes to recover for this player: the third tier's mage, ranger and melee sets shorten it. */
    public static float cooldownFactor(Player player, DamageClass cls) {
        return setTier(player, cls) == 3 ? SET3_COOLDOWN : 1F;
    }

    /** The family's charm, or (1.3) one of the charms made of its new hunters' trophies, which keep its bonus. */
    public static boolean hasCharm(Player player, Kind kind) {
        if (carries(player, VerdureItems.CHARMS.get(kind).get())) return true;
        for (Ferox f : Ferox.values()) if (f.kind == kind && hasCharm(player, f)) return true;
        return false;
    }

    /** The charm made of this one's trophy, for its perk. */
    public static boolean hasCharm(Player player, Ferox f) {
        return carries(player, VerdureItems.FEROX_CHARMS.get(f).get());
    }

    /** Carried anywhere in the inventory, or (1.10) worn in a Curios charm slot. */
    private static boolean carries(Player player, Item item) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (player.getInventory().getItem(i).is(item)) return true;
        return net.neoforged.fml.ModList.get().isLoaded("curios") && CuriosCompat.worn(player, item);
    }

    /** The player whose bonuses a blow carries: the attacker, or a summon's owner. */
    public static Player responsible(DamageSource source, DamageClass cls) {
        Entity cause = source.getEntity();
        if (cause instanceof Player p) return p;
        if (cls == DamageClass.SUMMON && cause instanceof OwnableEntity o && o.getOwner() instanceof Player p) return p;
        return null;
    }

    /** The class bonus (a fraction) a player's effects and charms give to a class's blows. */
    public static float bonus(Player p, DamageClass cls) {
        Kind k = kindOf(cls);
        if (k == null) return 0F;
        MobEffectInstance fx = p.getEffect(VerdurePotions.classEffect(k));
        return classBonus(fx == null ? 0 : fx.getAmplifier() + 1, hasCharm(p, k));
    }

    @SubscribeEvent
    public static void incoming(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        DamageSource source = e.getSource();
        if (source.is(DamageTypeTags.IS_FIRE) && platingTier(victim.getItemBySlot(EquipmentSlot.CHEST).getItem()) >= 2) {
            e.setCanceled(true);                                    // Pyre and Rimebark Plate: fire can't hurt you
            victim.clearFire();
            return;
        }
        DamageClass cls = VerdureDamage.classify(source);
        if (cls == DamageClass.OTHER) return;
        float mult = 1F;
        Player p = responsible(source, cls);
        if (p != null) {
            float best = bonus(p, cls);
            for (DamageClass c : VerdureDamage.classes(source)) {     // 1.16: a two-class hit takes its better class, not both
                float b = bonus(p, c);
                if (b > best) {
                    best = b;
                    cls = c;
                }
            }
            mult += best;
            if (cls == DamageClass.MAGIC && hasCharm(p, Ferox.SNAP_DRAKE) && p.getRandom().nextFloat() < SNAPJAW_CRIT) mult *= crit(victim);
            if (victim != p) charmPerks(p, victim, cls);
        }
        if (victim.hasEffect(VerdurePotions.MOONS_SPITE)) {
            mult *= SCORN_MULTIPLIER;                               // the lash's mark (set after its own blow): the next one cuts deeper
            victim.removeEffect(VerdurePotions.MOONS_SPITE);
        }
        if (mult != 1F) e.setAmount(e.getAmount() * mult);
    }

    private static float crit(LivingEntity victim) {
        if (victim.level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY(0.6), victim.getZ(), 10, 0.3, 0.3, 0.3, 0.2);
        return CRIT_MULTIPLIER;
    }

    /** 1.3: the Hookburr and Sundew Charms slow what their class hits; the Lantern Charm drinks from summon hits. */
    static void charmPerks(Player p, LivingEntity victim, DamageClass cls) {
        if ((cls == DamageClass.RANGED && hasCharm(p, Ferox.BURR_CRAWLER)) || (cls == DamageClass.MELEE && hasCharm(p, Ferox.DEW_CRAB))) {
            victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, CHARM_SLOW_TICKS, 0), p);
        }
        if (cls == DamageClass.SUMMON && hasCharm(p, Ferox.LANTERN_LEECH)) p.heal(LANTERN_HEAL);
    }

    /** The Cattail Charm: sneaking in the air, you drift down. */
    static void cattail(Player p) {
        if (p.isShiftKeyDown() && !p.onGround() && p.getDeltaMovement().y < 0 && hasCharm(p, Ferox.REED_WRETCH)) {
            p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 10, 0, true, false, true));
        }
    }

    /** Whips recover sooner with the Orchid Charm. */
    public static float lashFactor(Player p) {
        return hasCharm(p, Ferox.ORCHID_MANTIS) ? ORCHID_COOLDOWN : 1F;
    }

    /** A player's pets within their whips' command range. */
    static java.util.List<Mob> petsOf(Player p) {
        return p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(LASH_COMMAND_RANGE), m -> m instanceof OwnableEntity o && o.getOwner() == p);
    }

    static void pets(Player p, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        for (Mob pet : petsOf(p)) pet.addEffect(new MobEffectInstance(effect, 50, 0, true, true));
    }

    /**
     * The Cattail Charm every few ticks; every second the Orchid Charm (pets run faster) and the third tier's melee set
     * (Speed, and 1.16 swings that recover as much faster as its Cleaver's slash; Haste is gone, Oculifelis) and summoner
     * set (pets strengthened).
     */
    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.level().isClientSide()) return;
        if (p.tickCount % 5 == 0) cattail(p);
        if (p.tickCount % 20 != 0) return;
        if (hasCharm(p, Ferox.ORCHID_MANTIS)) pets(p, MobEffects.SPEED);
        Herb s = set(p);
        swingSpeed(p, s != null && s.tier == 3 && s.kind == Kind.MIRE_GHAST);
        if (s == null || s.tier != 3) return;
        if (s.kind == Kind.MIRE_GHAST) {
            p.addEffect(new MobEffectInstance(MobEffects.SPEED, 50, 0, true, false, true));
        } else if (s.kind == Kind.MOON_TICK) {
            pets(p, MobEffects.STRENGTH);
        }
    }

    static final net.minecraft.resources.Identifier SWING_SPEED = FloraFerox.id("rimebark_melee_swing");

    /** The third melee set's quicker swings: attack speed up by as much as its recovery is shortened. */
    static void swingSpeed(Player p, boolean on) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance a = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
        if (a == null || a.hasModifier(SWING_SPEED) == on) return;
        if (on) a.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(SWING_SPEED, 1F / SET3_COOLDOWN - 1F,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        else a.removeModifier(SWING_SPEED);
    }

    // ---- the genes ---------------------------------------------------------------------------------------------------
    @SubscribeEvent
    public static void drops(LivingDropsEvent e) {
        LivingEntity dead = e.getEntity();
        if (!(e.getSource().getEntity() instanceof Player) || dead.level().isClientSide()) return;
        EntityType<?> t = dead.getType();
        Item gene = t == EntityType.BLAZE || t == EntityType.MAGMA_CUBE ? VerdureItems.EMBER_GRAFT.get() : t == EntityType.STRAY ? VerdureItems.RIME_GRAFT.get() : null;
        if (gene != null && dead.getRandom().nextFloat() < GENE_DROP_CHANCE) {
            e.getDrops().add(new ItemEntity(dead.level(), dead.getX(), dead.getY(), dead.getZ(), new ItemStack(gene)));
        }
    }

    @SubscribeEvent
    public static void iceBroken(BreakBlockEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getPlayer().isCreative()) return;
        if (!e.getState().is(Blocks.PACKED_ICE) && !e.getState().is(Blocks.BLUE_ICE)) return;
        ItemStack tool = e.getPlayer().getMainHandItem();
        if (EnchantmentHelper.getItemEnchantmentLevel(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH), tool) > 0) return;
        if (level.getRandom().nextInt(ICE_GENE_ONE_IN) == 0) Block.popResource(level, e.getPos(), new ItemStack(VerdureItems.RIME_GRAFT.get()));
    }
}
