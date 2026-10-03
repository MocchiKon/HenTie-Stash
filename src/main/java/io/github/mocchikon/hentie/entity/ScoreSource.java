package io.github.mocchikon.hentie.entity;

/**
 * Whether {@link Series#getScore()} is the user's override or the materialized chapter average.
 * <p>
 * ORDINAL: never reorder.
 */
public enum ScoreSource
{
    USER_SET,
    DERIVED
}
