package io.github.mocchikon.hentie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.Artist;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Group;
import io.github.mocchikon.hentie.entity.Tag;
import io.github.mocchikon.hentie.repository.ArtistRepository;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.GroupRepository;
import io.github.mocchikon.hentie.repository.MetadataRuleRepository;
import io.github.mocchikon.hentie.repository.TagRepository;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.download.DownloadQueueService;
import io.github.mocchikon.hentie.service.download.DownloadWorker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import static org.assertj.core.api.Assertions.*;

/**
 * Metadata rules end to end through the download pipeline, from the source's free-text names to the
 * chapter's join rows. {@link MetadataRuleIT} covers the seam where rules are applied.
 * <p>
 * The worker thread is off in tests, so this calls {@link DownloadWorker#processNext()}, as its loop does.
 */
@SpringBootTest
@Transactional
class MetadataRuleDownloadIT
{
    private static final String NO_COMPRESSION = BuiltInCompressionMode.NONE.getKey();

    /** Matches {@code app.download.mock-dir} in the test profile. */
    private static final Path MOCK_DIR = Paths.get("./target/test-mock-server");

    @Autowired DownloadQueueService queueService;
    @Autowired DownloadWorker worker;
    @Autowired MetadataService metadataService;
    @Autowired MetadataRuleRepository ruleRepository;
    @Autowired ChapterRepository chapterRepository;
    @Autowired TagRepository tagRepository;
    @Autowired ArtistRepository artistRepository;
    @Autowired GroupRepository groupRepository;
    @Autowired ImageService imageService;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void resetMockSource() throws IOException
    {
        deleteRecursively(MOCK_DIR);
        worker.setPaused(false);
    }

    @Test
    void shouldNotBringBackADeletedTagWhenADownloadedGalleryCarriesIt() throws Exception
    {
        // GIVEN a tag the user deleted with a rule, and a gallery the source still tags with it.
        Tag removed = tag("rdl-deleted");
        metadataService.remove(MetadataType.TAG, removed.getId(), true);
        writeGallery("7100", "rdl-deleted", "rdl-kept", "rdl-artist", "rdl-group");
        em.flush();

        Integer chapterId = null;
        try
        {
            // WHEN the link is queued and the worker takes it.
            assertThat(queueService.enqueue(List.of("mock:7100"), NO_COMPRESSION, false).accepted()).isEqualTo(1);
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the chapter does not have it, and the tag was not re-created.
            Chapter chapter = chapterRepository.findByGalleryId("mock:7100").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getTags()).extracting("name").containsExactly("rdl-kept");
            assertThat(tagRepository.findByNameIgnoreCase("rdl-deleted")).isEmpty();
            // AND the rest of the gallery came through untouched.
            assertThat(chapter.getArtists()).extracting("name").containsExactly("rdl-artist");
            assertThat(chapter.getGroups()).extracting("name").containsExactly("rdl-group");
            assertThat(chapter.getTitleFull()).isEqualTo("[Test] Gallery 7100");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldUseTheMergeTargetWhenADownloadedGalleryCarriesTheMergedAwayName() throws Exception
    {
        // GIVEN two artists merged into one, remembered as a rule.
        Artist source = artist("rdl-merge-src");
        Artist target = artist("rdl-merge-dst");
        metadataService.merge(MetadataType.ARTIST, source.getId(), target.getId(), true);
        writeGallery("7101", "rdl-merge-tag", null, "rdl-merge-src", "rdl-merge-group");
        em.flush();

        Integer chapterId = null;
        try
        {
            // WHEN a gallery credited to the merged-away artist is downloaded.
            assertThat(queueService.enqueue(List.of("mock:7101"), NO_COMPRESSION, false).accepted()).isEqualTo(1);
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the chapter is credited to the surviving artist, not a re-created duplicate.
            Chapter chapter = chapterRepository.findByGalleryId("mock:7101").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getArtists()).extracting("id").containsExactly(target.getId());
            assertThat(chapter.getArtists()).extracting("name").containsExactly("rdl-merge-dst");
            assertThat(artistRepository.findByNameIgnoreCase("rdl-merge-src")).isEmpty();
            assertThat(chapter.getTags()).extracting("name").containsExactly("rdl-merge-tag");
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldUseTheRenamedGroupWhenADownloadedGalleryCarriesItsOldName() throws Exception
    {
        // GIVEN a group renamed, remembered as a rule, while the source still uses the old name.
        Group renamed = group("rdl-old-group");
        assertThat(metadataService.rename(MetadataType.GROUP, renamed.getId(), "rdl-new-group", true)).isEmpty();
        writeGallery("7102", "rdl-rename-tag", null, "rdl-rename-artist", "rdl-old-group");
        em.flush();

        Integer chapterId = null;
        try
        {
            // WHEN the gallery is downloaded.
            assertThat(queueService.enqueue(List.of("mock:7102"), NO_COMPRESSION, false).accepted()).isEqualTo(1);
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN it joins the renamed group instead of re-creating the old spelling.
            Chapter chapter = chapterRepository.findByGalleryId("mock:7102").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getGroups()).extracting("id").containsExactly(renamed.getId());
            assertThat(chapter.getGroups()).extracting("name").containsExactly("rdl-new-group");
            assertThat(groupRepository.findByNameIgnoreCase("rdl-old-group")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldCarryEveryNameThroughWhenNoRuleStandsAgainstIt() throws Exception
    {
        // GIVEN a rule for one kind only, and no rule at all for the names below.
        Tag removed = tag("rdl-unrelated");
        metadataService.remove(MetadataType.TAG, removed.getId(), true);
        writeGallery("7103", "rdl-plain-tag", null, "rdl-plain-artist", "rdl-plain-group");
        em.flush();

        Integer chapterId = null;
        try
        {
            // WHEN a gallery none of it applies to is downloaded.
            assertThat(queueService.enqueue(List.of("mock:7103"), NO_COMPRESSION, false).accepted()).isEqualTo(1);
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN everything arrives: the control that keeps the tests above from passing because the
            // import stopped working.
            Chapter chapter = chapterRepository.findByGalleryId("mock:7103").orElseThrow();
            chapterId = chapter.getId();
            assertThat(chapter.getTags()).extracting("name").containsExactly("rdl-plain-tag");
            assertThat(chapter.getArtists()).extracting("name").containsExactly("rdl-plain-artist");
            assertThat(chapter.getGroups()).extracting("name").containsExactly("rdl-plain-group");
            assertThat(tagRepository.findByNameIgnoreCase("rdl-plain-tag")).isPresent();
            assertThat(ruleRepository.findByTypeAndSourceNameLower(MetadataType.TAG, "rdl-plain-tag")).isEmpty();
        }
        finally
        {
            cleanUp(chapterId);
        }
    }

    @Test
    void shouldApplyTheRuleAgainWhenTheSameGalleryIsDownloadedAfterTheChapterIsGone() throws Exception
    {
        // GIVEN a gallery downloaded before the user ruled one of its tags out.
        writeGallery("7104", "rdl-later-blocked", "rdl-later-kept", "rdl-later-artist", "rdl-later-group");
        assertThat(queueService.enqueue(List.of("mock:7104"), NO_COMPRESSION, false).accepted()).isEqualTo(1);
        assertThat(worker.processNext()).isTrue();
        em.flush();

        Integer firstId = chapterRepository.findByGalleryId("mock:7104").orElseThrow().getId();
        Integer secondId = null;
        try
        {
            // The tag arrived with that download; the user now deletes it and remembers the decision.
            Tag arrived = tagRepository.findByNameIgnoreCase("rdl-later-blocked").orElseThrow();
            metadataService.remove(MetadataType.TAG, arrived.getId(), true);
            em.flush();

            // WHEN the user deletes the chapter and pastes the same link again.
            chapterRepository.deleteById(firstId);
            em.flush();
            assertThat(queueService.enqueue(List.of("mock:7104"), NO_COMPRESSION, false).accepted()).isEqualTo(1);
            assertThat(worker.processNext()).isTrue();
            em.flush();

            // THEN the re-download honours the rule: the chapter comes back without that tag, and the tag
            // is still gone from the library.
            Chapter again = chapterRepository.findByGalleryId("mock:7104").orElseThrow();
            secondId = again.getId();
            assertThat(again.getTags()).extracting("name").containsExactly("rdl-later-kept");
            assertThat(tagRepository.findByNameIgnoreCase("rdl-later-blocked")).isEmpty();
        }
        finally
        {
            cleanUp(firstId);
            cleanUp(secondId);
        }
    }

    /** A one-page mock gallery; {@code secondTag} is optional, to show a neighbour of a ruled-out name is kept. */
    private static void writeGallery(String id, String tag, String secondTag, String artist, String group)
            throws IOException
    {
        Path gallery = MOCK_DIR.resolve("galleries").resolve(id);
        Files.createDirectories(gallery);
        Files.writeString(gallery.resolve("1.webp"), "page 1 of " + id);

        String tags = "{\"type\": \"tag\", \"name\": \"" + tag + "\"}"
                + (secondTag == null ? "" : ",{\"type\": \"tag\", \"name\": \"" + secondTag + "\"}");
        String json = """
                {
                  "id": %s,
                  "media_id": "%s",
                  "title": {
                    "english": "[Test] Gallery %s",
                    "japanese": "%s in Japanese",
                    "pretty": "Gallery %s"
                  },
                  "tags": [
                    {"type": "language", "name": "english"},
                    %s,
                    {"type": "artist", "name": "%s"},
                    {"type": "group", "name": "%s"}
                  ],
                  "pages": [{"number": 1, "path": "galleries/%s/1.webp"}]
                }
                """.formatted(id, id, id, id, id, tags, artist, group, id);
        Files.writeString(MOCK_DIR.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }

    private Tag tag(String name)
    {
        Tag t = new Tag();
        t.setName(name);
        return tagRepository.save(t);
    }

    private Artist artist(String name)
    {
        Artist a = new Artist();
        a.setName(name);
        return artistRepository.save(a);
    }

    private Group group(String name)
    {
        Group g = new Group();
        g.setName(name);
        return groupRepository.save(g);
    }

    /** Files on disk are not rolled back with the transaction. */
    private void cleanUp(Integer chapterId)
    {
        if (chapterId != null)
        {
            imageService.discardStagedPages(chapterId);
            imageService.deleteAll(chapterId);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException
    {
        if (!Files.isDirectory(dir))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }
}
