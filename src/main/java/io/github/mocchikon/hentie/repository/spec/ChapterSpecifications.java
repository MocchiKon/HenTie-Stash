package io.github.mocchikon.hentie.repository.spec;

import io.github.mocchikon.hentie.dto.CandidateScope;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.link.*;
import jakarta.persistence.criteria.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

import static io.github.mocchikon.hentie.repository.spec.MetadataPredicates.addContainsAll;
import static io.github.mocchikon.hentie.repository.spec.MetadataPredicates.addContainsNone;

/**
 * No facet joins the root in a row-multiplying way, so no {@code DISTINCT} is needed; setting it would
 * force an expensive {@code count(distinct id)} on the pagination count.
 */
public final class ChapterSpecifications
{
    private ChapterSpecifications()
    {
    }

    /**
     * Meant for a capped fetch's own predicate, not a filter afterwards, so the cap is not spent on chapters
     * that would be dropped, and "in no series" can seek {@code ix_chapter__series_match_key}.
     */
    public static Specification<Chapter> linkableTo(int seriesId, CandidateScope scope)
    {
        return (root, query, cb) -> scope == CandidateScope.UNLINKED
                ? cb.isNull(root.get("series"))
                : cb.or(cb.isNull(root.get("series")), cb.notEqual(root.get("series").get("id"), seriesId));
    }

    /** Title search uses a {@code LIKE} scan; see {@link #from(SearchCriteria, String)}. */
    public static Specification<Chapter> from(SearchCriteria c)
    {
        return from(c, null);
    }

    /**
     * @param titlePhrase the FTS5 phrase from {@code TitleSearchIndex.phraseFor}, or {@code null} for a
     *                    {@code LIKE} scan (the index cannot answer a term under 3 characters)
     */
    public static Specification<Chapter> from(SearchCriteria c, String titlePhrase)
    {
        return from(c, titlePhrase, null);
    }

    /**
     * @param driver the included value the query starts from, every other one being checked per candidate
     *               (see {@code MetadataPredicates.addContainsAll}); {@code null} starts from none in
     *               particular
     */
    public static Specification<Chapter> from(SearchCriteria c, String titlePhrase, OperandKey driver)
    {
        return (root, query, cb) ->
        {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.isNotBlank(c.getTitle()))
            {
                predicates.add(TitlePredicate.build(c.getTitle(), titlePhrase, ChapterTitleMatch.class, driver,
                        root, query, cb));
            }

            addContainsAll(predicates, c.getTagIds(), MetadataType.TAG, ChapterTagLink.class, driver, root, query, cb);
            addContainsAll(predicates, c.getArtistIds(), MetadataType.ARTIST, ChapterArtistLink.class, driver, root, query, cb);
            addContainsAll(predicates, c.getCharacterIds(), MetadataType.CHARACTER, ChapterCharacterLink.class, driver, root, query, cb);
            addContainsAll(predicates, c.getParodyIds(), MetadataType.PARODY, ChapterParodyLink.class, driver, root, query, cb);
            addContainsAll(predicates, c.getGroupIds(), MetadataType.GROUP, ChapterGroupLink.class, driver, root, query, cb);
            addContainsAll(predicates, c.getCategoryIds(), MetadataType.CATEGORY, ChapterCategoryLink.class, driver, root, query, cb);

            addContainsNone(predicates, c.getExcludedTagIds(), ChapterTagLink.class, root, query, cb);
            addContainsNone(predicates, c.getExcludedArtistIds(), ChapterArtistLink.class, root, query, cb);
            addContainsNone(predicates, c.getExcludedCharacterIds(), ChapterCharacterLink.class, root, query, cb);
            addContainsNone(predicates, c.getExcludedParodyIds(), ChapterParodyLink.class, root, query, cb);
            addContainsNone(predicates, c.getExcludedGroupIds(), ChapterGroupLink.class, root, query, cb);
            addContainsNone(predicates, c.getExcludedCategoryIds(), ChapterCategoryLink.class, root, query, cb);

            if (c.getLanguages() != null && !c.getLanguages().isEmpty())
            {
                predicates.add(root.get("language").in(c.getLanguages()));
            }
            if (c.getStatuses() != null && !c.getStatuses().isEmpty())
            {
                predicates.add(root.get("status").in(c.getStatuses()));
            }

            Integer minScore = c.getMinScore();
            if (minScore != null && minScore > 0)
            {
                predicates.add(cb.greaterThanOrEqualTo(root.get("score"), (short) Math.clamp(minScore, 1, 10)));
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
                predicates.add(cb.greaterThanOrEqualTo(root.get("uploadDate"), c.getUploadFrom()));
            }
            if (c.getUploadTo() != null)
            {
                predicates.add(cb.lessThanOrEqualTo(root.get("uploadDate"), c.getUploadTo()));
            }

            if (StringUtils.isNotBlank(c.getGalleryId()))
            {
                predicates.add(cb.equal(root.get("galleryId"), c.getGalleryId().trim()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
