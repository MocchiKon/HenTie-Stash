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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10 on a queue that already has failed e-hentai downloads: the ones a login would have saved are marked to go
 * back to waiting once exhentai accepts the account, the rest are left as they are.
 */
class EhentaiAccountMigrationIT
{
    private static final Path DB = Paths.get("./target/migration-v10-test.sqlite");
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
    void shouldMarkTheFailuresALoginWouldHaveSaved() throws SQLException
    {
        // GIVEN a library at V9 with failed rows as the browser-cookies downloads left them
        flyway("9").migrate();
        try (Connection connection = DriverManager.getConnection(URL); Statement sql = connection.createStatement())
        {
            failed(sql, "a", "ehentai:1/0123456789", "gallery-dl could not download https://exhentai.org/g/1/0123456789/: "
                    + "[exhentai][error] NotFoundError: Requested gallery could not be found");
            failed(sql, "b", "ehentai:2/0123456789", "gallery-dl could not download https://e-hentai.org/g/2/0123456789/:"
                    + " AuthorizationError - e-hentai shows this gallery only to a logged-in account. Choose the browser");
            failed(sql, "c", "ehentai:3/0123456789", "exhentai.org refused the cookies from firefox (no ExHentai "
                    + "access, or not logged in there), and e-hentai.org would not show the gallery either");
            failed(sql, "d", "ehentai:4/0123456789", "gallery-dl could not download https://exhentai.org/g/4/0123456789/: "
                    + "Not enough GP - originals cost GP");
            failed(sql, "e", "ehentai:5/0123456789", "gallery-dl could not download https://e-hentai.org/g/5/0123456789/: "
                    + "NotFoundError: Requested gallery could not be found");
            failed(sql, "f", "hitomi:6", "only to a logged-in account");
            sql.execute("insert into download_queue (attempts, queued_at, gallery_id, link, request_delay, "
                    + "cookies_browser) values (0, '2024-01-01', 'ehentai:7/0123456789', 'g', '0', 'firefox')");
            sql.execute("insert into app_setting (setting_key, setting_value) "
                    + "values ('gallery-dl.cookies-browser', 'firefox')");
        }

        // WHEN
        flyway(null).migrate();

        // THEN only the e-hentai rows a login would have saved are marked, and the browser is forgotten
        try (Connection connection = DriverManager.getConnection(URL); Statement sql = connection.createStatement())
        {
            assertThat(single(sql, "select group_concat(link || '=' || needs_login, ' ') "
                    + "from (select link, needs_login from download_queue order by link)"))
                    .isEqualTo("a=1 b=1 c=1 d=0 e=0 f=0 g=0");
            assertThat(single(sql, "select count(*) from pragma_table_info('download_queue') "
                    + "where name = 'cookies_browser'")).isEqualTo("0");
            assertThat(single(sql, "select count(*) from pragma_table_info('subscription') "
                    + "where name = 'cookies_browser'")).isEqualTo("0");
            assertThat(single(sql, "select count(*) from app_setting where setting_key = 'gallery-dl.cookies-browser'"))
                    .isEqualTo("0");
            assertThat(single(sql, "pragma integrity_check")).isEqualTo("ok");
        }
    }

    private static void failed(Statement sql, String link, String galleryId, String error) throws SQLException
    {
        sql.execute("insert into download_queue (attempts, queued_at, gallery_id, link, request_delay, error) "
                + "values (1, '2024-01-01', '" + galleryId + "', '" + link + "', '0', '" + error + "')");
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
}
