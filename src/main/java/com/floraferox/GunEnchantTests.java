package com.floraferox;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.neoforge.common.util.FakePlayer;

/** 1.8: the seed guns' enchantments (GunEnchants). Registered with the rest (VerdureGameTests.TESTS). */
final class GunEnchantTests {
    private GunEnchantTests() { }

    private static final BlockPos MIDDLE = new BlockPos(16, 1, 16);

    private static Holder<Enchantment> holder(ServerLevel level, ResourceKey<Enchantment> key) {
        return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    private static ItemStack gun(ServerLevel level, int tier, Object... enchants) {
        ItemStack gun = new ItemStack(VerdureItems.WEAPONS.get(tier == 1 ? Herb.PULSEROOT : tier == 2 ? Herb.of(VerdureRules.Kind.PULSE_GNAT, 2) : Herb.of(VerdureRules.Kind.PULSE_GNAT, 3)).get());
        for (int i = 0; i < enchants.length; i += 2) {
            @SuppressWarnings("unchecked") ResourceKey<Enchantment> key = (ResourceKey<Enchantment>) enchants[i];
            gun.enchant(holder(level, key), (Integer) enchants[i + 1]);
        }
        return gun;
    }

    private static Seedshot lastShot(GameTestHelper h) {
        AABB around = new AABB(h.absolutePos(MIDDLE)).inflate(16);
        return h.getLevel().getEntities(VerdureEntities.SHOT.get(), around, e -> true).stream()
                .max((a, b) -> Integer.compare(a.getId(), b.getId())).orElse(null);
    }

    private static void clearShots(GameTestHelper h) {
        h.getLevel().getEntities(VerdureEntities.SHOT.get(), new AABB(h.absolutePos(MIDDLE)).inflate(16), e -> true).forEach(e -> e.discard());
    }

    /**
     * The enchantments are registered for the guns, keep their exclusive sets (Quickdraw against Bloom Shot; Infinity
     * against Seed Hopper, Weedkiller, Bloom Shot and Heartmend, but not against Heartsight or Quickdraw), bow enchantments
     * stay off, the guns have no item glint, and Weedkiller adds 2.5 a level against plant creatures only.
     */
    static void gunEnchantmentsAreData(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ItemStack gun = gun(level, 1);
        for (ResourceKey<Enchantment> k : java.util.List.of(GunEnchants.HEARTSIGHT, GunEnchants.SEED_HOPPER, GunEnchants.WEEDKILLER,
                GunEnchants.QUICKDRAW, GunEnchants.BLOOM_SHOT, GunEnchants.HEARTMEND, GunEnchants.INFINITY)) {
            h.assertTrue(holder(level, k).value().canEnchant(gun), k + " goes on the Pulse Pistol");
        }
        h.assertFalse(holder(level, Enchantments.POWER).value().canEnchant(gun), "Power doesn't go on a seed gun");
        h.assertFalse(Enchantment.areCompatible(holder(level, GunEnchants.QUICKDRAW), holder(level, GunEnchants.BLOOM_SHOT)), "Quickdraw and Bloom Shot exclude each other");
        for (ResourceKey<Enchantment> k : java.util.List.of(GunEnchants.SEED_HOPPER, GunEnchants.WEEDKILLER, GunEnchants.BLOOM_SHOT, GunEnchants.HEARTMEND)) {
            h.assertFalse(Enchantment.areCompatible(holder(level, GunEnchants.INFINITY), holder(level, k)), "Infinity excludes " + k);
        }
        h.assertTrue(Enchantment.areCompatible(holder(level, GunEnchants.INFINITY), holder(level, GunEnchants.HEARTSIGHT)), "Infinity goes with Heartsight");
        h.assertTrue(Enchantment.areCompatible(holder(level, GunEnchants.INFINITY), holder(level, GunEnchants.QUICKDRAW)), "Infinity goes with Quickdraw");
        ItemStack shiny = gun(level, 1, GunEnchants.HEARTSIGHT, 1);
        h.assertFalse(shiny.hasFoil(), "an enchanted seed gun has no item glint (only its heart shimmers)");
        ItemStack weed = gun(level, 1, GunEnchants.WEEDKILLER, 3);
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -4), 0F, 0F);
        Zombie zombie = VerdureGearTests.mob(h, EntityType.ZOMBIE, new Vec3(2, 0, 0));
        Mob plant = VerdureGearTests.mob(h, VerdureEntities.PULSE_GNAT.get(), new Vec3(-2, 0, 0));
        var src = level.damageSources().playerAttack(fp);
        float onPlant = EnchantmentHelper.modifyDamage(level, weed, plant, src, 5F);
        float onZombie = EnchantmentHelper.modifyDamage(level, weed, zombie, src, 5F);
        h.assertTrue(Math.abs(onPlant - 12.5F) < 0.01F, "Weedkiller III: 5 + 7.5 against a Pulse Gnat, got " + onPlant);
        h.assertTrue(onZombie == 5F, "Weedkiller does nothing to a zombie, got " + onZombie);
        zombie.discard();
        plant.discard();
        h.succeed();
    }

    /**
     * What each does to a shot: Infinity fires with no pellets; Seed Hopper saves about a third of them; Quickdraw III
     * fires faster; Bloom Shot fires a slower Bloom shot that bursts; Heartmend's shot passes through a zombie and heals a
     * pig on the shooter's team.
     */
    static void gunEnchantmentsChangeTheShot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -4), 0F, 0F);
        ItemStack inf = gun(level, 1, GunEnchants.INFINITY, 1);
        fp.setItemInHand(InteractionHand.MAIN_HAND, inf);
        h.assertTrue(inf.getItem().use(level, fp, InteractionHand.MAIN_HAND) == InteractionResult.SUCCESS, "Infinity fires with no Seed Pellets");

        ItemStack plain = gun(level, 1);
        fp.setItemInHand(InteractionHand.MAIN_HAND, plain);
        fp.getInventory().add(new ItemStack(VerdureItems.SEED_PELLET.get(), 1));
        plain.getItem().use(level, fp, InteractionHand.MAIN_HAND);
        double plainSpeed = lastShot(h).getDeltaMovement().length();
        h.assertTrue(lastShot(h).mode() == Seedshot.PLAIN, "a plain gun fires a plain shot");

        ItemStack quick = gun(level, 1, GunEnchants.QUICKDRAW, 3);
        fp.setItemInHand(InteractionHand.MAIN_HAND, quick);
        fp.getInventory().add(new ItemStack(VerdureItems.SEED_PELLET.get(), 1));
        quick.getItem().use(level, fp, InteractionHand.MAIN_HAND);
        h.assertTrue(lastShot(h).getDeltaMovement().length() > plainSpeed * 1.3, "Quickdraw III fires faster");

        ItemStack bloom = gun(level, 2, GunEnchants.BLOOM_SHOT, 1);
        fp.setItemInHand(InteractionHand.MAIN_HAND, bloom);
        fp.getInventory().add(new ItemStack(VerdureItems.SEED_PELLET.get(), 1));
        bloom.getItem().use(level, fp, InteractionHand.MAIN_HAND);
        Seedshot b = lastShot(h);
        h.assertTrue(b.mode() == Seedshot.BLOOM && b.getDeltaMovement().length() < plainSpeed * 0.7, "Bloom Shot fires a slower Bloom shot");
        clearShots(h);

        ItemStack hopper = gun(level, 1, GunEnchants.SEED_HOPPER, 1);
        fp.setItemInHand(InteractionHand.MAIN_HAND, hopper);
        fp.getInventory().clearContent();
        fp.setItemInHand(InteractionHand.MAIN_HAND, hopper);
        fp.getInventory().add(new ItemStack(VerdureItems.SEED_PELLET.get(), 60));
        for (int i = 0; i < 60; i++) hopper.getItem().use(level, fp, InteractionHand.MAIN_HAND);
        int left = VerdureWeapons.BloomGun.ammo(fp).getCount();
        h.assertTrue(left >= 8 && left <= 35, "Seed Hopper saves about a third of 60 pellets, " + left + " left");
        clearShots(h);

        ItemStack mend = gun(level, 1, GunEnchants.HEARTMEND, 1);
        fp.setItemInHand(InteractionHand.MAIN_HAND, mend);
        fp.getInventory().add(new ItemStack(VerdureItems.SEED_PELLET.get(), 1));
        mend.getItem().use(level, fp, InteractionHand.MAIN_HAND);
        Seedshot m = lastShot(h);
        Zombie zombie = VerdureGearTests.mob(h, EntityType.ZOMBIE, new Vec3(2, 0, 0));
        Pig pig = VerdureGearTests.mob(h, EntityType.PIG, new Vec3(-2, 0, 0));
        PlayerTeam team = level.getScoreboard().addPlayerTeam("ff_heartmend");
        level.getScoreboard().addPlayerToTeam(fp.getScoreboardName(), team);
        level.getScoreboard().addPlayerToTeam(pig.getStringUUID(), team);
        try {
            h.assertTrue(m.mode() == Seedshot.MEND, "Heartmend fires a mending shot");
            h.assertFalse(m.canHitEntity(zombie), "a mending shot passes through a zombie");
            h.assertTrue(m.canHitEntity(pig), "and stops at an ally");
            pig.setHealth(4F);
            m.onHitEntity(new EntityHitResult(pig));
            h.assertTrue(pig.getHealth() > 4F, "and heals it, to " + pig.getHealth());
            float zhp = zombie.getHealth();
            m.onHitEntity(new EntityHitResult(zombie));
            h.assertTrue(zombie.getHealth() == zhp, "it never hurts a zombie");
        } finally {
            level.getScoreboard().removePlayerTeam(team);
            zombie.discard();
            pig.discard();
            clearShots(h);
        }
        h.succeed();
    }

    /**
     * 1.10, left click (GunTrigger): pulling the trigger fires once and starts the cooldown; a second pull during the
     * cooldown fires nothing; letting go clears the hold; an empty gun held down doesn't fire.
     */
    static void seedGunsFireOnTheTrigger(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -4), 0F, 0F);
        fp.getInventory().clearContent();
        ItemStack gun = gun(level, 1);
        fp.setItemInHand(InteractionHand.MAIN_HAND, gun);
        fp.getInventory().add(new ItemStack(VerdureItems.SEED_PELLET.get(), 5));
        clearShots(h);
        GunTrigger.set(fp, true);
        h.assertTrue(lastShot(h) != null, "the trigger fires a shot");
        h.assertTrue(fp.getCooldowns().isOnCooldown(gun), "and starts the cooldown");
        int id = lastShot(h).getId();
        GunTrigger.set(fp, true);
        h.assertTrue(lastShot(h).getId() == id, "no second shot during the cooldown");
        h.assertTrue(GunTrigger.held(fp), "the trigger is held");
        GunTrigger.set(fp, false);
        h.assertFalse(GunTrigger.held(fp), "and let go");
        h.assertTrue(VerdureWeapons.BloomGun.ammo(fp).getCount() == 4, "one pellet spent");
        clearShots(h);
        h.succeed();
    }
}
