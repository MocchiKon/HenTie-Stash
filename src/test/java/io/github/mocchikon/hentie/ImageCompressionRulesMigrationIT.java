package io.github.mocchikon.hentie;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7 on a library that already has modes, subscriptions and queued rows, which a schema built from an empty file
 * never shows: SQLite rewrites the subscription table to drop its mode, and the rows must come through it.
 */
class ImageCompressionRulesMigrationIT
{
    private static final Path DB = Paths.get("./target/migration-v7-test.sqlite");
    private static final String URL = "jdbc:sqlite:" + DB + "?foreign_keys=ON";

    @BeforeEach
    @AfterEach
    void removeDatabase() throws IOException
    {
        Files.deleteIfExists(DB);
        Files.deleteIfExists(Paths.get(DB + "-wal"));
        Files.deleteIfExists(Paths.get(DB + "-shm"));
    }

    @Test
    void shouldKeepExistingRowsAndGiveExistingModesQualityOne() throws SQLException
    {
        // GIVEN a library at V6 with a mode, a subscription with its own mode, and a row it queued
        flyway("6").migrate();
        try (Connection connection = DriverManager.getConnection(URL); Statement sql = connection.createStatement())
        {
            sql.execute("insert into image_compression_mode (name, encoder, encoder_args, magick_args, "
                    + "min_relative_reduction, min_absolute_reduction, formats) values ('Mine', 0, '-q 90', '', 0, 0, '')");
            sql.execute("insert into subscription (name, source, query, poll_minutes, recheck_every_hours, "
                    + "recheck_depth_hours, compression_mode, request_delay, created_at, queued_count) values "
                    + "('artist', 'nhentai', 'artist:someone', 60, 24, 48, 'HIGH_REDUCTION', '0', CURRENT_TIMESTAMP, 7)");
            sql.execute("insert into download_queue (attempts, queued_at, link, gallery_id, compression_mode, "
                    + "request_delay, priority, subscription_id) values (0, CURRENT_TIMESTAMP, 'nhentai:1', 'nhentai:1', "
                    + "'VERY_HIGH_REDUCTION', '0', 1, (select id from subscription))");
        }

        // WHEN
        flyway(null).migrate();

        // THEN
        try (Connection connection = DriverManager.getConnection(URL); Statement sql = connection.createStatement())
        {
            assertThat(single(sql, "select quality from image_compression_mode where name = 'Mine'")).isEqualTo("1");
            assertThat(columns(sql, "subscription")).contains("upgrade_count").doesNotContain("compression_mode");
            assertThat(single(sql, "select name || ' ' || queued_count || ' ' || upgrade_count from subscription"))
                    .isEqualTo("artist 7 0");
            // What the subscription queued keeps the mode it was queued with, and still names it.
            assertThat(single(sql, "select compression_mode || ' ' || allow_upgrade || ' ' || "
                    + "(subscription_id = (select id from subscription)) from download_queue"))
                    .isEqualTo("VERY_HIGH_REDUCTION 0 1");
            assertThat(single(sql, "select count(*) from pragma_foreign_key_check")).isEqualTo("0");
            assertThat(single(sql, "select count(*) from image_compression_rule")).isEqualTo("0");
        }
    }

    private static Flyway flyway(String target)
    {
        var configuration = Flyway.configure().dataSource(URL, null, null).locations("classpath:db/migration");
        return (target == null ? configuration : configuration.target(target)).load();
    }

    private static String single(Statement sql, String query) throws SQLException
    {
        try (ResultSet rows = sql.executeQuery(query))
        {
            assertThat(rows.next()).as(query).isTrue();
            return rows.getString(1);
        }
    }

    private static List<String> columns(Statement sql, String table) throws SQLException
    {
        var names = new ArrayList<String>();
        try (ResultSet rows = sql.executeQuery("select name from pragma_table_info('" + table + "')"))
        {
            while (rows.next())
            {
                names.add(rows.getString(1));
            }
        }
        return names;
    }
}
