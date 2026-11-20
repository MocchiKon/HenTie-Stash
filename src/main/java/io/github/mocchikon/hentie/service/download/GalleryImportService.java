package io.github.mocchikon.hentie.service.download;

import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.entity.DownloadedGallery;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.DownloadedGalleryRepository;
import io.github.mocchikon.hentie.scrapper.EhTags;
import io.github.mocchikon.hentie.scrapper.GalleryData;
import io.github.mocchikon.hentie.scrapper.ResourceLink;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.LanguageService;
import io.github.mocchikon.hentie.service.MetadataService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Goes through {@link ChapterService#create} rather than saving a {@code Chapter} itself, so a downloaded
 * chapter is built by the same code as a hand-added one and the two cannot drift apart.
 */
@Service
@RequiredArgsConstructor
public class GalleryImportService
{
    private static final Logger log = LoggerFactory.getLogger(GalleryImportService.class);

    private final ChapterRepository chapterRepository;
    private final DownloadedGalleryRepository downloadedGalleryRepository;
    private final ChapterService chapterService;
    private final MetadataService metadataService;

    /**
     * A chapter found here is never updated, so a re-run cannot overwrite the user's edits. Its
     * {@link DownloadStatus} decides whether its images may be touched.
     */
    @Transactional(readOnly = true)
    public Optional<ExistingChapter> findChapter(String galleryId)
    {
        return chapterRepository.findByGalleryId(galleryId)
                .map(chapter -> new ExistingChapter(chapter.getId(), chapter.getDownloadStatus(),
                        chapter.getCompressionMode()));
    }

    @Transactional(readOnly = true)
    public Optional<Integer> findChapterId(String galleryId)
    {
        return chapterRepository.findByGalleryId(galleryId).map(Chapter::getId);
    }

    public record ExistingChapter(int id, DownloadStatus downloadStatus, String compressionMode)
    {
    }

    /**
     * What the library already has of these galleries, for a bulk queueing that must not fetch what is here.
     * A gallery it never had is absent from the map.
     */
    @Transactional(readOnly = true)
    public Map<String, Held> holdings(Collection<String> galleryIds)
    {
        if (galleryIds.isEmpty())
        {
            return Map.of();
        }
        var holdings = new HashMap<String, Held>();
        for (var chapter : chapterRepository.findDownloadStatusByGalleryIdIn(galleryIds))
        {
            holdings.put(chapter.getGalleryId(), new Held(chapter.getDownloadStatus() == DownloadStatus.PENDING
                    ? Holding.UNFINISHED : Holding.IN_LIBRARY, chapter.getId(), chapter.getCompressionMode()));
        }
        for (DownloadedGallery downloaded : downloadedGalleryRepository.findAllById(galleryIds))
        {
            holdings.putIfAbsent(downloaded.getGalleryId(), new Held(Holding.DELETED, null, null));
        }
        return holdings;
    }

    /**
     * @param chapterId       null for {@link Holding#DELETED}
     * @param compressionMode the chapter's: what an upgrade has to beat
     */
    public record Held(Holding holding, Integer chapterId, String compressionMode)
    {
        public boolean is(Holding other)
        {
            return holding == other;
        }
    }

    public enum Holding
    {
        /** Its download never finished, so queueing it fetches what is missing. */
        UNFINISHED,
        /** Downloaded, or added by hand. */
        IN_LIBRARY,
        /** Downloaded once, and its chapter deleted since. */
        DELETED
    }

    /**
     * Catches the same work from two sites, which the gallery-id check cannot see: the same full title in the same
     * language. Both the library and the permanent record of past downloads are checked, so a gallery deleted after
     * a download from another source does not come back through this one. Galleries of one source are never
     * compared: a source can hold two with one title. A chapter with no gallery id counts as another source. Must
     * run before {@link #importChapter}, which creates new metadata rows a refused gallery must not leave behind.
     *
     * @return what holds the title and language, or empty when nothing does
     * @throws PermanentDownloadException when the gallery has no title
     */
    @Transactional(readOnly = true)
    public Optional<DuplicateTitle> findDuplicateTitle(GalleryData data, ResourceLink link)
    {
        String titleFull = requireTitle(data, link.galleryId());
        String language = knownLanguageOf(data);
        var clashes = chapterRepository.findTitleFromOtherSources(titleFull, language, link.galleryIdPrefix(),
                Limit.of(1));
        if (!clashes.isEmpty())
        {
            var clash = clashes.getFirst();
            String source = clash.getGalleryId() == null ? "added without a gallery id" : "from " + clash.getGalleryId();
            return Optional.of(new DuplicateTitle(clash.getId(), true, "Chapter " + clash.getId() + " (" + source
                    + ") already has the title \"" + titleFull + "\" in " + language));
        }
        var pastDownloads = downloadedGalleryRepository.findTitleFromOtherSources(titleFull, language,
                link.galleryIdPrefix(), Limit.of(1));
        if (!pastDownloads.isEmpty())
        {
            var past = pastDownloads.getFirst();
            // Its chapter may name one deleted since; only a link to a chapter that still opens is offered.
            Integer chapterId = chapterRepository.existsById(past.getChapterId()) ? past.getChapterId() : null;
            return Optional.of(new DuplicateTitle(chapterId, false, "Gallery " + past.getGalleryId()
                    + " was downloaded before from another source with the title \"" + titleFull + "\" in "
                    + language));
        }
        return Optional.empty();
    }

    /**
     * What the duplicate-title check found.
     *
     * @param chapterId the chapter to link to, or null when a past download's chapter is gone
     * @param mergeable whether that chapter itself has the title and language, so the gallery's metadata may be
     *                  merged into it. Not for a past download: its chapter may have been retitled since, and then it
     *                  is not the same work any more.
     * @param reason    a sentence without its full stop, naming what clashed
     */
    public record DuplicateTitle(Integer chapterId, boolean mergeable, String reason)
    {
    }

    /**
     * Gives a duplicate's metadata to the chapter that has its title, in place of downloading it. The names go
     * through {@link MetadataService#resolveOrCreate} as an import's do, so the metadata rules, aliases and plain
     * tags apply; nothing the chapter has is removed. Created rows commit only with the merge.
     *
     * @return false when the chapter is gone, so nothing was merged
     */
    @Transactional
    public boolean mergeMetadata(GalleryData data, int chapterId)
    {
        var additions = new ChapterForm();
        additions.setTagIds(metadataService.resolveOrCreate(MetadataType.TAG, names(data.getTags())));
        additions.setArtistIds(metadataService.resolveOrCreate(MetadataType.ARTIST, names(data.getArtists())));
        additions.setCharacterIds(metadataService.resolveOrCreate(MetadataType.CHARACTER, names(data.getCharacters())));
        additions.setParodyIds(metadataService.resolveOrCreate(MetadataType.PARODY, names(data.getParodies())));
        additions.setGroupIds(metadataService.resolveOrCreate(MetadataType.GROUP, names(data.getGroups())));
        additions.setCategoryIds(metadataService.resolveOrCreate(MetadataType.CATEGORY, names(data.getCategories())));
        return chapterService.addMetadata(chapterId, additions);
    }

    /**
     * @throws PermanentDownloadException when the gallery has no title
     */
    @Transactional
    public int importChapter(GalleryData data, String galleryId)
    {
        var form = new ChapterForm();
        form.setTitleFull(requireTitle(data, galleryId));
        form.setTitle(clean(data.getPrettyTitle()));           // blank -> derived from titleFull
        form.setNativeTitle(clean(data.getJapaneseTitle()));
        form.setLanguage(languageOf(data, galleryId));
        form.setGalleryId(galleryId);
        form.setStatus(Status.NEW);
        form.setTagIds(metadataService.resolveOrCreate(MetadataType.TAG, names(data.getTags())));
        form.setArtistIds(metadataService.resolveOrCreate(MetadataType.ARTIST, names(data.getArtists())));
        form.setCharacterIds(metadataService.resolveOrCreate(MetadataType.CHARACTER, names(data.getCharacters())));
        form.setParodyIds(metadataService.resolveOrCreate(MetadataType.PARODY, names(data.getParodies())));
        form.setGroupIds(metadataService.resolveOrCreate(MetadataType.GROUP, names(data.getGroups())));
        form.setCategoryIds(metadataService.resolveOrCreate(MetadataType.CATEGORY, names(data.getCategories())));
        int chapterId = chapterService.create(form);
        // PENDING from the moment the row exists, so after a crash the next run may finish it.
        chapterService.setDownloadStatus(chapterId, DownloadStatus.PENDING);
        return chapterId;
    }

    /**
     * Idempotent, so a resumed item does not fail; the first download's timestamp is kept. Its title and language
     * come from the chapter, which is what the duplicate-title check compares a new gallery against after the
     * chapter is deleted.
     */
    @Transactional
    public void recordDownloaded(String galleryId, int chapterId)
    {
        if (downloadedGalleryRepository.existsById(galleryId))
        {
            return;
        }
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new IllegalStateException("Chapter " + chapterId + " of " + galleryId
                        + " was deleted before its download could be recorded."));
        var downloaded = new DownloadedGallery();
        downloaded.setGalleryId(galleryId);
        downloaded.setChapterId(chapterId);
        downloaded.setTitleFull(chapter.getTitleFull());
        downloaded.setLanguage(chapter.getLanguage());
        downloaded.setDownloadedAt(LocalDateTime.now());
        downloadedGalleryRepository.save(downloaded);
    }

    /** The pretty and native titles stand in for a missing full one. */
    private static String requireTitle(GalleryData data, String galleryId)
    {
        String title = StringUtils.firstNonBlank(data.getFullTitle(), data.getPrettyTitle(), data.getJapaneseTitle());
        if (StringUtils.isBlank(title))
        {
            throw new PermanentDownloadException("Gallery " + galleryId + " has no title to save it under.");
        }
        return title;
    }

    /**
     * A language the app does not know is taken for Japanese, what e-hentai takes a gallery without one for.
     * Refusing it would keep the gallery from ever being downloaded; keeping it as sent would make it a facet of
     * its own, since language is searched by exact value.
     */
    private static String languageOf(GalleryData data, String galleryId)
    {
        if (!LanguageService.isKnown(data.getLanguage()))
        {
            log.info("Gallery {} has no language the app knows ({}); saving it as {}", galleryId, data.getLanguage(),
                    EhTags.DEFAULT_LANGUAGE);
        }
        return knownLanguageOf(data);
    }

    /** The language {@link #importChapter} saves, so the duplicate-title check compares what would be stored. */
    private static String knownLanguageOf(GalleryData data)
    {
        return LanguageService.isKnown(data.getLanguage())
                ? LanguageService.canonical(data.getLanguage()) : EhTags.DEFAULT_LANGUAGE;
    }

    private static Collection<String> names(Set<String> values)
    {
        return values == null ? Set.of() : values;
    }

    /** The scraper's literal {@code "null"} means "not set", like blank. */
    private static String clean(String value)
    {
        String trimmed = StringUtils.trimToNull(value);
        return "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }
}
