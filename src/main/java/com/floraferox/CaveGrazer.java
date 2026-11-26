package com.floraferox;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowParentGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The Lush Caves grazers (and the Coil Snail, which hides in its shell and rams) share breeding, pollination and a neutral retaliation goal (DESIGN.md, section 16).
 * Each species has its own attack. Only Bloomsprout has ember and rime looks.
 */
public abstract class CaveGrazer extends Animal {
    private static final EntityDataAccessor<Integer> ANIM = SynchedEntityData.defineId(CaveGrazer.class, EntityDataSerializers.INT);
    /** The Coil Snail's ticks left drawn into its shell (0: out). */
    private static final EntityDataAccessor<Integer> HIDE = SynchedEntityData.defineId(CaveGrazer.class, EntityDataSerializers.INT);
    /** The Coil Snail: a first blow sends it into its shell this long, where it takes this much of each blow; a blow
     * while it hides brings it out ramming. It breeds with this tag's items. */
    public static final int HIDE_TICKS = 60, HIDE_IN = 5, HIDE_OUT = 10;
    public static final float HIDE_DAMAGE = 0.4F;
    public static final double RAM_KNOCKBACK = 1.2;
    public static final TagKey<Item> SNAIL_FOOD = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(FloraFerox.MODID, "coil_snail_food"));

    /** Its strike timings: a slow tell, a short blow (StrikeBrain). */
    public record Timing(int tell, int strike, int recover, int cooldown, double reach, float tellEnd, float strikeEnd)
            implements StrikeBrain.Timing { }

    /** The sheets' timings; reach is the shell's and the parasol's (the gills squirt farther: GILLS_REACH). */
    public static final Timing TIMING = new Timing(14, 6, 10, 30, 2.5, 0.45F, 0.7F);
    public static final double GILLS_REACH = 4.0;
    /** It answers for at most this long, or this many blows landed, then calms down. */
    public static final int ANSWER_TICKS = 200, ANSWER_HITS = 2;
    /** Its pollen: herbs this far round it (and 2 up or down), one every so often, dusted from this close. */
    public static final int POLLEN_RANGE = 6, POLLEN_COOLDOWN = 600, DUST_CHANCES = 1;
    public static final double POLLEN_REACH = 2.0;
    /** It gives up walking to a herb after this long (and waits a little before looking again). */
    private static final int POLLEN_GIVE_UP = 200, POLLEN_RETRY = 100;

    public final StrikeBrain brain = new StrikeBrain(new StrikeBrain.Timing() {
        public int tell() { return TIMING.tell(); }
        public int strike() { return TIMING.strike(); }
        public int recover() { return TIMING.recover(); }
        public int cooldown() { return TIMING.cooldown(); }
        public double reach() { return plan == Plan.GILLS ? GILLS_REACH : TIMING.reach(); }
        public float tellEnd() { return TIMING.tellEnd(); }
        public float strikeEnd() { return TIMING.strikeEnd(); }
    });

    private enum Plan { SHELL, GILLS, PARASOL, SNAIL }

    private final Plan plan;
    private int answerTicks, answers;
    private int pollenCooldown;
    /** For tests: blows landed, herbs dusted, and the last one dusted. */
    public int strikes, dusted;
    public BlockPos lastDusted;
    private VerdureElements.Sources sources;

    private CaveGrazer(EntityType<? extends CaveGrazer> type, Level level, Plan plan) {
        super(type, level);
        this.plan = plan;
        xpReward = 3;
    }

    // Separate classes keep vanilla breeding and following parents within each species.
    public static final class Bloomsprout extends CaveGrazer {
        Bloomsprout(EntityType<? extends CaveGrazer> type, Level level) {
            super(type, level, Plan.SHELL);
        }
    }

    public static final class Glowgill extends CaveGrazer {
        Glowgill(EntityType<? extends CaveGrazer> type, Level level) {
            super(type, level, Plan.GILLS);
        }
    }

    public static final class DripleafToad extends CaveGrazer {
        DripleafToad(EntityType<? extends CaveGrazer> type, Level level) {
            super(type, level, Plan.PARASOL);
        }
    }

    public static final class CoilSnail extends CaveGrazer {
        CoilSnail(EntityType<? extends CaveGrazer> type, Level level) {
            super(type, level, Plan.SNAIL);
        }
    }

    /** Whether it is the Coil Snail. */
    public boolean snail() { return plan == Plan.SNAIL; }

    /** Ticks left in its shell (0: out). */
    public int hideTicks() { return entityData.get(HIDE); }

    /** How far it is drawn into its shell, 0..1, for the renderer: in over HIDE_IN ticks, out over its last HIDE_OUT. */
    public float hidden(float partial) {
        float h = Math.max(0F, hideTicks() - partial);
        if (h <= 0F) return 0F;
        return Math.min(1F, (HIDE_TICKS - h) / HIDE_IN) * Math.min(1F, h / HIDE_OUT);
    }

    /** The snail's shell: the first blow sends it in and it lets the matter drop; a blow while in takes HIDE_DAMAGE of
     * the damage and brings it out to answer (AnswerGoal, its ram). */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (plan != Plan.SNAIL || isBaby()) return super.hurtServer(level, source, amount);
        boolean hiding = hideTicks() > 0;
        boolean hurt = super.hurtServer(level, source, hiding ? amount * HIDE_DAMAGE : amount);
        if (!hurt || !isAlive()) return hurt;
        if (!hiding && getTarget() == null) {
            entityData.set(HIDE, HIDE_TICKS);
            setLastHurtByMob(null);                              // no answer yet: it only hides
            getNavigation().stop();
            playSound(SoundEvents.TURTLE_SHAMBLE, 1.0F, 0.8F);
        } else if (hiding) {
            entityData.set(HIDE, Math.min(hideTicks(), HIDE_OUT));   // out it comes, and the blow is answered
        }
        return hurt;
    }

    @Override
    public net.minecraft.world.damagesource.DamageSources damageSources() {
        return sources == null ? sources = new VerdureElements.Sources(this) : sources;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Animal.createAnimalAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.2)
                .add(Attributes.ATTACK_DAMAGE, 3.0).add(Attributes.ARMOR, 2.0).add(Attributes.FOLLOW_RANGE, 16.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(ANIM, 0);
        b.define(HIDE, 0);
    }

    /** The brain's state and its ticks, synced for the renderer: state * 1000 + ticks. */
    public int anim() { return entityData.get(ANIM); }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new AnswerGoal());
        goalSelector.addGoal(2, new BreedGoal(this, 1.0));
        goalSelector.addGoal(3, new TemptGoal(this, 1.1, this::isFood, false));
        goalSelector.addGoal(4, new FollowParentGoal(this, 1.1));
        goalSelector.addGoal(5, new PollinateGoal());
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));                       // its kind are not called in
    }

    @Override
    public boolean isFood(ItemStack stack) { return plan == Plan.SNAIL ? stack.is(SNAIL_FOOD) : stack.is(Items.GLOW_BERRIES); }

    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        if (!(getType().create(level, EntitySpawnReason.BREEDING) instanceof CaveGrazer baby)) return null;
        Looks.set(baby, Looks.look(partner != null && partner.getType() == getType() && random.nextBoolean() ? partner : this).id());
        return baby;
    }

    /** A -flare or -rime herb fed to it gives it that strain's look for good (as the grafts do, Looks.use). */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Herb herb = plan == Plan.SHELL ? herbOf(stack) : null;
        if (herb != null && herb.tier > 1) {
            VerdureLooks.Look strain = Looks.ofKind("bloomsprout", herb.tier == 2 ? "ember" : "rime");
            if (strain != null && !Looks.look(this).id().equals(strain.id())) {
                if (level() instanceof ServerLevel level) {
                    Looks.set(this, strain.id());
                    if (!player.getAbilities().instabuild) stack.shrink(1);
                    playSound(SoundEvents.GENERIC_EAT.value(), 0.8F, 1.2F);
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + getBbHeight() * 0.6, getZ(), 12, 0.4, 0.3, 0.4, 0.0);
                }
                return InteractionResult.SUCCESS;
            }
        }
        return super.mobInteract(player, hand);
    }

    private static Herb herbOf(ItemStack stack) {
        if (stack.isEmpty()) return null;
        for (var e : VerdureItems.HERBS.entrySet()) if (stack.is(e.getValue().get())) return e.getKey();
        return null;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (pollenCooldown > 0) pollenCooldown--;
        int hide = hideTicks();
        if (hide > 0) {                                          // in its shell: it stays put
            entityData.set(HIDE, hide - 1);
            if (hide > HIDE_OUT) {
                getNavigation().stop();
                setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
            }
        }
        LivingEntity t = getTarget();
        if (t != null) {
            answerTicks++;
            if (!t.isAlive() || isBaby() || answerTicks > ANSWER_TICKS || answers >= ANSWER_HITS) {
                calm();
                t = null;
            }
        }
        boolean prey = t != null && hasLineOfSight(t);
        double d = prey ? gap(t) : Double.MAX_VALUE;
        switch (brain.tick(prey, d)) {
            case TELL -> {
                getNavigation().stop();
                playSound(plan == Plan.SHELL ? SoundEvents.SPORE_BLOSSOM_BREAK : plan == Plan.SNAIL ? SoundEvents.TURTLE_SHAMBLE
                                : SoundEvents.BIG_DRIPLEAF_TILT_DOWN, 1.0F,
                        1.0F + (random.nextFloat() - 0.5F) * 0.2F);
            }
            case STRIKE -> strike(level, prey ? t : null, d);
            default -> { }
        }
        if (brain.rooted()) {
            getNavigation().stop();
            setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
            if (prey) getLookControl().setLookAt(t, 30F, 30F);
        }
        entityData.set(ANIM, brain.state().ordinal() * 1000 + Math.min(999, brain.ticksInState()));
    }

    /** The gap between its body and its prey's. */
    private double gap(LivingEntity t) {
        return Math.max(0.0, distanceTo(t) - (getBbWidth() + t.getBbWidth()) * 0.5);
    }

    /** It lets the matter drop. */
    public void calm() {
        setTarget(null);
        setLastHurtByMob(null);
        answerTicks = 0;
        answers = 0;
    }

    @Override
    public void setTarget(LivingEntity target) {
        if (target != getTarget()) answerTicks = 0;
        super.setTarget(target);
    }

    /** Its species' attack: the shell's puff (Weakness; sparks or frost on its strain looks), the gills' squirt (Slowness),
     * the parasol's dumped water (puts out a fire). Of its element (VerdureElements.element). */
    private void strike(ServerLevel level, LivingEntity t, double d) {
        String kind = Looks.look(this).kind();
        ParticleOptions p = plan == Plan.GILLS ? ParticleTypes.SPLASH : plan == Plan.PARASOL ? ParticleTypes.FALLING_WATER
                : plan == Plan.SNAIL ? ParticleTypes.POOF
                : kind.equals("ember") ? ParticleTypes.FLAME : kind.equals("rime") ? ParticleTypes.SNOWFLAKE : ParticleTypes.SPORE_BLOSSOM_AIR;
        double y = getY() + getBbHeight() * (plan == Plan.PARASOL ? 1.0 : 0.5);
        level.sendParticles(p, getX(), y, getZ(), 20, 0.5, 0.3, 0.5, 0.05);
        playSound(plan == Plan.SHELL ? SoundEvents.AZALEA_LEAVES_BREAK : plan == Plan.SNAIL ? SoundEvents.SHULKER_CLOSE
                : SoundEvents.AXOLOTL_SPLASH, 1.0F, 1.0F);
        if (t == null || d > brain.profile.reach() + 0.5 || !hasLineOfSight(t)) return;
        level.sendParticles(p, t.getX(), t.getY() + t.getBbHeight() * 0.6, t.getZ(), 12, 0.3, 0.3, 0.3, 0.02);
        if (!doHurtTarget(level, t)) return;
        strikes++;
        answers++;
        switch (plan) {
            case SHELL -> {
                if (kind.equals("ember") || kind.equals("rime")) VerdureElements.burn(this, t, 3);   // sparks set alight, frost chills
                else t.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0), this);
            }
            case GILLS -> t.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0), this);
            case PARASOL -> t.clearFire();
            case SNAIL -> t.knockback(RAM_KNOCKBACK, getX() - t.getX(), getZ() - t.getZ());   // the shell's shove
        }
    }

    // ---- its pollen -----------------------------------------------------------------------------------------------

    /** The nearest unripe herb crop in reach of its pollen, or null. */
    public BlockPos findHerb(ServerLevel level) {
        BlockPos at = blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-POLLEN_RANGE, -2, -POLLEN_RANGE), at.offset(POLLEN_RANGE, 2, POLLEN_RANGE))) {
            BlockState s = level.getBlockState(p);
            if (!(s.getBlock() instanceof HerbCropBlock crop) || crop.isMaxAge(s)) continue;
            double dd = p.distToCenterSqr(position());
            if (dd < bestD) {
                bestD = dd;
                best = p.immutable();
            }
        }
        return best;
    }

    /** Dusts this herb: DUST_CHANCES extra growth chances, a puff of pollen; its pollen then needs time. Whether it grew. */
    public boolean dust(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof HerbCropBlock crop)) return false;
        boolean grew = crop.dust(level, pos, random, DUST_CHANCES);
        level.sendParticles(ParticleTypes.FALLING_SPORE_BLOSSOM, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 10, 0.3, 0.2, 0.3, 0.0);
        if (grew) level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.3, 0.2, 0.3, 0.0);
        playSound(SoundEvents.FLOWERING_AZALEA_STEP, 0.6F, 1.3F);
        pollenCooldown = POLLEN_COOLDOWN;
        dusted++;
        lastDusted = pos.immutable();
        return grew;
    }

    public int pollenCooldown() { return pollenCooldown; }
    /** For tests: ready its pollen now. */
    void pollenCooldown(int ticks) { pollenCooldown = ticks; }

    /** It walks to an unripe herb near it and dusts it, when its pollen is ready and nothing is bothering it. */
    private final class PollinateGoal extends Goal {
        private BlockPos herb;
        private int ticks;

        PollinateGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override
        public boolean canUse() {
            if (plan == Plan.SNAIL || pollenCooldown > 0 || getTarget() != null || isInLove() || random.nextInt(20) != 0
                    || !(level() instanceof ServerLevel level)) return false;
            herb = findHerb(level);
            return herb != null;
        }

        @Override
        public boolean canContinueToUse() {
            return herb != null && ticks < POLLEN_GIVE_UP && getTarget() == null && pollenCooldown == 0;
        }

        @Override
        public void start() {
            ticks = 0;
            getNavigation().moveTo(herb.getX() + 0.5, herb.getY(), herb.getZ() + 0.5, 1.0);
        }

        @Override
        public void tick() {
            ticks++;
            getLookControl().setLookAt(herb.getX() + 0.5, herb.getY() + 0.3, herb.getZ() + 0.5);
            if (herb.distToCenterSqr(position()) <= POLLEN_REACH * POLLEN_REACH + 1.0) {
                getNavigation().stop();
                if (level() instanceof ServerLevel level) dust(level, herb);
                herb = null;
            } else if (ticks % 20 == 0) {
                getNavigation().moveTo(herb.getX() + 0.5, herb.getY(), herb.getZ() + 0.5, 1.0);
            }
        }

        @Override
        public void stop() {
            if (herb != null && pollenCooldown == 0) pollenCooldown = POLLEN_RETRY;   // couldn't get there: look again later
            herb = null;
            getNavigation().stop();
        }
    }

    /** While it answers a blow it closes on whoever struck it (the brain shows the tell and strikes in reach). */
    private final class AnswerGoal extends Goal {
        AnswerGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override
        public boolean canUse() {
            LivingEntity t = getTarget();
            return t != null && t.isAlive();
        }

        @Override
        public void tick() {
            LivingEntity t = getTarget();
            if (t == null) return;
            getLookControl().setLookAt(t, 30F, 30F);
            if (!brain.rooted() && gap(t) > brain.profile.reach() * 0.8) getNavigation().moveTo(t, 1.25);
            else getNavigation().stop();
        }

        @Override
        public void stop() { getNavigation().stop(); }
    }

    // ---- spawning, saving, sounds -----------------------------------------------------------------------------------

    /** Lush Caves floors: moss, clay or rooted dirt (light doesn't matter down there). */
    public static boolean checkSpawn(EntityType<CaveGrazer> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource random) {
        if (reason == EntitySpawnReason.SPAWNER) return true;
        BlockState below = level.getBlockState(pos.below());
        return below.is(Blocks.MOSS_BLOCK) || below.is(Blocks.CLAY) || below.is(Blocks.ROOTED_DIRT) || below.is(Blocks.MOSS_CARPET);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putInt("PollenCooldown", pollenCooldown);
        out.putInt("Hide", hideTicks());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        pollenCooldown = in.getIntOr("PollenCooldown", 0);
        entityData.set(HIDE, in.getIntOr("Hide", 0));
    }

    @Override
    protected SoundEvent getAmbientSound() { return SoundEvents.TURTLE_AMBIENT_LAND; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.TURTLE_HURT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.TURTLE_DEATH; }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.AZALEA_STEP, 0.3F, 1.0F);
    }
}
