package com.floraferox;

import com.floraferox.VerdureRules.Ferox;
import com.floraferox.VerdureRules.Kind;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Flora Ferox items (docs/DESIGN.md, section 7): the mobs' spawn eggs; the herbs and their seeds; the genes and
 * the Grower's Almanacs; the tokens, charms and Seed Pellets; the weapons and the armour. Their textures, models, recipes
 * and tags come from tools/gen/verdure/content.py (the same ids).
 */
public final class VerdureItems {
    private VerdureItems() { }

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FloraFerox.MODID);

    public static final DeferredItem<SpawnEggItem> PULSE_GNAT_EGG = egg("pulse_gnat", VerdureEntities.PULSE_GNAT);
    public static final DeferredItem<SpawnEggItem> MIRE_GHAST_EGG = egg("mire_ghast", VerdureEntities.MIRE_GHAST);
    public static final DeferredItem<SpawnEggItem> DUSK_SPRIG_EGG = egg("dusk_sprig", VerdureEntities.DUSK_SPRIG);
    public static final DeferredItem<SpawnEggItem> MOON_TICK_EGG = egg("moon_tick", VerdureEntities.MOON_TICK);
    /** 1.2's six's eggs. */
    public static final java.util.Map<VerdureRules.Ferox, DeferredItem<SpawnEggItem>> FEROX_EGGS = new java.util.EnumMap<>(VerdureRules.Ferox.class);

    static {
        for (VerdureRules.Ferox f : VerdureRules.Ferox.values()) FEROX_EGGS.put(f, egg(f.id, () -> VerdureEntities.ferox(f)));
    }

    /** 1.4's wild mobs' eggs. */
    public static final java.util.Map<VerdureRules.Wild, DeferredItem<SpawnEggItem>> WILD_EGGS = new java.util.EnumMap<>(VerdureRules.Wild.class);

    static {
        for (VerdureRules.Wild w : VerdureRules.Wild.values()) WILD_EGGS.put(w, egg(w.id, () -> VerdureEntities.wild(w)));
    }

    private static DeferredItem<SpawnEggItem> egg(String name, Supplier<? extends EntityType<? extends Mob>> type) {
        return ITEMS.registerItem(name + "_spawn_egg", SpawnEggItem::new, () -> new Item.Properties().spawnEgg(type.get()));
    }

    // ---- the herbs ----------------------------------------------------------------------------------------------
    public static final Map<Herb, DeferredItem<Item>> HERBS = new EnumMap<>(Herb.class);
    public static final Map<Herb, DeferredItem<BlockItem>> SEEDS = new EnumMap<>(Herb.class);

    static {
        for (Herb h : Herb.values()) {
            HERBS.put(h, ITEMS.registerSimpleItem(h.id));
            SEEDS.put(h, ITEMS.registerItem(h.id + "_seeds", p -> new BlockItem(VerdureBlocks.CROPS.get(h).get(), p.useItemDescriptionPrefix())));
        }
    }

    public static final DeferredItem<BlockItem> GRAFTING_BENCH = ITEMS.registerSimpleBlockItem(VerdureBlocks.GRAFTING_BENCH);
    public static final DeferredItem<BlockItem> HOPPER_DEN = ITEMS.registerSimpleBlockItem(VerdureBlocks.HOPPER_DEN);
    public static final DeferredItem<Item> EMBER_GRAFT = ITEMS.registerSimpleItem("ember_graft", () -> new Item.Properties().rarity(Rarity.UNCOMMON));
    public static final DeferredItem<Item> RIME_GRAFT = ITEMS.registerSimpleItem("rime_graft", () -> new Item.Properties().rarity(Rarity.UNCOMMON));
    public static final DeferredItem<Item> GUIDE_1 = ITEMS.registerSimpleItem("growers_almanac_1", () -> new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> GUIDE_2 = ITEMS.registerSimpleItem("growers_almanac_2", () -> new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> GUIDE_3 = ITEMS.registerSimpleItem("growers_almanac_3", () -> new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> SEED_PELLET = ITEMS.registerSimpleItem("seed_pellet");

    // ---- the hunt's trophies and the charms the Weedwarden trades for them ---------------------------------------------
    public static final Map<Kind, DeferredItem<Item>> TOKENS = new EnumMap<>(Kind.class);
    public static final Map<Kind, DeferredItem<Item>> CHARMS = new EnumMap<>(Kind.class);

    static {
        for (Kind k : Kind.values()) {
            TOKENS.put(k, ITEMS.registerSimpleItem(Herb.of(k, 1).id + "_token"));
            CHARMS.put(k, ITEMS.registerSimpleItem(family(k) + "_charm", () -> new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));
        }
    }

    // ---- 1.3: the six's own drops, the charms made of them, and the Burr Pellet (tools/gen/verdure/content.py TROPHIES) ----
    public static final Map<Ferox, DeferredItem<Item>> TROPHIES = new EnumMap<>(Ferox.class);
    public static final Map<Ferox, DeferredItem<Item>> FEROX_CHARMS = new EnumMap<>(Ferox.class);
    public static final DeferredItem<Item> BURR_PELLET = ITEMS.registerSimpleItem("burr_pellet");
    /** 1.5: the Peace Lily Charm (Truce): worn as a Curios charm, the creatures leave you be till you strike them. */
    public static final DeferredItem<Item> PEACE_LILY_CHARM = ITEMS.registerSimpleItem("peace_lily_charm",
            () -> new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

    static {
        for (Ferox f : Ferox.values()) {
            TROPHIES.put(f, ITEMS.registerSimpleItem(trophy(f)));
            FEROX_CHARMS.put(f, ITEMS.registerSimpleItem(charm(f), () -> new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
        }
    }

    public static String trophy(Ferox f) {
        return switch (f) {
            case SNAP_DRAKE -> "snapjaw_petal";
            case BURR_CRAWLER -> "hookburr";
            case LANTERN_LEECH -> "lantern_husk";
            case ORCHID_MANTIS -> "orchid_blade";
            case REED_WRETCH -> "cattail_fluff";
            case DEW_CRAB -> "sundew_glue";
        };
    }

    public static String charm(Ferox f) {
        return switch (f) {
            case SNAP_DRAKE -> "snapjaw_charm";
            case BURR_CRAWLER -> "hookburr_charm";
            case LANTERN_LEECH -> "lantern_charm";
            case ORCHID_MANTIS -> "orchid_charm";
            case REED_WRETCH -> "cattail_charm";
            case DEW_CRAB -> "sundew_charm";
        };
    }

    /** The family's short name ("dusk", "pulse", "moon", "mire"), as the charms' ids use it. */
    public static String family(Kind k) {
        return switch (k) {
            case DUSK_SPRIG -> "dusk";
            case PULSE_GNAT -> "pulse";
            case MOON_TICK -> "moon";
            case MIRE_GHAST -> "mire";
        };
    }

    // ---- the weapons: Cleavers (melee), Sceptres (magic), the guns (ranged), Whips (summon) ---------------------------
    private static TagKey<Item> herbTag(int tier) {
        return ItemTags.create(FloraFerox.id("herbs/tier" + tier));
    }

    private static final ToolMaterial[] BLADE_MATERIAL = {null,
            new ToolMaterial(BlockTags.INCORRECT_FOR_IRON_TOOL, 300, 6F, 2.0F, 14, herbTag(1)),
            new ToolMaterial(BlockTags.INCORRECT_FOR_DIAMOND_TOOL, 800, 7F, 3.0F, 12, herbTag(2)),
            new ToolMaterial(BlockTags.INCORRECT_FOR_DIAMOND_TOOL, 1500, 8F, 3.5F, 12, herbTag(3))};
    private static final int[] STAFF_DURABILITY = {0, 250, 500, 900};
    private static final int[] GUN_DURABILITY = {0, 400, 800, 1500};
    private static final int[] LASH_DURABILITY = {0, 300, 600, 1200};
    private static final Rarity[] RARITY = {Rarity.COMMON, Rarity.COMMON, Rarity.UNCOMMON, Rarity.RARE};

    public static final Map<Herb, DeferredItem<Item>> WEAPONS = new EnumMap<>(Herb.class);

    private static final String[][] WEAPON_IDS = {
            {"mirecap_cleaver", "flare_cleaver", "rime_cleaver"},
            {"dusk_sceptre", "ember_sceptre", "rime_sceptre"},
            {"pulse_pistol", "scorch_spitter", "rime_repeater"},
            {"moonwisp_whip", "moonflare_whip", "moonrime_whip"}};

    static {
        for (int t = 1; t <= 3; t++) {
            final int tier = t;
            ToolMaterial mat = BLADE_MATERIAL[t];
            WEAPONS.put(Herb.of(Kind.MIRE_GHAST, t), ITEMS.registerItem(WEAPON_IDS[0][t - 1], p -> new VerdureWeapons.BudBlade(tier, p),
                    () -> new Item.Properties().sword(mat, 3.0F, -2.4F).rarity(RARITY[tier])));
            WEAPONS.put(Herb.of(Kind.DUSK_SPRIG, t), ITEMS.registerItem(WEAPON_IDS[1][t - 1], p -> new VerdureWeapons.PetalStaff(tier, p),
                    () -> new Item.Properties().durability(STAFF_DURABILITY[tier]).enchantable(12).rarity(RARITY[tier])));
            WEAPONS.put(Herb.of(Kind.PULSE_GNAT, t), ITEMS.registerItem(WEAPON_IDS[2][t - 1], p -> new VerdureWeapons.BloomGun(tier, p),
                    () -> new Item.Properties().durability(GUN_DURABILITY[tier]).enchantable(10).rarity(RARITY[tier])
                            .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false)));   // 1.8: only the inner heart shimmers
            WEAPONS.put(Herb.of(Kind.MOON_TICK, t), ITEMS.registerItem(WEAPON_IDS[3][t - 1], VerdureWeapons.Whip::new,
                    () -> new Item.Properties().durability(LASH_DURABILITY[tier]).enchantable(12).rarity(RARITY[tier])));
        }
    }

    /** 1.16, the Pulse family's real ranged weapons: a bow and a crossbow per strain (VerdureBows). */
    public static final Map<Herb, DeferredItem<Item>> BOWS = new EnumMap<>(Herb.class), CROSSBOWS = new EnumMap<>(Herb.class);

    public static final String[] BOW_IDS = {"heartstring_bow", "scorchstring_bow", "rimestring_bow"},
            CROSSBOW_IDS = {"pulse_arbalest", "scorch_arbalest", "rime_arbalest"};
    private static final int[] BOW_DURABILITY = {0, 450, 800, 1400};

    static {
        for (int t = 1; t <= 3; t++) {
            final int tier = t;
            BOWS.put(Herb.of(Kind.PULSE_GNAT, t), ITEMS.registerItem(BOW_IDS[t - 1], p -> new VerdureBows.HeartBow(tier, p),
                    () -> new Item.Properties().durability(BOW_DURABILITY[tier]).enchantable(1).rarity(RARITY[tier])));
            CROSSBOWS.put(Herb.of(Kind.PULSE_GNAT, t), ITEMS.registerItem(CROSSBOW_IDS[t - 1], p -> new VerdureBows.Arbalest(tier, p),
                    () -> new Item.Properties().durability(BOW_DURABILITY[tier]).enchantable(1).rarity(RARITY[tier])));
        }
    }

    /**
     * 1.13, a grappling hook for each weapon: plain items that Fundamentum fires from its hook slot (key H), each one's
     * reach, speed and count in data/floraferox/fundamentum/hooks/ (tools/gen, content.HOOKS).
     */
    public static final Map<Herb, DeferredItem<Item>> HOOKS = new EnumMap<>(Herb.class);

    private static final String[][] HOOK_IDS = {
            {"mirecap_grapple", "flare_grapple", "rime_grapple"},
            {"dusk_tendril", "ember_tendril", "rime_tendril"},
            {"pulse_hook", "scorch_hook", "rime_hook"},
            {"moonwisp_grapnel", "moonflare_grapnel", "moonrime_grapnel"}};

    static {
        Kind[] line = {Kind.MIRE_GHAST, Kind.DUSK_SPRIG, Kind.PULSE_GNAT, Kind.MOON_TICK};
        for (int k = 0; k < 4; k++) {
            for (int t = 1; t <= 3; t++) {
                final int tier = t;
                HOOKS.put(Herb.of(line[k], t), ITEMS.registerSimpleItem(HOOK_IDS[k][t - 1], () -> new Item.Properties().stacksTo(1).rarity(RARITY[tier])));
            }
        }
    }

    /**
     * 1.14, wings for each armour tier: Fundamentum flies them from its wings slot (stats in data/floraferox/fundamentum/
     * wings/) and draws them on the elytra model from their equipment asset; the equippable's body slot with no allowed
     * entities keeps them out of every other slot.
     */
    public static final DeferredItem<Item>[] WINGS = wings();

    @SuppressWarnings("unchecked")
    private static DeferredItem<Item>[] wings() {
        String[] ids = {null, "leafwing", "emberwing", "rimewing"};
        DeferredItem<Item>[] out = new DeferredItem[4];
        for (int t = 1; t <= 3; t++) {
            final int tier = t;
            ResourceKey<EquipmentAsset> asset = ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID,
                    net.minecraft.resources.Identifier.fromNamespaceAndPath(FloraFerox.MODID, ids[t]));
            out[t] = ITEMS.registerSimpleItem(ids[t], () -> new Item.Properties().stacksTo(1).rarity(RARITY[tier])
                    .component(DataComponents.EQUIPPABLE, net.minecraft.world.item.equipment.Equippable.builder(net.minecraft.world.entity.EquipmentSlot.BODY)
                            .setAsset(asset).setAllowedEntities(net.minecraft.core.HolderSet.direct()).setDamageOnHurt(false)
                            .setEquipOnInteract(false).build()));
        }
        return out;
    }

    /** The sprites the shots are drawn with (Seedshot); not in the creative tab. */
    public static final Map<String, DeferredItem<Item>> SHOTS = new LinkedHashMap<>();

    static {
        for (String s : new String[] {"mire_arc", "flare_arc", "rime_arc", "dusk_mote", "ember_mote", "rime_mote",
                "pulse_dart", "flare_dart", "rime_dart"}) {
            SHOTS.put(s, ITEMS.registerSimpleItem(s));
        }
    }

    // ---- the armour: three platings with their boots, and twelve class helmets that complete a tier's set ----------
    private static final int[] ARMOR_DURABILITY = {0, 15, 25, 33};
    /** Helmet, plating, leggings (1.9), boots. */
    private static final int[][] ARMOR_DEFENSE = {null, {2, 6, 3, 2}, {3, 7, 5, 3}, {3, 8, 6, 4}};
    private static final float[] ARMOR_TOUGHNESS = {0F, 0F, 1F, 2.5F};
    private static final Holder<SoundEvent>[] EQUIP = equipSounds();

    @SuppressWarnings("unchecked")
    private static Holder<SoundEvent>[] equipSounds() {
        return new Holder[] {null, SoundEvents.ARMOR_EQUIP_LEATHER, SoundEvents.ARMOR_EQUIP_CHAIN, SoundEvents.ARMOR_EQUIP_DIAMOND};
    }

    static ArmorMaterial material(int tier, String asset) {
        Map<ArmorType, Integer> defense = new EnumMap<>(ArmorType.class);
        int[] d = ARMOR_DEFENSE[tier];
        defense.put(ArmorType.HELMET, d[0]);
        defense.put(ArmorType.CHESTPLATE, d[1]);
        defense.put(ArmorType.LEGGINGS, d[2]);
        defense.put(ArmorType.BOOTS, d[3]);
        defense.put(ArmorType.BODY, 0);
        ResourceKey<EquipmentAsset> key = ResourceKey.create(EquipmentAssets.ROOT_ID, FloraFerox.id(asset));
        return new ArmorMaterial(ARMOR_DURABILITY[tier], defense, 12, EQUIP[tier], ARMOR_TOUGHNESS[tier], 0F, herbTag(tier), key);
    }

    private static final String[] PLATING_IDS = {null, "leafguard_plate", "emberbark_plate", "rimebark_plate"};
    private static final String[] LEGGINGS_IDS = {null, "leafguard_leggings", "emberbark_leggings", "rimebark_leggings"};
    private static final String[] BOOTS_IDS = {null, "leafguard_boots", "emberbark_boots", "rimebark_boots"};
    /** The boots' speed: movement, and from the second tier attack speed (Terraria's boots' bonuses). */
    private static final double[] BOOTS_MOVE = {0, 0.05, 0.10, 0.20};
    private static final double[] BOOTS_ATTACK = {0, 0, 0.10, 0.20};
    private static final String[][] HELMET_IDS = {null,
            {"leafguard_sallet", "leafguard_armet", "leafguard_cowl", "leafguard_visor"},
            {"emberbark_sallet", "emberbark_armet", "emberbark_cowl", "emberbark_visor"},
            {"rimebark_sallet", "rimebark_armet", "rimebark_cowl", "rimebark_visor"}};
    private static final Kind[] HELMET_KINDS = {Kind.DUSK_SPRIG, Kind.PULSE_GNAT, Kind.MOON_TICK, Kind.MIRE_GHAST};

    public static final DeferredItem<?>[] PLATINGS = new DeferredItem<?>[4];
    /** 1.9 (Oculifelis: "this mod needs leggings too"): worn with the plating, from the same armour material. */
    public static final DeferredItem<?>[] LEGGINGS = new DeferredItem<?>[4];
    public static final DeferredItem<?>[] BOOTS = new DeferredItem<?>[4];
    public static final Map<Herb, DeferredItem<Item>> HELMETS = new EnumMap<>(Herb.class);

    static {
        for (int t = 1; t <= 3; t++) {
            final int tier = t;
            ArmorMaterial set = material(t, PLATING_IDS[t]);
            PLATINGS[t] = ITEMS.registerSimpleItem(PLATING_IDS[t], () -> new Item.Properties().humanoidArmor(set, ArmorType.CHESTPLATE).rarity(RARITY[tier]));
            LEGGINGS[t] = ITEMS.registerSimpleItem(LEGGINGS_IDS[t], () -> new Item.Properties().humanoidArmor(set, ArmorType.LEGGINGS).rarity(RARITY[tier]));
            BOOTS[t] = ITEMS.registerSimpleItem(BOOTS_IDS[t], () -> {
                Item.Properties p = new Item.Properties().humanoidArmor(set, ArmorType.BOOTS).rarity(RARITY[tier]);
                ItemAttributeModifiers mods = set.createAttributes(ArmorType.BOOTS).withModifierAdded(Attributes.MOVEMENT_SPEED,
                        new AttributeModifier(FloraFerox.id("boots_speed"), BOOTS_MOVE[tier], AttributeModifier.Operation.ADD_MULTIPLIED_BASE), EquipmentSlotGroup.FEET);
                if (BOOTS_ATTACK[tier] > 0) mods = mods.withModifierAdded(Attributes.ATTACK_SPEED,
                        new AttributeModifier(FloraFerox.id("boots_attack_speed"), BOOTS_ATTACK[tier], AttributeModifier.Operation.ADD_MULTIPLIED_BASE), EquipmentSlotGroup.FEET);
                return p.attributes(mods);
            });
            for (int i = 0; i < 4; i++) {
                String hid = HELMET_IDS[t][i];
                ArmorMaterial own = material(t, hid);
                HELMETS.put(Herb.of(HELMET_KINDS[i], t), ITEMS.registerSimpleItem(hid, () -> new Item.Properties().humanoidArmor(own, ArmorType.HELMET).rarity(RARITY[tier])));
            }
        }
    }
}
