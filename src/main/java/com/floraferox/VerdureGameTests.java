package com.floraferox;

import com.floraferox.VerdureRules.DamageClass;
import com.floraferox.VerdureRules.Kind;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * Flora Ferox's mobs (docs/DESIGN.md, section 6): each brain's cadence, the class weakness, each mob's attack
 * landing on real prey, the Dusk Sprig rooting by day, the harvest waking its family, and each mob's drops (a little
 * of its plant). The prey is a still pig, so no test needs a player in the world. Each test is its own batch; every
 * step is scheduled up front (no delays fanned out from callbacks).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = FloraFerox.MODID)
public final class VerdureGameTests {
    private VerdureGameTests() { }

    public static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, FloraFerox.MODID);
    private static final Identifier ARENA = FloraFerox.id("gametest/arena");
    private static final BlockPos MIDDLE = new BlockPos(16, 1, 16);

    record Test(String name, Consumer<GameTestHelper> body, int maxTicks) {}

    private static final List<Test> BASE = List.of(
        new Test("brains_run_their_cadences", VerdureGameTests::verdureBrainsRunTheirCadences, 20),
        new Test("each_takes_full_damage_only_from_its_familys_class", VerdureGameTests::eachTakesFullDamageOnlyFromItsFamilysClass, 20),
        new Test("pulse_gnat_darts_through_its_prey", VerdureGameTests::pulseGnatDartsThroughItsPrey, 200),
        new Test("mire_ghast_raises_its_arms_then_slams", VerdureGameTests::mireGhastRaisesItsArmsThenSlams, 200),
        new Test("dusk_sprig_lunges_and_bites", VerdureGameTests::duskSprigLungesAndBites, 240),
        new Test("dusk_sprig_roots_by_day", VerdureGameTests::duskSprigRootsByDay, 40),
        new Test("moon_tick_hops_onto_its_prey", VerdureGameTests::moonTickHopsOntoItsPrey, 240),
        new Test("harvest_wakes_the_family", VerdureGameTests::harvestWakesTheFamily, 20),
        new Test("each_drops_a_little_of_its_plant", VerdureGameTests::eachDropsALittleOfItsPlant, 20),
        // 1.1 (VerdureGearTests)
        new Test("splicing_breeds_the_strains", VerdureGearTests::splicingBreedsTheStrains, 20),
        new Test("grown_herb_wakes_its_monster_and_drops_itself", VerdureGearTests::grownHerbWakesItsMonsterAndDropsItself, 20),
        new Test("herbs_brew_their_familys_drink", VerdureGearTests::herbsBrewTheirFamilysDrink, 20),
        new Test("class_bonuses_and_moons_spite", VerdureGearTests::classBonusesAndLunarScorn, 20),
        new Test("sets_and_fireproofing", VerdureGearTests::setsAndFireproofing, 20),
        new Test("weapons_fire", VerdureGearTests::weaponsFire, 20),
        new Test("sceptre_supports", VerdureGearTests::sceptreSupports, 20),
        new Test("helmet_set_effects", VerdureGearTests::helmetSetEffects, 20),
        new Test("hooks_upgrade_as_the_weapons_do", VerdureGearTests::hooksUpgradeAsTheWeaponsDo, 20),
        new Test("wings_fly_further_each_tier", VerdureGearTests::wingsFlyFurtherEachTier, 20),
        new Test("whips_crack", VerdureGearTests::whipsCrack, 160),
        new Test("the_weedwarden_keeps_the_grafting_bench", VerdureGearTests::theWeedwardenKeepsTheGraftingBench, 20),
        // 1.2's six (FeroxTests)
        new Test("ferox_brains_tell_then_strike", FeroxTests::brainsTellThenStrike, 20),
        new Test("ferox_take_full_damage_only_from_their_familys_class", FeroxTests::eachTakesFullDamageOnlyFromItsFamilysClass, 20),
        new Test("snap_drake_strikes_its_prey", h -> FeroxTests.strikesItsPrey(h, VerdureRules.Ferox.SNAP_DRAKE), 240),
        new Test("reed_wretch_strikes_its_prey", h -> FeroxTests.strikesItsPrey(h, VerdureRules.Ferox.REED_WRETCH), 240),
        new Test("lantern_leech_strikes_its_prey", h -> FeroxTests.strikesItsPrey(h, VerdureRules.Ferox.LANTERN_LEECH), 240),
        new Test("burr_crawler_strikes_its_prey", h -> FeroxTests.strikesItsPrey(h, VerdureRules.Ferox.BURR_CRAWLER), 240),
        new Test("orchid_mantis_strikes_its_prey", h -> FeroxTests.strikesItsPrey(h, VerdureRules.Ferox.ORCHID_MANTIS), 240),
        new Test("dew_crab_strikes_its_prey", h -> FeroxTests.strikesItsPrey(h, VerdureRules.Ferox.DEW_CRAB), 240),
        new Test("ferox_drop_a_little_of_their_plant", FeroxTests::eachDropsALittleOfItsPlant, 20),
        // 1.3
        new Test("trophies_gate_the_strains", FeroxTests::trophiesGateTheStrains, 20),
        new Test("ferox_charms_work", FeroxTests::feroxCharmsWork, 20),
        new Test("charms_worn_in_curios", FeroxTests::charmsWornInCurios, 20),
        new Test("later_strains_wake_the_new_hunters", FeroxTests::laterStrainsWakeTheNewHunters, 20),
        new Test("burr_pellets_load_the_guns", FeroxTests::burrPelletsLoadTheGuns, 20)
    );
    /** 1.4's wild mobs (WildTests): one strike test each, then the rest. */
    private static final List<Test> TESTS = java.util.stream.Stream.of(BASE.stream(),
            java.util.Arrays.stream(VerdureRules.Wild.values()).map(w -> new Test(w.id + "_strikes_its_prey", h -> WildTests.strikesItsPrey(h, w), 300)),
            java.util.stream.Stream.of(
                    new Test("wild_brains_tell_then_strike", WildTests::brainsTellThenStrike, 20),
                    new Test("wild_take_full_damage_only_from_their_familys_class", WildTests::eachTakesFullDamageOnlyFromItsFamilysClass, 20),
                    new Test("wild_drop_their_own", WildTests::eachDropsItsOwn, 20),
                    new Test("wild_neutral_ones_wait_to_be_hurt", WildTests::neutralOnesWaitToBeHurt, 100),
                    new Test("rift_narwhal_tames_and_rides", WildTests::riftNarwhalTamesAndRides, 20),
                    // 1.5: their own ways and the Peace Lily Charm (AiTests)
                    new Test("truce_spares_the_wearer_till_struck", AiTests::truceSparesTheWearerTillStruck, 20),
                    new Test("root_grub_burrows_and_erupts", AiTests::rootGrubBurrowsAndErupts, 300),
                    new Test("wild_mob_act_saves_and_loads", AiTests::wildMobActSavesAndLoads, 20),
                    new Test("briar_king_charges", AiTests::briarKingCharges, 300),
                    new Test("neutral_ones_call_their_kind", AiTests::neutralOnesCallTheirKind, 20),
                    new Test("bell_stalker_knows_when_watched", AiTests::bellStalkerKnowsWhenWatched, 20),
                    // 1.5.3: the Thorn Hopper's den
                    new Test("hopper_den_keeps_its_brood", WildTests::hopperDenKeepsItsBrood, 20),
                    // 1.8: the seed guns' enchantments (GunEnchantTests)
                    new Test("gun_enchantments_are_data", GunEnchantTests::gunEnchantmentsAreData, 20),
                    new Test("gun_enchantments_change_the_shot", GunEnchantTests::gunEnchantmentsChangeTheShot, 20),
                    new Test("seed_guns_fire_on_the_trigger", GunEnchantTests::seedGunsFireOnTheTrigger, 20),
                    // 1.16: the bows and crossbows (BowTests)
                    new Test("bow_seed_bursts", BowTests::bowSeedBursts, 60),
                    new Test("arbalest_roots_the_first", BowTests::arbalestRootsTheFirst, 20),
                    new Test("ranged_set_winds_faster", BowTests::rangedSetWindsFaster, 20)))
            .flatMap(x -> x).toList();

    static {
        for (Test t : TESTS) FUNCTIONS.register(t.name(), () -> t.body());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onRegisterTests(RegisterGameTestsEvent event) {
        for (Test t : TESTS) {
            Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(FloraFerox.id("env_" + t.name()), new TestEnvironmentDefinition.AllOf(List.of()));
            ResourceKey<Consumer<GameTestHelper>> fn = ResourceKey.create(Registries.TEST_FUNCTION, FloraFerox.id(t.name()));
            event.registerTest(FloraFerox.id(t.name()), new FunctionGameTestInstance(fn, new TestData<>(env, ARENA, t.maxTicks(), 0, true)));
        }
    }
    private static final IntUnaryOperator NO_RANDOM = bound -> 0;

    static <T extends Mob> T spawn(GameTestHelper h, EntityType<T> type, Vec3 rel) {
        h.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        T mob = type.create(h.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 at = h.absoluteVec(Vec3.atBottomCenterOf(MIDDLE).add(rel));
        mob.snapTo(at.x, at.y, at.z, 0F, 0F);
        mob.setPersistenceRequired();
        h.getLevel().addFreshEntity(mob);
        return mob;
    }

    static Pig prey(GameTestHelper h, Vec3 rel) {
        Pig pig = spawn(h, EntityType.PIG, rel);
        pig.setNoAi(true);
        return pig;
    }

    // ---- the brains --------------------------------------------------------------------------------------------
    private static void verdureBrainsRunTheirCadences(GameTestHelper h) {
        PulseGnatBrain fly = new PulseGnatBrain();
        fly.tick(true, 4, true, NO_RANDOM);
        h.assertTrue(fly.state() == PulseGnatBrain.State.ORBIT, "the fly orbits its prey");
        fly.forceCooldown(0);
        h.assertTrue(fly.tick(true, 4, true, NO_RANDOM) == PulseGnatBrain.Event.TELL, "then folds its wings (the tell)");
        PulseGnatBrain.Event e = PulseGnatBrain.Event.NONE;
        for (int i = 0; i < VerdureRules.FLY_TELL_TICKS && e == PulseGnatBrain.Event.NONE; i++) e = fly.tick(true, 4, true, NO_RANDOM);
        h.assertTrue(e == PulseGnatBrain.Event.DART && fly.fold() == 1F, "and darts after the tell, wings shut");
        fly.struck();
        h.assertTrue(fly.state() == PulseGnatBrain.State.RECOVER, "a strike ends the dart");

        MireGhastBrain ghoul = new MireGhastBrain();
        h.assertTrue(ghoul.tick(true, 5.0) == MireGhastBrain.Event.NONE, "the ghoul walks while its prey is out of reach");
        h.assertTrue(ghoul.tick(true, 1.0) == MireGhastBrain.Event.TELL && ghoul.rooted(), "in reach it stops and raises its arms");
        int slamAt = -1;
        for (int i = 1; i <= VerdureRules.GHOUL_TELL_TICKS && slamAt < 0; i++) if (ghoul.tick(true, 1.0) == MireGhastBrain.Event.SLAM) slamAt = i;
        h.assertTrue(slamAt == VerdureRules.GHOUL_TELL_TICKS, "the slam comes when the tell ends, at " + slamAt);

        DuskSprigBrain sprout = new DuskSprigBrain();
        h.assertTrue(sprout.tick(false, 99, false, true, true, NO_RANDOM) == DuskSprigBrain.Event.ROOT, "by day with no prey it roots");
        h.assertTrue(sprout.tick(true, 4, true, true, true, NO_RANDOM) == DuskSprigBrain.Event.UPROOT, "prey uproots it");
        sprout.forceCooldown(0);
        h.assertTrue(sprout.tick(true, 4, true, true, false, NO_RANDOM) == DuskSprigBrain.Event.REAR, "at lunge range it rears back (the tell)");
        DuskSprigBrain.Event s = DuskSprigBrain.Event.NONE;
        for (int i = 0; i < VerdureRules.SPROUT_REAR_TICKS && s == DuskSprigBrain.Event.NONE; i++) s = sprout.tick(true, 4, true, true, false, NO_RANDOM);
        h.assertTrue(s == DuskSprigBrain.Event.LEAP && sprout.gape() == 1F, "then lunges, jaws wide");

        MoonTickBrain louse = new MoonTickBrain();
        MoonTickBrain.Event l = MoonTickBrain.Event.NONE;
        for (int i = 0; i < VerdureRules.LOUSE_WAIT_MIN + VerdureRules.LOUSE_CROUCH_TICKS + 1 && l != MoonTickBrain.Event.JUMP; i++) l = louse.tick(true, true, NO_RANDOM);
        h.assertTrue(l == MoonTickBrain.Event.JUMP, "the louse crouches, then hops");
        for (int i = 0; i < 6; i++) louse.tick(true, false, NO_RANDOM);
        h.assertTrue(louse.open() > 0F, "its cases open in the air");
        h.assertTrue(louse.tick(true, true, NO_RANDOM) == MoonTickBrain.Event.LANDED, "and it lands");
        h.succeed();
    }

    // ---- the class weakness ------------------------------------------------------------------------------------------
    private static void eachTakesFullDamageOnlyFromItsFamilysClass(GameTestHelper h) {
        for (Kind k : Kind.values()) {
            for (DamageClass c : DamageClass.values()) {
                float want = c == k.weakness || c == DamageClass.OTHER ? 1F : VerdureRules.RESISTED;
                h.assertTrue(VerdureRules.damageScale(k, c) == want, k + " takes " + want + " from " + c);
            }
        }
        ServerLevel level = h.getLevel();
        Zombie zombie = spawn(h, EntityType.ZOMBIE, new Vec3(-6, 0, -6));
        zombie.setNoAi(true);
        Wolf wolf = spawn(h, EntityType.WOLF, new Vec3(-6, 0, 6));
        wolf.setOwner(FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "verdure-owner")));
        Arrow arrow = new Arrow(EntityType.ARROW, level);
        h.assertTrue(VerdureDamage.classify(level.damageSources().mobAttack(zombie)) == DamageClass.MELEE, "a blow is melee");
        h.assertTrue(VerdureDamage.classify(level.damageSources().arrow(arrow, zombie)) == DamageClass.RANGED, "an arrow is ranged");
        h.assertTrue(VerdureDamage.classify(level.damageSources().magic()) == DamageClass.MAGIC, "magic is magic");
        h.assertTrue(VerdureDamage.classify(level.damageSources().mobAttack(wolf)) == DamageClass.SUMMON, "a tamed wolf's bite is a summon's");
        h.assertTrue(VerdureDamage.classify(level.damageSources().fall()) == DamageClass.OTHER, "a fall is untyped");
        // 1.16: a gun's bolt is magic; a whip's lash is summon and ranged, full on both families but never more than full
        DamageSource bolt = VerdureDamage.source(level, VerdureDamage.SEED_BOLT, zombie, zombie);
        DamageSource lash = VerdureDamage.source(level, DamageClass.SUMMON, zombie, zombie);
        h.assertTrue(VerdureDamage.classes(bolt).equals(java.util.EnumSet.of(DamageClass.MAGIC)), "a gun's bolt is magic only");
        h.assertTrue(VerdureDamage.classes(lash).equals(java.util.EnumSet.of(DamageClass.SUMMON, DamageClass.RANGED)), "a lash is summon and ranged");
        h.assertTrue(VerdureDamage.scale(Kind.DUSK_SPRIG, bolt, 4F) == 4F && VerdureDamage.scale(Kind.PULSE_GNAT, bolt, 4F) == 2F, "a bolt is full on the sprig, half on the gnat");
        for (Kind k : new Kind[] { Kind.PULSE_GNAT, Kind.MOON_TICK }) h.assertTrue(VerdureDamage.scale(k, lash, 4F) == 4F, "a lash is full on " + k);
        for (Kind k : new Kind[] { Kind.MIRE_GHAST, Kind.DUSK_SPRIG }) h.assertTrue(VerdureDamage.scale(k, lash, 4F) == 2F, "and half on " + k);
        // on the mobs themselves: magic (which armour doesn't stop) halves on the fly, not on the sprout
        PulseGnat fly = spawn(h, VerdureEntities.PULSE_GNAT.get(), new Vec3(3, 0, 0));
        DuskSprig sprout = spawn(h, VerdureEntities.DUSK_SPRIG.get(), new Vec3(-3, 0, 0));
        DamageSource magic = level.damageSources().magic();
        fly.hurtServer(level, magic, 4F);
        sprout.hurtServer(level, magic, 4F);
        h.assertTrue(Math.abs(fly.getMaxHealth() - fly.getHealth() - 2F) < 0.01F, "the Pulse Gnat took half of 4 magic, took " + (fly.getMaxHealth() - fly.getHealth()));
        h.assertTrue(Math.abs(sprout.getMaxHealth() - sprout.getHealth() - 4F) < 0.01F, "the Dusk Sprig took all 4 magic, took " + (sprout.getMaxHealth() - sprout.getHealth()));
        for (Mob m : new Mob[] { zombie, wolf, fly, sprout }) m.discard();
        h.succeed();
    }

    // ---- the attacks, on real prey ---------------------------------------------------------------------------------
    private static void pulseGnatDartsThroughItsPrey(GameTestHelper h) {
        Pig pig = prey(h, Vec3.ZERO);
        PulseGnat fly = spawn(h, VerdureEntities.PULSE_GNAT.get(), new Vec3(4, 2, 0));
        fly.setTarget(pig);
        fly.brain.forceCooldown(0);
        h.succeedWhen(() -> {
            h.assertTrue(fly.hits > 0, "a heart shot struck");
            h.assertTrue(pig.getHealth() < pig.getMaxHealth(), "the pig is hurt");
            fly.discard();
            pig.discard();
        });
    }

    private static void mireGhastRaisesItsArmsThenSlams(GameTestHelper h) {
        Pig pig = prey(h, Vec3.ZERO);
        MireGhast ghoul = spawn(h, VerdureEntities.MIRE_GHAST.get(), new Vec3(3, 0, 0));
        ghoul.setTarget(pig);
        h.succeedWhen(() -> {
            h.assertTrue(ghoul.slams > 0, "a slam landed");
            h.assertTrue(pig.getHealth() < pig.getMaxHealth(), "the pig is hurt");
            ghoul.discard();
            pig.discard();
        });
    }

    private static void duskSprigLungesAndBites(GameTestHelper h) {
        Pig pig = prey(h, Vec3.ZERO);
        DuskSprig sprout = spawn(h, VerdureEntities.DUSK_SPRIG.get(), new Vec3(4, 0, 0));
        sprout.debugDaylight = false;
        sprout.setTarget(pig);
        sprout.brain.forceCooldown(0);
        h.succeedWhen(() -> {
            h.assertTrue(sprout.bites > 0, "a bite landed");
            h.assertTrue(pig.getHealth() < pig.getMaxHealth(), "the pig is hurt");
            sprout.discard();
            pig.discard();
        });
    }

    private static void duskSprigRootsByDay(GameTestHelper h) {
        DuskSprig sprout = spawn(h, VerdureEntities.DUSK_SPRIG.get(), Vec3.ZERO);
        sprout.debugDaylight = true;
        h.succeedWhen(() -> {
            h.assertTrue(sprout.brain.state() == DuskSprigBrain.State.ROOTED, "by day it roots, mouth shut");
            h.assertTrue(sprout.brain.gape() == 0F, "its jaws are shut");
            sprout.discard();
        });
    }

    private static void moonTickHopsOntoItsPrey(GameTestHelper h) {
        Pig pig = prey(h, Vec3.ZERO);
        MoonTick louse = spawn(h, VerdureEntities.MOON_TICK.get(), new Vec3(4, 0, 0));
        louse.setTarget(pig);
        h.succeedWhen(() -> {
            h.assertTrue(louse.hops > 0, "it hopped");
            h.assertTrue(louse.touches > 0, "and its touch hurt");
            h.assertTrue(pig.getHealth() < pig.getMaxHealth(), "the pig is hurt");
            louse.discard();
            pig.discard();
        });
    }

    // ---- the harvest, the drops ---------------------------------------------------------------------------------------
    private static void harvestWakesTheFamily(GameTestHelper h) {
        h.assertTrue(VerdureEvents.family(Blocks.SUNFLOWER.defaultBlockState()) == Kind.DUSK_SPRIG, "a sunflower is a Duskpetal");
        h.assertTrue(VerdureEvents.family(Blocks.ROSE_BUSH.defaultBlockState()) == Kind.PULSE_GNAT, "a rose is a Pulseroot");
        h.assertTrue(VerdureEvents.family(Blocks.BLUE_ORCHID.defaultBlockState()) == Kind.MOON_TICK, "a blue orchid is a Moonwisp");
        h.assertTrue(VerdureEvents.family(Blocks.LILY_PAD.defaultBlockState()) == Kind.MIRE_GHAST, "a lily pad is a Mirecap");
        h.assertTrue(VerdureEvents.family(Blocks.DANDELION.defaultBlockState()) == null, "a dandelion is only a dandelion");
        h.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        BlockPos at = h.absolutePos(MIDDLE);
        for (Kind k : Kind.values()) {
            Mob m = VerdureEvents.wake(h.getLevel(), at, k);
            h.assertTrue(m != null && m.isAlive() && m.getType() == VerdureEvents.type(k), k + " wakes");
            m.discard();
        }
        h.succeed();
    }

    /** Each mob's plant, as tools/gen/verdure/data.py DROPS writes its loot table: and (1.1) its token and its herb's seeds. */
    private static java.util.Set<net.minecraft.world.item.Item> plant(Kind k) {
        java.util.Set<net.minecraft.world.item.Item> s = new java.util.HashSet<>(switch (k) {
            case PULSE_GNAT -> java.util.Set.of(net.minecraft.world.item.Items.PINK_DYE);
            case MIRE_GHAST -> java.util.Set.of(net.minecraft.world.item.Items.MUD, net.minecraft.world.item.Items.STICK);
            case DUSK_SPRIG -> java.util.Set.of(net.minecraft.world.item.Items.YELLOW_DYE);
            case MOON_TICK -> java.util.Set.of(net.minecraft.world.item.Items.LIGHT_BLUE_DYE, net.minecraft.world.item.Items.AMETHYST_SHARD);
        });
        s.add(VerdureItems.TOKENS.get(k).get());
        s.add(VerdureItems.SEEDS.get(Herb.of(k, 1)).get());
        return s;
    }

    private static void eachDropsALittleOfItsPlant(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (Kind k : Kind.values()) {
            ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, FloraFerox.id("entities/" + k.id));
            LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
            h.assertTrue(table != LootTable.EMPTY, k + " has a loot table");
            Mob m = spawn(h, VerdureEvents.type(k), new Vec3(0, 0, 0));
            h.assertTrue(m.getLootTable().orElseThrow().equals(key), k + " uses it");
            int drops = 0;
            for (int i = 0; i < 400; i++) {
                LootParams p = new LootParams.Builder(level).withParameter(LootContextParams.THIS_ENTITY, m).withParameter(LootContextParams.ORIGIN, m.position())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic()).create(LootContextParamSets.ENTITY);
                for (ItemStack s : table.getRandomItems(p)) {
                    h.assertTrue(plant(k).contains(s.getItem()), k + " drops only its plant, not " + s);
                    drops += s.getCount();
                }
            }
            h.assertTrue(drops > 60, k + " drops a little of its plant: " + drops + " in 400");
            m.discard();
        }
        h.succeed();
    }
}
