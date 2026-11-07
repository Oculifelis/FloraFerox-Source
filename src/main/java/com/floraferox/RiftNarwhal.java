package com.floraferox;

import static com.floraferox.VerdureRules.*;

import com.floraferox.VerdureRules.Wild;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Rift Narwhal: a neutral flier of the outer End that charges whoever hurts it. Fed chorus fruit it may take to
 * you (one in NARWHAL_TAME_ONE_IN); tamed, it stays, never fights, and carries you where you look, like a happy ghast.
 */
public class RiftNarwhal extends WildFlier {
    private static final EntityDataAccessor<Boolean> TAMED = SynchedEntityData.defineId(RiftNarwhal.class, EntityDataSerializers.BOOLEAN);

    public RiftNarwhal(EntityType<? extends Monster> type, Level level) { super(type, level, Wild.RIFT_NARWHAL); }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(TAMED, false);
    }

    public boolean tamed() { return entityData.get(TAMED); }

    public void tame() {
        entityData.set(TAMED, true);
        setPersistenceRequired();
        setTarget(null);
    }

    @Override
    protected boolean hunts() { return !tamed(); }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!tamed() && held.is(Items.CHORUS_FRUIT)) {
            if (level() instanceof ServerLevel sl) {
                held.consume(1, player);
                boolean took = random.nextInt(NARWHAL_TAME_ONE_IN) == 0;
                if (took) tame();
                sl.sendParticles(took ? ParticleTypes.HEART : ParticleTypes.SMOKE, getX(), getY() + getBbHeight(), getZ(), 7, 0.5, 0.3, 0.5, 0.02);
                playSound(took ? SoundEvents.ALLAY_ITEM_TAKEN : SoundEvents.CHORUS_FRUIT_TELEPORT, 1.0F, 1.0F);
            }
            return InteractionResult.SUCCESS;
        }
        if (tamed() && held.isEmpty() && !isVehicle() && !player.isSecondaryUseActive()) {
            if (!level().isClientSide()) player.startRiding(this);
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public @Nullable LivingEntity getControllingPassenger() {
        return tamed() && getFirstPassenger() instanceof Player p ? p : super.getControllingPassenger();
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) { return tamed() && getPassengers().isEmpty(); }

    /** Where the rider looks, forward or back; up with jump (as a happy ghast flies). */
    @Override
    protected Vec3 getRiddenInput(Player rider, Vec3 self) {
        float forward = 0F, up = 0F;
        if (rider.zza != 0F) {
            float f = Mth.cos(rider.getXRot() * Mth.DEG_TO_RAD), u = -Mth.sin(rider.getXRot() * Mth.DEG_TO_RAD);
            if (rider.zza < 0F) {
                f *= -0.5F;
                u *= -0.5F;
            }
            forward = f;
            up = u;
        }
        if (rider.isJumping()) up += 0.5F;
        return new Vec3(rider.xxa, up, forward).scale(3.9F * NARWHAL_RIDE_SPEED);
    }

    @Override
    protected void tickRidden(Player rider, Vec3 input) {
        super.tickRidden(rider, input);
        float yRot = getYRot() + Mth.wrapDegrees(rider.getYRot() - getYRot()) * 0.1F;
        setRot(yRot, rider.getXRot() * 0.5F);
        yRotO = yBodyRot = yHeadRot = yRot;
    }

    @Override
    public void travel(Vec3 input) {
        if (isVehicle() && getControllingPassenger() != null) {
            float speed = (float) getAttributeValue(Attributes.FLYING_SPEED) * 5.0F / 3.0F;
            travelFlying(input, speed, speed, speed);
        } else {
            super.travel(input);
        }
    }

    @Override
    public boolean removeWhenFarAway(double dist) { return !tamed() && super.removeWhenFarAway(dist); }

    /** A tamed one stays, in peaceful too. */
    @Override
    public void checkDespawn() {
        if (!tamed()) super.checkDespawn();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putBoolean("Tamed", tamed());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        entityData.set(TAMED, in.getBooleanOr("Tamed", false));
    }
}
