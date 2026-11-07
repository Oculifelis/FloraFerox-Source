package com.floraferox;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.EventHooks;

/** 1.16: the bows and crossbows (VerdureBows). Registered with the rest (VerdureGameTests.TESTS). */
final class BowTests {
    private BowTests() { }

    private static AbstractArrow lastArrow(GameTestHelper h, FakePlayer fp) {
        return h.getLevel().getEntitiesOfClass(AbstractArrow.class, new AABB(fp.blockPosition()).inflate(16), a -> a.getOwner() == fp).stream()
                .max((a, b) -> Integer.compare(a.getId(), b.getId())).orElse(null);
    }

    /** A full draw leaves a heart seed; struck, the mark's seed bursts a moment later on what stands round it. */
    static void bowSeedBursts(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -4), 180F, -80F);
        ItemStack bow = new ItemStack(VerdureItems.BOWS.get(Herb.PULSEFLARE).get());
        fp.setItemInHand(InteractionHand.MAIN_HAND, bow);
        fp.getInventory().add(new ItemStack(Items.ARROW, 4));
        bow.getItem().releaseUsing(bow, level, fp, bow.getUseDuration(fp) - 5);
        AbstractArrow weak = lastArrow(h, fp);
        h.assertTrue(weak != null && weak.getPersistentData().getIntOr(VerdureBows.SEED, 0) == 0, "a short draw carries no seed");
        weak.discard();
        bow.getItem().releaseUsing(bow, level, fp, bow.getUseDuration(fp) - 20);
        AbstractArrow full = lastArrow(h, fp);
        h.assertTrue(full != null && full.isCritArrow() && full.getPersistentData().getIntOr(VerdureBows.SEED, 0) == 2, "a full draw carries the burning seed");
        Pig mark = VerdureGearTests.mob(h, EntityType.PIG, new Vec3(4, 0, 0)), near = VerdureGearTests.mob(h, EntityType.PIG, new Vec3(5.5, 0, 0)),
                far = VerdureGearTests.mob(h, EntityType.PIG, new Vec3(4, 0, 6));
        mark.hurtServer(level, level.damageSources().arrow(full, fp), 1F);
        full.discard();
        float nearHp = near.getHealth(), farHp = far.getHealth();
        h.assertTrue(VerdureBows.pendingSeeds() >= 1, "the seed is planted");
        h.runAfterDelay(VerdureBows.SEED_DELAY + 2, () -> {
            h.assertTrue(near.getHealth() < nearHp && near.isOnFire(), "the burst hurt and lit the pig beside the mark");
            h.assertTrue(mark.isOnFire(), "and the mark itself");
            h.assertTrue(far.getHealth() == farHp, "but not one six blocks off");
            h.succeed();
        });
    }

    /** A crossbow's bolt pierces one mob and roots the first it hits, only the first. */
    static void arbalestRootsTheFirst(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -4), 180F, -80F);
        ItemStack bow = new ItemStack(VerdureItems.CROSSBOWS.get(Herb.PULSERIME).get());
        bow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.ofNonEmpty(List.of(new ItemStack(Items.ARROW))));
        fp.setItemInHand(InteractionHand.MAIN_HAND, bow);
        bow.getItem().use(level, fp, InteractionHand.MAIN_HAND);
        AbstractArrow bolt = lastArrow(h, fp);
        h.assertTrue(bolt != null && bolt.getPierceLevel() >= 1, "the bolt pierces");
        h.assertTrue(bolt.getPersistentData().getIntOr(VerdureBows.ROOT, 0) == 3, "and carries the frozen root");
        Pig first = VerdureGearTests.mob(h, EntityType.PIG, new Vec3(4, 0, 0)), second = VerdureGearTests.mob(h, EntityType.PIG, new Vec3(6, 0, 0));
        first.hurtServer(level, level.damageSources().arrow(bolt, fp), 1F);
        second.hurtServer(level, level.damageSources().arrow(bolt, fp), 1F);
        bolt.discard();
        var slow = first.getEffect(MobEffects.SLOWNESS);
        h.assertTrue(slow != null && slow.getAmplifier() == 9, "the first is rooted");
        h.assertTrue(first.getTicksFrozen() > 0, "and chilled");
        h.assertTrue(second.getEffect(MobEffects.SLOWNESS) == null, "the second is only pierced");
        h.succeed();
    }

    /** The third-tier ranged set winds a bow 20% faster: 20 ticks of use count 25. */
    static void rangedSetWindsFaster(GameTestHelper h) {
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -4), 0F, 0F);
        ItemStack bow = new ItemStack(VerdureItems.BOWS.get(Herb.PULSEROOT).get());
        h.assertTrue(wind(fp, bow) == 20, "bare, 20 ticks count 20");
        VerdureGearTests.wear(fp, Herb.PULSERIME);
        int wound = wind(fp, bow);
        h.assertTrue(wound >= 24 && wound <= 26, "in the Rimebark ranged set they count about 25, got " + wound);
        h.succeed();
    }

    private static int wind(FakePlayer fp, ItemStack bow) {
        int d = 1000;
        for (int i = 0; i < 20; i++) d = EventHooks.onItemUseTick(fp, bow, d) - 1;
        return 1000 - d;
    }
}
