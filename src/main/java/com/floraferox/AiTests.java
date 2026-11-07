package com.floraferox;

import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Wild;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 1.5's own ways and the Peace Lily Charm (docs/DESIGN.md, section 11): the charm spares its wearer till struck, the
 * Root Grub burrows and erupts, the Briar King charges, the neutral ones call their kind, the Bell Stalker knows when
 * it is watched. A FakePlayer is never taken as a target, so a pig is the prey (and holds the charm in its hand).
 */
public final class AiTests {
    private AiTests() { }

    static void truceSparesTheWearerTillStruck(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Pig pig = VerdureGameTests.prey(h, Vec3.ZERO);
        pig.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(VerdureItems.PEACE_LILY_CHARM.get()));
        List<EntityType<? extends Mob>> types = List.of(VerdureEntities.wild(Wild.WARTBACK), VerdureEntities.wild(Wild.BRIAR_KING),
                VerdureEntities.ferox(Ferox.SNAP_DRAKE), VerdureEntities.MIRE_GHAST.get(), VerdureEntities.PULSE_GNAT.get());
        for (EntityType<? extends Mob> type : types) {
            Mob m = VerdureGameTests.spawn(h, type, new Vec3(3, 0, 0));
            m.setTarget(pig);
            h.assertTrue(m.getTarget() == null, type + " leaves the charm's wearer be");
            m.hurtServer(level, level.damageSources().mobAttack(pig), 1F);
            m.setTarget(pig);
            h.assertTrue(m.getTarget() == pig, type + " fights back once struck");
            m.discard();
        }
        pig.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Mob m = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.WARTBACK), new Vec3(3, 0, 0));
        m.setTarget(pig);
        h.assertTrue(m.getTarget() == pig, "without the charm it hunts as ever");
        m.discard();
        Item charm = VerdureItems.PEACE_LILY_CHARM.get();
        TagKey<Item> slot = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("curios", "charm"));
        h.assertTrue(charm.builtInRegistryHolder().is(slot), "the charm goes in a Curios charm slot");
        pig.discard();
        h.succeed();
    }

    /**
     * 1.15: a wild mob's act comes back from a save as it was, with all of it or none: a grub under the ground stays
     * under (unseen and unhurt), a charge keeps its heading and ticks, the move timers and one-shots keep their values,
     * and a charge saved with no heading comes back as nothing.
     */
    static void wildMobActSavesAndLoads(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        WildMob grub = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.ROOT_GRUB), new Vec3(0, 0, 0));
        net.minecraft.nbt.CompoundTag under = new net.minecraft.nbt.CompoundTag();
        under.putString("Act", "burrow");
        under.putInt("ActTicks", 7);
        under.putInt("BurrowCooldown", 0);
        load(level, grub, under);
        h.assertTrue(grub.act() == WildMob.Act.BURROW && grub.isInvisible(), "a saved burrow comes back under the ground, unseen");
        h.assertTrue(!grub.hurtServer(level, level.damageSources().generic(), 1F), "and unhurt");
        h.assertTrue(save(grub).getIntOr("ActTicks", -1) == 7, "with its ticks under");

        WildMob king = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.CRIMSON_BEHEMOTH), new Vec3(4, 0, 0));
        net.minecraft.nbt.CompoundTag charging = new net.minecraft.nbt.CompoundTag();
        charging.putString("Act", "charge");
        charging.putInt("ActTicks", 12);
        charging.putDouble("ChargeX", 0.6);
        charging.putDouble("ChargeZ", 0.8);
        charging.putInt("ChargeCooldown", 90);
        charging.putInt("VolleyCooldown", 40);
        charging.putInt("Brood", 1);
        charging.putBoolean("Flared", true);
        charging.putLong("Lair", new net.minecraft.core.BlockPos(3, 64, -2).asLong());
        load(level, king, charging);
        net.minecraft.nbt.CompoundTag back = save(king);
        h.assertTrue(king.act() == WildMob.Act.CHARGE && "charge".equals(back.getStringOr("Act", "")), "a charge comes back a charge");
        for (String k : List.of("ActTicks", "ChargeCooldown", "VolleyCooldown", "Brood")) {
            h.assertTrue(back.getIntOr(k, -9) == charging.getIntOr(k, -8), k + " round trips");
        }
        h.assertTrue(back.getDoubleOr("ChargeX", 0) == 0.6 && back.getDoubleOr("ChargeZ", 0) == 0.8, "with its heading");
        h.assertTrue(back.getBooleanOr("Flared", false) && back.getLongOr("Lair", 0L) == charging.getLongOr("Lair", 1L), "flare and lair kept");

        WildMob lost = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.BRIAR_KING), new Vec3(-4, 0, 0));
        net.minecraft.nbt.CompoundTag half = new net.minecraft.nbt.CompoundTag();
        half.putString("Act", "charge");
        half.putInt("ActTicks", 12);
        load(level, lost, half);
        h.assertTrue(lost.act() == WildMob.Act.NONE && save(lost).getIntOr("ActTicks", -1) == 0, "a charge with no heading is none");
        grub.discard();
        king.discard();
        lost.discard();
        h.succeed();
    }

    private static void load(ServerLevel level, WildMob mob, net.minecraft.nbt.CompoundTag tag) {
        mob.readAdditionalSaveData(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,
                level.registryAccess(), tag));
    }

    private static net.minecraft.nbt.CompoundTag save(WildMob mob) {
        net.minecraft.world.level.storage.TagValueOutput out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, mob.registryAccess());
        mob.addAdditionalSaveData(out);
        return out.buildResult();
    }

    static void rootGrubBurrowsAndErupts(GameTestHelper h) {
        Pig pig = VerdureGameTests.prey(h, Vec3.ZERO);
        WildMob g = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.ROOT_GRUB), new Vec3(9, 0, 0));
        g.setTarget(pig);
        boolean[] went = {false};
        h.onEachTick(() -> {
            if (g.act() == WildMob.Act.BURROW) {
                went[0] = true;
                h.assertTrue(g.isInvisible(), "under the ground it is not seen");
                h.assertTrue(!g.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1F), "nor hurt");
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(went[0], "it burrowed toward far prey");
            h.assertTrue(g.act() != WildMob.Act.BURROW && !g.isInvisible(), "and came up");
            h.assertTrue(g.strikes > 0 && pig.getHealth() < pig.getMaxHealth(), "under its prey");
            g.discard();
            pig.discard();
        });
    }

    static void briarKingCharges(GameTestHelper h) {
        Pig pig = VerdureGameTests.prey(h, Vec3.ZERO);
        WildMob k = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.BRIAR_KING), new Vec3(10, 0, 0));
        k.setTarget(pig);
        boolean[] charged = {false};
        h.onEachTick(() -> charged[0] |= k.act() == WildMob.Act.CHARGE);
        h.succeedWhen(() -> {
            h.assertTrue(charged[0], "it charged at far prey");
            h.assertTrue(pig.getHealth() < pig.getMaxHealth(), "and ran it down");
            k.discard();
            pig.discard();
        });
    }

    static void neutralOnesCallTheirKind(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Pig pig = VerdureGameTests.prey(h, new Vec3(0, 0, -4));
        WildMob a = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.POLLEN_PUFF), new Vec3(2, 1, 0));
        WildMob b = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.POLLEN_PUFF), new Vec3(-2, 1, 0));
        FeroxMob c = VerdureGameTests.spawn(h, VerdureEntities.ferox(Ferox.DEW_CRAB), new Vec3(2, 0, 3));
        FeroxMob d = VerdureGameTests.spawn(h, VerdureEntities.ferox(Ferox.DEW_CRAB), new Vec3(-2, 0, 3));
        a.hurtServer(level, level.damageSources().mobAttack(pig), 1F);
        c.hurtServer(level, level.damageSources().mobAttack(pig), 1F);
        h.assertTrue(b.getTarget() == pig, "a struck Pollen Puff calls the others");
        h.assertTrue(d.getTarget() == pig, "a struck Dew Crab calls the others");
        for (Mob m : new Mob[] {a, b, c, d, pig}) m.discard();
        h.succeed();
    }

    static void bellStalkerKnowsWhenWatched(GameTestHelper h) {
        WildMob s = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.BELL_STALKER), new Vec3(0, 0, 4));
        FakePlayer looking = VerdureGearTests.player(h, new Vec3(0, 0, 0), 0F, 10F);       // yaw 0 faces +z, at the stalker
        FakePlayer away = VerdureGearTests.player(h, new Vec3(0, 0, 0), 180F, 0F);
        looking.setYHeadRot(0F);                                                         // a player looks where its head turns
        away.setYHeadRot(180F);
        h.assertTrue(s.watched(looking), "it knows it is watched");
        h.assertTrue(!s.watched(away), "and when it is not");
        s.discard();
        h.succeed();
    }
}
