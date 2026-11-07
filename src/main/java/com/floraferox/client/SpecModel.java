package com.floraferox.client;

import com.floraferox.model.AnimState;
import com.floraferox.model.ModelSpec;
import com.floraferox.model.Pose;
import java.util.EnumSet;
import java.util.function.BiConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.core.Direction;

/**
 * A vanilla EntityModel built from a shared {@link ModelSpec} and animated by shared
 * animation code (VerdureAnimations) through the {@link Pose} interface.
 * On 26.1 the model only sees the render state; the renderer fills its {@link AnimState} during extraction.
 */
public class SpecModel extends EntityModel<SpecRenderState> {
    private final ModelPart[] parts;
    private final BiConsumer<Pose, AnimState> animation;

    private final Pose pose = new Pose() {
        @Override
        public void rot(int i, float x, float y, float z) {
            ModelPart p = parts[i];
            p.xRot += x;
            p.yRot += y;
            p.zRot += z;
        }

        @Override
        public void move(int i, float x, float y, float z) {
            ModelPart p = parts[i];
            p.x += x;
            p.y += y;
            p.z += z;
        }

        @Override
        public void scale(int i, float s) {
            ModelPart p = parts[i];
            p.xScale *= s;
            p.yScale *= s;
            p.zScale *= s;
        }

        @Override
        public void scale(int i, float x, float y, float z) {
            ModelPart p = parts[i];
            p.xScale *= x;
            p.yScale *= y;
            p.zScale *= z;
        }

        @Override
        public void visible(int i, boolean v) { parts[i].visible = v; }
    };

    public SpecModel(ModelPart root, ModelSpec spec, BiConsumer<Pose, AnimState> animation) {
        super(root);
        this.animation = animation;
        this.parts = new ModelPart[spec.parts.length];
        for (int i = 0; i < spec.parts.length; i++) {
            ModelSpec.Part p = spec.parts[i];
            parts[i] = (p.parent < 0 ? root : parts[p.parent]).getChild(p.name);
        }
    }

    public static LayerDefinition createLayer(ModelSpec spec) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition[] defs = new PartDefinition[spec.parts.length];
        for (int i = 0; i < spec.parts.length; i++) {
            ModelSpec.Part p = spec.parts[i];
            CubeListBuilder cubes = CubeListBuilder.create();
            for (ModelSpec.Cube c : p.cubes) {
                cubes.texOffs(c.u, c.v).mirror(c.mirror);
                if (c.w == 0 || c.h == 0 || c.d == 0) {
                    // a flat plane: one face, seen from both sides (its two coincident faces, drawn without culling,
                    // flickered against each other: z-fighting)
                    Direction face = c.h == 0 ? Direction.UP : c.w == 0 ? Direction.EAST : Direction.NORTH;
                    cubes.addBox(c.x, c.y, c.z, c.w, c.h, c.d, EnumSet.of(face));
                } else {
                    cubes.addBox(c.x, c.y, c.z, c.w, c.h, c.d, new CubeDeformation(c.inflate));
                }
            }
            PartDefinition parent = p.parent < 0 ? mesh.getRoot() : defs[p.parent];
            defs[i] = parent.addOrReplaceChild(p.name, cubes, PartPose.offsetAndRotation(p.px, p.py, p.pz, p.rx, p.ry, p.rz));
        }
        return LayerDefinition.create(mesh, spec.texWidth, spec.texHeight);
    }

    @Override
    public void setupAnim(SpecRenderState state) {
        super.setupAnim(state);
        for (ModelPart p : allParts()) p.visible = true;
        animation.accept(pose, state.anim);
    }
}
