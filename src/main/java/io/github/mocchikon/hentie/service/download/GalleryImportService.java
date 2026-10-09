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
    public Map<String, Holding> holdings(Collection<String> galleryIds)
    {
        if (galleryIds.isEmpty())
        {
            return Map.of();
        }
        var holdings = new HashMap<String, Holding>();
        for (var chapter : chapterRepository.findDownloadStatusByGalleryIdIn(galleryIds))
        {
            holdings.put(chapter.getGalleryId(), chapter.getDownloadStatus() == DownloadStatus.PENDING
                    ? Holding.UNFINISHED : Holding.IN_LIBRARY);
        }
        for (DownloadedGallery downloaded : downloadedGalleryRepository.findAllById(galleryIds))
        {
            holdings.putIfAbsent(downloaded.getGalleryId(), Holding.DELETED);
        }
        return holdings;
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
     * Catches the same work from two sites, which the gallery-id check cannot see. Galleries of one source
     * are never compared: a source can hold two with one title. A chapter with no gallery id counts as
     * another source. Must run before {@link #importChapter}, which creates new metadata rows a refused
     * gallery must not leave behind.
     *
     * @throws PermanentDownloadException naming the chapter that holds the title
     */
    @Transactional(readOnly = true)
    public void rejectTitleFromOtherSources(GalleryData data, ResourceLink link)
    {
        String titleFull = requireTitle(data, link.galleryId());
        var clashes = chapterRepository.findTitleFromOtherSources(titleFull, link.galleryIdPrefix(), Limit.of(1));
        if (!clashes.isEmpty())
        {
            var clash = clashes.getFirst();
            String source = clash.getGalleryId() == null ? "added without a gallery id" : "from " + clash.getGalleryId();
            throw new PermanentDownloadException("Chapter " + clash.getId() + " (" + source + ") already has the title \""
                    + titleFull + "\", and this link was queued to avoid duplicated titles from other sources.");
        }
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

    /** Idempotent, so a resumed item does not fail; the first download's timestamp is kept. */
    @Transactional
    public void recordDownloaded(String galleryId, int chapterId)
    {
        if (downloadedGalleryRepository.existsById(galleryId))
        {
            return;
        }
        var downloaded = new DownloadedGallery();
        downloaded.setGalleryId(galleryId);
        downloaded.setChapterId(chapterId);
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
        if (LanguageService.isKnown(data.getLanguage()))
        {
            return LanguageService.canonical(data.getLanguage());
        }
        log.info("Gallery {} has no language the app knows ({}); saving it as {}", galleryId, data.getLanguage(),
                EhTags.DEFAULT_LANGUAGE);
        return EhTags.DEFAULT_LANGUAGE;
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
