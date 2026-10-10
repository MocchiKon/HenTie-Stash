package io.github.mocchikon.hentie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots with {@code ddl-auto=validate}, so a new field without its migration fails here. Also pins what
 * Hibernate cannot validate: the FTS5 tables, their triggers, the join tables' foreign keys and the
 * search/matching indexes.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class SchemaMigrationIT
{
    @Autowired JdbcTemplate jdbc;

    @Test
    void shouldMatchTheEntityMappingsWhenSchemaComesFromMigrations() // TODO After releasing change assert to contains
    {
        // Starting the context is the real check; this assertion keeps the test from passing vacuously.
        assertThat(appliedMigrations()).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void shouldCreateTheFtsIndexAndItsTriggersWhenMigrating()
    {
        // Virtual tables are not mapped, so nothing else checks that title search has its index.
        assertThat(objectNames("type = 'table' AND sql LIKE 'CREATE VIRTUAL TABLE%'"))
                .containsExactlyInAnyOrder("chapter_fts", "series_fts");
        assertThat(objectNames("type = 'trigger'")).containsExactlyInAnyOrder(
                "chapter_fts_ai", "chapter_fts_ad", "chapter_fts_au",
                "series_fts_ai", "series_fts_ad", "series_fts_au");
    }

    @Test
    void shouldCreateTheSearchIndexesWhenMigrating()
    {
        // A dropped index would only show up as slowness.
        assertThat(objectNames("type = 'index'")).contains(
                "ix_chapter__status_score", "ix_chapter__status_page_num", "ix_chapter__status_disk_size",
                "ix_series__status_score", "ix_series__status_page_num", "ix_series__status_disk_size",
                "ix_chapter__score_id_status", "ix_chapter__page_num_id_status", "ix_chapter__disk_size_id_status",
                "ix_series__score_id_status", "ix_series__page_num_id_status", "ix_series__disk_size_id_status",
                "ix_chapter__language", "ix_chapter__language_score", "ix_chapter__language_page_num",
                "ix_chapter__language_disk_size", "ix_chapter__id", "ix_series__id",
                "ix_series_eff_languages__language_series",
                "ix_chapter_tags__tag_chapter", "ix_chapter_tags__chapter_tag",
                "ix_series_eff_tags__tag_series", "ix_series_eff_tags__series_tag",
                "idx_series_created_date");
        // Superseded by the (<sort>, id, status) indexes, which serve every query they did.
        assertThat(objectNames("type = 'index'")).doesNotContain(
                "idx_chapter_score", "idx_chapter_page_num", "idx_chapter_disk_size",
                "idx_series_score", "idx_series_page_num", "idx_series_disk_size", "idx_series_eff_lang");
        // No query can use the first; ix_chapter__series_match_key starts with the second's column.
        assertThat(objectNames("type = 'index'")).doesNotContain("idx_chapter_num", "idx_chapter_series");
    }

    @Test
    void shouldCreateTheMatchingIndexesWhenMigrating()
    {
        // Without these, matching scans both tables once per chapter - invisible until the library is large.
        assertThat(objectNames("type = 'index'")).contains(
                "ix_chapter__match_key", "ix_chapter__series_match_key", "ix_chapter__native_match_key",
                "ix_series__match_key");
        // Both tables are walked by nearest title through the spaceless-key expression index, not a block column.
        assertThat(objectNames("type = 'index'")).doesNotContain("ix_chapter__match_block", "ix_series__match_block");
    }

    /**
     * SQLite uses an expression index only for the expression spelled as declared, so the expression is
     * asserted, not only the name.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ix_chapter__condensed_match_key", "ix_series__condensed_match_key"})
    void shouldCreateTheCondensedMatchKeyIndexesWhenMigrating(String index)
    {
        // GIVEN + WHEN
        List<String> definitions = jdbc.queryForList(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?", String.class, index);

        // THEN
        assertThat(definitions).singleElement()
                .satisfies(sql -> assertThat(sql).contains("replace(match_key, ' ', '')"));
    }

    @Test
    void shouldCreateTheMetadataRuleIndexesWhenMigrating()
    {
        // Recording a rule upserts on the unique one; the other two serve every read of rules.
        assertThat(objectNames("type = 'index'")).contains(
                "ux_metadata_rule__type_source", "ix_metadata_rule__type_id", "ix_metadata_rule__type_target");
    }

    /**
     * The queue's {@code compression_mode} has a default, so without it every row still inserts and every
     * download silently runs uncompressed.
     */
    @Test
    void shouldCreateTheImageCompressionSchemaWhenMigrating()
    {
        // GIVEN + WHEN + THEN
        assertThat(objectNames("type = 'table'")).contains("image_compression_mode");
        assertThat(columnNames("download_queue")).contains("compression_mode");
        assertThat(columnNames("image_compression_mode")).contains(
                "name", "encoder", "encoder_args", "magick_args",
                "min_relative_reduction", "min_absolute_reduction", "formats");
    }

    /**
     * A missing column would silently download every duplicate; a missing index would make each checked
     * download scan the chapter table.
     */
    @Test
    void shouldCreateTheDuplicateTitleSchemaWhenMigrating()
    {
        // GIVEN + WHEN + THEN
        assertThat(columnNames("download_queue")).contains("avoid_duplicate_titles");
        assertThat(objectNames("type = 'index'")).contains("ix_chapter__title_full_gallery_id");
    }

    /**
     * The queue's order and a subscription's limits read these indexes; without them the worker sorts the queue on
     * every item and each enqueue scans it. A deleted subscription must leave its queue rows (SET NULL) and take
     * its checkpoints along (CASCADE).
     */
    @Test
    void shouldCreateTheSubscriptionSchemaWhenMigrating()
    {
        // GIVEN + WHEN + THEN
        assertThat(objectNames("type = 'table'")).contains("subscription", "subscription_checkpoint");
        assertThat(columnNames("download_queue")).contains("priority", "subscription_id");
        assertThat(columnNames("subscription")).contains("newest_gallery_id", "oldest_gallery_id", "oldest_cursor",
                "catch_up_top", "catch_up_cursor", "catch_up_stop", "revision", "recheck_every_hours",
                "recheck_depth_hours");
        assertThat(objectNames("type = 'index'")).contains("ix_download_queue__error_priority_id",
                "ix_download_queue__gallery_id", "ix_download_queue__subscription_error",
                "ix_subscription_checkpoint__subscription_recorded");
        assertThat(objectNames("type = 'index'")).doesNotContain("ix_download_queue__error_id");
        assertThat(foreignKeys("download_queue")).containsExactly("subscription_id -> subscription(id) SET NULL");
        assertThat(foreignKeys("subscription_checkpoint"))
                .containsExactly("subscription_id -> subscription(id) CASCADE");
    }

    /**
     * A reused id would let a new chapter inherit what a deleted one left behind (its image folder, a
     * download publishing into it, a bookmark). Nothing but the keyword prevents it, so every table with a
     * generated key is checked.
     */
    @Test
    void shouldDeclareEveryGeneratedIdAutoincrementWhenMigrating()
    {
        // GIVEN
        List<String> tablesWithGeneratedIds = List.of("chapter", "series", "artist", "character", "group_artists",
                "parody", "tag", "category", "metadata_rule", "download_queue", "image_compression_mode",
                "subscription", "subscription_checkpoint");

        // WHEN
        List<String> autoincrementTables = objectNames("type = 'table' AND sql LIKE '%primary key autoincrement%'");

        // THEN
        assertThat(autoincrementTables).containsAll(tablesWithGeneratedIds);
    }

    @Test
    void shouldNotReuseTheIdOfTheNewestRowWhenItIsDeleted()
    {
        // GIVEN
        jdbc.update("INSERT INTO tag (name) VALUES ('autoincrement probe 1')");
        long deletedId = jdbc.queryForObject("SELECT id FROM tag WHERE name = 'autoincrement probe 1'", Long.class);
        jdbc.update("DELETE FROM tag WHERE id = ?", deletedId);

        // WHEN
        jdbc.update("INSERT INTO tag (name) VALUES ('autoincrement probe 2')");

        // THEN
        long nextId = jdbc.queryForObject("SELECT id FROM tag WHERE name = 'autoincrement probe 2'", Long.class);
        jdbc.update("DELETE FROM tag WHERE id = ?", nextId);
        assertThat(nextId).isGreaterThan(deletedId);
    }

    /**
     * The foreign keys are hand-written in V1 and Hibernate's validation ignores them, so a join table
     * without them would only show up as search results naming deleted rows.
     */
    @Test
    void shouldDeclareCascadingForeignKeysOnEveryJoinTableWhenMigrating()
    {
        // GIVEN
        Map<String, List<String>> expected = new LinkedHashMap<>();
        for (String[] meta : new String[][]{{"tags", "tag_id", "tag"}, {"artists", "artist_id", "artist"},
                {"characters", "character_id", "character"}, {"parodies", "parody_id", "parody"},
                {"groups", "group_id", "group_artists"}, {"categories", "category_id", "category"}})
        {
            String reference = meta[1] + " -> " + meta[2] + "(id) CASCADE";
            expected.put("chapter_" + meta[0], List.of("chapter_id -> chapter(id) CASCADE", reference));
            expected.put("series_" + meta[0], List.of("series_id -> series(id) CASCADE", reference));
            expected.put("series_effective_" + meta[0], List.of("series_id -> series(id) CASCADE", reference));
        }
        expected.put("series_effective_languages", List.of("series_id -> series(id) CASCADE"));

        // WHEN + THEN
        expected.forEach((table, keys) -> assertThat(foreignKeys(table)).as(table).containsExactlyInAnyOrderElementsOf(keys));
    }

    /** Without such an index, every delete of a parent row scans the whole join table for its links. */
    @Test
    void shouldLeadAnIndexWithEveryForeignKeyColumnWhenMigrating()
    {
        // GIVEN
        List<String> tables = objectNames("type = 'table' AND sql LIKE '%references%'");

        // WHEN + THEN the 19 join tables, the queue's subscription and a subscription's checkpoints
        assertThat(tables).hasSize(21);
        for (String table : tables)
        {
            List<String> leadingColumns = jdbc.queryForList(
                    "SELECT ii.name FROM pragma_index_list('" + table + "') il "
                            + "JOIN pragma_index_info(il.name) ii WHERE ii.seqno = 0", String.class);
            List<String> fkColumns = jdbc.queryForList(
                    "SELECT \"from\" FROM pragma_foreign_key_list('" + table + "')", String.class);
            assertThat(leadingColumns).as(table).containsAll(fkColumns);
        }
    }

    @Test
    void shouldDeleteTheLinksOfARowDeletedOutsideHibernate()
    {
        // GIVEN
        jdbc.update("INSERT INTO tag (name) VALUES ('cascade probe')");
        long tagId = jdbc.queryForObject("SELECT id FROM tag WHERE name = 'cascade probe'", Long.class);
        jdbc.update("INSERT INTO chapter (status, upload_date, language, title_full, title_pretty) "
                + "VALUES (0, '2026-01-01', 'English', 'cascade probe', 'cascade probe')");
        long chapterId = jdbc.queryForObject("SELECT id FROM chapter WHERE title_full = 'cascade probe'", Long.class);
        jdbc.update("INSERT INTO chapter_tags (chapter_id, tag_id) VALUES (?, ?)", chapterId, tagId);

        // WHEN
        jdbc.update("DELETE FROM chapter WHERE id = ?", chapterId);

        // THEN
        long links = jdbc.queryForObject("SELECT count(*) FROM chapter_tags WHERE tag_id = ?", Long.class, tagId);
        long tags = jdbc.queryForObject("SELECT count(*) FROM tag WHERE id = ?", Long.class, tagId);
        jdbc.update("DELETE FROM tag WHERE id = ?", tagId);
        assertThat(links).isZero();
        assertThat(tags).isOne();
    }

    @Test
    void shouldRefuseALinkToAMissingRow()
    {
        // GIVEN
        long missingId = jdbc.queryForObject("SELECT coalesce(max(id), 0) + 1000 FROM chapter", Long.class);

        // WHEN + THEN
        assertThatThrownBy(() -> jdbc.update("INSERT INTO chapter_tags (chapter_id, tag_id) VALUES (?, ?)",
                missingId, missingId))
                .rootCause()
                .isInstanceOfSatisfying(SQLiteException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY));
    }

    private List<String> appliedMigrations()
    {
        return jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = 1", String.class);
    }

    private List<String> objectNames(String where)
    {
        return jdbc.queryForList("SELECT name FROM sqlite_master WHERE " + where, String.class);
    }

    /** As {@code "<column> -> <table>(<column>) <on delete>"}. */
    private List<String> foreignKeys(String table)
    {
        return jdbc.queryForList("SELECT \"from\" || ' -> ' || \"table\" || '(' || \"to\" || ') ' || on_delete "
                + "FROM pragma_foreign_key_list('" + table + "')", String.class);
    }

    private List<String> columnNames(String table)
    {
        return jdbc.queryForList("SELECT name FROM pragma_table_info('" + table + "')", String.class);
    }
}
