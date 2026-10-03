package io.github.mocchikon.hentie.repository.spec;

import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.link.MetadataLink;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.List;
import java.util.Objects;

/**
 * Shared by both specs so the chapter and series searches cannot drift apart. The table is chosen by the
 * {@link MetadataLink} projection the caller passes. {@link CompoundSearchQuery} mirrors these semantics.
 */
final class MetadataPredicates
{
    private MetadataPredicates()
    {
    }

    /** What every facet, Criteria and native alike, filters on. */
    static List<Integer> distinctIds(List<Integer> ids)
    {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    /**
     * One condition per id, all AND-ed. Without a {@code driver}, each is a non-correlated {@code IN}:
     * evaluated once from the index, where a correlated {@code EXISTS} would run per row. With one, only the
     * driver is an {@code IN} and SQLite starts from it; the others become a correlated {@code EXISTS}, one
     * index seek per candidate. An {@code IN} list is built in full before the first row, so beside a small
     * driver a broad value would cost more than the whole search (a 14-chapter artist within a tag on half
     * the library: 66 ms against 0 ms).
     */
    static void addContainsAll(List<Predicate> predicates, List<Integer> ids, MetadataType type,
                               Class<? extends MetadataLink> linkType, OperandKey driver,
                               Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb)
    {
        if (query == null)
        {
            return;
        }
        for (Integer id : distinctIds(ids))
        {
            var sub = query.subquery(Integer.class);
            Root<? extends MetadataLink> link = sub.from(linkType);
            if (driver == null || driver.equals(OperandKey.of(type, id)))
            {
                sub.select(link.get("ownerId"));
                sub.where(cb.equal(link.get("metaId"), id));
                predicates.add(root.get("id").in(sub));
            }
            else
            {
                sub.select(cb.literal(1));
                sub.where(cb.equal(link.get("ownerId"), root.get("id")), cb.equal(link.get("metaId"), id));
                predicates.add(cb.exists(sub));
            }
        }
    }

    /**
     * A correlated {@code NOT EXISTS} per facet, not the {@code IN} of {@link #addContainsAll}: an exclusion
     * keeps almost every row, so a {@code NOT IN} would first load every owner carrying the value (~375 ms
     * against ~2 ms for a page). Correlated, a {@code LIMIT}ed page can stop early.
     */
    static void addContainsNone(List<Predicate> predicates, List<Integer> ids,
                                Class<? extends MetadataLink> linkType,
                                Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb)
    {
        var values = distinctIds(ids);
        if (values.isEmpty() || query == null)
        {
            return;
        }
        var sub = query.subquery(Integer.class);
        Root<? extends MetadataLink> link = sub.from(linkType);
        sub.select(cb.literal(1));
        sub.where(cb.equal(link.get("ownerId"), root.get("id")), link.get("metaId").in(values));
        predicates.add(cb.not(cb.exists(sub)));
    }
}
