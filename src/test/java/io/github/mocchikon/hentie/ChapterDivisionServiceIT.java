package io.github.mocchikon.hentie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Character;
import io.github.mocchikon.hentie.entity.DownloadQueueItem;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.entity.Group;
import io.github.mocchikon.hentie.entity.Metadata;
import io.github.mocchikon.hentie.entity.Parody;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.entity.Tag;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.CharacterRepository;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.repository.GroupRepository;
import io.github.mocchikon.hentie.repository.ParodyRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.ChapterDivisionService;
import io.github.mocchikon.hentie.service.ChapterDivisionService.CreatedPart;
import io.github.mocchikon.hentie.service.ChapterDivisionService.NewPart;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import io.github.mocchikon.hentie.service.match.TitleKey;

import static org.assertj.core.api.Assertions.*;

/**
 * Not transactional: a division is a series of short transactions around file moves, and running it inside one
 * would test something else. Rows are cleaned up by hand.
 */
@SpringBootTest
class ChapterDivisionServiceIT
{
    private static final String LOSSLESS = BuiltInCompressionMode.LOSSLESS.getKey();

    @Autowired ChapterDivisionService divisionService;
    @Autowired ChapterService chapterService;
    @Autowired ChapterRepository chapterRepository;
    @Autowired DownloadQueueRepository queueRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired CharacterRepository characterRepository;
    @Autowired ParodyRepository parodyRepository;
    @Autowired GroupRepository groupRepository;
    @Autowired ImageDirectory imageDirectory;
    @Autowired ImageService imageService;
    @Autowired ImageCompressionService compressionService;
    @Autowired SettingsService settingsService;
    @Autowired AppProperties appProperties;
    @Autowired JdbcTemplate jdbc;

    private final List<Integer> chapters = new ArrayList<>();
    private final List<Integer> queueRows = new ArrayList<>();
    private final List<Metadata> metadata = new ArrayList<>();
    private final List<Path> strayFiles = new ArrayList<>();

    @AfterEach
    void cleanUp() throws IOException
    {
        queueRepository.deleteAllById(queueRows);
        queueRows.clear();
        chapters.stream().filter(chapterRepository::existsById).forEach(chapterService::delete);
        chapters.clear();
        // After the chapters, whose links would otherwise block the delete.
        for (Metadata item : metadata)
        {
            switch (item)
            {
                case Tag tag -> tagRepository.delete(tag);
                case Artist artist -> artistRepository.delete(artist);
                case Character character -> characterRepository.delete(character);
                case Parody parody -> parodyRepository.delete(parody);
                case Group group -> groupRepository.delete(group);
                default -> throw new IllegalStateException("Unknown metadata " + item);
            }
        }
        metadata.clear();
        for (Path file : strayFiles)
        {
            Files.deleteIfExists(file);
        }
        strayFiles.clear();
        // The settings cache is a shared singleton.
        settingsService.setMatchAutoLinkEnabled(appProperties.isMatchAutoLinkDefault());
    }

    // ---- the division --------------------------------------------------------

    @Test
    void shouldMoveEachPartsPagesIntoANewChapterNumberedFromOne() throws IOException
    {
        // GIVEN a compilation of three chapters: pages 1-2, 3-4 and 5.
        int id = chapterWithPages("Divide Pages Vol 1", "1.jpg", "2.jpg", "3.png", "4.jpg", "5.jpg");

        // WHEN it is divided at pages 3 and 5.
        var outcome = divide(id, new NewPart("3.png", "Divide Pages 2"), new NewPart("5.jpg", "Divide Pages 3"));

        // THEN two chapters were made, in reading order...
        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.created()).extracting(CreatedPart::titleFull, CreatedPart::pageCount)
                .containsExactly(tuple("Divide Pages 2", 2), tuple("Divide Pages 3", 1));
        int second = track(outcome.created().get(0).chapterId());
        int third = track(outcome.created().get(1).chapterId());
        assertThat(second).isLessThan(third);
        // ...each holding its own pages, numbered from 1 and keeping their extensions...
        assertThat(pageFiles(second)).containsExactly("1.png", "2.jpg");
        assertThat(content(second, "1.png")).isEqualTo(pageContent(id, "3.png"));
        assertThat(content(second, "2.jpg")).isEqualTo(pageContent(id, "4.jpg"));
        assertThat(pageFiles(third)).containsExactly("1.jpg");
        assertThat(content(third, "1.jpg")).isEqualTo(pageContent(id, "5.jpg"));
        // ...while the chapter keeps the first part under the names it had...
        assertThat(pageFiles(id)).containsExactly("1.jpg", "2.jpg");
        // ...and every stored page count and size agrees with its folder (search filters on them).
        for (int chapterId : List.of(id, second, third))
        {
            Chapter chapter = chapter(chapterId);
            assertThat(chapter.getPageNum()).as("page_num of %s", chapterId).isEqualTo(pageFiles(chapterId).size());
            assertThat(chapter.getDiskSize()).as("disk_size of %s", chapterId).isEqualTo(imageService.diskSize(chapterId));
        }
        // With auto-linking off (the test profile's default) matching puts the parts nowhere.
        assertThat(outcome.created()).extracting(CreatedPart::seriesId).containsOnlyNulls();
    }

    @Test
    void shouldCopyEverythingButTheTitleAndTheGalleryIdIntoEachPart()
    {
        // GIVEN a downloaded, curated compilation.
        int id = chapterWithPages("Divide Meta Vol 1", "1.jpg", "2.jpg");
        var form = chapterService.toForm(id);
        form.setNativeTitle("Divide Meta native");
        form.setStatus(Status.REVIEWED_FAVOURITE);
        form.setScore(8);
        form.setLanguage("Japanese");
        form.setGalleryId("divide-test:1");
        form.setTagIds(List.of(tag("divide meta tag").getId()));
        form.setArtistIds(List.of(artist("divide meta artist").getId()));
        form.setCharacterIds(List.of(character("divide meta character").getId()));
        form.setParodyIds(List.of(parody("divide meta parody").getId()));
        form.setGroupIds(List.of(group("divide meta group").getId()));
        chapterService.update(form);
        chapterService.setDownloadStatus(id, DownloadStatus.SUCCESSFUL);

        // WHEN it is divided with no status picked for the part.
        var outcome = divide(id, new NewPart("2.jpg", "Divide Meta 2 [Digital]"));

        // THEN the part carries the compilation's metadata, its status included...
        int part = track(outcome.created().getFirst().chapterId());
        ChapterForm copied = chapterService.toForm(part);
        assertThat(copied.getNativeTitle()).isEqualTo("Divide Meta native");
        assertThat(copied.getStatus()).isEqualTo(Status.REVIEWED_FAVOURITE);
        assertThat(copied.getScore()).isEqualTo(8);
        assertThat(copied.getLanguage()).isEqualTo("Japanese");
        assertThat(copied.getTagIds()).isEqualTo(form.getTagIds());
        assertThat(copied.getArtistIds()).isEqualTo(form.getArtistIds());
        assertThat(copied.getCharacterIds()).isEqualTo(form.getCharacterIds());
        assertThat(copied.getParodyIds()).isEqualTo(form.getParodyIds());
        assertThat(copied.getGroupIds()).isEqualTo(form.getGroupIds());
        // ...under its own title, whose pretty form is derived from it as for any new chapter...
        assertThat(copied.getTitleFull()).isEqualTo("Divide Meta 2 [Digital]");
        assertThat(copied.getTitle()).isEqualTo("Divide Meta 2");
        // ...but not the gallery id (unique) nor the download status: no download may ever touch its pages.
        Chapter created = chapter(part);
        assertThat(created.getGalleryId()).isNull();
        assertThat(created.getDownloadStatus()).isEqualTo(DownloadStatus.NONE);
        assertThat(created.getUploadDate()).isEqualTo(LocalDate.now());
        assertThat(chapter(id).getGalleryId()).isEqualTo("divide-test:1");
    }

    @Test
    void shouldGiveEveryNewChapterTheStatusPickedForThem()
    {
        // GIVEN a compilation marked as a favourite.
        int id = chapterWithPages("Divide Status Vol 1", "1.jpg", "2.jpg", "3.jpg");
        var form = chapterService.toForm(id);
        form.setStatus(Status.REVIEWED_FAVOURITE);
        chapterService.update(form);

        // WHEN it is divided into three, the new chapters to be looked at again.
        var outcome = divisionService.divide(id, null, Status.NEW,
                List.of(new NewPart("2.jpg", "Divide Status 2"), new NewPart("3.jpg", "Divide Status 3")));

        // THEN both new chapters are New, and the compilation keeps its own status.
        assertThat(outcome.created()).hasSize(2);
        for (CreatedPart part : outcome.created())
        {
            track(part.chapterId());
            assertThat(chapter(part.chapterId()).getStatus()).isEqualTo(Status.NEW);
        }
        assertThat(chapter(id).getStatus()).isEqualTo(Status.REVIEWED_FAVOURITE);
    }

    @Test
    void shouldRetitleThePartThatStaysAndDeriveItsPrettyTitleAgain()
    {
        // GIVEN a compilation whose pretty title was typed by hand.
        int id = chapterWithPages("Divide Rename Vol 1", "1.jpg", "2.jpg");
        var form = chapterService.toForm(id);
        form.setTitle("The whole volume");
        chapterService.update(form);

        // WHEN it is divided, with a new title for what stays.
        divisionService.divide(id, "Divide Rename 1 [Scan]", null, List.of(new NewPart("2.jpg", "Divide Rename 2")))
                .created().forEach(part -> track(part.chapterId()));

        // THEN the typed pretty title gives way to one derived from the new title, and the match key follows.
        Chapter renamed = chapter(id);
        assertThat(renamed.getTitleFull()).isEqualTo("Divide Rename 1 [Scan]");
        assertThat(renamed.getTitle()).isEqualTo("Divide Rename 1");
        assertThat(renamed.getMatchKey()).isEqualTo(TitleKey.of("Divide Rename 1 [Scan]").getMatchKey());
    }

    @Test
    void shouldPutEachPartWhereMatchingPutsIt()
    {
        // GIVEN auto-linking on, and a compilation matching put in a series of its own.
        settingsService.setMatchAutoLinkEnabled(true);
        Artist artist = artist("divide saga artist");
        int id = chapterWithPages("Divide Saga", "1.jpg", "2.jpg", "3.jpg");
        var form = chapterService.toForm(id);
        form.setArtistIds(List.of(artist.getId()));
        chapterService.update(form);
        Chapter compilation = chapter(id);
        assertThat(compilation.getSeries()).isNotNull();

        // WHEN a part is cut out under a title of the same family.
        var outcome = divide(id, new NewPart("2.jpg", "Divide Saga 2"));

        // THEN matching, as for any new chapter, put it in that series, numbered from its title...
        CreatedPart part = outcome.created().getFirst();
        track(part.chapterId());
        assertThat(part.seriesId()).isEqualTo(compilation.getSeries().getId());
        assertThat(part.seriesTitle()).isEqualTo(compilation.getSeries().getTitle());
        assertThat(chapter(part.chapterId()).getChapterNum()).isCloseTo(2.0f, within(0.001f));
        // ...and the compilation is still where it was.
        assertThat(chapter(id).getSeries().getId()).isEqualTo(compilation.getSeries().getId());
    }

    @Test
    void shouldRemoveASupersededSourceSoItCannotBecomeAPageOfTheChapterAgain() throws IOException
    {
        // GIVEN a killed compression run left 2.png beside the 2.jxl that replaced it (listed as one page).
        int id = chapterWithPages("Divide Superseded Vol 1", "1.jpg", "2.png", "2.jxl", "3.jpg");
        assertThat(pageFiles(id)).containsExactly("1.jpg", "2.jxl", "3.jpg");

        // WHEN a new chapter starts at page 2.
        int part = track(divide(id, new NewPart("2.jxl", "Divide Superseded 2")).created().getFirst().chapterId());

        // THEN the source did not stay behind to become this chapter's page 2.
        assertThat(pageFiles(part)).containsExactly("1.jxl", "2.jpg");
        assertThat(pageFiles(id)).containsExactly("1.jpg");
        assertThat(imageDirectory.chapterDir(id).resolve("2.png")).doesNotExist();
    }

    @Test
    void shouldCarryTheCompressionModeOnlyWithCompressedPages()
    {
        // GIVEN a chapter compressed by a mode that replaced page 2 only.
        int id = chapterWithPages("Divide Compressed Vol 1", "1.jpg", "2.jxl", "3.jpg");
        chapterService.setCompressionMode(id, LOSSLESS);

        // WHEN the compressed page and the uncompressed one after it become chapters of their own.
        var outcome = divide(id,
                new NewPart("2.jxl", "Divide Compressed 2"), new NewPart("3.jpg", "Divide Compressed 3"));
        int compressed = track(outcome.created().get(0).chapterId());
        int uncompressed = track(outcome.created().get(1).chapterId());

        // THEN only the chapter holding the compressed page keeps the mode; the others offer no pointless re-download.
        assertThat(chapter(compressed).getCompressionMode()).isEqualTo(LOSSLESS);
        assertThat(chapter(uncompressed).getCompressionMode()).isNull();
        assertThat(chapter(id).getCompressionMode()).isNull();
    }

    @Test
    void shouldKeepTheCompressionModeOfAChapterThatKeepsACompressedPage()
    {
        // GIVEN a chapter compressed throughout.
        int id = chapterWithPages("Divide Kept Compressed Vol 1", "1.jxl", "2.jxl");
        chapterService.setCompressionMode(id, LOSSLESS);

        // WHEN it is divided.
        int part = track(divide(id, new NewPart("2.jxl", "Divide Kept Compressed 2")).created().getFirst().chapterId());

        // THEN both halves are compressed pages, and both say so.
        assertThat(chapter(id).getCompressionMode()).isEqualTo(LOSSLESS);
        assertThat(chapter(part).getCompressionMode()).isEqualTo(LOSSLESS);
    }

    @Test
    void shouldTakeBackAPartWhosePagesCannotBeMovedAndStopThere() throws IOException
    {
        // GIVEN a file squatting on the third part's future folder (ids count up from the chapter just made, so it will be max + 2).
        int id = chapterWithPages("Divide Blocked Vol 1", "1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg");
        int highest = jdbc.queryForObject("select max(id) from chapter", Integer.class);
        Path squatter = imageDirectory.chapterDir(highest + 2);
        Files.writeString(squatter, "not a folder", StandardCharsets.UTF_8);
        strayFiles.add(squatter);

        // WHEN it is divided at pages 3 and 5.
        var outcome = divisionService.divide(id, "Divide Blocked 1", null,
                List.of(new NewPart("3.jpg", "Divide Blocked 2"), new NewPart("5.jpg", "Divide Blocked 3")));

        // THEN the second part was made...
        assertThat(outcome.complete()).isFalse();
        assertThat(outcome.created()).extracting(CreatedPart::chapterId).containsExactly(highest + 1);
        track(highest + 1);
        assertThat(pageFiles(highest + 1)).containsExactly("1.jpg", "2.jpg");
        // ...the third was taken back, and the outcome says where its pages are...
        assertThat(chapterRepository.existsById(highest + 2)).isFalse();
        assertThat(outcome.stoppedBecause()).contains("Part 3 (from page 5)").contains("still in this chapter");
        // ...still in the divided chapter, whose stats follow...
        assertThat(pageFiles(id)).containsExactly("1.jpg", "2.jpg", "5.jpg");
        assertThat(chapter(id).getPageNum()).isEqualTo(3);
        // ...and which keeps its title, since it still holds pages of the part not made.
        assertThat(chapter(id).getTitleFull()).isEqualTo("Divide Blocked Vol 1");
    }

    // ---- refusals: nothing changes ------------------------------------------------

    @Test
    void shouldRefuseAChapterWhoseDownloadNeverFinished()
    {
        // GIVEN a chapter whose download was cut short: queueing its link again fetches every page it lacks.
        int id = chapterWithPages("Divide Pending Vol 1", "1.jpg", "2.jpg");
        chapterService.setDownloadStatus(id, DownloadStatus.PENDING);

        // WHEN / THEN
        assertRefusedUntouched(id, List.of(new NewPart("2.jpg", "Divide Pending 2")), "never finished");
    }

    @Test
    void shouldRefuseWhileADownloadOfTheChapterIsWaitingInTheQueue()
    {
        // GIVEN a full-quality re-download of the chapter waiting to run: it works out its pages when it starts.
        int id = chapterWithPages("Divide Queued Vol 1", "1.jpg", "2.jpg");
        galleryWithQueueRow(id, "divide-test:2", null);

        // WHEN / THEN
        assertRefusedUntouched(id, List.of(new NewPart("2.jpg", "Divide Queued 2")), "waiting in the download queue");
    }

    @Test
    void shouldDivideAChapterWhoseOnlyQueueRowFailed()
    {
        // GIVEN a failed row for the chapter's gallery: nothing runs it until a retry, which starts afresh.
        int id = chapterWithPages("Divide Failed Row Vol 1", "1.jpg", "2.jpg");
        galleryWithQueueRow(id, "divide-test:3", "page 2 could not be downloaded");

        // WHEN
        var outcome = divide(id, new NewPart("2.jpg", "Divide Failed Row 2"));

        // THEN
        track(outcome.created().getFirst().chapterId());
        assertThat(outcome.complete()).isTrue();
    }

    @Test
    void shouldRefuseAChapterWithALanguageTheAppDoesNotKnow()
    {
        // GIVEN an imported row whose language no chapter could be created with.
        int id = chapterWithPages("Divide Language Vol 1", "1.jpg", "2.jpg");
        jdbc.update("update chapter set language = ? where id = ?", "Klingonese", id);

        // WHEN / THEN
        assertRefusedUntouched(id, List.of(new NewPart("2.jpg", "Divide Language 2")), "Klingonese");
    }

    @Test
    void shouldRefuseTheFirstPageAsTheStartOfANewChapter()
    {
        int id = chapterWithPages("Divide First Vol 1", "1.jpg", "2.jpg");

        assertRefusedUntouched(id, List.of(new NewPart("1.jpg", "Divide First 2")), "first page always stays");
    }

    @Test
    void shouldRefuseAPageThatIsNoLongerOneOfTheChapters()
    {
        // GIVEN a divide page rendered before page 2 was deleted in another tab.
        int id = chapterWithPages("Divide Stale Vol 1", "1.jpg", "3.jpg");

        assertRefusedUntouched(id, List.of(new NewPart("2.jpg", "Divide Stale 2")), "\"2.jpg\" is not one of them");
    }

    @Test
    void shouldRefuseADivisionThatMarksNothing()
    {
        int id = chapterWithPages("Divide Nothing Vol 1", "1.jpg", "2.jpg");

        assertRefusedUntouched(id, List.of(), "Mark the first page");
    }

    @Test
    void shouldRefuseTheSamePageMarkedTwice()
    {
        int id = chapterWithPages("Divide Twice Vol 1", "1.jpg", "2.jpg", "3.jpg");

        assertRefusedUntouched(id, List.of(new NewPart("2.jpg", "Divide Twice 2"), new NewPart("2.jpg", "Again")),
                "marked twice");
    }

    @Test
    void shouldRefuseAPartWithoutATitle()
    {
        int id = chapterWithPages("Divide Blank Vol 1", "1.jpg", "2.jpg", "3.jpg");

        assertRefusedUntouched(id, List.of(new NewPart("2.jpg", "Divide Blank 2"), new NewPart("3.jpg", "  ")),
                "Give part 3 (from page 3) a title");
    }

    @Test
    void shouldRefuseATitleLongerThanTheColumn()
    {
        int id = chapterWithPages("Divide Long Vol 1", "1.jpg", "2.jpg");

        assertRefusedUntouched(id, List.of(new NewPart("2.jpg", "x".repeat(256))), "too long");
    }

    @Test
    void shouldRefuseABlankTitleForThePartThatStays()
    {
        int id = chapterWithPages("Divide Blank Kept Vol 1", "1.jpg", "2.jpg");

        long before = chapterRepository.count();
        assertThatThrownBy(() -> divisionService.divide(id, " ", null,
                List.of(new NewPart("2.jpg", "Divide Blank Kept 2"))))
                .isInstanceOf(ChapterDivisionService.Refused.class)
                .hasMessageContaining("Give the part that stays a title");
        assertThat(chapterRepository.count()).isEqualTo(before);
        assertThat(chapter(id).getTitleFull()).isEqualTo("Divide Blank Kept Vol 1");
    }

    @Test
    void shouldGiveAPartWithNoTitleTheChaptersOwn()
    {
        // GIVEN the post a browser without JavaScript makes: marks, but no title fields.
        int id = chapterWithPages("Divide Untitled Vol 1", "1.jpg", "2.jpg");

        // WHEN
        var outcome = divide(id, new NewPart("2.jpg", null));

        // THEN the part is named like the compilation, to be renamed on its edit page.
        assertThat(outcome.created()).extracting(CreatedPart::titleFull).containsExactly("Divide Untitled Vol 1");
        track(outcome.created().getFirst().chapterId());
    }

    @Test
    void shouldRefuseWhileAnImageCompressionRunHoldsTheLock() throws InterruptedException
    {
        // GIVEN a sweep, which may be re-encoding the pages that would move.
        int id = chapterWithPages("Divide Busy Vol 1", "1.jpg", "2.jpg");

        // WHEN / THEN refused, not waited for: a sweep holds the lock for hours.
        try (var ignored = RunLockHolder.hold(compressionService))
        {
            assertRefusedUntouched(id, List.of(new NewPart("2.jpg", "Divide Busy 2")), "Image Compression run");
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private ChapterDivisionService.Outcome divide(int id, NewPart... parts)
    {
        return divisionService.divide(id, null, null, List.of(parts));
    }

    private void assertRefusedUntouched(int id, List<NewPart> parts, String reason)
    {
        List<String> pages = pageFiles(id);
        long before = chapterRepository.count();

        assertThatThrownBy(() -> divisionService.divide(id, null, null, parts))
                .isInstanceOf(ChapterDivisionService.Refused.class)
                .hasMessageContaining(reason);

        assertThat(chapterRepository.count()).isEqualTo(before);
        assertThat(pageFiles(id)).isEqualTo(pages);
    }

    /** Each page holds text naming it, so tests can tell where it moved. */
    private int chapterWithPages(String titleFull, String... pages)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        int id = track(chapterService.create(form));
        try
        {
            Path dir = imageDirectory.chapterDir(id);
            Files.createDirectories(dir);
            for (String page : pages)
            {
                Files.writeString(dir.resolve(page), pageContent(id, page), StandardCharsets.UTF_8);
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
        chapterService.rescanImages(id);
        return id;
    }

    private void galleryWithQueueRow(int chapterId, String galleryId, String error)
    {
        var form = chapterService.toForm(chapterId);
        form.setGalleryId(galleryId);
        chapterService.update(form);
        var item = new DownloadQueueItem();
        item.setLink(galleryId);
        item.setGalleryId(galleryId);
        item.setChapterId(chapterId);
        item.setError(error);
        item.setReplacePages(error == null);
        queueRows.add(queueRepository.save(item).getId());
    }

    private static String pageContent(int chapterId, String page)
    {
        return "chapter " + chapterId + " page " + page;
    }

    private String content(int chapterId, String page) throws IOException
    {
        return Files.readString(imageDirectory.chapterDir(chapterId).resolve(page), StandardCharsets.UTF_8);
    }

    private List<String> pageFiles(int chapterId)
    {
        return imageDirectory.list(chapterId);
    }

    private Chapter chapter(int id)
    {
        return chapterRepository.findById(id).orElseThrow();
    }

    private int track(int chapterId)
    {
        chapters.add(chapterId);
        return chapterId;
    }

    private Tag tag(String name)
    {
        var tag = new Tag();
        tag.setName(name);
        return remember(tagRepository.save(tag));
    }

    private Artist artist(String name)
    {
        var artist = new Artist();
        artist.setName(name);
        return remember(artistRepository.save(artist));
    }

    private Character character(String name)
    {
        var character = new Character();
        character.setName(name);
        return remember(characterRepository.save(character));
    }

    private Parody parody(String name)
    {
        var parody = new Parody();
        parody.setName(name);
        return remember(parodyRepository.save(parody));
    }

    private Group group(String name)
    {
        var group = new Group();
        group.setName(name);
        return remember(groupRepository.save(group));
    }

    private <T extends Metadata> T remember(T item)
    {
        metadata.add(item);
        return item;
    }
}
