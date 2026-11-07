package com.floraferox.model;

/**
 * Version-independent description of a box-UV entity model. Each target converts it into its own
 * vanilla {@code LayerDefinition}; geometry and UVs come from tools/gen/creatures.py.
 */
public final class ModelSpec {
    public final String name;
    public final int texWidth;
    public final int texHeight;
    public final Part[] parts;

    public ModelSpec(String name, int texWidth, int texHeight, Part[] parts) {
        this.name = name;
        this.texWidth = texWidth;
        this.texHeight = texHeight;
        this.parts = parts;
    }

    /** A part; {@code parent} is an index into {@link #parts} (always earlier) or -1 for root children. */
    public static final class Part {
        public final String name;
        public final int parent;
        public final float px, py, pz, rx, ry, rz;
        public final Cube[] cubes;

        public Part(String name, int parent, float px, float py, float pz, float rx, float ry, float rz, Cube[] cubes) {
            this.name = name;
            this.parent = parent;
            this.px = px; this.py = py; this.pz = pz;
            this.rx = rx; this.ry = ry; this.rz = rz;
            this.cubes = cubes;
        }
    }

    public static final class Cube {
        public final int u, v;
        public final float x, y, z;
        public final int w, h, d;
        public final float inflate;
        public final boolean mirror;

        public Cube(int u, int v, float x, float y, float z, int w, int h, int d, float inflate, boolean mirror) {
            this.u = u; this.v = v;
            this.x = x; this.y = y; this.z = z;
            this.w = w; this.h = h; this.d = d;
            this.inflate = inflate;
            this.mirror = mirror;
        }
    }
}
