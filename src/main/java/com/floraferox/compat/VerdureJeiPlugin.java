package com.floraferox.compat;

import com.floraferox.FloraFerox;
import com.floraferox.Herb;
import com.floraferox.Splicing;
import com.floraferox.VerdureItems;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 1.9, JEI (Oculifelis: "any crafting stations or whatnot need recipe browser support"). Loaded only with JEI.
 * <ul>
 * <li>The Grafting Bench's splices ({@link Splicing}): wheat seeds and a herb's flowers, a herb's seeds and an Ember or
 * Rime Graft; the bench is their station.</li>
 * <li>The brews need nothing here: they are NeoForge brewing mixes (VerdurePotions), which JEI's brewing page already
 * shows; each herb gets an info line pointing there.</li>
 * <li>JEI has no villager trades, so the Weedwarden's job is told on the bench's info page; the Thorn Hopper Den gets one
 * too.</li>
 * </ul>
 * Everything else is plain crafting, which JEI shows by itself.
 */
@JeiPlugin
public final class VerdureJeiPlugin implements IModPlugin {
    /** One splice: what goes in (either slot may take several items) and the seeds that come out. */
    public record Splice(List<ItemStack> first, List<ItemStack> second, ItemStack result) { }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    public static final IRecipeType<Splice> SPLICING = IRecipeType.create(FloraFerox.id("splicing"), Splice.class);

    @Override
    public Identifier getPluginUid() {
        return FloraFerox.id("jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new SplicingCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addCraftingStation(SPLICING, VerdureItems.GRAFTING_BENCH.get());
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        List<Splice> splices = splices();
        registration.addRecipes(SPLICING, splices);
        LOGGER.info("Flora Ferox: {} Grafting Bench splices for JEI", splices.size());
        registration.addIngredientInfo(VerdureItems.GRAFTING_BENCH.get(), Component.translatable("gui.floraferox.jei.bench_info"));
        registration.addIngredientInfo(VerdureItems.HOPPER_DEN.get(), Component.translatable("gui.floraferox.jei.den_info"));
        for (Herb h : Herb.values()) registration.addIngredientInfo(VerdureItems.HERBS.get(h).get(), Component.translatable("gui.floraferox.jei.brew_info"));
    }

    /** Every splice, worked out from {@link Splicing#result} itself so the page can't drift from the bench. */
    static List<Splice> splices() {
        List<Splice> out = new ArrayList<>();
        ItemStack wheat = new ItemStack(Items.WHEAT_SEEDS);
        for (Herb h : Herb.values()) {
            ItemStack seeds = new ItemStack(VerdureItems.SEEDS.get(h).get());
            if (h.tier == 1) {
                List<ItemStack> flowers = new ArrayList<>();
                for (Item item : BuiltInRegistries.ITEM) {
                    if (!(item instanceof BlockItem)) continue;
                    ItemStack flower = new ItemStack(item);
                    if (ItemStack.isSameItem(Splicing.result(wheat, flower), seeds)) flowers.add(flower);
                }
                if (!flowers.isEmpty()) out.add(new Splice(List.of(wheat), flowers, seeds));
            } else {
                ItemStack from = new ItemStack(VerdureItems.SEEDS.get(Herb.of(h.kind, h.tier - 1)).get());
                ItemStack graft = new ItemStack(h.tier == 2 ? VerdureItems.EMBER_GRAFT.get() : VerdureItems.RIME_GRAFT.get());
                if (ItemStack.isSameItem(Splicing.result(from, graft), seeds)) out.add(new Splice(List.of(from), List.of(graft), seeds));
            }
        }
        return out;
    }

    /** seeds + flower or graft -> seeds, one row. */
    static final class SplicingCategory extends AbstractRecipeCategory<Splice> {
        SplicingCategory(IGuiHelper gui) {
            super(SPLICING, Component.translatable("gui.floraferox.jei.splicing"), gui.createDrawableItemLike(VerdureItems.GRAFTING_BENCH.get()), 116, 26);
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, Splice s, IFocusGroup focuses) {
            builder.addInputSlot(1, 5).setStandardSlotBackground().addItemStacks(s.first());
            builder.addInputSlot(37, 5).setStandardSlotBackground().addItemStacks(s.second());
            builder.addOutputSlot(95, 5).setOutputSlotBackground().addItemStack(s.result());
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, Splice s, IFocusGroup focuses) {
            builder.addRecipePlusSign().setPosition(21, 7);
            builder.addRecipeArrow().setPosition(61, 5);
        }
    }
}
