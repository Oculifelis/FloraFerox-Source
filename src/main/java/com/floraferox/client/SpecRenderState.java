package com.floraferox.client;

import com.floraferox.model.AnimState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/** Render state for Flora Ferox's mobs: the vanilla living state plus the shared animation inputs. */
public class SpecRenderState extends LivingEntityRenderState {
    public final AnimState anim = new AnimState();
}
