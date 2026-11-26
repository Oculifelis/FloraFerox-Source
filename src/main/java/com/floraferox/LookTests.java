package com.floraferox;

import com.floraferox.VerdureLooks.Look;
import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Kind;
import com.floraferox.VerdureRules.Wild;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** The looks (Looks, docs/DESIGN.md section 12): rolled, grown, given by items, saved, and each one's files there. */
final class LookTests {
    private LookTests() { }

    /** Every Flora Ferox mob's id. */
    static List<String> mobs() {
        List<String> out = new ArrayList<>(List.of("pulse_gnat", "mire_ghast", "dusk_sprig", "moon_tick", "bloomsprout", "glowgill", "dripleaf_toad",
                "coil_snail"));
        for (Ferox f : Ferox.values()) out.add(f.id);
        for (Wild w : Wild.values()) out.add(w.id);
        return out;
    }

    /** Each look has its texture, glow and model. Glowgill and Dripleaf Toad have base models only. */
    static void everyLookIsThere(GameTestHelper h) {
        h.assertTrue(mobs().size() == 37, "expected 37 mobs, got " + mobs().size());
        for (String mob : mobs()) {
            Look[] looks = Looks.of(mob);
            h.assertTrue(looks[0].kind().equals("base"), mob + "'s first look is its base");
            int ember = 0, rime = 0;
            for (Look l : looks) {
                if (l.kind().equals("ember")) ember++;
                if (l.kind().equals("rime")) rime++;
                if (l.kind().equals("base")) continue;
                for (String file : new String[] { l.id() + ".png", l.id() + "_glow.png" }) {
                    h.assertTrue(LookTests.class.getResource("/assets/floraferox/textures/entity/" + mob + "/" + file) != null, mob + " has " + file);
                }
                if (l.model()) {
                    h.assertTrue(VerdureModels.get(mob + "/" + l.id()) != null, mob + "/" + l.id() + " has its model");
                    h.assertTrue(VerdureModels.clips(mob + "/" + l.id()) != null, mob + "/" + l.id() + " has its motion");
                }
                if (l.item() != null) {
                    h.assertTrue(BuiltInRegistries.ITEM.containsKey(Identifier.parse(l.item())), mob + "/" + l.id() + "'s item " + l.item() + " exists");
                }
            }
            if (mob.equals("glowgill") || mob.equals("dripleaf_toad") || mob.equals("coil_snail")) {
                h.assertTrue(looks.length == 1, mob + " has just its own base look");
                h.assertTrue(VerdureModels.clips(mob) != null, mob + " has its own motion");
            } else {
                h.assertTrue(ember == 1 && rime == 1, mob + " should have one ember look and one rime look");
            }
        }
        h.succeed();
    }

    /** Rule 2: natural rolls give the base and the common looks, rares about 1.5%, and never ember or rime. */
    static void naturalRollsAreWeighted(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(16, 1, 16));
        RandomSource r = RandomSource.create(42);
        for (String mob : mobs()) {
            for (int i = 0; i < 200; i++) {
                String id = Looks.roll(mob, level, at, r);
                Look l = find(mob, id);
                h.assertTrue(l != null, mob + " rolled a look it has: " + id);
                // a boss's ember or rime look may come by itself where the test happens to stand in a hot or snowy biome
                h.assertTrue(!l.kind().equals("ember") && !l.kind().equals("rime") || Looks.biomeLooks(mob, level.getBiome(at)).contains(id),
                        mob + " never rolls its " + l.kind() + " look outside its biomes");
            }
        }
        int base = 0, common = 0, rare = 0, n = 10000;
        for (int i = 0; i < n; i++) {
            switch (find("pulse_gnat", Looks.roll("pulse_gnat", level, at, r)).kind()) {
                case "base" -> base++;
                case "common" -> common++;
                case "rare" -> rare++;
                default -> { }
            }
        }
        h.assertTrue(rare > n * 0.008 && rare < n * 0.025, "rares roll about 1.5%: " + rare + " in " + n);
        h.assertTrue(base > n * 0.3 && common > n * 0.5, "the base and the commons share the rest: " + base + " and " + common);
        Mob fresh = VerdureGameTests.spawn(h, VerdureEntities.ferox(Ferox.SNAP_DRAKE), new Vec3(2, 0, 2));
        h.assertTrue(!fresh.getData(Looks.LOOK).isEmpty(), "a mob joining the world rolls its look");
        fresh.discard();
        h.succeed();
    }

    /** Rule 1: the first strain grows the base look, the flare strain the ember look, the rime strain the rime look. */
    static void herbStrainsSetTheLook(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(16, 1, 16));
        for (Kind k : Kind.values()) {
            for (int tier = 1; tier <= 3; tier++) {
                for (int i = 0; i < 4; i++) {
                    Mob m = VerdureEvents.wake(level, at, Herb.of(k, tier));
                    String kind = Looks.look(m).kind();
                    String want = tier == 1 ? "base" : tier == 2 ? "ember" : "rime";
                    // the small rare roll on top, and sometimes the farm's biome look
                    h.assertTrue(kind.equals(want) || kind.equals("rare") || tier == 1 && kind.equals("biome"),
                            Herb.of(k, tier) + " grows " + Looks.mob(m) + "'s " + want + " look, not " + kind);
                    m.discard();
                }
            }
        }
        h.succeed();
    }

    /** Rule 7: a look's item used on the mob gives it for good, and is used up; other items leave it be. */
    static void itemsGiveTheirLook(GameTestHelper h) {
        FakePlayer fp = VerdureGearTests.player(h, new Vec3(0, 0, -2), 0F, 0F);
        Mob drake = VerdureGameTests.spawn(h, VerdureEntities.ferox(Ferox.SNAP_DRAKE), new Vec3(0, 0, 0));
        Looks.set(drake, "base");
        fp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK, 4));
        fp.interactOn(drake, InteractionHand.MAIN_HAND, fp.position());
        h.assertTrue(drake.getData(Looks.LOOK).equals("base") && fp.getMainHandItem().getCount() == 4, "a stick changes nothing");
        fp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_INGOT, 4));
        fp.interactOn(drake, InteractionHand.MAIN_HAND, fp.position());
        h.assertTrue(drake.getData(Looks.LOOK).equals("x_rare_gilded"), "gold gilds the Snap Drake, not " + drake.getData(Looks.LOOK));
        h.assertTrue(Looks.look(drake).sparkle(), "and the gold look sparkles");
        h.assertTrue(fp.getMainHandItem().getCount() == 3, "the ingot is used up");
        fp.interactOn(drake, InteractionHand.MAIN_HAND, fp.position());
        h.assertTrue(fp.getMainHandItem().getCount() == 3, "a look it already wears costs nothing");
        fp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(VerdureItems.EMBER_GRAFT.get()));
        fp.interactOn(drake, InteractionHand.MAIN_HAND, fp.position());
        h.assertTrue(Looks.look(drake).kind().equals("ember"), "an Ember Graft gives the ember look");
        fp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(VerdureItems.RIME_GRAFT.get()));
        Mob puff = VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.LANTERNPUFF), new Vec3(2, 0, 0));
        fp.interactOn(puff, InteractionHand.MAIN_HAND, fp.position());
        h.assertTrue(Looks.look(puff).kind().equals("rime"), "a Rime Graft gives a wild mob its rime look");
        drake.discard();
        puff.discard();
        h.succeed();
    }

    /** Rule 4: the look is saved with the mob. */
    static void theLookIsSaved(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Mob tick = VerdureGameTests.spawn(h, VerdureEntities.MOON_TICK.get(), new Vec3(0, 0, 0));
        Looks.set(tick, "x_rare_eclipse");
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        tick.saveWithoutId(out);
        CompoundTag tag = out.buildResult();
        Mob back = VerdureEntities.MOON_TICK.get().create(level, EntitySpawnReason.LOAD);
        back.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        h.assertTrue(back.getData(Looks.LOOK).equals("x_rare_eclipse"), "the eclipse look comes back, not " + back.getData(Looks.LOOK));
        tick.discard();
        h.succeed();
    }

    /** Rule 3: the four new species are there, fireproof, and spawn only in their own biomes. */
    static void theNewSpecies(GameTestHelper h) {
        Set<String> seen = new HashSet<>();
        for (Wild w : new Wild[] { Wild.WARPED_WARTBACK, Wild.SNARLSTUMP, Wild.BLIGHTCAP, Wild.GLISTERLURE }) {
            EntityType<?> type = VerdureEntities.wild(w);
            h.assertTrue(type.fireImmune(), w.id + " is fireproof");
            h.assertTrue(LookTests.class.getResource("/data/floraferox/neoforge/biome_modifier/" + w.id + "_spawns.json") != null, w.id + " spawns somewhere");
            Mob m = VerdureGameTests.spawn(h, VerdureEntities.wild(w), new Vec3(2 * seen.size(), 0, 3));
            h.assertTrue(m.isAlive(), w.id + " spawns");
            seen.add(w.id);
            m.discard();
        }
        h.succeed();
    }

    /** The bosses' biome looks (boss_variants.py, all Good 2026-11-09): which biome gives which, and each one's model. */
    static void bossLooksComeByBiome(GameTestHelper h) {
        String[][] want = {
            { "briar_king", "minecraft:pale_garden", "v2_pale" },
            { "briar_king", "minecraft:mangrove_swamp", "v2_mangrove" },
            { "briar_king", "minecraft:desert", "v2_ember" },
            { "briar_king", "minecraft:snowy_plains", "v2_rime" },
            { "crimson_behemoth", "minecraft:warped_forest", "v3_warped" },
            { "crimson_behemoth", "minecraft:mushroom_fields", "v4_mycelium" },
            { "crimson_behemoth", "minecraft:basalt_deltas", "v4_ember" },
            { "crimson_behemoth", "minecraft:snowy_plains", "v3_rime" },
        };
        var biomes = h.getLevel().registryAccess().lookupOrThrow(Registries.BIOME);
        for (String[] w : want) {
            Holder<Biome> here = biomes.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(w[1])));
            List<String> got = Looks.biomeLooks(w[0], here);
            h.assertTrue(got.contains(w[2]), w[0] + " in " + w[1] + " may be " + w[2] + ", got " + got);
            Look l = find(w[0], w[2]);
            h.assertTrue(l != null, w[0] + " has " + w[2]);
            if (!w[2].equals("v3_warped")) {
                h.assertTrue(l.model() && VerdureModels.get(w[0] + "/" + w[2]) != null, w[0] + "/" + w[2] + " has its own model");
            }
        }
        Holder<Biome> plains = biomes.getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:plains")));
        h.assertTrue(Looks.biomeLooks("briar_king", plains).isEmpty() && Looks.biomeLooks("crimson_behemoth", plains).isEmpty(),
                "no boss biome look in plains");
        h.succeed();
    }

    /** The Behemoth's biome looks grow by phase: phase n's st parts, and every ice, ooze and basalt growth up to n. */
    static void behemothGrowsByPhase(GameTestHelper h) {
        for (String look : new String[] { "v4_ember", "v3_rime", "v4_mycelium" }) {
            String key = "crimson_behemoth/" + look;
            var rig = new VerdureAnimations.WildRig(VerdureModels.get(key), key, VerdureRules.Wild.CRIMSON_BEHEMOTH, false, false);
            h.assertTrue(rig.staged(), key + " grows by phase");
        }
        var plain = new VerdureAnimations.WildRig(VerdureModels.get("crimson_behemoth"), "crimson_behemoth", VerdureRules.Wild.CRIMSON_BEHEMOTH, false, false);
        h.assertTrue(!plain.staged(), "the plain Behemoth keeps its cracks");
        CrimsonBehemoth cb = (CrimsonBehemoth) VerdureGameTests.spawn(h, VerdureEntities.wild(VerdureRules.Wild.CRIMSON_BEHEMOTH), new Vec3(4, 0, 4));
        h.assertTrue(cb.stage() == 1, "it starts in phase 1, not " + cb.stage());
        cb.discard();
        h.succeed();
    }

    /**
     * The Thorn Matriarch's final body (t4_springqueen) and every look of it: two brood flaps the client tears open
     * from her phase 2, a paint per phase (three), and her look's phase follows her fight: 1, 2 at her first call, 3
     * at her second.
     */
    static void matriarchOpensByPhase(GameTestHelper h) {
        List<String> keys = new ArrayList<>(List.of("thorn_matriarch"));
        for (Look l : Looks.of("thorn_matriarch")) if (l.model()) keys.add("thorn_matriarch/" + l.id());
        h.assertTrue(Looks.of("thorn_matriarch").length >= 6, "her five looks are wired");   // all on her own body
        for (String key : keys) {
            var rig = new VerdureAnimations.WildRig(VerdureModels.get(key), key, Wild.THORN_MATRIARCH, false, false);
            h.assertTrue(rig.brood().length == 2, key + " has two brood flaps, not " + rig.brood().length);
            h.assertTrue(VerdureModels.clips(key).length == 5, key + " has walk, attack, idle, bloom and wings clips");
        }
        h.assertTrue(VerdureModels.phases("thorn_matriarch") == 3, "she has a paint per phase");
        h.assertTrue(VerdureModels.phases("crimson_behemoth") == 1, "the Behemoth keeps one paint");
        for (Look l : Looks.of("thorn_matriarch")) {
            String stem = "/assets/floraferox/textures/entity/thorn_matriarch" + (l.id().equals(Looks.of("thorn_matriarch")[0].id()) ? "" : "/" + l.id());
            for (int n = 2; n <= 3; n++) for (String end : new String[] { ".png", "_glow.png" }) {
                String path = stem + "_phase" + n + end;
                h.assertTrue(LookTests.class.getResource(path) != null, "missing " + path);
            }
        }
        ThornMatriarch tm = (ThornMatriarch) VerdureGameTests.spawn(h, VerdureEntities.wild(Wild.THORN_MATRIARCH), new Vec3(4, 0, 4));
        h.assertTrue(tm.stage() == 1, "she starts in phase 1, not " + tm.stage());
        tm.phase.to(ThornMatriarch.Phase.CALL);
        h.runAfterDelay(2, () -> {
            h.assertTrue(tm.stage() == 2, "her first call opens phase 2, not " + tm.stage());
            tm.phase.to(ThornMatriarch.Phase.SWARM);
            h.runAfterDelay(2, () -> {
                h.assertTrue(tm.stage() == 3, "the swarm is phase 3, not " + tm.stage());
                tm.phase.to(ThornMatriarch.Phase.DYING);
                h.runAfterDelay(2, () -> {
                    h.assertTrue(tm.stage() == 3, "she keeps phase 3 as she dies, not " + tm.stage());
                    tm.discard();
                    h.succeed();
                });
            });
        });
    }

    private static Look find(String mob, String id) {
        for (Look l : Looks.of(mob)) if (l.id().equals(id)) return l;
        return null;
    }
}
