package com.floraferox;

import com.floraferox.VerdureLooks.Look;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Every mob's looks (docs/DESIGN.md, section 12; the table is VerdureLooks, made from tools/gen/verdure/looks.py). The
 * look is saved on the mob (rule 4) and rolled once, when it first joins a world: a rare at about 1.5%, else its
 * biome's look, else the moon's look on its night half the time, else the base or a common one by weight (rule 2).
 * The ember and rime looks never roll: they come from the herb strains (rule 1) or a graft used on the mob, and every
 * other look's item gives it too (rule 7). Gold looks sparkle.
 */
@EventBusSubscriber(modid = FloraFerox.MODID)
public final class Looks {
    private Looks() { }

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, FloraFerox.MODID);
    /** The look's id; "" until it is rolled. */
    public static final Supplier<AttachmentType<String>> LOOK = ATTACHMENTS.register("look", () -> AttachmentType.builder(() -> "")
            .serialize(Codec.STRING.fieldOf("look"), s -> !s.isEmpty()).sync(ByteBufCodecs.STRING_UTF8).copyOnDeath().build());

    /** Rare rolls: about 1.5% (rule 2: "rares at about 1-2%"). */
    public static final float RARE = 0.015F;
    /** The base look's share of a natural roll; the common looks share the rest. */
    public static final float BASE_SHARE = 0.4F;
    /** How often the moon's look comes on its night. */
    public static final float MOON = 0.5F;
    /** How often a grown monster takes its farm's biome look (rule 1: "a chance, not always"). */
    public static final float FARM_BIOME = 0.25F;

    private static final Map<String, Look[]> TABLE = new ConcurrentHashMap<>();

    /** The mob's looks; look 0 is its base. */
    public static Look[] of(String mob) {
        return TABLE.computeIfAbsent(mob, VerdureLooks::of);
    }

    /** A Flora Ferox entity's id ("pulse_gnat"), or null for anyone else's. */
    public static String mob(Entity e) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return FloraFerox.MODID.equals(id.getNamespace()) ? id.getPath() : null;
    }

    /** The look the mob wears; base when it has none yet or its id is unknown. */
    public static Look look(Entity e) {
        String mob = mob(e);
        if (mob == null) return of("")[0];
        String id = e.getData(LOOK);
        for (Look l : of(mob)) if (l.id().equals(id)) return l;
        return of(mob)[0];
    }

    public static void set(Entity e, String id) {
        e.setData(LOOK, id);
    }

    /** The look of this kind (ember, rime), if the mob has one. */
    public static Look ofKind(String mob, String kind) {
        for (Look l : of(mob)) if (l.kind().equals(kind)) return l;
        return null;
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof Mob mob) || !mob.getData(LOOK).isEmpty()) return;
        String key = mob(mob);
        if (key == null) return;
        set(mob, roll(key, mob.level(), mob.blockPosition(), mob.getRandom()));
    }

    /** A natural spawn's look (rule 2). */
    public static String roll(String mob, Level level, BlockPos pos, RandomSource random) {
        Look[] looks = of(mob);
        if (looks.length == 1) return looks[0].id();
        Look rare = rare(looks, level, pos, random);
        if (rare != null) return rare.id();
        List<Look> biome = pick(looks, "biome", level, pos);
        biome.addAll(placed(looks, level.getBiome(pos)));
        if (!biome.isEmpty()) return biome.get(random.nextInt(biome.size())).id();
        List<Look> moon = pick(looks, "moon", level, pos);
        if (!moon.isEmpty() && random.nextFloat() < MOON) return moon.get(random.nextInt(moon.size())).id();
        List<Look> common = pick(looks, "common", level, pos);
        if (common.isEmpty() || random.nextFloat() < BASE_SHARE) return looks[0].id();
        return common.get(random.nextInt(common.size())).id();
    }

    /** A grown monster's look (rule 1): its strain's, sometimes its farm biome's, and the rare roll on top. */
    public static String grown(String mob, Herb herb, Level level, BlockPos pos, RandomSource random) {
        Look[] looks = of(mob);
        Look rare = rare(looks, level, pos, random);
        if (rare != null) return rare.id();
        if (herb.tier > 1) {
            Look strain = ofKind(mob, herb.tier == 2 ? "ember" : "rime");
            if (strain != null) return strain.id();
        }
        List<Look> biome = pick(looks, "biome", level, pos);
        if (!biome.isEmpty() && random.nextFloat() < FARM_BIOME) return biome.get(random.nextInt(biome.size())).id();
        return looks[0].id();
    }

    private static Look rare(Look[] looks, Level level, BlockPos pos, RandomSource random) {
        if (random.nextFloat() >= RARE) return null;
        List<Look> rares = pick(looks, "rare", level, pos);
        return rares.isEmpty() ? null : rares.get(random.nextInt(rares.size()));
    }

    /** The looks of a kind that may come here: a look with no biomes anywhere, one with biomes only in them. */
    private static List<Look> pick(Look[] looks, String kind, Level level, BlockPos pos) {
        List<Look> out = new ArrayList<>();
        for (Look l : looks) {
            if (!l.kind().equals(kind)) continue;
            if (kind.equals("moon") ? moon(l, level, pos) : l.biomes().length == 0 ? !kind.equals("biome") : in(l, level, pos)) out.add(l);
        }
        return out;
    }

    /** A boss's ember or rime look where it also comes by itself (looks.py BOSS_BIOMES: hot or snowy biomes). */
    static List<Look> placed(Look[] looks, Holder<Biome> here) {
        List<Look> out = new ArrayList<>();
        for (Look l : looks) {
            if ((l.kind().equals("ember") || l.kind().equals("rime")) && l.biomes().length > 0 && in(l, here)) out.add(l);
        }
        return out;
    }

    /** The looks a natural spawn may take from this biome alone (for tests): its biome looks and a boss's placed ones. */
    static List<String> biomeLooks(String mob, Holder<Biome> here) {
        List<String> out = new ArrayList<>();
        for (Look l : of(mob)) if (l.kind().equals("biome") && l.biomes().length > 0 && in(l, here)) out.add(l.id());
        for (Look l : placed(of(mob), here)) out.add(l.id());
        return out;
    }

    private static boolean moon(Look l, Level level, BlockPos pos) {
        MoonPhase phase = level.environmentAttributes().getValue(EnvironmentAttributes.MOON_PHASE, pos);
        for (String b : l.biomes()) {
            if (b.equals("full") && phase == MoonPhase.FULL_MOON || b.equals("new") && phase == MoonPhase.NEW_MOON) return true;
        }
        return false;
    }

    private static boolean in(Look l, Level level, BlockPos pos) {
        return in(l, level.getBiome(pos));
    }

    private static boolean in(Look l, Holder<Biome> here) {
        for (String b : l.biomes()) {
            if (b.startsWith("#") ? here.is(TagKey.create(Registries.BIOME, Identifier.parse(b.substring(1))))
                    : here.is(Identifier.parse(b))) return true;
        }
        return false;
    }

    /** Rule 7: a look's item used on the mob gives it for good, with a sparkle puff. */
    @SubscribeEvent
    public static void use(PlayerInteractEvent.EntityInteract e) {
        String mob = mob(e.getTarget());
        if (mob == null) return;
        Look now = look(e.getTarget());
        Look next = given(mob, e.getItemStack(), now, e.getTarget().getRandom());
        if (next == null) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        set(e.getTarget(), next.id());
        if (!e.getEntity().getAbilities().instabuild) e.getItemStack().shrink(1);
        Entity t = e.getTarget();
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, t.getX(), t.getY() + t.getBbHeight() * 0.6, t.getZ(), 12, t.getBbWidth() * 0.4, t.getBbHeight() * 0.3, t.getBbWidth() * 0.4, 0.0);
    }

    /** The look this item gives the mob, one it doesn't wear yet; null if none. */
    public static Look given(String mob, ItemStack stack, Look now, RandomSource random) {
        if (stack.isEmpty()) return null;
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        List<Look> out = new ArrayList<>();
        for (Look l : of(mob)) if (item.equals(l.item()) && l != now) out.add(l);
        return out.isEmpty() ? null : out.get(random.nextInt(out.size()));
    }

    /** Gold looks glint now and then (rule 2). */
    @SubscribeEvent
    public static void sparkle(EntityTickEvent.Post e) {
        Entity t = e.getEntity();
        if (!t.level().isClientSide() || t.tickCount % 8 != 0 || t.isInvisible() || mob(t) == null || !look(t).sparkle()) return;
        RandomSource r = t.getRandom();
        t.level().addParticle(ParticleTypes.WAX_ON, t.getRandomX(0.6), t.getRandomY(), t.getRandomZ(0.6), 0.0, r.nextFloat() * 0.02, 0.0);
    }
}
