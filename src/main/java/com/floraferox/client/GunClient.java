package com.floraferox.client;

import com.floraferox.FloraFerox;
import com.floraferox.GunEnchants;
import com.floraferox.VerdureWeapons;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RegisterConditionalItemModelPropertyEvent;
import org.jspecify.annotations.Nullable;

/**
 * 1.8, the seed guns' enchantments on the client (GunEnchants): the item model condition that picks the Heartsight
 * model, and Heartsight's zoom while sneaking with the gun in hand.
 */
@EventBusSubscriber(modid = FloraFerox.MODID, value = Dist.CLIENT)
public final class GunClient {
    private GunClient() { }

    /**
     * Item model condition {@code floraferox:has_enchantment}: true when the stack holds the named enchantment.
     * <pre>{"type": "minecraft:condition", "property": "floraferox:has_enchantment", "enchantment": "floraferox:heartsight", ...}</pre>
     */
    public record HasEnchantment(ResourceKey<Enchantment> enchantment) implements ConditionalItemModelProperty {
        public static final MapCodec<HasEnchantment> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ResourceKey.codec(Registries.ENCHANTMENT).fieldOf("enchantment").forGetter(HasEnchantment::enchantment)
        ).apply(i, HasEnchantment::new));

        @Override
        public boolean get(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner, int seed, ItemDisplayContext context) {
            return GunEnchants.has(stack, enchantment);
        }

        @Override
        public MapCodec<HasEnchantment> type() {
            return MAP_CODEC;
        }
    }

    @SubscribeEvent
    public static void properties(RegisterConditionalItemModelPropertyEvent e) {
        e.register(FloraFerox.id("has_enchantment"), HasEnchantment.MAP_CODEC);
    }

    /** Heartsight's zoom, 0 to 1, this tick and last (eased in and out over GunEnchants.SIGHT_TICKS). */
    private static float zoom, zoomO;

    static boolean sighting(Player p) {
        if (p == null || !p.isShiftKeyDown()) return false;
        ItemStack held = p.getMainHandItem();
        return held.getItem() instanceof VerdureWeapons.BloomGun && GunEnchants.has(held, GunEnchants.HEARTSIGHT);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        zoomO = zoom;
        float step = 1F / GunEnchants.SIGHT_TICKS;
        zoom = Mth.clamp(zoom + (sighting(Minecraft.getInstance().player) ? step : -step), 0F, 1F);
    }

    @SubscribeEvent
    public static void fov(ComputeFovModifierEvent e) {
        if (e.getPlayer() != Minecraft.getInstance().player) return;
        float z = Mth.lerp(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true), zoomO, zoom);
        if (z > 0F) e.setNewFovModifier(e.getNewFovModifier() * Mth.lerp(z * z * (3F - 2F * z), 1F, GunEnchants.SIGHT_FOV));
    }
}
