package io.github.mocchikon.hentie.service.match;

import io.github.mocchikon.hentie.repository.SeriesRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The block seek of both finders: rows whose spaceless key sorts near a title's, walked outward from where the
 * title sorts in an index on {@value #CONDENSED}. A crowded block (every title starting "Isekai") so yields the
 * titles nearest this one, not the oldest, and the two directions of matching find the same rows. Four seeks, with
 * the head being the first {@value MatchScore#MIN_SHARED_HEAD} letters:
 * <ol>
 *   <li>rows starting with the head;</li>
 *   <li>rows whose whole key is a shorter start of the head, by equality, since in key order they sit below every
 *       row extending them;</li>
 *   <li>the nearest ones below the head, and</li>
 *   <li>the nearest ones above it - where a title misspelled past the block sorts.</li>
 * </ol>
 * The caller runs each as native SQL pinned to its expression index, adding its own filter.
 */
final class NearestTitles
{
    /**
     * Spelled exactly as {@code ix_chapter__condensed_match_key} and {@code ix_series__condensed_match_key}
     * declare it: SQLite uses an expression index only for that exact expression. Native SQL, because Criteria may
     * bind the literals as parameters.
     */
    static final String CONDENSED = "replace(match_key, ' ', '')";

    private static final String IN_RANGE = CONDENSED + " >= ? and " + CONDENSED + " < ?";

    private static final String ASCENDING = CONDENSED + ", id";

    private static final String DESCENDING = CONDENSED + " desc, id desc";

    record Seek(String condition, List<Object> values, String order)
    {
    }

    private NearestTitles()
    {
    }

    static List<Seek> seeks(TitleKey key)
    {
        String block = key.getMatchBlock();
        String title = key.getMatchKey().replace(" ", "");
        if (block.length() < TitleKey.BLOCK_LENGTH)
        {
            return List.of(new Seek(CONDENSED + " = ?", List.of(title), ASCENDING));
        }
        String head = head(title);
        String pastHead = head + SeriesRepository.MATCH_KEY_RANGE_END;
        var starts = new ArrayList<Object>();
        for (int length = block.length(); length < head.length(); length++)
        {
            starts.add(head.substring(0, length));
        }

        var seeks = new ArrayList<Seek>();
        seeks.add(new Seek(IN_RANGE, List.of(head, pastHead), ASCENDING));
        if (!starts.isEmpty())
        {
            String anyOf = CONDENSED + " in (" + String.join(", ", Collections.nCopies(starts.size(), "?")) + ")";
            seeks.add(new Seek(anyOf, starts, "length(" + CONDENSED + ") desc, id"));
        }
        seeks.add(new Seek(IN_RANGE, List.of(block, head), DESCENDING));
        seeks.add(new Seek(IN_RANGE, List.of(pastHead, block + SeriesRepository.MATCH_KEY_RANGE_END), ASCENDING));
        return seeks;
    }

    /** Never splits a surrogate pair: half of one would bind as text no index entry holds. */
    private static String head(String title)
    {
        int end = Math.min(title.length(), MatchScore.MIN_SHARED_HEAD);
        return end > 0 && Character.isHighSurrogate(title.charAt(end - 1)) ? title.substring(0, end - 1)
                : title.substring(0, end);
    }
}
