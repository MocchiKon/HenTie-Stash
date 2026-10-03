package io.github.mocchikon.hentie.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.mocchikon.hentie.GateHolder;
import io.github.mocchikon.hentie.SqliteLockHolder;
import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.MetadataService;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * A few representative actions while the library is busy: a short wait is invisible, a long one is a 503 that says
 * nothing was changed, as a page for a form and as JSON for {@code app.js}, and a page view never waits.
 * <p>
 * Not {@code @Transactional}: the holders are other threads and connections, and each action must begin its own
 * transaction, as a real request does. It commits to the shared database, so its rows go after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BusyLibraryWebIT
{
    private static final String BUSY_WITH_SWEEP =
            "The library is busy with the test sweep. Nothing was changed; try again in a moment.";
    private static final String BUSY_OUTSIDE =
            "The library's database is in use by another program. Nothing was changed; try again in a moment.";

    @Autowired MockMvc mvc;
    @Autowired WriteGate writeGate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ChapterService chapterService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired MetadataService metadataService;
    @Autowired TagRepository tagRepository;
    @Autowired ImageDirectory imageDirectory;
    @Autowired AppProperties appProperties;
    @Autowired CacheManager cacheManager;
    @Value("${spring.datasource.url}") String datasourceUrl;

    private long budget;
    private Integer chapterId;

    @BeforeEach
    void setUp()
    {
        budget = appProperties.getWrites().getRequestWaitMillis();
        cacheManager.getCache(CacheConfig.METADATA).clear();
        var form = new ChapterForm();
        form.setTitleFull("busy-web chapter");
        form.setLanguage("English");
        form.setStatus(Status.NEW);
        chapterId = chapterService.create(form);
    }

    @AfterEach
    void cleanUp()
    {
        chapterService.delete(chapterId);
        tagRepository.findByNameIgnoreCase("busy-web tag")
                .ifPresent(tag -> metadataService.remove(MetadataType.TAG, tag.getId(), false));
    }

    // ---- a form ---------------------------------------------------------------------------------------------------

    @Test
    void shouldSaveAChapterEditThatWaitedForAShortHold() throws Exception
    {
        try (GateHolder ignored = hold().releaseIn(Duration.ofMillis(budget / 3)))
        {
            mvc.perform(editChapter("busy-web edited"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/chapter/" + chapterId));
        }
        assertThat(chapter().getTitleFull()).isEqualTo("busy-web edited");
    }

    @Test
    void shouldAnswerAChapterEditWithTheBusyPageAndChangeNothingWhenTheHoldOutlastsTheBudget() throws Exception
    {
        try (GateHolder ignored = hold())
        {
            mvc.perform(editChapter("busy-web edited"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(view().name("error"))
                    .andExpect(model().attribute("status", "The library is busy"))
                    .andExpect(model().attribute("error", BUSY_WITH_SWEEP))
                    .andExpect(model().attribute("retryable", true))
                    .andExpect(content().string(containsString("Go back")));
        }
        assertThat(chapter().getTitleFull()).isEqualTo("busy-web chapter");
    }

    @Test
    void shouldAnswerAMetadataAddWithTheBusyPageWhileAProgramOutsideTheAppHoldsTheLock() throws Exception
    {
        try (SqliteLockHolder ignored = SqliteLockHolder.hold(datasourceUrl))
        {
            mvc.perform(post("/manage/add").with(user("user")).with(csrf())
                            .param("type", "tag").param("name", "busy-web tag"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(model().attribute("error", BUSY_OUTSIDE));
        }
        assertThat(tagRepository.findByNameIgnoreCase("busy-web tag")).isEmpty();

        // AND once the lock is free, the same add goes through.
        mvc.perform(post("/manage/add").with(user("user")).with(csrf())
                        .param("type", "tag").param("name", "busy-web tag"))
                .andExpect(status().is3xxRedirection());
        assertThat(tagRepository.findByNameIgnoreCase("busy-web tag")).isPresent();
    }

    /** A long-running form waits for its turn instead: the user started it and watches its spinner. */
    @Test
    void shouldLetALongRunningFormWaitPastTheBudget() throws Exception
    {
        int tagId = metadataService.resolveOrCreate(MetadataType.TAG, List.of("busy-web tag")).getFirst();
        try (GateHolder ignored = hold().releaseIn(Duration.ofMillis(3 * budget)))
        {
            long start = System.nanoTime();
            mvc.perform(post("/manage/remove").with(user("user")).with(csrf())
                            .param("type", "tag").param("id", Integer.toString(tagId)).param("createRule", "false"))
                    .andExpect(status().is3xxRedirection());
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(3 * budget - 30);
        }
        assertThat(tagRepository.findById(tagId)).isEmpty();
    }

    /** It saves files before its first write, so it must be refused before that. */
    @Test
    void shouldRefuseAnUploadBeforeSavingAnyFile() throws Exception
    {
        var file = new MockMultipartFile("files", "1.jpg", "image/jpeg", "not really a jpeg".getBytes());
        try (GateHolder ignored = hold())
        {
            mvc.perform(multipart("/chapter/" + chapterId + "/images").file(file).with(user("user")).with(csrf()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(model().attribute("error", BUSY_WITH_SWEEP));
        }
        assertThat(imageDirectory.pageNumbers(chapterId)).isEmpty();
        assertThat(chapter().getPageNum()).isZero();
    }

    // ---- what app.js posts ----------------------------------------------------------------------------------------

    @Test
    void shouldAnswerAReviewActionWithJsonTheScriptShowsAndChangeNothing() throws Exception
    {
        try (GateHolder ignored = hold())
        {
            mvc.perform(post("/chapter/" + chapterId + "/mark-reviewed").with(user("user")).with(csrf())
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(jsonPath("$.message").value(BUSY_WITH_SWEEP))
                    .andExpect(jsonPath("$.retryAfterSeconds").value(1));
        }
        assertThat(chapter().getStatus()).isEqualTo(Status.NEW);

        // AND with the gate free, the same post goes through.
        mvc.perform(post("/chapter/" + chapterId + "/mark-reviewed").with(user("user")).with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is3xxRedirection());
        assertThat(chapter().getStatus()).isEqualTo(Status.REVIEWED);
    }

    @Test
    void shouldKeepThePageWhenAnInPlaceDeleteIsRefused() throws Exception
    {
        // GIVEN a chapter of two pages, its stats in step with them.
        Path dir = writePages();
        chapterService.rescanImages(chapterId);

        try (GateHolder ignored = hold())
        {
            // WHEN the edit page deletes a page in place while the library stays busy.
            mvc.perform(post("/chapter/" + chapterId + "/images/delete").with(user("user")).with(csrf())
                            .param("filename", "1.jpg").param("inPlace", "true")
                            .accept(MediaType.APPLICATION_JSON))
                    // THEN it is refused with the message, and the page is still there.
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value(BUSY_WITH_SWEEP));
        }
        assertThat(dir.resolve("1.jpg")).exists();
        assertThat(chapter().getPageNum()).isEqualTo(2);

        // AND with the gate free, the delete goes through.
        mvc.perform(post("/chapter/" + chapterId + "/images/delete").with(user("user")).with(csrf())
                        .param("filename", "1.jpg").param("inPlace", "true")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
        assertThat(dir.resolve("1.jpg")).doesNotExist();
        assertThat(chapter().getPageNum()).isEqualTo(1);
    }

    // ---- page views -----------------------------------------------------------------------------------------------

    /** The repair runs on a later view: waiting for it would slow down the page, failing it would lose the page. */
    @Test
    void shouldShowTheDetailPageAtOnceAndSkipTheRepairWhileTheLibraryIsBusy() throws Exception
    {
        // GIVEN a page viewed once, so rendering it costs no more than in the app, and pages that then appeared
        // without the app writing them, so the stored count is behind.
        mvc.perform(get("/chapter/" + chapterId).with(user("user"))).andExpect(status().isOk());
        writePages();
        assertThat(chapter().getPageNum()).isZero();

        try (GateHolder ignored = hold())
        {
            // WHEN the detail page is opened while the library stays busy.
            long start = System.nanoTime();
            mvc.perform(get("/chapter/" + chapterId).with(user("user")))
                    // THEN it renders without waiting for the gate, and with the stored numbers search uses.
                    .andExpect(status().isOk())
                    .andExpect(view().name("chapter-view"));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(budget);
        }
        assertThat(chapter().getPageNum()).isZero();

        // AND the next view, with the gate free, repairs them.
        mvc.perform(get("/chapter/" + chapterId).with(user("user"))).andExpect(status().isOk());
        assertThat(chapter().getPageNum()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private GateHolder hold() throws InterruptedException
    {
        return GateHolder.hold(transactionManager, writeGate, "the test sweep");
    }

    private MockHttpServletRequestBuilder editChapter(String titleFull)
    {
        return post("/chapter/" + chapterId).with(user("user")).with(csrf())
                .param("titleFull", titleFull)
                .param("language", "English")
                .param("status", "NEW");
    }

    private Chapter chapter()
    {
        return chapterRepository.findById(chapterId).orElseThrow();
    }

    private Path writePages() throws Exception
    {
        Path dir = imageDirectory.chapterDir(chapterId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1.jpg"), "first", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("2.jpg"), "second", StandardCharsets.UTF_8);
        return dir;
    }
}
