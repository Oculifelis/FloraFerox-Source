package com.floraferox;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The Lush Caves grazers (DESIGN.md section 16): spawning, retaliation, breeding and pollination. */
final class CaveGrazerTests {
    private CaveGrazerTests() { }

    private static final BlockPos MIDDLE = new BlockPos(16, 1, 16);

    /** A Lush Caves creature on moss, clay or rooted dirt, never on stone; it leaves what's near it alone. */
    static void spawns(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var type = VerdureEntities.BLOOMSPROUT.get();
        BlockPos at = h.absolutePos(MIDDLE.offset(4, 0, 4));
        for (var grazer : List.of(type, VerdureEntities.GLOWGILL.get(), VerdureEntities.DRIPLEAF_TOAD.get(),
                VerdureEntities.COIL_SNAIL.get())) {
            h.assertTrue(grazer.getCategory() == MobCategory.CREATURE, "the grazer uses the creature cap");
            for (var floor : List.of(Blocks.MOSS_BLOCK, Blocks.CLAY, Blocks.ROOTED_DIRT)) {
                level.setBlockAndUpdate(at.below(), floor.defaultBlockState());
                h.assertTrue(CaveGrazer.checkSpawn(grazer, level, EntitySpawnReason.NATURAL, at, level.getRandom()), "it spawns on " + floor);
            }
            level.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
            h.assertTrue(!CaveGrazer.checkSpawn(grazer, level, EntitySpawnReason.NATURAL, at, level.getRandom()), "it cannot spawn on stone");
        }
        CaveGrazer snout = VerdureGameTests.spawn(h, type, Vec3.ZERO);
        String look = Looks.look(snout).kind();
        h.assertTrue(!look.equals("ember") && !look.equals("rime"), "a natural one never wears a strain look");
        h.assertTrue(snout.isFood(new ItemStack(Items.GLOW_BERRIES)) && !snout.isFood(new ItemStack(Items.WHEAT)), "glow berries are its food");
        Pig pig = VerdureGameTests.prey(h, new Vec3(1.6, 0, 0));
        h.runAfterDelay(60, () -> {
            h.assertTrue(snout.getTarget() == null && snout.strikes == 0, "left alone, it leaves the pig alone");
            h.assertTrue(pig.getHealth() == pig.getMaxHealth(), "and the pig is unhurt");
            h.succeed();
        });
    }

    /** Hit, it answers whoever hit it (twice at most), then calms down; another CaveGrazer by it doesn't join in. */
    static void answersThenCalms(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CaveGrazer snout = VerdureGameTests.spawn(h, VerdureEntities.BLOOMSPROUT.get(), Vec3.ZERO);
        CaveGrazer other = VerdureGameTests.spawn(h, VerdureEntities.BLOOMSPROUT.get(), new Vec3(0, 0, 2));
        Pig pig = VerdureGameTests.prey(h, new Vec3(1.6, 0, 0));
        pig.setHealth(pig.getMaxHealth());
        // a few ticks in: HurtByTargetGoal ignores a blow stamped on tick 0 (its own timestamp starts at 0)
        h.runAfterDelay(5, () -> snout.hurtServer(level, level.damageSources().mobAttack(pig), 1F));
        h.succeedWhen(() -> {
            h.assertTrue(snout.strikes >= 1, "it answered the pig: " + snout.strikes);
            h.assertTrue(snout.strikes <= CaveGrazer.ANSWER_HITS, "no more than " + CaveGrazer.ANSWER_HITS + " times: " + snout.strikes);
            h.assertTrue(snout.getTarget() == null, "and calmed down");
            h.assertTrue(other.getTarget() == null && other.strikes == 0, "its neighbour never joined in");
        });
    }

    /** Two in love make a baby, which wears one parent's look. */
    static void breeds(GameTestHelper h) {
        CaveGrazer a = VerdureGameTests.spawn(h, VerdureEntities.BLOOMSPROUT.get(), Vec3.ZERO);
        CaveGrazer b = VerdureGameTests.spawn(h, VerdureEntities.BLOOMSPROUT.get(), new Vec3(1.5, 0, 0));
        Looks.set(a, "x2_ember");
        Looks.set(b, "x2_rime");
        a.setInLove(null);
        b.setInLove(null);
        CaveGrazer glowgill = VerdureGameTests.spawn(h, VerdureEntities.GLOWGILL.get(), new Vec3(0, 0, 2));
        CaveGrazer toad = VerdureGameTests.spawn(h, VerdureEntities.DRIPLEAF_TOAD.get(), new Vec3(0, 0, 4));
        CaveGrazer snail = VerdureGameTests.spawn(h, VerdureEntities.COIL_SNAIL.get(), new Vec3(0, 0, -3));
        glowgill.setInLove(null);
        toad.setInLove(null);
        snail.setInLove(null);
        h.assertTrue(a.canMate(b) && !a.canMate(glowgill) && !a.canMate(toad) && !glowgill.canMate(toad) && !snail.canMate(toad)
                        && !a.canMate(snail),
                "each species breeds only with its own kind");
        AABB box = new AABB(h.absolutePos(MIDDLE)).inflate(8);
        h.succeedWhen(() -> {
            List<CaveGrazer> babies = h.getLevel().getEntitiesOfClass(CaveGrazer.class, box, CaveGrazer::isBaby);
            h.assertTrue(babies.size() == 1, "one baby: " + babies.size());
            CaveGrazer baby = babies.get(0);
            h.assertTrue(baby.getType() == VerdureEntities.BLOOMSPROUT.get(), "the baby is its parents' species");
            String look = Looks.look(baby).id();
            h.assertTrue(look.equals("x2_ember") || look.equals("x2_rime"), "it wears a parent's strain look: " + look);
        });
    }

    /** The Coil Snail: a first blow sends it into its shell, where it lets the matter drop; a blow while it hides takes
     * HIDE_DAMAGE of the damage and brings it out ramming. It eats Coil Yams, not glow berries, and dusts no herbs. */
    static void snailHides(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        CaveGrazer snail = VerdureGameTests.spawn(h, VerdureEntities.COIL_SNAIL.get(), Vec3.ZERO);
        Pig pig = VerdureGameTests.prey(h, new Vec3(1.6, 0, 0));
        pig.setHealth(pig.getMaxHealth());
        h.assertTrue(snail.snail() && snail.isFood(new ItemStack(VerdureItems.COIL_YAM.get())) && !snail.isFood(new ItemStack(Items.GLOW_BERRIES)),
                "Coil Yams are its food");
        h.assertTrue(VerdureItems.COIL_YAM.get().getBlock() == VerdureBlocks.COIL_YAMS.get(), "the yam plants its own crop");
        float max = snail.getMaxHealth();
        h.runAfterDelay(5, () -> {
            snail.hurtServer(level, level.damageSources().mobAttack(pig), 4F);
            h.assertTrue(snail.hideTicks() == CaveGrazer.HIDE_TICKS, "the first blow sends it into its shell");
            h.assertTrue(snail.getLastHurtByMob() == null, "and it lets the matter drop");
            float full = max - armoured(snail, level, pig, 4F);
            h.assertTrue(Math.abs(snail.getHealth() - full) < 0.01F, "the first blow lands in full: " + snail.getHealth() + ", not " + full);
        });
        h.runAfterDelay(25, () -> {                              // past the blow's invulnerable ticks, still in its shell
            h.assertTrue(snail.hideTicks() > CaveGrazer.HIDE_OUT && snail.strikes == 0, "it is still hiding, and has not struck");
            snail.hurtServer(level, level.damageSources().mobAttack(pig), 4F);
            float want = max - armoured(snail, level, pig, 4F) - armoured(snail, level, pig, 4F * CaveGrazer.HIDE_DAMAGE);
            h.assertTrue(Math.abs(snail.getHealth() - want) < 0.01F, "the shell takes most of a blow: " + snail.getHealth() + ", not " + want);
            h.assertTrue(snail.hideTicks() <= CaveGrazer.HIDE_OUT, "and it comes out");
        });
        h.runAfterDelay(30, () -> h.succeedWhen(() -> h.assertTrue(snail.strikes >= 1, "it rammed the pig: " + snail.strikes)));
    }

    /** A blow after the snail's own armour (vanilla's rule), so the shell's share is checked, not the armour's. */
    private static float armoured(CaveGrazer snail, ServerLevel level, Pig pig, float amount) {
        return net.minecraft.world.damagesource.CombatRules.getDamageAfterAbsorb(snail, amount, level.damageSources().mobAttack(pig),
                (float) snail.getArmorValue(), (float) snail.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS));
    }

    /** It finds the unripe herb in reach (not one 12 blocks off), its dust grows even the slowest strain, and it walks
     * over to a herb and dusts it by itself. */
    static void pollinates(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        HerbCropBlock crop = VerdureBlocks.CROPS.get(Herb.DUSKRIME).get();
        BlockPos far = herb(h, MIDDLE.offset(-12, 0, 0), crop);
        BlockPos lab = herb(h, MIDDLE.offset(0, 0, 4), crop);
        CaveGrazer snout = VerdureGameTests.spawn(h, VerdureEntities.BLOOMSPROUT.get(), Vec3.ZERO);
        h.assertTrue(lab.equals(snout.findHerb(level)), "it finds the herb 4 blocks off: " + snout.findHerb(level));
        int dusts = 0;
        while (!crop.isMaxAge(level.getBlockState(lab)) && dusts < 200) {
            snout.dust(level, lab);
            dusts++;
        }
        h.assertTrue(crop.isMaxAge(level.getBlockState(lab)), "its dust grew a Duskrime ripe (" + dusts + " dusts)");
        h.assertTrue(snout.findHerb(level) == null, "a ripe herb needs no dust, and one 12 blocks off is out of reach");
        h.assertTrue(snout.pollenCooldown() == CaveGrazer.POLLEN_COOLDOWN, "after a dust its pollen needs " + CaveGrazer.POLLEN_COOLDOWN + " ticks");
        BlockPos near = herb(h, MIDDLE.offset(3, 0, 0), crop);
        snout.dusted = 0;
        snout.pollenCooldown(0);
        h.succeedWhen(() -> {
            h.assertTrue(snout.dusted >= 1 && near.equals(snout.lastDusted), "it walked to the herb 3 blocks off and dusted it");
            h.assertTrue(level.getBlockState(far).getValue(HerbCropBlock.AGE) == 0, "the herb 12 blocks off was never dusted");
        });
    }

    /** A young herb on wet farmland with a light by it; its absolute position. */
    private static BlockPos herb(GameTestHelper h, BlockPos rel, HerbCropBlock crop) {
        h.setBlock(rel.below(), Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7));
        h.setBlock(rel, crop.getStateForAge(0));
        h.setBlock(rel.offset(0, 0, 1), Blocks.GLOWSTONE);
        return h.absolutePos(rel);
    }
}
