package io.github.mocchikon.hentie.repository.spec;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Favourites only, or only what carries a favourite artist, for chapters and series alike. Both are flags on the
 * owner row with an index of their own ({@code (favourite, id)}, {@code (favourite_artist, id)}). As the
 * {@code driver}, or without one, an {@code IN} over that index, which SQLite starts from: the marked rows are
 * usually few. Beside another driver, a check of the candidate's own column, which costs nothing once its row is
 * read.
 */
final class FavouritePredicate
{
    private FavouritePredicate()
    {
    }

    static <T> Predicate build(Class<T> owner, OperandKey driver, Root<T> root, CriteriaQuery<?> query,
                               CriteriaBuilder cb)
    {
        return flag(owner, "favourite", OperandKey.FAVOURITE, driver, root, query, cb);
    }

    static <T> Predicate buildArtist(Class<T> owner, OperandKey driver, Root<T> root, CriteriaQuery<?> query,
                                     CriteriaBuilder cb)
    {
        return flag(owner, "favouriteArtist", OperandKey.FAVOURITE_ARTIST, driver, root, query, cb);
    }

    private static <T> Predicate flag(Class<T> owner, String attribute, OperandKey key, OperandKey driver,
                                      Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb)
    {
        if (driver == null || driver.equals(key))
        {
            Subquery<Integer> marked = query.subquery(Integer.class);
            Root<T> row = marked.from(owner);
            marked.select(row.get("id")).where(cb.isTrue(row.get(attribute)));
            return root.get("id").in(marked);
        }
        return cb.isTrue(root.get(attribute));
    }
}
