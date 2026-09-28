package com.vvcs.model;

/** Pixel dimensions of a video frame, either of a project profile or of a source asset. */
public record Resolution(int width, int height) {

    public static Resolution of(int width, int height) {
        return new Resolution(width, height);
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}
