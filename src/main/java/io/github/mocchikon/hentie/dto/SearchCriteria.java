package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.Status;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Everything is AND-ed: a result carries every included value, of every facet, and no excluded one. */
@Data
public class SearchCriteria
{
    private SearchType type = SearchType.CHAPTER;

    /** Matched against titleFull and nativeTitle, never the display-only pretty title. */
    private String title;

    private List<Integer> tagIds = new ArrayList<>();
    private List<Integer> artistIds = new ArrayList<>();
    private List<Integer> characterIds = new ArrayList<>();
    private List<Integer> parodyIds = new ArrayList<>();
    private List<Integer> groupIds = new ArrayList<>();
    private List<Integer> categoryIds = new ArrayList<>();

    /** Separate lists, not a signed id, so a link that knows nothing about exclusion keeps meaning "include". */
    private List<Integer> excludedTagIds = new ArrayList<>();
    private List<Integer> excludedArtistIds = new ArrayList<>();
    private List<Integer> excludedCharacterIds = new ArrayList<>();
    private List<Integer> excludedParodyIds = new ArrayList<>();
    private List<Integer> excludedGroupIds = new ArrayList<>();
    private List<Integer> excludedCategoryIds = new ArrayList<>();

    private List<String> languages = new ArrayList<>();
    private List<Status> statuses = new ArrayList<>();

    /** 1-10; the half-star UI shows it as 0.5-5. */
    private Integer minScore;

    /** Inclusive; a null bound is open-ended. */
    private Integer minPages;
    private Integer maxPages;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate uploadFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate uploadTo;

    /** Chapter search only; the series spec ignores it. */
    private String galleryId;

    private int page = 0;
    /** 0 = the page size from Settings. */
    private int size = 0;

    private SortBy sortBy = SortBy.DATE;
    private Sort.Direction sortDir = Sort.Direction.DESC;

    public boolean isSeries()
    {
        return type == SearchType.SERIES;
    }

    /** The search count cache key: filtering fields only, since paging and sort do not change the total. */
    public String filterKey()
    {
        return new StringBuilder()
                .append(type)
                .append("|title=").append(StringUtils.trimToEmpty(title).toLowerCase())
                .append("|tags=").append(tagIds)
                .append("|artists=").append(artistIds)
                .append("|characters=").append(characterIds)
                .append("|parodies=").append(parodyIds)
                .append("|groups=").append(groupIds)
                .append("|categories=").append(categoryIds)
                .append("|-tags=").append(excludedTagIds)
                .append("|-artists=").append(excludedArtistIds)
                .append("|-characters=").append(excludedCharacterIds)
                .append("|-parodies=").append(excludedParodyIds)
                .append("|-groups=").append(excludedGroupIds)
                .append("|-categories=").append(excludedCategoryIds)
                .append("|languages=").append(languages)
                .append("|statuses=").append(statuses)
                .append("|minScore=").append(minScore)
                .append("|minPages=").append(minPages)
                .append("|maxPages=").append(maxPages)
                .append("|from=").append(uploadFrom)
                .append("|to=").append(uploadTo)
                .append("|gallery=").append(StringUtils.trimToEmpty(galleryId))
                .toString();
    }

    /**
     * Without a filter the search is the whole library, so "Delete all matching" is refused. A gallery id on a
     * series search does not count: the form only hides that field, so an earlier value may still arrive.
     * <p>
     * Only valid after {@link #normalize()}, which drops values that look like filters but match every row.
     */
    public boolean hasFilter()
    {
        return StringUtils.isNotBlank(title) || (!isSeries() && StringUtils.isNotBlank(galleryId))
                || notEmpty(tagIds) || notEmpty(artistIds) || notEmpty(characterIds) || notEmpty(parodyIds)
                || notEmpty(groupIds) || notEmpty(categoryIds) || notEmpty(excludedTagIds)
                || notEmpty(excludedArtistIds) || notEmpty(excludedCharacterIds) || notEmpty(excludedParodyIds)
                || notEmpty(excludedGroupIds) || notEmpty(excludedCategoryIds)
                || notEmpty(languages) || notEmpty(statuses)
                || minScore != null || minPages != null || maxPages != null || uploadFrom != null || uploadTo != null;
    }

    private static boolean notEmpty(List<?> values)
    {
        return values != null && !values.isEmpty();
    }

    /**
     * Gives the specs, {@link #filterKey()}, the native count and {@link #hasFilter()} one shape to read, so
     * they cannot disagree about whether a value filters. Must run before anything reads the criteria.
     * <ul>
     *   <li>Drops values that match every row (null ids, blank languages, {@code minScore}/{@code minPages}
     *       below 1, every status at once). Kept, they would pass for a filter and split one count across
     *       two cache keys.</li>
     *   <li>An id both included and excluded stays included only: that is what an id means to a link that
     *       knows nothing about exclusion, and the form could not render it as two chips.</li>
     * </ul>
     */
    public void normalize()
    {
        tagIds = withoutNulls(tagIds);
        artistIds = withoutNulls(artistIds);
        characterIds = withoutNulls(characterIds);
        parodyIds = withoutNulls(parodyIds);
        groupIds = withoutNulls(groupIds);
        categoryIds = withoutNulls(categoryIds);
        excludedTagIds = without(withoutNulls(excludedTagIds), tagIds);
        excludedArtistIds = without(withoutNulls(excludedArtistIds), artistIds);
        excludedCharacterIds = without(withoutNulls(excludedCharacterIds), characterIds);
        excludedParodyIds = without(withoutNulls(excludedParodyIds), parodyIds);
        excludedGroupIds = without(withoutNulls(excludedGroupIds), groupIds);
        excludedCategoryIds = without(withoutNulls(excludedCategoryIds), categoryIds);

        if (languages != null)
        {
            languages = new ArrayList<>(languages.stream().filter(StringUtils::isNotBlank).toList());
        }
        statuses = withoutNulls(statuses);
        if (statuses != null && statuses.containsAll(EnumSet.allOf(Status.class)))
        {
            statuses = new ArrayList<>();
        }
        if (minScore != null && minScore < 1)
        {
            minScore = null;
        }
        if (minPages != null && minPages < 1)
        {
            minPages = null;
        }
    }

    private static <T> List<T> withoutNulls(List<T> values)
    {
        return values == null ? null : new ArrayList<>(values.stream().filter(Objects::nonNull).toList());
    }

    private static List<Integer> without(List<Integer> ids, List<Integer> removed)
    {
        if (ids == null || removed == null || removed.isEmpty())
        {
            return ids;
        }
        var kept = new ArrayList<>(ids);
        kept.removeIf(removed::contains);
        return kept;
    }
}
