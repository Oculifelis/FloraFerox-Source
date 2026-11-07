package com.floraferox;

import com.floraferox.VerdureRules.DamageClass;
import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Kind;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 1.2's six (docs/DESIGN.md, section 8): the strike brain's cadence for each, each taking full damage only from its
 * family's class, each landing its blow on a still pig (and the leech drinking, the burr and the sundew clinging), and
 * each one's drops; and 1.3's trophies, their charms, the later strains' hunters and the Burr Pellet.
 */
public final class FeroxTests {
    private FeroxTests() { }

    static void brainsTellThenStrike(GameTestHelper h) {
        for (Ferox f : Ferox.values()) {
            StrikeBrain b = new StrikeBrain(f);
            h.assertTrue(b.tick(true, f.reach + 1) == StrikeBrain.Event.NONE, f + " closes while its prey is out of reach");
            h.assertTrue(b.tick(true, f.reach) == StrikeBrain.Event.TELL && b.rooted(), f + " stops in reach to show its tell");
            int at = -1;
            for (int i = 1; i <= f.tell && at < 0; i++) if (b.tick(true, f.reach) == StrikeBrain.Event.STRIKE) at = i;
            h.assertTrue(at == f.tell, f + " strikes as the tell ends, at " + at);
            h.assertTrue(StrikeBrain.attack(f, StrikeBrain.State.STRIKE, 0) == f.tellEnd, f + "'s blow starts where its sheets' tell ends");
            for (int i = 0; i < f.strike + f.recover; i++) b.tick(true, f.reach);
            h.assertTrue(b.state() == StrikeBrain.State.WALK, f + " recovers and walks again");
            h.assertTrue(b.tick(true, f.reach) == StrikeBrain.Event.NONE, f + " waits out its cooldown");
            StrikeBrain lost = new StrikeBrain(f);
            lost.tick(true, 0);
            lost.tick(false, Double.MAX_VALUE);
            h.assertTrue(lost.state() == StrikeBrain.State.WALK, f + " drops its tell when its prey is gone");
        }
        h.succeed();
    }

    static void eachTakesFullDamageOnlyFromItsFamilysClass(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        DamageSource magic = level.damageSources().magic();
        for (Ferox f : Ferox.values()) {
            FeroxMob m = VerdureGameTests.spawn(h, VerdureEntities.ferox(f), new Vec3(0, 0, 0));
            m.hurtServer(level, magic, 4F);
            float want = f.kind.weakness == DamageClass.MAGIC ? 4F : 2F;
            float took = m.getMaxHealth() - m.getHealth();
            h.assertTrue(Math.abs(took - want) < 0.01F, f + " (" + f.kind + ") took " + took + " of 4 magic, not " + want);
            m.discard();
        }
        h.succeed();
    }

    static void strikesItsPrey(GameTestHelper h, Ferox f) {
        Pig pig = VerdureGameTests.prey(h, Vec3.ZERO);
        FeroxMob m = VerdureGameTests.spawn(h, VerdureEntities.ferox(f), new Vec3(3.5, 0, 0));
        m.setTarget(pig);
        if (f == Ferox.LANTERN_LEECH) m.setHealth(m.getMaxHealth() - 8F);
        float hurt = m.getHealth();
        h.succeedWhen(() -> {
            h.assertTrue(m.strikes > 0, "a blow landed");
            h.assertTrue(pig.getHealth() < pig.getMaxHealth(), "the pig is hurt");
            if (f == Ferox.LANTERN_LEECH) h.assertTrue(m.getHealth() > hurt, "the leech drank: " + hurt + " -> " + m.getHealth());
            if (f == Ferox.BURR_CRAWLER || f == Ferox.DEW_CRAB) h.assertTrue(pig.hasEffect(MobEffects.SLOWNESS), "the pig is held fast");
            m.discard();
            pig.discard();
        });
    }

    /** Each one's plant, as tools/gen/verdure/data.py DROPS writes its loot table, with its family's token and seeds. */
    private static final Map<Ferox, Set<Item>> PLANT = Map.of(
            Ferox.SNAP_DRAKE, Set.of(Items.ORANGE_DYE),
            Ferox.REED_WRETCH, Set.of(Items.SUGAR_CANE),
            Ferox.LANTERN_LEECH, Set.of(Items.GLOW_BERRIES),
            Ferox.BURR_CRAWLER, Set.of(Items.WHEAT_SEEDS),
            Ferox.ORCHID_MANTIS, Set.of(Items.WHITE_DYE),
            Ferox.DEW_CRAB, Set.of(Items.SLIME_BALL, Items.RED_DYE));

    static void eachDropsALittleOfItsPlant(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (Ferox f : Ferox.values()) {
            Set<Item> plant = new java.util.HashSet<>(PLANT.get(f));
            plant.add(VerdureItems.TOKENS.get(f.kind).get());
            plant.add(VerdureItems.SEEDS.get(Herb.of(f.kind, 1)).get());
            Item trophy = VerdureItems.TROPHIES.get(f).get();
            plant.add(trophy);                                 // 1.3 (the burning seeds and grafts need a player's kill)
            int trophies = 0;
            ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, FloraFerox.id("entities/" + f.id));
            LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
            h.assertTrue(table != LootTable.EMPTY, f + " has a loot table");
            FeroxMob m = VerdureGameTests.spawn(h, VerdureEntities.ferox(f), Vec3.ZERO);
            h.assertTrue(m.getLootTable().orElseThrow().equals(key), f + " uses it");
            int drops = 0;
            for (int i = 0; i < 400; i++) {
                LootParams p = new LootParams.Builder(level).withParameter(LootContextParams.THIS_ENTITY, m).withParameter(LootContextParams.ORIGIN, m.position())
                        .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic()).create(LootContextParamSets.ENTITY);
                for (ItemStack s : table.getRandomItems(p)) {
                    h.assertTrue(plant.contains(s.getItem()), f + " drops only its plant, not " + s);
                    drops += s.getCount();
                    if (s.is(trophy)) trophies += s.getCount();
                }
            }
            h.assertTrue(drops > 60, f + " drops a little of its plant: " + drops + " in 400");
            h.assertTrue(trophies > 90 && trophies < 200, f + " drops its trophy about a third of the time: " + trophies + " in 400");
            m.discard();
        }
        h.succeed();
    }

    // ---- 1.3: the trophies, their charms, the later strains' hunters, the Burr Pellet --------------------------------
    private static String familyTag(Kind k) {
        return switch (k) {
            case DUSK_SPRIG -> "night";
            case PULSE_GNAT -> "heart";
            case MOON_TICK -> "lunar";
            case MIRE_GHAST -> "bog";
        };
    }

    private static void needs(GameTestHelper h, RecipeManager recipes, DeferredItem<?> result, ItemStack trophy) {
        var r = recipes.byKey(ResourceKey.create(Registries.RECIPE, result.getId())).orElse(null);
        h.assertTrue(r != null, "a recipe for " + result.getId());
        h.assertTrue(r.value().placementInfo().ingredients().stream().anyMatch(i -> i.test(trophy)), result.getId() + " takes " + trophy);
    }

    static void trophiesGateTheStrains(GameTestHelper h) {
        RecipeManager recipes = h.getLevel().getServer().getRecipeManager();
        for (Ferox f : Ferox.values()) {
            ItemStack t = new ItemStack(VerdureItems.TROPHIES.get(f).get());
            h.assertTrue(t.is(ItemTags.create(FloraFerox.id("trophies"))) && t.is(ItemTags.create(FloraFerox.id("trophies/" + familyTag(f.kind)))),
                    f + "'s trophy is in its family's tag");
            for (int tier = 2; tier <= 3; tier++) {
                Herb herb = Herb.of(f.kind, tier);
                needs(h, recipes, VerdureItems.WEAPONS.get(herb), t);
                needs(h, recipes, VerdureItems.HELMETS.get(herb), t);
                needs(h, recipes, VerdureItems.PLATINGS[tier], t);
                needs(h, recipes, VerdureItems.LEGGINGS[tier], t);
                needs(h, recipes, VerdureItems.BOOTS[tier], t);
            }
            needs(h, recipes, VerdureItems.FEROX_CHARMS.get(f), t);
        }
        needs(h, recipes, VerdureItems.BURR_PELLET, new ItemStack(VerdureItems.TROPHIES.get(Ferox.BURR_CRAWLER).get()));
        h.succeed();
    }

    private static ItemStack charm(Ferox f) {
        return new ItemStack(VerdureItems.FEROX_CHARMS.get(f).get());
    }

    private static boolean slowedBy(GameTestHelper h, FakePlayer fp, DamageClass cls, Vec3 at) {
        Pig pig = VerdureGearTests.mob(h, EntityType.PIG, at);
        pig.hurtServer(h.getLevel(), VerdureDamage.source(h.getLevel(), cls, fp, fp), 1F);
        return pig.hasEffect(MobEffects.SLOWNESS);
    }

    static void feroxCharmsWork(GameTestHelper h) {
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -3), 0F, 0F);
        fp.getInventory().add(charm(Ferox.DEW_CRAB));
        h.assertTrue(Math.abs(VerdureGear.bonus(fp, DamageClass.MELEE) - VerdureRules.CHARM_BONUS) < 1e-5, "the Sundew Charm keeps the Mire Charm's +15% melee");
        h.assertTrue(slowedBy(h, fp, DamageClass.MELEE, new Vec3(-4, 0, 0)), "and its melee hits slow");
        h.assertTrue(!slowedBy(h, fp, DamageClass.RANGED, new Vec3(-2, 0, 0)), "but not its ranged ones");
        fp.getInventory().clearContent();
        fp.getInventory().add(charm(Ferox.BURR_CRAWLER));
        h.assertTrue(VerdureGear.hasCharm(fp, Kind.PULSE_GNAT), "the Hookburr Charm counts as the Pulse Charm");
        h.assertTrue(slowedBy(h, fp, DamageClass.RANGED, new Vec3(0, 0, 0)), "and its ranged hits slow");
        fp.getInventory().clearContent();
        fp.getInventory().add(charm(Ferox.LANTERN_LEECH));
        fp.setHealth(10F);
        slowedBy(h, fp, DamageClass.SUMMON, new Vec3(2, 0, 0));
        h.assertTrue(fp.getHealth() > 10F, "the Lantern Charm: a summon hit heals, now " + fp.getHealth());
        fp.getInventory().clearContent();
        h.assertTrue(VerdureGear.lashFactor(fp) == 1F, "without the Orchid Charm, whips keep their pace");
        fp.getInventory().add(charm(Ferox.ORCHID_MANTIS));
        h.assertTrue(VerdureGear.lashFactor(fp) == VerdureRules.ORCHID_COOLDOWN, "with it they recover sooner");
        fp.getInventory().clearContent();
        fp.getInventory().add(charm(Ferox.REED_WRETCH));
        fp.setShiftKeyDown(true);
        fp.setOnGround(false);
        fp.setDeltaMovement(0, -0.5, 0);
        VerdureGear.cattail(fp);
        h.assertTrue(fp.hasEffect(MobEffects.SLOW_FALLING), "the Cattail Charm: sneaking in the air, you drift down");
        fp.setShiftKeyDown(false);
        fp.getInventory().clearContent();
        fp.getInventory().add(charm(Ferox.SNAP_DRAKE));
        h.assertTrue(Math.abs(VerdureGear.bonus(fp, DamageClass.MAGIC) - VerdureRules.CHARM_BONUS) < 1e-5, "the Snapjaw Charm keeps the Dusk Charm's +15% magic");
        fp.getInventory().clearContent();
        h.succeed();
    }

    static void laterStrainsWakeTheNewHunters(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = middle(h);
        for (Kind k : Kind.values()) {
            Mob first = VerdureEvents.wake(level, at, Herb.of(k, 1));
            h.assertTrue(first != null && first.getType() == VerdureEvents.type(k), Herb.of(k, 1) + " still wakes " + k);
            first.discard();
            Set<EntityType<?>> hunters = new java.util.HashSet<>();
            for (Ferox f : Ferox.values()) if (f.kind == k) hunters.add(VerdureEntities.ferox(f));
            for (int tier = 2; tier <= 3; tier++) {
                for (int i = 0; i < 6; i++) {
                    Mob m = VerdureEvents.wake(level, at, Herb.of(k, tier));
                    h.assertTrue(m != null && hunters.contains(m.getType()), Herb.of(k, tier) + " wakes one of the family's new hunters, not " + (m == null ? null : m.getType()));
                    m.discard();
                }
            }
        }
        h.succeed();
    }

    static void burrPelletsLoadTheGuns(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -4), 0F, 20F);
        ItemStack gun = new ItemStack(VerdureItems.WEAPONS.get(Herb.PULSEROOT).get());
        fp.setItemInHand(InteractionHand.MAIN_HAND, gun);
        fp.getInventory().add(new ItemStack(VerdureItems.BURR_PELLET.get(), 3));
        h.assertTrue(VerdureWeapons.BloomGun.ammo(fp).is(VerdureItems.BURR_PELLET.get()), "Burr Pellets are ammunition");
        h.assertTrue(gun.getItem().use(level, fp, InteractionHand.MAIN_HAND) == InteractionResult.SUCCESS, "the Pulse Pistol fires them");
        h.assertTrue(VerdureWeapons.BloomGun.ammo(fp).getCount() == 2, "and spends one");
        level.getEntities(VerdureEntities.SHOT.get(), new AABB(middle(h)).inflate(12), e -> true).forEach(e -> e.discard());
        h.succeed();
    }

    private static BlockPos middle(GameTestHelper h) {
        return h.absolutePos(new BlockPos(16, 1, 16));
    }

    /**
     * 1.10: every charm goes in a Curios charm slot (VerdureGear counts it worn there as well as carried). A GameTest
     * FakePlayer gets no Curios slots, so wearing one is checked in the hidden client instead.
     */
    static void charmsWornInCurios(GameTestHelper h) {
        TagKey<Item> slot = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("curios", "charm"));
        for (var c : VerdureItems.CHARMS.values()) h.assertTrue(c.get().builtInRegistryHolder().is(slot), c.getId() + " goes in a Curios charm slot");
        for (Ferox f : Ferox.values()) h.assertTrue(VerdureItems.FEROX_CHARMS.get(f).get().builtInRegistryHolder().is(slot), f + "'s charm goes in a Curios charm slot");
        h.succeed();
    }
}
