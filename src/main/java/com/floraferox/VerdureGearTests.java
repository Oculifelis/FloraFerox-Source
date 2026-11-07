package com.floraferox;

import com.mojang.authlib.GameProfile;
import com.floraferox.VerdureRules.DamageClass;
import com.floraferox.VerdureRules.Kind;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/**
 * The 1.1 content (docs/DESIGN.md, section 7): splicing, the herbs' harvest and drops, the brews, the class bonuses and
 * Moon's Spite, the armour sets and the platings' fireproofing, the weapons firing, and the Weedwarden's registration and
 * trades. Registered with the mobs' tests (VerdureGameTests.TESTS).
 */
final class VerdureGearTests {
    private VerdureGearTests() { }

    private static final BlockPos MIDDLE = new BlockPos(16, 1, 16);

    static FakePlayer player(GameTestHelper h, Vec3 rel, float yRot, float xRot) {
        FakePlayer fp = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "verdure-tester"));
        Vec3 at = h.absoluteVec(Vec3.atBottomCenterOf(MIDDLE).add(rel));
        fp.snapTo(at.x, at.y, at.z, yRot, xRot);
        fp.getInventory().clearContent();
        fp.removeAllEffects();
        return fp;
    }

    static <T extends Mob> T mob(GameTestHelper h, EntityType<T> type, Vec3 rel) {
        h.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        T m = type.create(h.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 at = h.absoluteVec(Vec3.atBottomCenterOf(MIDDLE).add(rel));
        m.snapTo(at.x, at.y, at.z, 0F, 0F);
        m.setNoAi(true);
        m.setPersistenceRequired();
        h.getLevel().addFreshEntity(m);
        return m;
    }

    private static ItemStack seeds(Herb h) {
        return new ItemStack(VerdureItems.SEEDS.get(h).get());
    }

    static void splicingBreedsTheStrains(GameTestHelper h) {
        ItemStack wheat = new ItemStack(Items.WHEAT_SEEDS);
        h.assertTrue(Splicing.result(wheat, new ItemStack(Items.SUNFLOWER)).is(seeds(Herb.DUSKPETAL).getItem()), "wheat seeds + a sunflower: Duskpetal");
        h.assertTrue(Splicing.result(new ItemStack(Items.POPPY), wheat).is(seeds(Herb.PULSEROOT).getItem()), "a poppy + wheat seeds (either order): Pulseroot");
        h.assertTrue(Splicing.result(wheat, new ItemStack(Items.BLUE_ORCHID)).is(seeds(Herb.MOONWISP).getItem()), "a blue orchid: Moonwisp");
        h.assertTrue(Splicing.result(wheat, new ItemStack(Items.LILY_PAD)).is(seeds(Herb.MIRECAP).getItem()), "a lily pad: Mirecap");
        h.assertTrue(Splicing.result(wheat, new ItemStack(Items.DANDELION)).isEmpty(), "a dandelion breeds nothing");
        ItemStack alien = new ItemStack(VerdureItems.EMBER_GRAFT.get()), thing = new ItemStack(VerdureItems.RIME_GRAFT.get());
        for (Kind k : Kind.values()) {
            h.assertTrue(Splicing.result(seeds(Herb.of(k, 1)), alien).is(seeds(Herb.of(k, 2)).getItem()), k + ": first seeds + Ember Graft = burning strain");
            h.assertTrue(Splicing.result(thing, seeds(Herb.of(k, 2))).is(seeds(Herb.of(k, 3)).getItem()), k + ": burning seeds + Rime Graft = frozen strain");
            h.assertTrue(Splicing.result(seeds(Herb.of(k, 1)), thing).isEmpty(), k + ": the frozen strain needs the burning one first");
        }
        h.succeed();
    }

    static void grownHerbWakesItsMonsterAndDropsItself(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        level.getServer().setDifficulty(Difficulty.NORMAL, true);
        BlockPos rel = MIDDLE.offset(2, 0, 0);
        h.setBlock(rel.below(), Blocks.FARMLAND);
        HerbCropBlock crop = VerdureBlocks.CROPS.get(Herb.MIRECAP).get();
        BlockState grown = crop.getStateForAge(3);
        BlockPos at = h.absolutePos(rel);
        List<ItemStack> young = Block.getDrops(crop.getStateForAge(1), level, at, null);
        h.assertTrue(young.size() == 1 && young.get(0).is(VerdureItems.SEEDS.get(Herb.MIRECAP).get()), "a young herb gives back its seed: " + young);
        boolean herb = false;
        for (int i = 0; i < 20 && !herb; i++) for (ItemStack s : Block.getDrops(grown, level, at, null)) herb |= s.is(VerdureItems.HERBS.get(Herb.MIRECAP).get());
        h.assertTrue(herb, "a grown herb gives the herb");
        h.setBlock(rel, grown);
        FakePlayer fp = player(h, new Vec3(0, 0, -1), 0F, 0F);
        fp.gameMode.destroyBlock(at);
        h.assertTrue(level.getBlockState(at).isAir(), "the herb was picked");
        AABB box = new AABB(at).inflate(3);
        h.assertTrue(!level.getEntities(VerdureEntities.MIRE_GHAST.get(), box, e -> true).isEmpty(), "picking a grown Mirecap wakes a Mire Ghast");
        level.getEntities(VerdureEntities.MIRE_GHAST.get(), box, e -> true).forEach(e -> e.discard());
        h.succeed();
    }

    static void herbsBrewTheirFamilysDrink(GameTestHelper h) {
        ItemStack awkward = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD);
        for (Herb herb : Herb.values()) {
            ItemStack ingredient = new ItemStack(VerdureItems.HERBS.get(herb).get());
            h.assertTrue(h.getLevel().potionBrewing().hasMix(awkward, ingredient), herb + " brews in an Awkward Potion");
            ItemStack out = h.getLevel().potionBrewing().mix(ingredient, awkward);
            PotionContents pc = out.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            h.assertTrue(pc != null && pc.is(VerdurePotions.BREWS.get(herb)), herb + " gives its brew, not " + pc);
            boolean classFx = false;
            for (MobEffectInstance fx : pc.getAllEffects()) classFx |= fx.is(VerdurePotions.classEffect(herb.kind)) && fx.getAmplifier() == herb.tier - 1;
            h.assertTrue(classFx, herb + "'s brew carries its family's effect at its tier");
        }
        h.succeed();
    }

    static void classBonusesAndLunarScorn(GameTestHelper h) {
        h.assertTrue(Math.abs(VerdureRules.classBonus(2, true) - (0.2F + 0.15F)) < 1e-5, "the bonuses add up (1.16: a set adds none)");
        ServerLevel level = h.getLevel();
        FakePlayer fp = player(h, new Vec3(0, 0, -3), 0F, 0F);
        Pig plain = mob(h, EntityType.PIG, new Vec3(-2, 0, 0));
        plain.hurtServer(level, VerdureDamage.source(level, DamageClass.MAGIC, fp, fp), 2F);
        h.assertTrue(Math.abs(plain.getMaxHealth() - plain.getHealth() - 2F) < 0.01F, "no bonus: 2 damage, took " + (plain.getMaxHealth() - plain.getHealth()));
        fp.addEffect(new MobEffectInstance(VerdurePotions.DUSK_VEIL, 200, 1));
        Pig cloaked = mob(h, EntityType.PIG, new Vec3(0, 0, 0));
        cloaked.hurtServer(level, VerdureDamage.source(level, DamageClass.MAGIC, fp, fp), 2F);
        h.assertTrue(Math.abs(cloaked.getMaxHealth() - cloaked.getHealth() - 2.4F) < 0.01F, "Dusk Veil II: +20% magic, took " + (cloaked.getMaxHealth() - cloaked.getHealth()));
        Pig ranged = mob(h, EntityType.PIG, new Vec3(2, 0, 0));
        ranged.hurtServer(level, VerdureDamage.source(level, DamageClass.RANGED, fp, fp), 2F);
        h.assertTrue(Math.abs(ranged.getMaxHealth() - ranged.getHealth() - 2F) < 0.01F, "Dusk Veil doesn't help ranged blows");
        fp.removeAllEffects();
        fp.getInventory().add(new ItemStack(VerdureItems.CHARMS.get(Kind.PULSE_GNAT).get()));
        Pig charmed = mob(h, EntityType.PIG, new Vec3(4, 0, 0));
        charmed.hurtServer(level, VerdureDamage.source(level, DamageClass.RANGED, fp, fp), 2F);
        h.assertTrue(Math.abs(charmed.getMaxHealth() - charmed.getHealth() - 2.3F) < 0.01F, "the Heart Charm: +15% ranged, took " + (charmed.getMaxHealth() - charmed.getHealth()));
        fp.getInventory().clearContent();
        Pig scorned = mob(h, EntityType.PIG, new Vec3(-4, 0, 0));
        scorned.addEffect(new MobEffectInstance(VerdurePotions.MOONS_SPITE, 100, 0));
        scorned.hurtServer(level, VerdureDamage.source(level, DamageClass.MELEE, fp, fp), 2F);
        h.assertTrue(Math.abs(scorned.getMaxHealth() - scorned.getHealth() - 3F) < 0.01F, "Moon's Spite: the next blow x1.5, took " + (scorned.getMaxHealth() - scorned.getHealth()));
        h.assertTrue(!scorned.hasEffect(VerdurePotions.MOONS_SPITE), "and the scorn is spent");
        h.succeed();
    }

    static void setsAndFireproofing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Zombie z = mob(h, EntityType.ZOMBIE, new Vec3(0, 0, 0));
        z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(VerdureItems.HELMETS.get(Herb.PULSEFLARE).get()));
        z.setItemSlot(EquipmentSlot.CHEST, new ItemStack(VerdureItems.PLATINGS[2].get()));
        h.assertTrue(VerdureGear.set(z) == null, "no boots, no set");
        z.setItemSlot(EquipmentSlot.FEET, new ItemStack(VerdureItems.BOOTS[2].get()));
        h.assertTrue(VerdureGear.set(z) == null, "no leggings, no set (1.9: a full set is four pieces)");
        z.setItemSlot(EquipmentSlot.LEGS, new ItemStack(VerdureItems.LEGGINGS[2].get()));
        h.assertTrue(VerdureGear.set(z) == Herb.PULSEFLARE, "Emberbark Armet, Plate, Leggings and Boots: the second ranger set");
        h.assertTrue(VerdureGear.setTier(z, DamageClass.RANGED) == 2 && VerdureGear.setTier(z, DamageClass.MELEE) == 0, "it serves ranged only");
        z.setItemSlot(EquipmentSlot.FEET, new ItemStack(VerdureItems.BOOTS[1].get()));
        h.assertTrue(VerdureGear.set(z) == null, "boots of another tier break the set");
        z.setItemSlot(EquipmentSlot.FEET, new ItemStack(VerdureItems.BOOTS[2].get()));
        z.setItemSlot(EquipmentSlot.LEGS, new ItemStack(VerdureItems.LEGGINGS[3].get()));
        h.assertTrue(VerdureGear.set(z) == null, "and so do leggings of another tier");
        h.assertTrue(new ItemStack(VerdureItems.LEGGINGS[1].get()).is(net.minecraft.tags.ItemTags.LEG_ARMOR), "the leggings are leg armour");
        float hp = z.getHealth();
        z.hurtServer(level, level.damageSources().onFire(), 4F);
        h.assertTrue(z.getHealth() == hp, "Emberbark Plate: fire can't hurt it");
        z.setItemSlot(EquipmentSlot.CHEST, new ItemStack(VerdureItems.PLATINGS[1].get()));
        z.hurtServer(level, level.damageSources().onFire(), 4F);
        h.assertTrue(z.getHealth() < hp, "Leafguard Plate isn't fireproof");
        h.succeed();
    }

    static void weaponsFire(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer fp = player(h, new Vec3(0, 0, -4), 0F, 20F);
        AABB around = new AABB(h.absolutePos(MIDDLE)).inflate(12);
        int before = level.getEntities(VerdureEntities.SHOT.get(), around, e -> true).size();
        ItemStack blade = new ItemStack(VerdureItems.WEAPONS.get(Herb.MIRECAP).get());
        fp.setItemInHand(InteractionHand.MAIN_HAND, blade);
        h.assertTrue(blade.getItem().use(level, fp, InteractionHand.MAIN_HAND) == InteractionResult.SUCCESS, "the Mirecap Cleaver slashes");
        ItemStack gun = new ItemStack(VerdureItems.WEAPONS.get(Herb.PULSEROOT).get());
        fp.setItemInHand(InteractionHand.MAIN_HAND, gun);
        h.assertTrue(gun.getItem().use(level, fp, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "the Pulse Pistol won't fire without Seed Pellets");
        fp.getInventory().add(new ItemStack(VerdureItems.SEED_PELLET.get(), 3));
        h.assertTrue(gun.getItem().use(level, fp, InteractionHand.MAIN_HAND) == InteractionResult.SUCCESS, "with Seed Pellets it fires");
        h.assertTrue(VerdureWeapons.BloomGun.ammo(fp).getCount() == 2, "and spends one");
        ItemStack staff = new ItemStack(VerdureItems.WEAPONS.get(Herb.DUSKPETAL).get());
        fp.setItemInHand(InteractionHand.MAIN_HAND, staff);
        ((VerdureWeapons.PetalStaff) staff.getItem()).cast(level, fp, InteractionHand.MAIN_HAND);
        h.assertTrue(level.getEntities(VerdureEntities.SHOT.get(), around, e -> true).size() == before + 3, "three shots flew");
        level.getEntities(VerdureEntities.SHOT.get(), around, e -> true).forEach(e -> e.discard());
        h.succeed();
    }

    /** A full set of the helmet's tier on a body. */
    static void wear(net.minecraft.world.entity.LivingEntity body, Herb helmet) {
        body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(VerdureItems.HELMETS.get(helmet).get()));
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(VerdureItems.PLATINGS[helmet.tier].get()));
        body.setItemSlot(EquipmentSlot.LEGS, new ItemStack(VerdureItems.LEGGINGS[helmet.tier].get()));
        body.setItemSlot(EquipmentSlot.FEET, new ItemStack(VerdureItems.BOOTS[helmet.tier].get()));
    }

    /** 1.12: a sceptre's right click is its tier's support, on one cooldown shared by every sceptre. */
    /** 1.13: every hook goes in Fundamentum's hook slot with its data loaded, each strain reaching further than the last. */
    static void hooksUpgradeAsTheWeaponsDo(GameTestHelper h) {
        net.minecraft.tags.TagKey<net.minecraft.world.item.Item> slot = net.minecraft.tags.ItemTags.create(net.minecraft.resources.Identifier.fromNamespaceAndPath("curios", "hook"));
        for (Kind k : new Kind[]{Kind.MIRE_GHAST, Kind.DUSK_SPRIG, Kind.PULSE_GNAT, Kind.MOON_TICK}) {
            double reach = 0;
            for (int t = 1; t <= 3; t++) {
                ItemStack hook = new ItemStack(VerdureItems.HOOKS.get(Herb.of(k, t)).get());
                h.assertTrue(hook.is(slot), hook.getItem() + " goes in the hook slot");
                com.fundamentum.hook.Hooks.Def def = com.fundamentum.hook.Hooks.def(hook);
                h.assertTrue(def != null && def.texture() != null, hook.getItem() + " has its hook data");
                h.assertTrue(def.range() > reach, hook.getItem() + " reaches further than the strain before");
                reach = def.range();
            }
        }
        h.succeed();
    }

    /** 1.14: each tier's wings go in Fundamentum's wings slot, fly longer than the tier before and draw from their asset. */
    static void wingsFlyFurtherEachTier(GameTestHelper h) {
        net.minecraft.tags.TagKey<net.minecraft.world.item.Item> slot = net.minecraft.tags.ItemTags.create(net.minecraft.resources.Identifier.fromNamespaceAndPath("curios", "wings"));
        int ticks = 0;
        for (int t = 1; t <= 3; t++) {
            ItemStack wings = new ItemStack(VerdureItems.WINGS[t].get());
            h.assertTrue(wings.is(slot), wings.getItem() + " goes in the wings slot");
            com.fundamentum.wings.Wings.Stats stats = com.fundamentum.wings.Wings.STATS.get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(wings.getItem()));
            h.assertTrue(stats != null && stats.flightTicks() > ticks, wings.getItem() + " flies longer than the tier before");
            ticks = stats.flightTicks();
            var eq = wings.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
            h.assertTrue(eq != null && eq.assetId().isPresent(), wings.getItem() + " is drawn from its equipment asset");
        }
        h.succeed();
    }

    static void sceptreSupports(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer fp = player(h, new Vec3(0, 0, 0), 0F, 0F);
        fp.getPersistentData().remove(FloraFerox.MODID + ":support_ready");
        Zombie z = mob(h, EntityType.ZOMBIE, new Vec3(2, 0, 0));
        fp.setHealth(10F);
        ItemStack dusk = new ItemStack(VerdureItems.WEAPONS.get(Herb.DUSKPETAL).get());
        fp.setItemInHand(InteractionHand.MAIN_HAND, dusk);
        h.assertTrue(dusk.getItem().use(level, fp, InteractionHand.MAIN_HAND) == InteractionResult.SUCCESS, "Dusk Bloom");
        h.assertTrue(fp.getHealth() == 10F + VerdureRules.BLOOM_HEAL, "heals you two hearts, was " + fp.getHealth());
        h.assertTrue(z.getHealth() == z.getMaxHealth(), "and never a hostile");
        ItemStack rime = new ItemStack(VerdureItems.WEAPONS.get(Herb.DUSKRIME).get());
        fp.setItemInHand(InteractionHand.MAIN_HAND, rime);
        h.assertTrue(rime.getItem().use(level, fp, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "another sceptre waits on the same cooldown");
        fp.getPersistentData().remove(FloraFerox.MODID + ":support_ready");
        h.assertTrue(rime.getItem().use(level, fp, InteractionHand.MAIN_HAND) == InteractionResult.SUCCESS, "Rime Shell once ready");
        h.assertTrue(fp.hasEffect(net.minecraft.world.effect.MobEffects.RESISTANCE) && fp.getAbsorptionAmount() > 0F, "hardens you");
        h.assertTrue(z.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS) && z.isFullyFrozen(), "and slows and freezes the zombie near you");
        fp.getPersistentData().remove(FloraFerox.MODID + ":support_ready");
        ItemStack ember = new ItemStack(VerdureItems.WEAPONS.get(Herb.DUSKFLARE).get());
        fp.setItemInHand(InteractionHand.MAIN_HAND, ember);
        h.assertTrue(ember.getItem().use(level, fp, InteractionHand.MAIN_HAND) == InteractionResult.SUCCESS
                && fp.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE), "Ember Ward makes you fireproof");
        fp.getPersistentData().remove(FloraFerox.MODID + ":support_ready");
        h.succeed();
    }

    /** 1.12: each class helmet's own set effect (VerdureSets); a FakePlayer can't be hurt, so its side is called directly. */
    static void helmetSetEffects(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer fp = player(h, new Vec3(0, 0, -3), 0F, 0F);
        for (String k : new String[] {"phoenix_ready", "aegis_ready", "hibernate_ready", "flash_ready"}) fp.getPersistentData().remove(FloraFerox.MODID + ":" + k);
        Zombie z = mob(h, EntityType.ZOMBIE, new Vec3(0, 0, -1));
        var hit = level.damageSources().mobAttack(z);
        // Rootbound
        fp.setShiftKeyDown(true);
        h.assertTrue(VerdureSets.hurt(fp, Herb.MOONWISP, hit, 4F) == VerdureRules.ROOT_TAKEN, "Rootbound: sneaking, less damage");
        fp.setShiftKeyDown(false);
        h.assertTrue(VerdureSets.hurt(fp, Herb.MOONWISP, hit, 4F) == 1F, "standing, all of it");
        // Glacial Aegis
        h.assertTrue(VerdureSets.hurt(fp, Herb.PULSERIME, hit, 4F) == 0F, "Glacial Aegis turns the first blow");
        h.assertTrue(VerdureSets.hurt(fp, Herb.PULSERIME, hit, 4F) == 1F, "then has to regrow");
        // Hibernate
        fp.setHealth(8F);
        h.assertTrue(VerdureSets.hurt(fp, Herb.MOONRIME, hit, 1F) == 1F, "Hibernate: a light blow passes");
        h.assertTrue(VerdureSets.hurt(fp, Herb.MOONRIME, hit, 4F) == 0F && fp.hasEffect(net.minecraft.world.effect.MobEffects.RESISTANCE),
                "one that would leave you under 3 hearts seals you in ice");
        h.assertTrue(VerdureSets.hurt(fp, Herb.MOONRIME, hit, 4F) == 1F, "once a minute");
        // Phoenix Bloom
        fp.setHealth(1F);
        VerdureSets.phoenix(fp, level);
        h.assertTrue(fp.getHealth() == VerdureRules.PHOENIX_HEALTH && z.isOnFire(), "Phoenix Bloom: back at 4 hearts, the zombie burning");
        h.assertTrue(!VerdureSets.ready(fp, FloraFerox.MODID + ":phoenix_ready"), "and it waits five minutes");
        // Smelter's Touch
        h.assertTrue(VerdureSets.smelt(level, new ItemStack(Items.RAW_IRON, 2)).is(Items.IRON_INGOT), "Smelter's Touch: raw iron smelts to ingots");
        h.assertTrue(VerdureSets.smelt(level, new ItemStack(Items.RAW_IRON, 2)).getCount() == 2, "one for one");
        h.assertTrue(VerdureSets.smelt(level, new ItemStack(Items.DIRT)).isEmpty(), "dirt stays dirt");
        // Frostwalk
        BlockPos water = h.absolutePos(new BlockPos(5, 1, 5));
        level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(water.above(), Blocks.AIR.defaultBlockState());
        fp.snapTo(water.getX() + 0.5, water.getY() + 1, water.getZ() + 0.5, 0F, 0F);
        VerdureSets.frostwalk(fp, level);
        h.assertTrue(level.getBlockState(water).is(Blocks.FROSTED_ICE), "Frostwalk: water under your feet freezes");
        for (String k : new String[] {"phoenix_ready", "aegis_ready", "hibernate_ready", "flash_ready"}) fp.getPersistentData().remove(FloraFerox.MODID + ":" + k);
        h.succeed();
    }

    /** 1.6: the whips are Fundamentum cord whips; a FakePlayer doesn't tick, so the test steps its swing (Whips.tick). */
    private static final java.lang.reflect.Method WHIP_TICK = whipTick();

    private static java.lang.reflect.Method whipTick() {
        try {
            java.lang.reflect.Method m = com.fundamentum.whip.Whips.class.getDeclaredMethod("tick", net.minecraft.server.level.ServerPlayer.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Fundamentum's Whips.tick moved", e);
        }
    }

    /**
     * Each whip in turn swings at zombies (fire-resistant, still) in front of the player: the Moonwisp at one dead ahead,
     * the Moonflare's two strands at two either side, the Moonrime's three at three across a wide cone. Every one is
     * wounded by summon damage, scorned (Moon's Spite), strained by the tier, and glows (the colour is client side); the whip
     * wears once and cools down.
     */
    static void whipsCrack(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer fp = player(h, new Vec3(0, 0, -4), 0F, 0F);
        int[] tick = {0};
        int[] tier = {1};
        List<Zombie> zombies = new java.util.ArrayList<>();
        ItemStack[] whip = {ItemStack.EMPTY};
        h.onEachTick(() -> {
            if (tier[0] > 3) return;
            int t = tier[0];
            tick[0]++;
            fp.getCooldowns().tick();
            try {
                WHIP_TICK.invoke(null, fp);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            if (tick[0] == 1) {
                whip[0] = new ItemStack(VerdureItems.WEAPONS.get(Herb.of(Kind.MOON_TICK, t)).get());
                fp.setItemInHand(InteractionHand.MAIN_HAND, whip[0]);
                com.fundamentum.whip.WhipDefinition def = com.fundamentum.whip.Whips.definition(whip[0]);
                h.assertTrue(def != null, "tier " + t + " is a Fundamentum whip");
                h.assertTrue(def.tuning.strands() == t && def.homingDegrees > 0 && (t == 1) != def.strandTargets && def.idleCoil
                        && def.cordFx == (t == 2 ? com.fundamentum.whip.WhipCordFx.ENERGY : com.fundamentum.whip.WhipCordFx.GLOW),
                        "tier " + t + ": " + t + " strand(s), homing, coiled, its cord shader");
                h.assertTrue(def.tuning.damage() == VerdureRules.LASH_DAMAGE[t] && Math.abs(def.tuning.length() - VerdureRules.LASH_RANGE) <= 0.5,
                        "tier " + t + ": the lash's damage and about its range");
                h.assertTrue(whip[0].is(net.minecraft.tags.ItemTags.create(net.minecraft.resources.Identifier.fromNamespaceAndPath("fundamentum", "whips"))),
                        "tier " + t + " is in #fundamentum:whips");
                double[] offs = t == 1 ? new double[] {0} : t == 2 ? new double[] {-30, 30} : new double[] {-40, 0, 40};
                for (double off : offs) {
                    double r = Math.toRadians(off), d = t == 1 ? 4.2 : 3.5;
                    Zombie z = mob(h, EntityType.ZOMBIE, new Vec3(-Math.sin(r) * d, 0, -4 + Math.cos(r) * d));
                    z.setNoGravity(true);
                    z.addEffect(new MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 1200, 0, false, false));
                    zombies.add(z);
                }
            } else if (tick[0] == 3) {
                h.assertTrue(com.fundamentum.whip.Whips.swing(fp), "tier " + t + " swings");
                h.assertTrue(fp.getCooldowns().isOnCooldown(whip[0]), "tier " + t + " cools down");
            } else if (tick[0] == 35) {
                for (Zombie z : zombies) {
                    h.assertTrue(z.getHealth() < z.getMaxHealth(), "tier " + t + ": the zombie at " + z.position() + " is wounded");
                    h.assertTrue(z.hasEffect(VerdurePotions.MOONS_SPITE), "tier " + t + ": and scorned");
                    h.assertTrue(z.getLastDamageSource() != null && VerdureDamage.classify(z.getLastDamageSource()) == DamageClass.SUMMON,
                            "tier " + t + ": by summon damage");
                    h.assertTrue(z.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING), "tier " + t + ": and glows (its colour is client side)");
                    h.assertTrue(t != 3 || z.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS), "the Moonrime chills");
                    z.discard();
                }
                h.assertTrue(whip[0].getDamageValue() == 1, "tier " + t + " wore once: " + whip[0].getDamageValue());
                zombies.clear();
                tier[0]++;
                tick[0] = 0;
                if (tier[0] > 3) h.succeed();
            }
        });
    }

    static void theWeedwardenKeepsTheGraftingBench(GameTestHelper h) {
        BlockState table = VerdureBlocks.GRAFTING_BENCH.get().defaultBlockState();
        h.assertTrue(PoiTypes.forState(table).map(p -> p.is(VerdureVillagers.WEEDWARDEN_POI.getKey())).orElse(false), "the Grafting Bench is the Weedwarden's workstation");
        var prof = VerdureVillagers.WEEDWARDEN.get();
        h.assertTrue(prof.heldJobSite().test(PoiTypes.forState(table).orElseThrow()), "the Weedwarden holds it");
        var sets = h.getLevel().registryAccess().lookupOrThrow(Registries.TRADE_SET);
        for (int lvl = 1; lvl <= 5; lvl++) {
            ResourceKey<TradeSet> key = prof.tradeSetsByLevel().get(lvl);
            h.assertTrue(key != null, "a trade set for level " + lvl);
            TradeSet set = sets.getValue(key);
            h.assertTrue(set != null && set.getTrades().size() >= 3, "level " + lvl + " has its trades: " + (set == null ? "none" : set.getTrades().size()));
        }
        h.succeed();
    }
}
