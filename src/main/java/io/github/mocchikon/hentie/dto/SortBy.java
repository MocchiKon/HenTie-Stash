package io.github.mocchikon.hentie.dto;

/**
 * {@code DATE} sorts by {@code id}, a fast proxy for the date. A new constant needs its plain index and its
 * {@code (status, ...)} composite on both entities.
 */
public enum SortBy
{
    DATE,
    SCORE,
    PAGE_NUM,
    DISK_SIZE
}
