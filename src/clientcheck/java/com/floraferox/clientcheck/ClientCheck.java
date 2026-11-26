package com.floraferox.clientcheck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Dev client check, compiled only with {@code gradlew runClient -Pclientcheck} (its own source set, never in a jar):
 * muted, off screen, never focused and never holding the mouse, in a fresh flat survival world it fires each seed gun
 * at a still iron golem (left click must fire, right click must not), throws each plant throwable, and flies each pair
 * of wings (rise, glide, land), with screenshots, then closes. Log lines start with CLIENTCHECK; the last is DONE.
 */
@EventBusSubscriber(modid = "floraferox", value = Dist.CLIENT)
public final class ClientCheck {
    private ClientCheck() { }

    static final boolean ON = Boolean.getBoolean("floraferox.clientcheck");
    private static final String NS = "floraferox";
    /** The flat world's floor: players stand at y -60. */
    private static final int G = -60;
    private static final String[] GUNS = {"pulse_pistol", "scorch_spitter", "rime_repeater"};
    private static final String[] THROWN = {"runed_seed", "spore_flask", "leafrang", "thorn_javelin"};
    private static final String[] WINGS = {"leafwing", "emberwing", "rimewing"};

    private record Step(int delay, Runnable action) { }

    private static final List<Step> STEPS = new ArrayList<>();
    private static boolean hidden, started, built, attack, use, jump;
    private static int index, ticks, failed, waiting;
    private static final Set<Integer> seen = new HashSet<>();
    private static int newShots, newOther;
    private static double baseY, peakY, glideStartY;
    private static boolean gliding, landed;
    private static float landHealth;
    private static float golemStart;

    /** Before the game handles input: keep the window unfocused and our keys pressed. */
    @SubscribeEvent
    public static void pre(ClientTickEvent.Pre e) {
        if (!ON) return;
        Minecraft mc = Minecraft.getInstance();
        mc.mouseHandler.releaseMouse();
        mc.options.keyAttack.setDown(attack);
        mc.options.keyUse.setDown(use);
        mc.options.keyJump.setDown(jump);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (!ON) return;
        Minecraft mc = Minecraft.getInstance();
        mc.mouseHandler.releaseMouse();
        if (!hidden) {
            hidden = true;
            long handle = mc.getWindow().handle();
            GLFW.glfwSetWindowPos(handle, -4000, -4000);
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc.options.pauseOnLostFocus = false;
            mc.getTutorial().setStep(TutorialSteps.NONE);
        }
        mc.getToastManager().clear();
        if (mc.level == null || mc.player == null) {
            if (++waiting % 200 == 0) say("waiting, screen " + (mc.screen == null ? "none" : mc.screen.getClass().getName()));
            // the loader's warnings screen (deprecated mods.toml keys of any mod) waits for a click; go on to the title
            if (!started && mc.screen != null && mc.screen.getClass().getSimpleName().equals("LoadingErrorScreen")) {
                say("skipping the mod loading warnings screen");
                mc.setScreen(new TitleScreen());
            }
            if (!started && mc.screen instanceof TitleScreen) {
                started = true;
                create(mc);
            }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        if (!built) {
            built = true;
            if (mc.screen != null) mc.setScreen(null);
            script(mc, server);
        }
        if (mc.screen != null && !(mc.screen instanceof TitleScreen)) mc.setScreen(null);
        // new floraferox/fundamentum entities seen on this client, sorted into seed shots and the rest
        for (Entity en : mc.level.entitiesForRendering()) {
            if (en == mc.player || en instanceof LivingEntity || !seen.add(en.getId())) continue;
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(en.getType());
            if (key.getPath().equals("seedshot")) newShots++;
            else if (key.getNamespace().equals(NS) || key.getNamespace().equals("fundamentum")) {
                newOther++;
                say("  saw " + key);
            }
        }
        if (mc.player.getY() > peakY) peakY = mc.player.getY();
        if (gliding && !landed && mc.player.onGround()) {            // first touchdown after the glide: let go of jump
            landed = true;
            landHealth = mc.player.getHealth();
            jump = false;
        }
        if (index >= STEPS.size()) return;
        Step step = STEPS.get(index);
        if (++ticks >= step.delay) {
            ticks = 0;
            index++;
            try {
                step.action.run();
            } catch (RuntimeException ex) {
                fail("step " + index + ": " + ex);
                ex.printStackTrace();
            }
        }
    }

    private static void at(int wait, Runnable action) {
        STEPS.add(new Step(Math.max(1, wait), action));
    }

    private static void script(Minecraft mc, MinecraftServer server) {
        at(100, () -> {
            cmd(server, "time set 6000");
            cmd(server, "weather clear 1000000");
            cmd(server, "gamerule doDaylightCycle false");
            cmd(server, "fill -12 " + G + " -12 12 " + (G + 30) + " 30 air");
            srv(server, p -> put(server, 0.5, 0.5, 0F, 0F));
            cmd(server, "summon minecraft:iron_golem 0.5 " + G + " 10.5 {NoAI:1b,Silent:1b,Health:100f}");
        });
        for (String gun : GUNS) {
            at(30, () -> {
                hold(server, gun);
                srv(server, p -> { put(server, 0.5, 0.5, 0F, 0F); p.setHealth(p.getMaxHealth()); });
                mc.player.setYRot(0F); mc.player.setXRot(0F);
            });
            at(20, () -> {
                cmd(server, "kill @e[type=minecraft:iron_golem]");
                cmd(server, "summon minecraft:iron_golem 0.5 " + G + " 6.5 {NoAI:1b,Silent:1b,Health:100f,Rotation:[180f,0f]}");
            });
            at(5, () -> {
                golemStart = golemHealth(server);
                say("aim: player " + mc.player.position() + " yaw " + mc.player.getYRot() + " pitch " + mc.player.getXRot() + ", golem " + golemPos(server) + " health " + golemStart);
                newShots = 0;
                use = true;                                       // right click: must not fire (1.20 round 2)
            });
            at(30, () -> {
                use = false;
                check(newShots == 0, gun + " right click fires nothing (shots " + newShots + ")");
                newShots = 0;
                attack = true;                                    // left click: the trigger
            });
            at(15, () -> shot(mc, gun + "-firing"));
            at(35, () -> {
                attack = false;
                int dur = mc.player.getMainHandItem().getDamageValue();
                say(gun + " shots seen " + newShots + ", durability used " + dur);
                check(newShots >= 2, gun + " left click fires (shots " + newShots + ")");
            });
            at(40, () -> {
                float lost = golemStart - golemHealth(server);
                say(gun + " golem lost " + lost);
                check(lost > 0F, gun + " shots hurt the golem");
                check(newShots < 200, gun + " no runaway fire after release (shots " + newShots + ")");
                cmd(server, "data merge entity @e[type=minecraft:iron_golem,limit=1] {Health:100f}");
            });
        }
        for (String t : THROWN) {
            at(20, () -> {
                hold(server, t);
                srv(server, p -> put(server, 0.5, 0.5, 0F, -10F));
                mc.player.setYRot(0F); mc.player.setXRot(-10F);
                newOther = 0;
                use = true;
            });
            at(20, () -> use = false);
            at(6, () -> shot(mc, t + "-thrown"));
            at(40, () -> {
                say(t + " thrown entities seen " + newOther);
                check(newOther >= 1, t + " throws something");
                cmd(server, "kill @e[type=!minecraft:player,type=!minecraft:iron_golem]");
            });
        }
        at(20, () -> {
            hold(server, "minecraft:air");
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        });
        for (String w : WINGS) {
            at(20, () -> {
                srv(server, p -> {
                    put(server, -6.5, -6.5, 0F, 0F);
                    p.fallDistance = 0;
                    boolean ok = CuriosApi.getCuriosInventory(p).map(inv -> {
                        if (!inv.getCurios().containsKey("wings")) return false;
                        inv.setEquippedCurio("wings", 0, stack(w));
                        return true;
                    }).orElse(false);
                    check(ok, w + " equipped in the wings slot");
                });
            });
            at(20, () -> { baseY = mc.player.getY(); peakY = baseY; jump = true; });
            at(4, () -> jump = false);                            // off the ground, then hold jump in the air to rise
            at(2, () -> jump = true);
            at(14, () -> shot(mc, w + "-rising"));
            at(56, () -> {                                        // jump stays held: the flight runs out, then it glides
                say(w + " rose " + String.format("%.2f", peakY - baseY));
                check(peakY - baseY > 3.0, w + " rises (" + String.format("%.2f", peakY - baseY) + " blocks)");
                glideStartY = mc.player.getY();
            });
            at(10, () -> { shot(mc, w + "-gliding"); gliding = true; landed = false; });
            at(10, () -> {
                double fell = glideStartY - mc.player.getY();
                say(w + " fell " + String.format("%.2f", fell) + " in 20 ticks after rising");
                check(fell < 4.0, w + " glides instead of dropping (fell " + String.format("%.2f", fell) + ")");
            });
            at(400, () -> {
                jump = false;
                gliding = false;
                check(landed, w + " lands");
                check(landHealth >= mc.player.getMaxHealth() - 0.01F, w + " landing does no harm (health " + landHealth + ")");
                shot(mc, w + "-landed");
                srv(server, p -> CuriosApi.getCuriosInventory(p).ifPresent(inv -> inv.setEquippedCurio("wings", 0, ItemStack.EMPTY)));
            });
        }
        at(20, () -> {
            say("DONE failed=" + failed);
            mc.stop();
        });
    }

    private static String golemPos(MinecraftServer server) {
        for (Entity en : server.overworld().getAllEntities()) {
            if (en.getType() == net.minecraft.world.entity.EntityType.IRON_GOLEM) return en.position().toString();
        }
        return "none";
    }

    private static float golemHealth(MinecraftServer server) {
        for (Entity en : server.overworld().getAllEntities()) {
            if (en.getType() == net.minecraft.world.entity.EntityType.IRON_GOLEM && en.isAlive()) return ((LivingEntity) en).getHealth();
        }
        fail("no golem");
        return 0F;
    }

    private static void hold(MinecraftServer server, String id) {
        srv(server, p -> {
            p.getInventory().setSelectedSlot(0);
            p.getInventory().setItem(0, id.equals("minecraft:air") ? ItemStack.EMPTY : stack(id));
            p.getInventory().setItem(1, stack("seed_pellet"));
            p.getInventory().getItem(1).setCount(64);
        });
    }

    private static ItemStack stack(String id) {
        Identifier key = id.contains(":") ? Identifier.parse(id) : Identifier.fromNamespaceAndPath(NS, id);
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (item == null) {
            fail("no item " + key);
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, item.getDefaultMaxStackSize());
    }

    private static void check(boolean ok, String what) {
        if (ok) say("PASS " + what);
        else fail(what);
    }

    /** A real teleport: setting the server player's position alone never reaches the client, which keeps its own. */
    private static void put(MinecraftServer server, double x, double z, float yaw, float pitch) {
        cmd(server, "tp @a " + x + " " + G + " " + z + " " + yaw + " " + pitch);
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private static void srv(MinecraftServer server, Consumer<ServerPlayer> action) {
        server.execute(() -> action.accept(server.getPlayerList().getPlayers().get(0)));
    }

    private static void shot(Minecraft mc, String name) {
        String file = "clientcheck-" + name + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), 1, msg -> say("saved " + file));
    }

    private static void fail(String why) {
        failed++;
        say("FAIL " + why);
    }

    private static void say(String line) {
        System.out.println("CLIENTCHECK " + line);
    }

    private static void create(Minecraft mc) {
        say("creating a flat world");
        String name = "clientcheck" + System.currentTimeMillis() / 1000;
        LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL,
            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261111L, false, false),
            provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            new TitleScreen());
    }
}
