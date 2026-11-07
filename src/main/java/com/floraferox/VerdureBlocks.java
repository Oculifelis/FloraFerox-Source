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

    public static final DeferredBlock<GraftingBenchBlock> GRAFTING_BENCH = BLOCKS.registerBlock("grafting_bench", GraftingBenchBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD));

    public static final DeferredBlock<HopperDenBlock> HOPPER_DEN = BLOCKS.registerBlock("thorn_hopper_den", HopperDenBlock::new,
            () -> BlockBehaviour.Properties.of().mapColor(MapColor.DIRT).strength(0.6F).randomTicks().sound(SoundType.ROOTED_DIRT));

    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, FloraFerox.MODID);
    public static final DeferredHolder<Feature<?>, HopperDenBlock.DenFeature> DEN_FEATURE = FEATURES.register("thorn_hopper_den", HopperDenBlock.DenFeature::new);

    public static final DeferredHolder<MenuType<?>, MenuType<GraftingMenu>> GRAFTING_MENU = MENUS.register("grafting_bench",
            () -> new MenuType<>(GraftingMenu::new, FeatureFlags.DEFAULT_FLAGS));
}
