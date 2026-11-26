package com.floraferox;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Flora Ferox's blocks: the twelve herbs' crops, the Grafting Bench with its menu, and the Thorn Hopper's den with its worldgen. */
public final class VerdureBlocks {
    private VerdureBlocks() { }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FloraFerox.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, FloraFerox.MODID);

    /** Each herb's crop, named for the herb as wheat's is (its seeds are the block's item). */
    public static final Map<Herb, DeferredBlock<HerbCropBlock>> CROPS = new EnumMap<>(Herb.class);

    static {
        for (Herb h : Herb.values()) {
            CROPS.put(h, BLOCKS.registerBlock(h.id, p -> new HerbCropBlock(h, p), () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PLANT).noCollision().randomTicks().instabreak().sound(SoundType.CROP).pushReaction(PushReaction.DESTROY)));
        }
    }

    /** The Coil Snail's yam crop (its item, the yam, is its seed). */
    public static final DeferredBlock<CoilYamBlock> COIL_YAMS = BLOCKS.registerBlock("coil_yams", CoilYamBlock::new, () -> BlockBehaviour.Properties.of()
            .mapColor(MapColor.PLANT).noCollision().randomTicks().instabreak().sound(SoundType.CROP).pushReaction(PushReaction.DESTROY));
    public static final DeferredBlock<GraftingBenchBlock> GRAFTING_BENCH = BLOCKS.registerBlock("grafting_bench", GraftingBenchBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD));

    public static final DeferredBlock<HopperDenBlock> HOPPER_DEN = BLOCKS.registerBlock("thorn_hopper_den", HopperDenBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.DIRT).strength(0.6F).randomTicks().sound(SoundType.ROOTED_DIRT));

    /** The Dormant Behemoth's mound (docs/DESIGN.md section 14): the core, its dome, and the arena it raises. */
    public static final DeferredBlock<BehemothCoreBlock> BEHEMOTH_CORE = BLOCKS.registerBlock("behemoth_core", BehemothCoreBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(-1F, 3600000F).lightLevel(s -> s.getValue(BehemothCoreBlock.SPENT) ? 0 : 12)
                    .sound(SoundType.SHROOMLIGHT).noLootTable());
    public static final DeferredBlock<net.minecraft.world.level.block.Block> BEHEMOTH_MOUND = BLOCKS.registerSimpleBlock("behemoth_mound",
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(50F, 1200F).sound(SoundType.WART_BLOCK).noLootTable());
    public static final DeferredBlock<net.minecraft.world.level.block.Block> BEHEMOTH_ROOTS = BLOCKS.registerSimpleBlock("behemoth_roots",
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.CRIMSON_STEM).strength(1F).sound(SoundType.STEM).noLootTable());
    public static final DeferredBlock<net.minecraft.world.level.block.Block> BEHEMOTH_PILLAR = BLOCKS.registerSimpleBlock("behemoth_pillar",
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(-1F, 3600000F).lightLevel(s -> 15).sound(SoundType.SHROOMLIGHT).noLootTable());

    /** The three summoned bosses' homes (docs/DESIGN.md section 15): the King's throne and brambles, the Bloom's bud. */
    public static final DeferredBlock<BriarThroneBlock> BRIAR_THRONE = BLOCKS.registerBlock("briar_throne", BriarThroneBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(-1F, 3600000F).sound(SoundType.WOOD).noLootTable());
    public static final DeferredBlock<BriarBrambleBlock> BRIAR_BRAMBLE = BLOCKS.registerBlock("briar_bramble", BriarBrambleBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.PLANT).noCollision().noOcclusion().strength(-1F, 3600000F).sound(SoundType.SWEET_BERRY_BUSH)
                    .pushReaction(PushReaction.BLOCK).noLootTable());
    public static final DeferredBlock<CarrionBudBlock> CARRION_BUD = BLOCKS.registerBlock("carrion_bud", CarrionBudBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_MAGENTA).noCollision().noOcclusion().strength(-1F, 3600000F).sound(SoundType.SPORE_BLOSSOM)
                    .lightLevel(s -> s.getValue(CarrionBudBlock.SPENT) ? 0 : 6).noLootTable());

    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, FloraFerox.MODID);
    public static final DeferredHolder<Feature<?>, HopperDenBlock.DenFeature> DEN_FEATURE = FEATURES.register("thorn_hopper_den", HopperDenBlock.DenFeature::new);
    public static final DeferredHolder<Feature<?>, BehemothCoreBlock.MoundFeature> MOUND_FEATURE = FEATURES.register("behemoth_mound", BehemothCoreBlock.MoundFeature::new);
    public static final DeferredHolder<Feature<?>, BriarThroneBlock.ThroneFeature> THRONE_FEATURE = FEATURES.register("briar_throne", BriarThroneBlock.ThroneFeature::new);
    public static final DeferredHolder<Feature<?>, CarrionBudBlock.BudFeature> BUD_FEATURE = FEATURES.register("carrion_bud", CarrionBudBlock.BudFeature::new);

    public static final DeferredHolder<MenuType<?>, MenuType<GraftingMenu>> GRAFTING_MENU = MENUS.register("grafting_bench",
            () -> new MenuType<>(GraftingMenu::new, FeatureFlags.DEFAULT_FLAGS));
}
