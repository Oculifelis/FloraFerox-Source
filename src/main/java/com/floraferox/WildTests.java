package com.floraferox;

import com.floraferox.VerdureRules.DamageClass;
import com.floraferox.VerdureRules.Mark;
import com.floraferox.VerdureRules.Move;
import com.floraferox.VerdureRules.Style;
import com.floraferox.VerdureRules.Wild;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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
 * fireproof, the Lanternpuff leaving players be, and the Rift Narwhal taken with chorus fruit and mounted. 2.2's summoned
 * three (section 15): their thresholds, their arenas rising and falling with them, their spoils and trophies.
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
            h.assertTrue(VerdureModels.clips(w.id) != null && VerdureModels.clips(w.id).length >= 2, w + " has its walk and attack baked (and its idle, once picked)");
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
            pastItsWake(m);
            m.hurtServer(level, magic, 4F);
            float want = w.kind.weakness == DamageClass.MAGIC ? 4F : 2F;
            float took = m.getMaxHealth() - m.getHealth();
            h.assertTrue(Math.abs(took - want) < 0.01F, w + " (" + w.kind + ") took " + took + " of 4 magic, not " + want);
            h.assertTrue(m.fireImmune() == w.fireproof(), w + " is fireproof only if it is the Nether's");
            m.discard();
        }
        h.succeed();
    }

    /** The Crimson Behemoth: immune while it wakes, then a roar (immune too) at SPOREFALL_AT of its health. */
    static void behemothRoarsIntoItsPhases(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CrimsonBehemoth b = (CrimsonBehemoth) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.CRIMSON_BEHEMOTH), Vec3.ZERO);
        h.assertTrue(b.phase.is(CrimsonBehemoth.Phase.WAKE), "it starts waking");
        h.assertTrue(!b.hurtServer(level, level.damageSources().magic(), 4F), "and can't be hurt while it wakes");
        b.phase.to(CrimsonBehemoth.Phase.STAMPEDE);
        b.setHealth(b.getMaxHealth() * (VerdureRules.SPOREFALL_AT - 0.05F));
        h.succeedWhen(() -> {
            h.assertTrue(b.phase.is(CrimsonBehemoth.Phase.ROAR), "under " + VerdureRules.SPOREFALL_AT + " of its health it roars");
            h.assertTrue(!b.hurtServer(level, level.damageSources().magic(), 4F), "and can't be hurt while it roars");
        });
    }

    /**
     * Its last phase's punish window: rearing for a stamp, and just after the stamp lands, its cracks flare and any hit
     * does FLARE_BONUS times its damage; out of the window, or rearing in an earlier phase, a hit does its plain damage.
     */
    static void behemothFlareTakesMore(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CrimsonBehemoth b = (CrimsonBehemoth) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.CRIMSON_BEHEMOTH), Vec3.ZERO);
        b.phase.to(CrimsonBehemoth.Phase.STAMPEDE);
        float plain = took(b, level, 4F);
        b.rear();
        h.assertTrue(!b.flaring(), "rearing in its first phase it doesn't flare");
        h.assertTrue(Math.abs(took(b, level, 4F) - plain) < 0.01F, "and a hit does its plain damage");
        b.phase.to(CrimsonBehemoth.Phase.ROT_BLOOM);
        h.assertTrue(!b.flaring() && Math.abs(took(b, level, 4F) - plain) < 0.01F, "in its last phase, standing, a hit does its plain damage");
        b.rear();
        h.assertTrue(b.flaring(), "rearing in its last phase its cracks flare");
        float flared = took(b, level, 4F);
        h.assertTrue(Math.abs(flared - plain * VerdureRules.FLARE_BONUS) < 0.01F, "and a hit does " + VerdureRules.FLARE_BONUS + "x: " + flared + " for " + plain);
        h.succeedWhen(() -> {
            h.assertTrue(b.move() != CrimsonBehemoth.Moves.REAR, "it stamps");
            h.assertTrue(!b.flaring(), "and the window closes a moment after the stamp lands");
        });
    }

    /** What a magic hit of this much takes off it now (its health put back after). */
    private static float took(CrimsonBehemoth b, ServerLevel level, float amount) {
        b.setHealth(b.getMaxHealth());
        b.invulnerableTime = 0;
        b.hurtServer(level, level.damageSources().magic(), amount);
        float took = b.getMaxHealth() - b.getHealth();
        b.setHealth(b.getMaxHealth());
        b.invulnerableTime = 0;
        return took;
    }

    /** A boss can't be hurt while it wakes; this puts it straight into its first fighting phase. */
    static void pastItsWake(WildMob m) {
        if (m instanceof CrimsonBehemoth b) b.phase.to(CrimsonBehemoth.Phase.STAMPEDE);
        if (m instanceof ThornMatriarch b) b.phase.to(ThornMatriarch.Phase.LEAPS);
        if (m instanceof BriarKing b) b.phase.to(BriarKing.Phase.COURT);
        if (m instanceof CarrionBloom b) b.phase.to(CarrionBloom.Phase.LURE);
    }

    static void strikesItsPrey(GameTestHelper h, Wild w) {
        Pig pig = VerdureGameTests.prey(h, Vec3.ZERO);
        double gap = w.move == Move.STILL ? 1.5 + w.width * 0.5 : w.style == Style.SHOT ? 5.0 : 3.0 + w.width * 0.5;
        WildMob m = VerdureGameTests.spawn(h, VerdureEntities.wild(w), new Vec3(gap, 0, 0));
        pastItsWake(m);
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

    /** Both End fliers can climb while crossing open air far above the arena floor. */
    static void endFliersCrossAir(GameTestHelper h) {
        WildMob jelly = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.VOID_JELLY), new Vec3(0, 8, -4));
        WildMob narwhal = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.RIFT_NARWHAL), new Vec3(0, 8, 4));
        List<WildMob> fliers = List.of(jelly, narwhal);
        double startY = jelly.getY();
        double startX = jelly.getX();
        for (WildMob flier : fliers) {
            Vec3 to = flier.position().add(7, 4, 0);
            h.assertTrue(flier.getNavigation().isStableDestination(BlockPos.containing(to)), "open air is a flight destination");
            h.assertTrue(flier.getNavigation().moveTo(to.x, to.y, to.z, 1.0), "there is a route through open air");
        }
        h.succeedWhen(() -> {
            for (WildMob flier : fliers) {
                h.assertTrue(!flier.onGround() && flier.getY() > startY + 2 && flier.getX() > startX + 4,
                        flier.getType().toShortString() + " climbed while crossing open air");
            }
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

    // ---- 2.2: the summoned three (DESIGN.md section 15) ----

    static void matriarchThresholds(GameTestHelper h) {
        ThornMatriarch b = (ThornMatriarch) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.THORN_MATRIARCH), Vec3.ZERO);
        thresholds(h, b, b.phase::state, ThornMatriarch.Phase.EMERGE, ThornMatriarch.Phase.CALL, ThornMatriarch.Phase.BROOD, ThornMatriarch.Phase.SWARM,
                VerdureRules.BROOD_AT, VerdureRules.SWARM_AT, () -> b.phase.to(ThornMatriarch.Phase.LEAPS));
    }

    static void kingThresholds(GameTestHelper h) {
        BriarKing b = (BriarKing) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.BRIAR_KING), Vec3.ZERO);
        thresholds(h, b, b.phase::state, BriarKing.Phase.RISE, BriarKing.Phase.ROAR, BriarKing.Phase.THORNCOURT, BriarKing.Phase.WILD_ROSE,
                VerdureRules.THORNCOURT_AT, VerdureRules.WILD_ROSE_AT, () -> b.phase.to(BriarKing.Phase.COURT));
    }

    static void bloomThresholds(GameTestHelper h) {
        CarrionBloom b = (CarrionBloom) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.CARRION_BLOOM), Vec3.ZERO);
        thresholds(h, b, b.phase::state, CarrionBloom.Phase.BLOOM, CarrionBloom.Phase.WILT, CarrionBloom.Phase.FEEDING, CarrionBloom.Phase.CARRION,
                VerdureRules.FEEDING_AT, VerdureRules.CARRION_AT, () -> b.phase.to(CarrionBloom.Phase.LURE));
    }

    /**
     * Immune while it wakes; then under its first threshold it roars (immune) into its second phase, and under its
     * second it roars again into its last.
     */
    private static void thresholds(GameTestHelper h, WildBoss b, Supplier<Enum<?>> state, Enum<?> wake, Enum<?> roar, Enum<?> second, Enum<?> last,
                                   float at1, float at2, Runnable wakeUp) {
        ServerLevel level = h.getLevel();
        String who = b.getType().toShortString();
        h.assertTrue(state.get() == wake, who + " starts in " + wake);
        h.assertTrue(!b.hurtServer(level, level.damageSources().magic(), 4F), who + " can't be hurt while it wakes");
        wakeUp.run();
        b.setHealth(b.getMaxHealth() * (at1 - 0.05F));
        int[] step = {0};
        h.onEachTick(() -> {
            Enum<?> s = state.get();
            if (step[0] == 0 && s == roar) {
                h.assertTrue(!b.hurtServer(level, level.damageSources().magic(), 4F), who + " can't be hurt while it roars");
                step[0] = 1;
            } else if (step[0] == 1 && s == second) {
                b.setHealth(b.getMaxHealth() * (at2 - 0.05F));
                step[0] = 2;
            } else if (step[0] == 2 && s == roar) step[0] = 3;
            else if (step[0] == 3 && s == last) step[0] = 4;
        });
        h.succeedWhen(() -> {
            h.assertTrue(step[0] == 4, who + ": under " + at1 + " it roars into " + second + ", under " + at2 + " into " + last + " (reached step " + step[0] + ")");
            b.discard();
        });
    }

    /** Her berry ring rises round the den as she wakes; it falls with her (the den stays spent) or as she goes home (the den is ready). */
    static void matriarchRing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos den = h.absolutePos(new BlockPos(16, 0, 16));
        level.setBlock(den, VerdureBlocks.HOPPER_DEN.get().defaultBlockState(), 3);
        ThornMatriarch b = (ThornMatriarch) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.THORN_MATRIARCH), Vec3.ZERO);
        HopperDenBlock.rest(level, den, true);
        b.wakeAt(level, den);
        List<BlockPos> ring = ringStands(h, b, "her berry ring");
        h.assertTrue(HopperDenBlock.wake(level, den) == null, "a spent den calls no second Matriarch");
        b.kill(level);
        ringFallen(h, b, ring, "her berry ring, once she falls,");
        h.assertTrue(level.getBlockState(den).getValue(HopperDenBlock.SPENT), "the den stays spent once she falls");
        ThornMatriarch c = (ThornMatriarch) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.THORN_MATRIARCH), Vec3.ZERO);
        c.wakeAt(level, den);
        ring = ringStands(h, c, "her berry ring");
        c.goHome(level);
        ringFallen(h, c, ring, "her berry ring, once she goes home,");
        h.assertTrue(c.isRemoved() && !level.getBlockState(den).getValue(HopperDenBlock.SPENT), "she goes back in and the den is ready again");
        h.succeed();
    }

    /** His bramble wall rises round the throne as he wakes (never by day); it falls with him or as he sinks back. */
    static void kingRing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos throne = h.absolutePos(new BlockPos(16, 0, 16));
        level.setBlock(throne, VerdureBlocks.BRIAR_THRONE.get().defaultBlockState(), 3);
        if (level.isBrightOutside()) h.assertTrue(BriarThroneBlock.wake(level, throne) == null, "by day the throne sleeps");
        BriarKing b = (BriarKing) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.BRIAR_KING), Vec3.ZERO);
        BriarThroneBlock.rest(level, throne, true);
        b.wakeAt(level, throne);
        List<BlockPos> ring = ringStands(h, b, "his bramble wall");
        h.assertTrue(BriarThroneBlock.wake(level, throne) == null, "a spent throne wakes no second King");
        b.kill(level);
        ringFallen(h, b, ring, "his bramble wall, once he falls,");
        h.assertTrue(level.getBlockState(throne).getValue(BriarThroneBlock.SPENT), "the throne stays spent once he falls");
        BriarKing c = (BriarKing) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.BRIAR_KING), Vec3.ZERO);
        c.wakeAt(level, throne);
        ring = ringStands(h, c, "his bramble wall");
        c.goHome(level);
        ringFallen(h, c, ring, "his bramble wall, once he sinks,");
        h.assertTrue(c.isRemoved() && !level.getBlockState(throne).getValue(BriarThroneBlock.SPENT), "he sinks and the throne is ready again");
        h.succeed();
    }

    /** Bait under the bud drops the Bloom; its vines hang from the ceiling round it, and go with it. */
    static void bloomVines(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 0; x <= 32; x++) for (int z = 0; z <= 32; z++) level.setBlock(h.absolutePos(new BlockPos(x, 7, z)), Blocks.STONE.defaultBlockState(), 2);
        BlockPos bud = h.absolutePos(new BlockPos(16, 6, 16));
        level.setBlock(bud, VerdureBlocks.CARRION_BUD.get().defaultBlockState(), 3);
        h.assertTrue(bud.equals(CarrionBudBlock.near(level, h.absolutePos(new BlockPos(16, 1, 16)))), "bait on the ground finds the bud over it");
        CarrionBloom b = CarrionBudBlock.wake(level, bud);
        h.assertTrue(b != null, "the bud drops its Bloom");
        h.assertTrue(level.getBlockState(bud).getValue(CarrionBudBlock.SPENT) && CarrionBudBlock.wake(level, bud) == null, "and is spent");
        h.assertTrue(b.vines().size() == VerdureRules.VINES, "its " + VerdureRules.VINES + " vines hang round it, not " + b.vines().size());
        List<BlockPos> ring = ringStands(h, b, "its vines");
        b.kill(level);
        ringFallen(h, b, ring, "its vines, once it dies,");
        CarrionBudBlock.rest(level, bud, false);
        CarrionBloom c = CarrionBudBlock.wake(level, bud);
        h.assertTrue(c != null, "a ready bud drops another");
        ring = ringStands(h, c, "its vines");
        c.goHome(level);
        ringFallen(h, c, ring, "its vines, once it closes,");
        h.assertTrue(c.isRemoved() && !level.getBlockState(bud).getValue(CarrionBudBlock.SPENT), "it closes back into a ready bud");
        h.succeed();
    }

    /** Round 14: the Bloom never moves, not even in its last phase while it fights, and nothing pushes it. */
    static void bloomStaysRooted(GameTestHelper h) {
        Pig pig = VerdureGameTests.prey(h, new Vec3(8, 0, 0));
        CarrionBloom b = (CarrionBloom) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.CARRION_BLOOM), Vec3.ZERO);
        b.phase.to(CarrionBloom.Phase.CARRION);
        b.setTarget(pig);
        Vec3 at = b.position();
        h.assertTrue(!b.isPushable() && b.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) >= 1.0, "nothing pushes it");
        double[] moved = {0};
        h.onEachTick(() -> {
            b.push(1.0, 0, 1.0);
            moved[0] = Math.max(moved[0], Math.hypot(b.getX() - at.x, b.getZ() - at.z));
        });
        h.succeedWhen(() -> {
            h.assertTrue(pig.getHealth() < pig.getMaxHealth(), "it reaches its prey from where it stands");
            h.assertTrue(moved[0] < 0.01, "and never moves (it drifted " + moved[0] + ")");
            b.discard();
            pig.discard();
        });
    }

    private static List<BlockPos> ringStands(GameTestHelper h, WildBoss b, String what) {
        List<BlockPos> ring = List.copyOf(b.ring());
        h.assertTrue(!ring.isEmpty(), what + " rises as it wakes");
        for (BlockPos p : ring) h.assertTrue(b.ours(h.getLevel().getBlockState(p)), what + " stands at " + p);
        return ring;
    }

    private static void ringFallen(GameTestHelper h, WildBoss b, List<BlockPos> ring, String what) {
        for (BlockPos p : ring) h.assertTrue(!b.ours(h.getLevel().getBlockState(p)), what + " is gone at " + p);
    }

    private record Spoil(Item item, int lo, int hi) {}

    /** Each drops its materials every time, in their ranges; its trophy never comes from the loot, only once to each player. */
    static void bossesDropTheirSpoils(GameTestHelper h) {
        spoils(h, Wild.THORN_MATRIARCH, VerdureItems.MATRIARCH_CREST.get(),
                new Spoil(VerdureItems.ROYAL_THORNS.get(), 2, 4), new Spoil(Items.SWEET_BERRIES, 6, 12), new Spoil(Items.ROSE_BUSH, 1, 2));
        spoils(h, Wild.BRIAR_KING, VerdureItems.KINGS_ROSEHIP.get(),
                new Spoil(VerdureItems.BRIAR_HEARTWOOD.get(), 1, 3), new Spoil(Items.ROSE_BUSH, 2, 4), new Spoil(Items.DARK_OAK_LOG, 4, 8));
        spoils(h, Wild.CARRION_BLOOM, VerdureItems.CARRION_HEART.get(), new Spoil(VerdureItems.CARRION_PETAL.get(), 1, 3),
                new Spoil(Items.SPORE_BLOSSOM, 2, 4), new Spoil(Items.GLOW_BERRIES, 6, 10), new Spoil(Items.MOSS_BLOCK, 4, 8));
        h.succeed();
    }

    private static void spoils(GameTestHelper h, Wild w, Item trophy, Spoil... want) {
        ServerLevel level = h.getLevel();
        WildBoss b = (WildBoss) VerdureGameTests.spawn(h, VerdureEntities.wild(w), Vec3.ZERO);
        LootTable table = level.getServer().reloadableRegistries().getLootTable(b.getLootTable().orElseThrow());
        for (int i = 0; i < 50; i++) {
            LootParams p = new LootParams.Builder(level).withParameter(LootContextParams.THIS_ENTITY, b).withParameter(LootContextParams.ORIGIN, b.position())
                    .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic()).create(LootContextParamSets.ENTITY);
            var drops = table.getRandomItems(p);
            for (Spoil s : want) {
                int n = drops.stream().filter(d -> d.is(s.item())).mapToInt(ItemStack::getCount).sum();
                h.assertTrue(n >= s.lo() && n <= s.hi(), w + " drops " + s.lo() + "-" + s.hi() + " " + s.item() + ", not " + n);
            }
            h.assertTrue(drops.stream().noneMatch(d -> d.is(trophy)), w + "'s trophy is not in its loot");
        }
        FakePlayer fp = VerdureGearTests.player(h, Vec3.ZERO, 0F, 0F);
        ItemEntity first = b.award(level, fp);
        h.assertTrue(first != null && first.getItem().is(trophy), w + " gives a player its trophy");
        h.assertTrue(b.award(level, fp) == null, w + " gives each player its trophy once");
        first.discard();
        b.discard();
    }
}
