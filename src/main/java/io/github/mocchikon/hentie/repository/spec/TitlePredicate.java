package io.github.mocchikon.hentie.repository.spec;

import io.github.mocchikon.hentie.entity.link.TitleMatch;
import jakarta.persistence.criteria.*;

import java.util.*;

/**
 * Matches {@code titleFull} and {@code nativeTitle} only: the pretty title falls back to {@code titleFull},
 * so searching it would add a column without finding more.
 * <p>
 * SQLite {@code LIKE}/{@code lower()} fold ASCII only, while the FTS5 trigram index folds Unicode case, so
 * the index answers whenever it can. The {@code LIKE} scan is only for terms too short for a trigram, and
 * recovers case folding by matching every {@link #caseVariants case variant}.
 * <p>
 * Both branches treat the term literally, so {@code 50% off} is found by typing it.
 */
final class TitlePredicate
{
    /** SQLite has no default {@code LIKE} escape character. */
    private static final char ESCAPE = '\\';

    private TitlePredicate()
    {
    }

    /**
     * With another value as the {@code driver}, the index match becomes a correlated {@code EXISTS}: an
     * {@code IN} would build the full match list first, every chapter for a common term (a 14-chapter artist
     * with a term in every title: 198 ms against 3 ms).
     */
    static Predicate build(String term, String ftsPhrase, Class<? extends TitleMatch> matchType, OperandKey driver,
                           Path<?> root, CriteriaQuery<?> query, CriteriaBuilder cb)
    {
        if (ftsPhrase != null)
        {
            if (driver == null || driver.equals(OperandKey.TITLE))
            {
                return root.get("id").in(matchingIds(ftsPhrase, matchType, query, cb));
            }
            Subquery<Integer> hit = query.subquery(Integer.class);
            var match = hit.from(matchType);
            hit.select(cb.literal(1))
                    .where(cb.equal(match.get("ownerId"), root.get("id")), cb.equal(match.get("matchQuery"), ftsPhrase));
            return cb.exists(hit);
        }
        List<Predicate> anyVariant = new ArrayList<>();
        for (String variant : caseVariants(term.trim()))
        {
            var like = "%" + escapeWildcards(variant) + "%";
            anyVariant.add(cb.like(cb.lower(root.get("titleFull")), like, ESCAPE));
            anyVariant.add(cb.like(cb.lower(root.get("nativeTitle")), like, ESCAPE));
        }
        return cb.or(anyVariant.toArray(Predicate[]::new));
    }

    /** An FTS5 {@code MATCH} in JPA form; see {@link TitleMatch}. */
    private static Subquery<Integer> matchingIds(String ftsPhrase, Class<? extends TitleMatch> matchType,
                                                 CriteriaQuery<?> query, CriteriaBuilder cb)
    {
        Subquery<Integer> hits = query.subquery(Integer.class);
        var match = hits.from(matchType);
        return hits.select(match.get("ownerId"))
                .where(cb.equal(match.get("matchQuery"), ftsPhrase));
    }

    /**
     * ASCII characters give one form ({@code lower()} folds them), non-ASCII ones both, so {@code "Ä"}
     * yields {@code [ä, Ä]}. At most 4 variants, since this path only sees 1-2 character terms.
     */
    private static List<String> caseVariants(String term)
    {
        List<String> variants = new ArrayList<>(List.of(""));
        for (int i = 0; i < term.length(); )
        {
            int codePoint = term.codePointAt(i);
            i += Character.charCount(codePoint);

            String character = new String(Character.toChars(codePoint));
            String lower = character.toLowerCase(Locale.ROOT);
            String upper = character.toUpperCase(Locale.ROOT);
            Set<String> forms = codePoint < 128 || lower.equals(upper)
                    ? Set.of(lower)
                    : new LinkedHashSet<>(List.of(lower, upper));

            List<String> extended = new ArrayList<>(variants.size() * forms.size());
            for (String prefix : variants)
            {
                for (String form : forms)
                {
                    extended.add(prefix + form);
                }
            }
            variants = extended;
        }
        return variants;
    }

    /** The escape character goes first, or it would escape the escapes added after it. */
    private static String escapeWildcards(String term)
    {
        return term.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
