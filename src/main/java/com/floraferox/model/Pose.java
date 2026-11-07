package com.floraferox.model;

/** Target-side sink for shared animation code. All values are added to the part's rest pose. */
public interface Pose {
    void rot(int part, float x, float y, float z);

    void move(int part, float x, float y, float z);

    void scale(int part, float s);

    /** Non-uniform scale (multiplies the part's current scale per axis). */
    void scale(int part, float x, float y, float z);

    void visible(int part, boolean visible);
}
