package io.github.mocchikon.hentie.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * {@code UNLINKED} is the default because it is the safe one: linking a chapter from another series moves
 * it out, and an emptied series is deleted.
 */
@Getter
@RequiredArgsConstructor
public enum CandidateScope
{
    UNLINKED("Chapters in no series"),
    ALL("All chapters");

    private final String displayName;
}
