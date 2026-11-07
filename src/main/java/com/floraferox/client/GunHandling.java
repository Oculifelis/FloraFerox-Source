package com.floraferox.client;

import com.floraferox.FloraFerox;
import com.floraferox.GunEnchants;
import com.floraferox.GunTrigger;
import com.floraferox.VerdureItems;
import com.floraferox.VerdureWeapons;
import com.fundamentum.pose.FirstPersonHands;
import com.fundamentum.pose.PosePlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 1.10, the seed guns handle like Quiverfall's guns (Oculifelis): left click is the trigger (GunTrigger) and never
 * swings or breaks blocks; the gun is held up and aimed (the Pulse Pistol as a bow, the two big guns in both hands as a
 * crossbow); a pellet counter sits beside the hotbar; and every shot kicks the camera up a little and lets it settle,
 * never moving where the player aims.
 *
 * <p>1.11 (Oculifelis, 2026-11-04: "not held right in first person", "should be one handed", "shooting animations like
 * UR guns have kinda"): every seed gun is held as a pistol, one arm up along the look in third person (GunAnim.PISTOL);
 * in first person the player's own fist closes on its grip (Fundamentum's FirstPersonHands), it no longer dips each shot
 * (the durability change made vanilla re-equip it), it is raised once when taken in hand, and each shot kicks gun and
 * arm together (GunAnim, from gun_anim JSON).
 */
@EventBusSubscriber(modid = FloraFerox.MODID, value = Dist.CLIENT)
public final class GunHandling {
    private GunHandling() { }

    /** Camera kick per tier, in degrees; a Bloom Shot kicks harder. */
    static final float[] KICK = {0F, 1.4F, 2.0F, 1.1F};

    private static boolean down;
    private static ItemStack pulled = ItemStack.EMPTY;
    private static float lastCooldown;
    private static float kick;
    private static long kickTime = -100L;
    /** The client's own clock (ticks), when the last shot's kick and the last draw started, and what was drawn. */
    private static long clock;
    private static float shotAt = -1000F, drawAt = -1000F;
    private static @Nullable Item drawItem;
    private static int drawSlot = -1;
    /** Ticks to raise a gun taken in hand. */
    static final float DRAW_TICKS = 5F;

    static boolean holdingGun(@Nullable Player p) {
        return p != null && p.getMainHandItem().getItem() instanceof VerdureWeapons.BloomGun;
    }

    /** A seed gun or (1.12) a sceptre in the main hand: left click is its trigger. */
    static boolean holdingTrigger(@Nullable Player p) {
        return p != null && GunTrigger.triggers(p.getMainHandItem());
    }

    /** 1.12: other players' last shots on this client's clock, by entity id (GunTrigger.Shot). */
    private static final java.util.Map<Integer, Float> OTHERS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The server says this player fired a seed gun. */
    public static void shotBy(int entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == entity) return;      // our own shots are timed by the cooldown
        OTHERS.put(entity, (float) clock);
        if (OTHERS.size() > 64) OTHERS.entrySet().removeIf(en -> clock - en.getValue() > 40F);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        clock++;
        if (p != null) {
            Item held = p.getMainHandItem().getItem();
            int slot = p.getInventory().getSelectedSlot();
            if (held != drawItem || slot != drawSlot) {
                drawItem = held;
                drawSlot = slot;
                drawAt = clock;
            }
        }
        boolean want = mc.screen == null && p != null && p.isAlive() && !p.isSpectator() && holdingTrigger(p) && mc.options.keyAttack.isDown();
        if (want && down && p.getMainHandItem() != pulled) send(false);    // swapped guns while firing: let go first
        if (want != down) send(want);
        // a shot starts the gun's cooldown: that is when the camera kicks
        if (holdingGun(p) && mc.level != null) {
            ItemStack gun = p.getMainHandItem();
            float cd = p.getCooldowns().getCooldownPercent(gun, 0F);
            if (cd > lastCooldown + 0.3F) {
                int tier = ((VerdureWeapons.BloomGun) gun.getItem()).tier();
                kick = KICK[tier] * (GunEnchants.has(gun, GunEnchants.BLOOM_SHOT) ? 1.4F : 1F);
                kickTime = mc.level.getGameTime();
                shotAt = clock;
            }
            lastCooldown = cd;
        } else {
            lastCooldown = 0F;
        }
    }

    private static void send(boolean value) {
        Minecraft mc = Minecraft.getInstance();
        down = value;
        pulled = value && mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
        if (mc.getConnection() != null) ClientPacketDistributor.sendToServer(new GunTrigger.Payload(value));
    }

    /** Left click with a seed gun or sceptre is the trigger, so the vanilla attack (and block breaking) is cancelled. */
    @SubscribeEvent
    public static void click(InputEvent.InteractionKeyMappingTriggered e) {
        if (e.isAttack() && e.getHand() == InteractionHand.MAIN_HAND && holdingTrigger(Minecraft.getInstance().player)) {
            e.setCanceled(true);
            e.setSwingHand(false);
        }
    }

    /** Recoil: the camera kicks up and settles back over about half a second. */
    @SubscribeEvent
    public static void camera(ViewportEvent.ComputeCameraAngles e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float t = (float) (mc.level.getGameTime() - kickTime) + (float) e.getPartialTick();
        if (t >= 0F && t <= 12F) e.setPitch(e.getPitch() - kick * (float) Math.exp(-t * 0.35F) * Math.min(1F, t * 2F + 0.3F));
    }

    /** Ticks since the local player's last shot, smooth between ticks. */
    static float sinceShot(float pt) {
        return clock + pt - shotAt;
    }

    static @Nullable GunAnim anim(ItemStack stack) {
        return stack.getItem() instanceof VerdureWeapons.BloomGun ? GunAnim.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()) : null;
    }

    /**
     * Where a seed gun sits in first person: vanilla's held-item spot, raised over DRAW_TICKS when taken in hand (never
     * dipped by the re-equip a shot's durability change sets off), with the shot's kick on the hand.
     */
    static void place(PoseStack pose, HumanoidArm arm, ItemStack stack, float pt) {
        int inv = arm == HumanoidArm.RIGHT ? 1 : -1;
        float e = Mth.clamp((clock + pt - drawAt) / DRAW_TICKS, 0F, 1F);
        float low = (1F - e) * (1F - e);
        pose.translate(inv * 0.56F, -0.52F - 0.6F * low, -0.72F);
        GunAnim a = anim(stack);
        if (a != null) a.applyHandKick(pose, sinceShot(pt), inv < 0);
    }

    /** Fundamentum's first-person hands: the trigger hand's fist on the gun's grip, following its draw and kick. */
    static final FirstPersonHands.Frame FRAME = new FirstPersonHands.Frame() {
        @Override
        public boolean place(PoseStack pose, AbstractClientPlayer player, ItemStack stack, HumanoidArm arm, float pt, float equip) {
            if (anim(stack) == null) return false;
            GunHandling.place(pose, arm, stack, pt);
            return true;
        }

        @Override
        public boolean gripFrame(PoseStack pose, ItemStack stack, boolean leftHanded) {
            GunAnim a = anim(stack);
            if (a == null) return false;
            a.applyGripFrame(pose, leftHanded);
            return true;
        }

        @Override
        public PosePlayer.@Nullable FirstPerson firstPerson(ItemStack stack) {
            GunAnim a = anim(stack);
            return a == null ? null : new PosePlayer.FirstPerson("gun", null, new float[] {a.armPitch, a.armYaw, 0F}, null, false, 0F);
        }
    };

    @SubscribeEvent
    public static void extensions(RegisterClientExtensionsEvent e) {
        IClientItemExtensions held = new IClientItemExtensions() {
            @Override
            public HumanoidModel.@Nullable ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return GunAnim.PISTOL.getValue();
            }

            @Override
            public boolean applyForgeHandTransform(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack stack, float pt, float equip, float swing) {
                GunHandling.place(pose, arm, stack, pt);
                return true;
            }
        };
        VerdureItems.WEAPONS.values().forEach(w -> {
            if (w.get() instanceof VerdureWeapons.BloomGun) e.registerItem(held, w.get());
        });
        FirstPersonHands.registerFrame(FRAME);
    }

    /** The third-person arm's kick: the local player's from its own shots, (1.12) everyone else's from GunTrigger.Shot. */
    @SubscribeEvent
    public static void renderStates(RegisterRenderStateModifiersEvent e) {
        e.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override
            public <T extends Avatar & ClientAvatarEntity> void accept(T entity, AvatarRenderState state) {
                Minecraft mc = Minecraft.getInstance();
                if (!(entity instanceof Player player)) return;
                GunAnim a = anim(player.getMainHandItem());
                if (a == null) return;
                float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                float since;
                if (player == mc.player) {
                    since = sinceShot(pt);
                } else {
                    Float at = OTHERS.get(player.getId());
                    if (at == null) return;
                    since = clock - at + pt;
                }
                state.setRenderData(GunAnim.ARM_KICK, a.armRise * a.kickAmount(since));
            }
        });
    }

    @SubscribeEvent
    public static void reload(AddClientReloadListenersEvent e) {
        e.addListener(FloraFerox.id("gun_anim"), new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                GunAnim.reset();
            }
        });
    }

    @SubscribeEvent
    public static void layers(RegisterGuiLayersEvent e) {
        e.registerAboveAll(FloraFerox.id("seed_gun_hud"), GunHandling::hud);
    }

    /** The pellets you carry, bottom right past the hotbar: the next pellet's icon and the count (∞ with Infinity). */
    static void hud(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (p == null || mc.options.hideGui || !holdingGun(p)) return;
        ItemStack gun = p.getMainHandItem();
        ItemStack next = VerdureWeapons.BloomGun.ammo(p);
        boolean infinite = p.hasInfiniteMaterials() || GunEnchants.has(gun, GunEnchants.INFINITY);
        int count = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(VerdureItems.SEED_PELLET.get()) || s.is(VerdureItems.BURR_PELLET.get())) count += s.getCount();
        }
        int x = Math.min(g.guiWidth() / 2 + 91 + 30, g.guiWidth() - 52);
        int y = g.guiHeight() - 20;
        g.item(next.isEmpty() ? new ItemStack(VerdureItems.SEED_PELLET.get()) : next, x, y - 2);
        String text = infinite ? "∞" : String.valueOf(count);
        g.text(mc.font, Component.literal(text), x + 18, y + 2, !infinite && count == 0 ? 0xFFFF5050 : 0xFFE8F0D0, true);
    }
}
