package com.vvcs.model;

/**
 * Media nature of a timeline lane.
 *
 * <p>{@link #UNKNOWN} exists so that a track whose kind cannot be established from the
 * source project is never mislabelled as video or audio.
 */
public enum TrackType {
    VIDEO,
    AUDIO,
    UNKNOWN
}
