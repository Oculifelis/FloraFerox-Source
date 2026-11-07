package com.floraferox.client;

import com.floraferox.FloraFerox;
import com.floraferox.VerdureBows;
import com.floraferox.VerdureItems;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * 1.16: what vanilla only does for its own bow and crossbow (it checks the item itself): a drawn bow zooms the view in,
 * and a loaded arbalest is held up in both hands, aimed.
 */
@EventBusSubscriber(modid = FloraFerox.MODID, value = Dist.CLIENT)
public final class BowClient {
    private BowClient() { }

    @SubscribeEvent
    public static void extensions(RegisterClientExtensionsEvent e) {
        IClientItemExtensions hold = new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return !entity.swinging && CrossbowItem.isCharged(stack) ? HumanoidModel.ArmPose.CROSSBOW_HOLD : null;
            }
        };
        VerdureItems.CROSSBOWS.values().forEach(i -> e.registerItem(hold, i.get()));
    }

    @SubscribeEvent
    public static void fov(ComputeFovModifierEvent e) {
        Player p = e.getPlayer();
        if (!p.isUsingItem() || !(p.getUseItem().getItem() instanceof VerdureBows.HeartBow)) return;
        float scale = Math.min(p.getTicksUsingItem() / 20.0F, 1.0F);
        e.setNewFovModifier(e.getNewFovModifier() * (1.0F - Mth.square(scale) * 0.15F));
    }
}
