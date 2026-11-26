package com.floraferox;

import com.floraferox.VerdureElements.Attack;
import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Wild;
import com.fundamentum.element.ElementAffinities;
import com.fundamentum.element.ElementAuras;
import com.fundamentum.element.ElementRef;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.phys.Vec3;

/** The mobs' elements (VerdureElements, docs/DESIGN.md section 13). */
final class ElementTests {
    private ElementTests() { }

    /** Each mob's main blow, in its base look: the element tag its type is in, or null for physical. */
    private static final Map<String, String> MAIN = Map.ofEntries(
            Map.entry("mire_ghast", "water"), Map.entry("dew_crab", "water"), Map.entry("pitcher_spitter", "water"),
            Map.entry("pollen_puff", "air"), Map.entry("graft_twin", "fire"), Map.entry("bell_stalker", "shadow"),
            Map.entry("pearl_pod", "arcane"), Map.entry("void_jelly", "poison"), Map.entry("warped_stilt", "poison"),
            Map.entry("blightcap", "poison"), Map.entry("soulwick", "fire"), Map.entry("cinder_thistle", "fire"),
            Map.entry("lanternpuff", "fire"), Map.entry("bloomsprout", "air"),
            Map.entry("glowgill", "water"), Map.entry("dripleaf_toad", "water"));
    private static final String[] ELEMENTS = { "air", "arcane", "earth", "electric", "fire", "holy", "ice", "poison", "shadow", "water" };

    private static Mob spawn(GameTestHelper h, String mob, String kind) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(FloraFerox.id(mob));
        @SuppressWarnings("unchecked")
        Mob m = VerdureGameTests.spawn(h, (EntityType<Mob>) type, new Vec3(0, 0, 0));
        Looks.set(m, Looks.ofKind(mob, kind).id());
        return m;
    }

    private static boolean in(DamageSource s, String element) {
        return ElementRef.parse("#fundamentum:element/" + element).matches(s);
    }

    private static String elementOf(DamageSource s) {
        String out = null;
        for (String e : ELEMENTS) if (in(s, e)) out = out == null ? e : out + "+" + e;
        return out;
    }

    /** Every mob's blow is of its element (or physical), and of no other. */
    static void everyMobHitsWithItsElement(GameTestHelper h) {
        for (String mob : LookTests.mobs()) {
            Mob m = spawn(h, mob, "base");
            String got = elementOf(m.damageSources().mobAttack(m));
            h.assertTrue(java.util.Objects.equals(got, MAIN.get(mob)), mob + " hits with " + MAIN.get(mob) + ", not " + got);
            m.discard();
        }
        Mob grub = spawn(h, "root_grub", "base");
        VerdureElements.Sources s = (VerdureElements.Sources) grub.damageSources();
        s.attack(Attack.ERUPT);
        h.assertTrue(in(s.mobAttack(grub), "earth"), "the Root Grub's erupt is earth");
        s.attack(Attack.MAIN);
        h.assertTrue(elementOf(s.mobAttack(grub)) == null, "its lunge is physical");
        Mob twin = spawn(h, "graft_twin", "base");
        ((VerdureElements.Sources) twin.damageSources()).attack(Attack.RIME);
        h.assertTrue(in(twin.damageSources().mobAttack(twin), "ice"), "the Graft Twin's rime mote is ice");
        Mob cap = spawn(h, "blightcap", "base");
        h.assertTrue(VerdureElements.SPORE.matches(cap.damageSources().mobAttack(cap)), "a Blightcap's burst is spore");
        Mob jelly = spawn(h, "void_jelly", "base");
        h.assertTrue(!VerdureElements.SPORE.matches(jelly.damageSources().mobAttack(jelly)), "a Void Jelly's venom isn't spore");
        h.succeed();
    }

    /** Ember turns physical and ice blows to fire, rime physical and fire to ice; the rest keep theirs. */
    static void looksChangeTheElement(GameTestHelper h) {
        String[][] cases = { { "snap_drake", "ember", "fire" }, { "snap_drake", "rime", "ice" }, { "soulwick", "rime", "ice" },
                { "cinder_thistle", "ember", "fire" }, { "mire_ghast", "ember", "water" }, { "mire_ghast", "rime", "water" },
                { "pearl_pod", "rime", "arcane" }, { "blightcap", "ember", "poison" },
                { "bloomsprout", "ember", "fire" }, { "bloomsprout", "rime", "ice" } };
        for (String[] c : cases) {
            Mob m = spawn(h, c[0], c[1]);
            String got = elementOf(m.damageSources().mobAttack(m));
            h.assertTrue(c[2].equals(got), c[0] + " " + c[1] + " hits with " + c[2] + ", not " + got);
            m.discard();
        }
        Mob twin = spawn(h, "graft_twin", "ember");
        ((VerdureElements.Sources) twin.damageSources()).attack(Attack.RIME);
        h.assertTrue(in(twin.damageSources().mobAttack(twin), "fire"), "an ember Graft Twin's rime mote burns");
        h.assertTrue(VerdureElements.chills(spawn(h, "soulwick", "rime")), "a rime Soulwick chills");
        h.assertTrue(!VerdureElements.chills(spawn(h, "soulwick", "base")), "a base Soulwick burns");
        h.succeed();
    }

    private static float taken(GameTestHelper h, Mob target, String element) {
        ServerLevel level = h.getLevel();
        DamageSource s = VerdureDamage.source(level, VerdureElements.type(element), null, null);
        ElementAffinities.Result r = ElementAffinities.resolve(target, s);
        return r.immune() ? 0F : r.multiplier();
    }

    private static void near(GameTestHelper h, float got, float want, String what) {
        h.assertTrue(Math.abs(got - want) < 0.01F, what + ": " + want + ", not " + got);
    }

    /** The species' strengths and weaknesses, and the look's on top. */
    static void strongAndWeak(GameTestHelper h) {
        near(h, taken(h, spawn(h, "pulse_gnat", "base"), "scorch"), 1.5F, "a Pulse Gnat takes fire");
        near(h, taken(h, spawn(h, "pulse_gnat", "base"), "gust"), 1.5F, "a Pulse Gnat takes air");
        near(h, taken(h, spawn(h, "mire_ghast", "base"), "soak"), 0.5F, "a Mire Ghast takes water");
        near(h, taken(h, spawn(h, "cinder_thistle", "base"), "scorch"), 0F, "a Cinder Thistle takes fire");
        near(h, taken(h, spawn(h, "cinder_thistle", "base"), "soak"), 1.5F, "a Cinder Thistle takes water");
        near(h, taken(h, spawn(h, "crimson_behemoth", "base"), "frost"), 1.5F, "a Crimson Behemoth takes ice");
        near(h, taken(h, spawn(h, "crimson_behemoth", "base"), "venom"), 0.5F, "a Crimson Behemoth takes poison");
        near(h, taken(h, spawn(h, "blightcap", "base"), "spore"), 0F, "a Blightcap takes its own spores");
        near(h, taken(h, spawn(h, "warped_stilt", "base"), "venom"), 0.5F, "a Warped Stilt takes venom");
        near(h, taken(h, spawn(h, "rift_narwhal", "base"), "scorch"), 1F, "a Rift Narwhal takes fire");
        near(h, taken(h, spawn(h, "bloomsprout", "base"), "soak"), 0.75F, "a Bloomsprout takes water");
        near(h, taken(h, spawn(h, "glowgill", "base"), "soak"), 0.5F, "a Glowgill takes water");
        near(h, taken(h, spawn(h, "dripleaf_toad", "base"), "soak"), 0.5F, "a Dripleaf Toad takes water");
        near(h, taken(h, spawn(h, "coil_snail", "base"), "scorch"), 1.5F, "a Coil Snail takes fire");
        // the look on top: ember +0.5 fire -0.5 ice, rime the other way
        near(h, taken(h, spawn(h, "pulse_gnat", "ember"), "scorch"), 1F, "an ember Pulse Gnat takes fire");
        near(h, taken(h, spawn(h, "pulse_gnat", "ember"), "frost"), 1.5F, "an ember Pulse Gnat takes ice");
        near(h, taken(h, spawn(h, "pulse_gnat", "rime"), "scorch"), 2F, "a rime Pulse Gnat takes fire");
        near(h, taken(h, spawn(h, "crimson_behemoth", "rime"), "frost"), 1F, "a rime Crimson Behemoth takes ice");
        h.succeed();
    }

    /** Spore flash: a fire hit on a creature coated in spores uses the spores up. Spore counts as poison. */
    static void sporeFlash(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Mob bloom = spawn(h, "carrion_bloom", "base");
        Pig pig = VerdureGameTests.prey(h, new Vec3(1, 0, 0));
        DamageSource spore = VerdureDamage.source(level, VerdureElements.type("spore"), bloom, bloom);
        h.assertTrue(in(spore, "poison"), "spore counts as poison");
        h.assertTrue(!in(VerdureDamage.source(level, VerdureElements.type("venom"), bloom, bloom), "spore") && !VerdureElements.SPORE.matches(
                VerdureDamage.source(level, VerdureElements.type("venom"), bloom, bloom)), "venom isn't spore");
        VerdureElements.spores(level, (net.minecraft.world.entity.LivingEntity) bloom, pig);
        h.assertTrue(ElementAuras.find(pig, VerdureElements.SPORE) != null, "the puff coats it in spores");
        pig.setHealth(pig.getMaxHealth());
        pig.hurtServer(level, VerdureDamage.source(level, VerdureElements.type("scorch"), null, null), 1F);
        h.runAfterDelay(2, () -> {
            h.assertTrue(ElementAuras.find(pig, VerdureElements.SPORE) == null, "the fire hit flashed the spores away");
            h.succeed();
        });
    }
}
