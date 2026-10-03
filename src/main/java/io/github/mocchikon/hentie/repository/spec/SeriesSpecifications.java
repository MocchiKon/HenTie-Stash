package io.github.mocchikon.hentie.repository.spec;

import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.entity.link.*;
import jakarta.persistence.criteria.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

import static io.github.mocchikon.hentie.repository.spec.MetadataPredicates.addContainsAll;
import static io.github.mocchikon.hentie.repository.spec.MetadataPredicates.addContainsNone;

/**
 * Reads only the series' materialized effective metadata and columns, so there is no join out to chapters
 * and search costs the same as chapter search. No {@code DISTINCT}, as in {@link ChapterSpecifications}.
 */
public final class SeriesSpecifications
{
    private SeriesSpecifications()
    {
    }

    /** Title search uses a {@code LIKE} scan; see {@link #from(SearchCriteria, String)}. */
    public static Specification<Series> from(SearchCriteria c)
    {
        return from(c, null);
    }

    /**
     * @param titlePhrase the FTS5 phrase from {@code TitleSearchIndex.phraseFor}, or {@code null} for a
     *                    {@code LIKE} scan (the index cannot answer a term under 3 characters)
     */
    public static Specification<Series> from(SearchCriteria c, String titlePhrase)
    {
        return from(c, titlePhrase, null);
    }

    /**
     * @param driver the included value the query starts from, every other one being checked per candidate
     *               (see {@code MetadataPredicates.addContainsAll}); {@code null} starts from none in
     *               particular
     */
    public static Specification<Series> from(SearchCriteria c, String titlePhrase, OperandKey driver)
    {
        return (root, query, cb) ->
        {
            if (query == null)
            {
                return cb.conjunction();
            }
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.isNotBlank(c.getTitle()))
            {
                predicates.add(TitlePredicate.build(c.getTitle(), titlePhrase, SeriesTitleMatch.class, driver,
                        root, query, cb));
            }

            addContainsAll(predicates, c.getTagIds(), MetadataType.TAG, SeriesTagLink.class, driver, root, query, cb);
            addContainsAll(predicates, c.getArtistIds(), MetadataType.ARTIST, SeriesArtistLink.class, driver, root, query, cb);
            addContainsAll(predicates, c.getCharacterIds(), MetadataType.CHARACTER, SeriesCharacterLink.class, driver, root, query, cb);
            addContainsAll(predicates, c.getParodyIds(), MetadataType.PARODY, SeriesParodyLink.class, driver, root, query, cb);
            addContainsAll(predicates, c.getGroupIds(), MetadataType.GROUP, SeriesGroupLink.class, driver, root, query, cb);
            addContainsAll(predicates, c.getCategoryIds(), MetadataType.CATEGORY, SeriesCategoryLink.class, driver, root, query, cb);

            addContainsNone(predicates, c.getExcludedTagIds(), SeriesTagLink.class, root, query, cb);
            addContainsNone(predicates, c.getExcludedArtistIds(), SeriesArtistLink.class, root, query, cb);
            addContainsNone(predicates, c.getExcludedCharacterIds(), SeriesCharacterLink.class, root, query, cb);
            addContainsNone(predicates, c.getExcludedParodyIds(), SeriesParodyLink.class, root, query, cb);
            addContainsNone(predicates, c.getExcludedGroupIds(), SeriesGroupLink.class, root, query, cb);
            addContainsNone(predicates, c.getExcludedCategoryIds(), SeriesCategoryLink.class, root, query, cb);

            if (c.getLanguages() != null && !c.getLanguages().isEmpty())
            {
                predicates.add(languagePredicate(c.getLanguages(), driver, root, query, cb));
            }

            if (c.getStatuses() != null && !c.getStatuses().isEmpty())
            {
                predicates.add(root.get("status").in(c.getStatuses()));
            }

            Integer minScore = c.getMinScore();
            if (minScore != null && minScore > 0)
            {
                final short minScoreDb = (short) Math.clamp(minScore, 1, 10);
                // `score` holds the effective value, so no aggregate over chapters is needed.
                predicates.add(cb.and(cb.isNotNull(root.get("score")),
                        cb.greaterThanOrEqualTo(root.get("score"), minScoreDb)));
            }

            if (c.getMinPages() != null)
            {
                predicates.add(cb.greaterThanOrEqualTo(root.get("pageNum"), c.getMinPages()));
            }
            if (c.getMaxPages() != null)
            {
                predicates.add(cb.lessThanOrEqualTo(root.get("pageNum"), c.getMaxPages()));
            }

            if (c.getUploadFrom() != null)
            {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdDate"), c.getUploadFrom()));
            }
            if (c.getUploadTo() != null)
            {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdDate"), c.getUploadTo()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Without a {@code driver} the shape depends on the query being built (the count selects {@code Long}). A
     * language matches many series, so the {@code LIMIT}ed content page uses a correlated {@code EXISTS} that
     * can stop after a page, while the count, which cannot stop early, uses a non-correlated {@code IN}
     * (~205 ms against ~630 ms). Do not collapse them into one shape. With a driver, both queries take the
     * driver's shape: an {@code IN} when the languages are the driver, else an {@code EXISTS} per candidate.
     */
    private static Predicate languagePredicate(List<String> languages, OperandKey driver, Root<Series> root,
                                               CriteriaQuery<?> query, CriteriaBuilder cb)
    {
        boolean nonCorrelated = driver == null ? query.getResultType() == Long.class : driver.equals(OperandKey.LANGUAGES);
        if (nonCorrelated)
        {
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root<Series> subRoot = sub.from(Series.class);
            sub.select(subRoot.get("id"));
            sub.where(subRoot.join("effectiveLanguages").in(languages));
            return root.get("id").in(sub);
        }
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<Series> correlated = sub.correlate(root);
        Join<Series, String> lang = correlated.join("effectiveLanguages");
        sub.select(cb.literal(1));
        sub.where(lang.in(languages));
        return cb.exists(sub);
    }
}
