package io.github.mocchikon.hentie.repository.spec;

import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.SearchCriteria;
import io.github.mocchikon.hentie.dto.SortBy;
import io.github.mocchikon.hentie.entity.Status;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Native count and page queries for searches whose included values are all broad. The join-table indexes
 * {@code (meta_id, owner_id)}, the title index and the status and language indexes all return owner ids in
 * order, so an {@code INTERSECT}/{@code EXCEPT} of them is a merge that reads each index entry once and
 * builds nothing, and {@code ORDER BY 1 ... OFFSET} over it skips index entries instead of rows (a tag on half
 * the library, last page: 203 ms against 10 ms). The Criteria form builds every {@code IN} list in full and
 * probes it per row.
 * <p>
 * Other sorts walk the sort index with {@code +o.id IN (...)}: SQLite prices any {@code IN (subquery)} as
 * about 25 rows and would start from the list, look up every match and sort them all (1.1 s against 0.13 s
 * for a middle page). The unary plus makes the list a filter only, so the walk follows the sort order and
 * stops after a page. There, a single status or language stays a row filter, so the {@code (status, ...)} and
 * {@code (language, ...)} indexes can be walked.
 * <p>
 * Exact only while every join-table row and title-index entry names an existing owner: deletes remove the
 * links through Hibernate and the index entries through the triggers. Kept in sync with the specs by hand;
 * {@code CompoundSearchIT} compares both.
 */
public final class CompoundSearchQuery
{
    private CompoundSearchQuery()
    {
    }

    /** {@code params} bind 1-based, in order. */
    public record Query(String sql, List<Object> params)
    {
    }

    /**
     * One set of owner ids the search ANDs in, read from one index in id order. Join tables are bags and can
     * name an owner twice, so their rows are not {@code unique}.
     */
    public record Operand(OperandKey key, String sql, List<Object> params, boolean unique)
    {
    }

    /** False for a title term under 3 characters: its {@code LIKE} fallback exists in the spec only. */
    public static boolean expressible(SearchCriteria c, String titlePhrase)
    {
        return StringUtils.isBlank(c.getTitle()) || titlePhrase != null;
    }

    public static boolean hasExclusions(SearchCriteria c)
    {
        for (MetadataType type : MetadataType.values())
        {
            if (!MetadataPredicates.distinctIds(excludedIds(c, type)).isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    public static List<Operand> operands(SearchCriteria c, String titlePhrase)
    {
        Owner owner = Owner.of(c);
        List<Operand> operands = new ArrayList<>();
        for (MetadataType type : MetadataType.values())
        {
            for (Integer id : MetadataPredicates.distinctIds(includedIds(c, type)))
            {
                operands.add(new Operand(OperandKey.of(type, id), owner.linkArm(type), List.of(id), false));
            }
        }
        if (StringUtils.isNotBlank(c.getTitle()) && titlePhrase != null)
        {
            operands.add(new Operand(OperandKey.TITLE,
                    "select rowid from " + owner.fts + " where " + owner.fts + " match ?", List.of(titlePhrase), true));
        }
        if (c.isSeries() && isNotEmpty(c.getLanguages()))
        {
            operands.add(new Operand(OperandKey.LANGUAGES,
                    "select series_id from series_effective_languages where language in ("
                            + placeholders(c.getLanguages().size()) + ")",
                    List.copyOf(c.getLanguages()), false));
        }
        return operands;
    }

    /**
     * Whether owner-row filters remain once a single status or language is streamed. With exclusions only,
     * a remaining filter would have to check every owner minus the excluded ones, so the Criteria page, which
     * stops after one page, is the better choice there.
     */
    public static boolean leavesRowFilters(SearchCriteria c, String titlePhrase)
    {
        return !new Compound(c, titlePhrase, true).filters.isEmpty();
    }

    /** Whether the search filters on the owner rows at all: status, chapter language, score, pages, dates, gallery. */
    public static boolean hasOwnerFilters(SearchCriteria c)
    {
        return !new Compound(c, null, false).filters.isEmpty();
    }

    /** How many rows the operand has, counting no further than {@code cap}: one short index read. */
    public static Query probe(Operand operand, int cap)
    {
        List<Object> params = new ArrayList<>(operand.params());
        params.add(cap);
        return new Query("select count(*) from (" + operand.sql() + " limit ?)", params);
    }

    /**
     * @param streamOwnerFilters merge a single status or language in as one more stream, when no other row
     *                           filter is left. Worth it when the other streams are broad; beside a small one,
     *                           checking its rows is cheaper than reading every chapter of that status.
     */
    public static Query count(SearchCriteria c, String titlePhrase, boolean streamOwnerFilters)
    {
        Compound compound = new Compound(c, titlePhrase, streamOwnerFilters);
        if (compound.everyOwner && compound.filters.isEmpty())
        {
            // Exclusions only: all owners minus the excluded ones. Reading only the excluded streams is faster
            // than merging them against every owner id (30 ms against 122 ms on 1.5M chapters).
            String excluded = compound.excepted.size() == 1
                    ? compound.excepted.getFirst().replaceFirst("^select ", "select distinct ")
                    : String.join(" union ", compound.excepted);
            return new Query("select (select count(*) from " + compound.owner.table + ") - (select count(*) from ("
                    + excluded + "))", compound.params(List.of()));
        }
        if (compound.filters.isEmpty())
        {
            return new Query("select count(*) from (" + compound.rows() + ")", compound.params(List.of()));
        }
        return new Query("select count(*) from " + compound.owner.table + " o where o.id in (" + compound.sql()
                + ")" + compound.filterSql(), compound.params(List.of()));
    }

    /** The ids of one page, in page order. */
    public static Query pageIds(SearchCriteria c, String titlePhrase, SortBy sortBy, Sort.Direction direction,
                                long offset, int limit)
    {
        boolean byDate = sortBy == SortBy.DATE;
        Compound compound = new Compound(c, titlePhrase, byDate);
        String dir = direction == Sort.Direction.ASC ? "asc" : "desc";
        List<Object> page = List.of(limit, offset);
        String table = compound.owner.table;
        if (byDate && compound.filters.isEmpty())
        {
            return new Query(compound.rows() + " order by 1 " + dir + " limit ? offset ?", compound.params(page));
        }
        if (byDate)
        {
            return new Query("select o.id from " + table + " o where o.id in (" + compound.sql() + ")"
                    + compound.filterSql() + " order by o.id " + dir + " limit ? offset ?", compound.params(page));
        }
        return new Query("select o.id from " + table + " o where +o.id in (" + compound.sql() + ")"
                + compound.filterSql() + " order by " + sortColumns(sortBy, dir) + " limit ? offset ?",
                compound.params(page));
    }

    /** As the Criteria sort: the stat, then id, both in the chosen direction, scores missing last. */
    private static String sortColumns(SortBy sortBy, String dir)
    {
        return switch (sortBy)
        {
            case SCORE -> "o.score " + dir + ("asc".equals(dir) ? " nulls last" : "") + ", o.id " + dir;
            case PAGE_NUM -> "o.page_num " + dir + ", o.id " + dir;
            case DISK_SIZE -> "o.disk_size " + dir + ", o.id " + dir;
            case DATE -> "o.id " + dir;
        };
    }

    /**
     * The intersected streams, the excepted ones and the owner-row filters left over. Params are kept per
     * part, so they bind in the order the SQL names them.
     */
    private static final class Compound
    {
        final Owner owner;
        final List<String> streams = new ArrayList<>();
        final List<Object> streamParams = new ArrayList<>();
        final List<String> excepted = new ArrayList<>();
        final List<Object> exceptedParams = new ArrayList<>();
        final List<String> filters = new ArrayList<>();
        final List<Object> filterParams = new ArrayList<>();
        boolean singleStreamUnique = true;
        /** Nothing is included, so the streams are every owner. */
        boolean everyOwner;

        Compound(SearchCriteria c, String titlePhrase, boolean streamOwnerFilters)
        {
            owner = Owner.of(c);
            for (Operand operand : operands(c, titlePhrase))
            {
                streams.add(operand.sql());
                streamParams.addAll(operand.params());
                singleStreamUnique = operand.unique();
            }

            List<Status> statuses = isNotEmpty(c.getStatuses()) ? c.getStatuses() : List.of();
            List<String> languages = !c.isSeries() && isNotEmpty(c.getLanguages()) ? c.getLanguages() : List.of();
            List<String> rowFilters = new ArrayList<>();
            List<Object> rowFilterParams = new ArrayList<>();
            addOwnerRowFilters(c, rowFilters, rowFilterParams);

            // Streamed only when that leaves no filter at all. A filter left over needs the whole merge built
            // before the first row, and a status stream would only make it bigger (tag + NEW + a date range,
            // first page: 69 ms against 117 ms).
            if (streamOwnerFilters && statuses.size() <= 1 && languages.size() <= 1 && rowFilters.isEmpty())
            {
                if (!statuses.isEmpty())
                {
                    addUniqueStream("select id from " + owner.table + " where status = ?", statuses.getFirst().ordinal());
                }
                if (!languages.isEmpty())
                {
                    addUniqueStream("select id from chapter where language = ?", languages.getFirst());
                }
            }
            else
            {
                if (!statuses.isEmpty())
                {
                    filters.add("o.status in (" + placeholders(statuses.size()) + ")");
                    statuses.forEach(status -> filterParams.add(status.ordinal()));
                }
                if (!languages.isEmpty())
                {
                    filters.add("o.language in (" + placeholders(languages.size()) + ")");
                    filterParams.addAll(languages);
                }
            }
            filters.addAll(rowFilters);
            filterParams.addAll(rowFilterParams);

            if (streams.isEmpty())
            {
                // Exclusions only: subtract from every owner, read from the narrow ix_<owner>__id.
                streams.add("select id from " + owner.table);
                everyOwner = true;
            }

            for (MetadataType type : MetadataType.values())
            {
                // One stream per value: "in (?, ?)" would come out as two runs the merge must sort first.
                for (Integer id : MetadataPredicates.distinctIds(excludedIds(c, type)))
                {
                    excepted.add(owner.linkArm(type));
                    exceptedParams.add(id);
                }
            }
        }

        private void addUniqueStream(String sql, Object param)
        {
            streams.add(sql);
            streamParams.add(param);
            singleStreamUnique = true;
        }

        /** The filters no index stream can stand in for. */
        private void addOwnerRowFilters(SearchCriteria c, List<String> rowFilters, List<Object> params)
        {
            Integer minScore = c.getMinScore();
            if (minScore != null && minScore > 0)
            {
                rowFilters.add(c.isSeries() ? "o.score is not null and o.score >= ?" : "o.score >= ?");
                params.add(Math.clamp(minScore, 1, 10));
            }
            if (c.getMinPages() != null)
            {
                rowFilters.add("o.page_num >= ?");
                params.add(c.getMinPages());
            }
            if (c.getMaxPages() != null)
            {
                rowFilters.add("o.page_num <= ?");
                params.add(c.getMaxPages());
            }
            if (c.getUploadFrom() != null)
            {
                rowFilters.add("o." + owner.dateColumn + " >= ?");
                params.add(c.getUploadFrom());
            }
            if (c.getUploadTo() != null)
            {
                rowFilters.add("o." + owner.dateColumn + " <= ?");
                params.add(c.getUploadTo());
            }
            if (!c.isSeries() && StringUtils.isNotBlank(c.getGalleryId()))
            {
                rowFilters.add("o.gallery_id = ?");
                params.add(c.getGalleryId().trim());
            }
        }

        /** Compound operators share one precedence and associate left: (A ∩ B ∩ …) − E1 − E2 … */
        String sql()
        {
            String sql = String.join(" intersect ", streams);
            return excepted.isEmpty() ? sql : sql + " except " + String.join(" except ", excepted);
        }

        /** As {@link #sql()}, each owner once: a lone join-table stream has no compound operator to dedupe it. */
        String rows()
        {
            boolean lone = streams.size() == 1 && excepted.isEmpty();
            return lone && !singleStreamUnique ? sql().replaceFirst("^select ", "select distinct ") : sql();
        }

        String filterSql()
        {
            return filters.isEmpty() ? "" : " and " + String.join(" and ", filters);
        }

        List<Object> params(List<Object> trailing)
        {
            List<Object> params = new ArrayList<>(streamParams);
            params.addAll(exceptedParams);
            params.addAll(filterParams);
            params.addAll(trailing);
            return params;
        }
    }

    /** What differs between the two searchable owners. */
    private record Owner(boolean series, String table, String ownerColumn, String fts, String dateColumn)
    {
        static Owner of(SearchCriteria c)
        {
            return c.isSeries()
                    ? new Owner(true, "series", "series_id", "series_fts", "created_date")
                    : new Owner(false, "chapter", "chapter_id", "chapter_fts", "upload_date");
        }

        String linkArm(MetadataType type)
        {
            String table = series ? type.getSeriesEffectiveJoinTable() : type.getChapterJoinTable();
            return "select " + ownerColumn + " from " + table + " where " + type.getFkColumn() + " = ?";
        }
    }

    private static List<Integer> includedIds(SearchCriteria c, MetadataType type)
    {
        return switch (type)
        {
            case TAG -> c.getTagIds();
            case ARTIST -> c.getArtistIds();
            case CHARACTER -> c.getCharacterIds();
            case PARODY -> c.getParodyIds();
            case GROUP -> c.getGroupIds();
            case CATEGORY -> c.getCategoryIds();
        };
    }

    private static List<Integer> excludedIds(SearchCriteria c, MetadataType type)
    {
        return switch (type)
        {
            case TAG -> c.getExcludedTagIds();
            case ARTIST -> c.getExcludedArtistIds();
            case CHARACTER -> c.getExcludedCharacterIds();
            case PARODY -> c.getExcludedParodyIds();
            case GROUP -> c.getExcludedGroupIds();
            case CATEGORY -> c.getExcludedCategoryIds();
        };
    }

    private static boolean isNotEmpty(List<?> list)
    {
        return list != null && !list.isEmpty();
    }

    private static String placeholders(int n)
    {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
