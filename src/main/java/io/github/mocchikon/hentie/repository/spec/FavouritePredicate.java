package io.github.mocchikon.hentie.repository.spec;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Favourites only, for chapters and series alike. As the {@code driver}, or without one, an {@code IN} over
 * {@code ix_<owner>__favourite}, which SQLite starts from: the favourites are usually few. Beside another
 * driver, a check of the candidate's own column, which costs nothing once its row is read.
 */
final class FavouritePredicate
{
    private FavouritePredicate()
    {
    }

    static <T> Predicate build(Class<T> owner, OperandKey driver, Root<T> root, CriteriaQuery<?> query,
                               CriteriaBuilder cb)
    {
        if (driver == null || driver.equals(OperandKey.FAVOURITE))
        {
            Subquery<Integer> favourites = query.subquery(Integer.class);
            Root<T> favourite = favourites.from(owner);
            favourites.select(favourite.get("id")).where(cb.isTrue(favourite.get("favourite")));
            return root.get("id").in(favourites);
        }
        return cb.isTrue(root.get("favourite"));
    }
}
