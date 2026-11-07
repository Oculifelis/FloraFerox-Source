package com.floraferox;

import com.floraferox.VerdureRules.DamageClass;
import com.floraferox.VerdureRules.Mark;
import com.floraferox.VerdureRules.Move;
import com.floraferox.VerdureRules.Style;
import com.floraferox.VerdureRules.Wild;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 1.4's wild mobs (docs/DESIGN.md, section 10): each brain's cadence, each taking full damage only from its family's
 * class, each landing its blow (or shot, or burst) on a still pig with its mark, each one's drops, the Nether's
 * fireproof, the Lanternpuff leaving players be, and the Rift Narwhal taken with chorus fruit and mounted.
 */
public final class WildTests {
    private WildTests() { }

    static void brainsTellThenStrike(GameTestHelper h) {
        for (Wild w : Wild.values()) {
            StrikeBrain b = new StrikeBrain(w);
            h.assertTrue(b.tick(true, w.reach + 1) == StrikeBrain.Event.NONE, w + " closes while its prey is out of reach");
            h.assertTrue(b.tick(true, w.reach) == StrikeBrain.Event.TELL && b.rooted(), w + " stops in reach to show its tell");
            int at = -1;
            for (int i = 1; i <= w.tell && at < 0; i++) if (b.tick(true, w.reach) == StrikeBrain.Event.STRIKE) at = i;
            h.assertTrue(at == w.tell, w + " strikes as the tell ends, at " + at);
            for (int i = 0; i < w.strike + w.recover; i++) b.tick(true, w.reach);
            h.assertTrue(b.state() == StrikeBrain.State.WALK, w + " recovers and walks again");
            h.assertTrue(VerdureModels.clips(w.id) != null && VerdureModels.clips(w.id).length == 2, w + " has its walk and attack baked");
        }
        h.succeed();
    }

    /** The den sends out no hoppers by night, tops its brood up to DEN_BROOD by day, and they keep near it. */
    static void hopperDenKeepsItsBrood(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        level.getServer().setDifficulty(Difficulty.NORMAL, true);
        BlockPos den = h.absolutePos(new BlockPos(16, 0, 16));
        level.setBlock(den, VerdureBlocks.HOPPER_DEN.get().defaultBlockState(), 3);
        var hoppers = new AABB(den).inflate(VerdureRules.DEN_HOME * 2);
        var type = VerdureEntities.wild(Wild.THORN_HOPPER);
        h.assertTrue(HopperDenBlock.brood(level, den, level.getRandom(), false) == 0, "no hoppers come out by night");
        for (int i = 0; i < 20; i++) HopperDenBlock.brood(level, den, level.getRandom(), true);
        var brood = level.getEntities(type, hoppers, e -> true);
        h.assertTrue(brood.size() == VerdureRules.DEN_BROOD, "by day the den keeps " + VerdureRules.DEN_BROOD + " hoppers out, not " + brood.size());
        for (WildMob m : brood) {
            h.assertTrue(m.hasHome() && m.getHomePosition().equals(den) && m.getHomeRadius() == VerdureRules.DEN_HOME, "each keeps near its den");
            m.discard();
        }
        h.succeed();
    }

    static void eachTakesFullDamageOnlyFromItsFamilysClass(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        DamageSource magic = level.damageSources().magic();
        for (Wild w : Wild.values()) {
            WildMob m = VerdureGameTests.spawn(h, VerdureEntities.wild(w), Vec3.ZERO);
            m.hurtServer(level, magic, 4F);
            float want = w.kind.weakness == DamageClass.MAGIC ? 4F : 2F;
            float took = m.getMaxHealth() - m.getHealth();
            h.assertTrue(Math.abs(took - want) < 0.01F, w + " (" + w.kind + ") took " + took + " of 4 magic, not " + want);
            h.assertTrue(m.fireImmune() == w.fireproof(), w + " is fireproof only if it is the Nether's");
            m.discard();
        }
        h.succeed();
    }

    static void strikesItsPrey(GameTestHelper h, Wild w) {
        Pig pig = VerdureGameTests.prey(h, Vec3.ZERO);
        double gap = w.move == Move.STILL ? 1.5 + w.width * 0.5 : w.style == Style.SHOT ? 5.0 : 3.0 + w.width * 0.5;
        WildMob m = VerdureGameTests.spawn(h, VerdureEntities.wild(w), new Vec3(gap, 0, 0));
        m.setTarget(pig);
        h.succeedWhen(() -> {
            if (w.style == Style.SHOT) h.assertTrue(m.shots > 0, "it fired");
            else h.assertTrue(m.strikes > 0, "a blow landed");
            h.assertTrue(pig.getHealth() < pig.getMaxHealth(), "the pig is hurt");
            if (w.mark == Mark.SLOW) h.assertTrue(pig.hasEffect(MobEffects.SLOWNESS), "the pig is slowed");
            if (w.mark == Mark.POISON) h.assertTrue(pig.hasEffect(MobEffects.POISON), "the pig is poisoned");
            if (w.mark == Mark.WEAKNESS) h.assertTrue(pig.hasEffect(MobEffects.WEAKNESS), "the pig is weakened");
            m.discard();
            pig.discard();
        });
    }

    /** Only what data.py DROPS lists: a loot table each, and something in 400 kills. */
    static void eachDropsItsOwn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (Wild w : Wild.values()) {
            ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, FloraFerox.id("entities/" + w.id));
            LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
            h.assertTrue(table != LootTable.EMPTY, w + " has a loot table");
            WildMob m = VerdureGameTests.spawn(h, VerdureEntities.wild(w), Vec3.ZERO);
            h.assertTrue(m.getLootTable().orElseThrow().equals(key), w + " uses it");
            ItemStack token = new ItemStack(VerdureItems.TOKENS.get(w.kind).get());
            int tokens = 0;
            for (int i = 0; i < 400; i++) {
                LootParams p = new LootParams.Builder(level).withParameter(LootContextParams.THIS_ENTITY, m).withParameter(LootContextParams.ORIGIN, m.position())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic()).create(LootContextParamSets.ENTITY);
                for (ItemStack s : table.getRandomItems(p)) if (ItemStack.isSameItem(s, token)) tokens += s.getCount();
            }
            h.assertTrue(tokens > 120, w + " drops its family's token: " + tokens + " in 400");
            m.discard();
        }
        h.succeed();
    }

    /** The Lanternpuff leaves a player be until hurt (a FakePlayer is never hunted, so the others' hunt is untested here). */
    static void neutralOnesWaitToBeHurt(GameTestHelper h) {
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -3), 0F, 0F);
        WildMob puff = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.LANTERNPUFF), new Vec3(3, 0, 0));
        h.runAfterDelay(40, () -> {
            h.assertTrue(puff.getTarget() == null, "the Lanternpuff leaves the player be");
            // a FakePlayer is never taken as a target, so a pig does the hurting
            Pig pig = VerdureGameTests.prey(h, new Vec3(3, 0, -3));
            puff.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(pig), 1F);
            h.runAfterDelay(10, () -> {
                h.assertTrue(puff.getTarget() == pig, "hurt, the Lanternpuff turns on what hurt it");
                puff.discard();
                pig.discard();
                h.succeed();
            });
        });
    }

    /** Chorus fruit tames it now and then; tamed it stays, never turns on anyone, and carries its rider. */
    static void riftNarwhalTamesAndRides(GameTestHelper h) {
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -4), 0F, 0F);
        RiftNarwhal n = (RiftNarwhal) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.RIFT_NARWHAL), new Vec3(0, 0, 2));
        h.assertTrue(!n.canAddPassenger(fp), "a wild one carries no one");
        fp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CHORUS_FRUIT, 64));
        int fed = 0;
        while (!n.tamed() && fed < 64) {
            n.mobInteract(fp, InteractionHand.MAIN_HAND);
            fed++;
        }
        h.assertTrue(n.tamed(), "chorus fruit tamed it, after " + fed);
        h.assertTrue(fed > 0 && fp.getMainHandItem().getCount() == 64 - fed, "each try eats a fruit");
        h.assertTrue(n.isPersistenceRequired(), "a tamed one stays");
        n.setTarget(fp);
        h.assertTrue(!n.hunts(), "and never fights");
        fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        // a FakePlayer may not ride (FakePlayer.startRiding), so this checks the way on, not the ride
        h.assertTrue(n.canAddPassenger(fp), "a tamed one takes a rider");
        h.assertTrue(n.mobInteract(fp, InteractionHand.MAIN_HAND).consumesAction(), "an empty hand climbs on");
        n.discard();
        h.succeed();
    }
}
