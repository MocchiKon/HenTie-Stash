package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.*;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.entity.Metadata;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.mapper.EntityMapper;
import io.github.mocchikon.hentie.repository.*;
import io.github.mocchikon.hentie.service.match.ChapterMatchingService;
import io.github.mocchikon.hentie.service.match.TitleKey;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ChapterService
{
    private static final Logger log = LoggerFactory.getLogger(ChapterService.class);

    private final ChapterRepository chapterRepository;
    private final ImageService imageService;
    private final TagRepository tagRepository;
    private final ArtistRepository artistRepository;
    private final CharacterRepository characterRepository;
    private final ParodyRepository parodyRepository;
    private final GroupRepository groupRepository;
    private final CategoryRepository categoryRepository;
    private final EntityMapper entityMapper;
    private final SeriesService seriesService;
    private final ChapterMatchingService chapterMatchingService;
    private final SearchCountCache searchCountCache;
    private final ChapterRemoval chapterRemoval;

    @Transactional(readOnly = true)
    public Chapter get(int id)
    {
        return chapterRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found"));
    }

    @Transactional(readOnly = true)
    public ChapterForm toForm(int id)
    {
        return entityMapper.toForm(get(id));
    }

    /**
     * Read-only, so a page view never waits for the write lock. Repairing the stats it reports is the caller's job
     * ({@link ImageStatsService#healIfDrifted}).
     */
    @Transactional(readOnly = true)
    public ChapterViewModel buildView(int id)
    {
        Chapter chapter = get(id);
        List<String> pageUrls = imageService.pageUrls(id);

        List<ChipGroup> groups = new ArrayList<>();
        addGroup(groups, "Tags", chapter.getTags(), MetadataType.TAG);
        addGroup(groups, "Artists", chapter.getArtists(), MetadataType.ARTIST);
        addGroup(groups, "Characters", chapter.getCharacters(), MetadataType.CHARACTER);
        addGroup(groups, "Parodies", chapter.getParodies(), MetadataType.PARODY);
        addGroup(groups, "Groups", chapter.getGroups(), MetadataType.GROUP);
        addGroup(groups, "Categories", chapter.getCategories(), MetadataType.CATEGORY);
        if (StringUtils.isNotBlank(chapter.getLanguage()))
        {
            ChipDto chip = new ChipDto(chapter.getLanguage(),
                    href(SearchType.CHAPTER, "languages", chapter.getLanguage()), null);
            groups.add(new ChipGroup("Language", List.of(chip), false));
        }

        Integer seriesId = chapter.getSeries() == null ? null : chapter.getSeries().getId();
        Neighbours neighbours = neighboursOf(chapter);

        return ChapterViewModel.builder()
                .chapter(chapter)
                .thumbnailUrl(imageService.thumbnailUrl(id))
                .detailGroups(groups)
                .pageUrls(pageUrls)
                .pageCount(chapter.getPageNum())
                .diskSizeDisplay(ImageService.humanReadableSize(chapter.getDiskSize()))
                .statusDisplay(chapter.getStatus() == null ? null : chapter.getStatus().getDisplayName())
                .seriesId(seriesId)
                .prevChapterId(neighbours.previous())
                .nextChapterId(neighbours.next())
                .build();
    }

    /** Null for none. */
    public record Neighbours(Integer previous, Integer next)
    {
        private static final Neighbours NONE = new Neighbours(null, null);
    }

    /** What the reader is told about the chapter it moves to. */
    public record Neighbour(int id, String title)
    {
    }

    /** {@code storedPageNum} is not for the reader: it lets the caller check the stats without loading the row again. */
    public record NeighbourChapter(Neighbour neighbour, Integer storedPageNum)
    {
    }

    /**
     * Shares {@link #neighboursOf} with the detail page, so the two cannot disagree. The stored page count comes
     * along because this is the one route into the reader that passes through no detail page, so the caller repairs
     * the stats here.
     */
    @Transactional(readOnly = true)
    public Optional<NeighbourChapter> neighbour(int id, boolean forward)
    {
        Neighbours neighbours = neighboursOf(get(id));
        Integer targetId = forward ? neighbours.next() : neighbours.previous();
        if (targetId == null)
        {
            return Optional.empty();
        }
        Chapter target = get(targetId);
        return Optional.of(new NeighbourChapter(new Neighbour(target.getId(), target.getTitle()), target.getPageNum()));
    }

    /**
     * Previous and next stay within the same series <b>and</b> the same language, so a series holding every
     * language of a work reads on in the one the reader chose.
     */
    private Neighbours neighboursOf(Chapter chapter)
    {
        if (chapter.getSeries() == null)
        {
            return Neighbours.NONE;
        }
        String language = chapter.getLanguage() == null ? "" : chapter.getLanguage();
        List<Chapter> siblings = chapterRepository.findBySeriesIdAndLanguageOrderByChapterNumAscIdAsc(
                chapter.getSeries().getId(), language);
        for (int i = 0; i < siblings.size(); i++)
        {
            if (siblings.get(i).getId().equals(chapter.getId()))
            {
                return new Neighbours(i > 0 ? siblings.get(i - 1).getId() : null,
                        i < siblings.size() - 1 ? siblings.get(i + 1).getId() : null);
            }
        }
        return Neighbours.NONE;
    }

    /** {@code chapterNum} stays null until a series operation assigns it. */
    @CacheEvict(value = {CacheConfig.LANGUAGES, CacheConfig.SEARCH_COUNT}, allEntries = true)
    @Transactional
    public int create(ChapterForm form)
    {
        String galleryId = StringUtils.trimToNull(form.getGalleryId());
        if (galleryId != null && chapterRepository.existsByGalleryId(galleryId))
        {
            throw new DuplicateValueException("galleryId", "That gallery ID is already in use, it must be unique.");
        }
        validateLanguage(form.getLanguage());

        Chapter chapter = new Chapter();
        chapter.setTitleFull(form.getTitleFull());
        chapter.setNativeTitle(form.getNativeTitle());
        TitleKey key = applyMatchKeys(chapter);
        chapter.setTitle(prettyTitle(form, key));
        chapter.setUploadDate(LocalDate.now());   // never user-editable
        chapter.setStatus(form.getStatus() != null ? form.getStatus() : Status.NEW);
        chapter.setGalleryId(galleryId);
        chapter.setLanguage(LanguageService.canonical(form.getLanguage()));
        chapter.setScore(toDbScore(form.getScore()));

        chapter.setTags(tagRepository.findAllById(form.getTagIds()));
        chapter.setArtists(artistRepository.findAllById(form.getArtistIds()));
        chapter.setCharacters(characterRepository.findAllById(form.getCharacterIds()));
        chapter.setParodies(parodyRepository.findAllById(form.getParodyIds()));
        chapter.setGroups(groupRepository.findAllById(form.getGroupIds()));
        chapter.setCategories(categoryRepository.findAllById(form.getCategoryIds()));

        chapterRepository.save(chapter);

        // Here rather than in the controller, so every way of adding a chapter inherits it.
        chapterMatchingService.autoLink(chapter, key);
        return chapter.getId();
    }

    @CacheEvict(value = {CacheConfig.LANGUAGES, CacheConfig.SEARCH_COUNT}, allEntries = true)
    @Transactional
    public void update(ChapterForm form)
    {
        String galleryId = StringUtils.trimToNull(form.getGalleryId());
        if (galleryId != null && chapterRepository.existsByGalleryIdAndIdNot(galleryId, form.getId()))
        {
            throw new DuplicateValueException("galleryId", "That gallery ID is already in use, it must be unique.");
        }
        validateLanguage(form.getLanguage());

        Chapter chapter = get(form.getId());
        chapter.setTitleFull(form.getTitleFull());
        chapter.setNativeTitle(form.getNativeTitle());
        // A cleared pretty title is re-derived, not blanked: the column is NOT NULL.
        chapter.setTitle(prettyTitle(form, applyMatchKeys(chapter)));
        chapter.setStatus(form.getStatus() != null ? form.getStatus() : Status.NEW);   // status is NOT NULL
        chapter.setGalleryId(galleryId);
        chapter.setLanguage(LanguageService.canonical(form.getLanguage()));
        chapter.setScore(toDbScore(form.getScore()));

        chapter.setTags(tagRepository.findAllById(form.getTagIds()));
        chapter.setArtists(artistRepository.findAllById(form.getArtistIds()));
        chapter.setCharacters(characterRepository.findAllById(form.getCharacterIds()));
        chapter.setParodies(parodyRepository.findAllById(form.getParodyIds()));
        chapter.setGroups(groupRepository.findAllById(form.getGroupIds()));
        chapter.setCategories(categoryRepository.findAllById(form.getCategoryIds()));

        chapterRepository.save(chapter);

        Integer seriesId = chapter.getSeries() == null ? null : chapter.getSeries().getId();
        seriesService.recomputeDerived(seriesId);
    }

    /**
     * The pretty title is derived again even if it was typed: it named what the chapter held before. The
     * chapter is not matched again and stays in its series, as after an edit.
     */
    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public void retitle(int id, String titleFull)
    {
        Chapter chapter = get(id);
        chapter.setTitleFull(titleFull);
        chapter.setTitle(applyMatchKeys(chapter).getPrettyTitle());
        chapterRepository.save(chapter);
    }

    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public void delete(int id)
    {
        Chapter chapter = get(id);
        Integer seriesId = chapter.getSeries() == null ? null : chapter.getSeries().getId();
        chapterRepository.delete(chapter);
        // Emptiness first, so a series about to go is not re-materialized for nothing.
        if (!seriesService.deleteIfEmpty(seriesId))
        {
            seriesService.recomputeDerived(seriesId);
        }
        chapterRemoval.finish(List.of(chapter));
    }

    /**
     * Each touched series is handled once per batch, not once per chapter: a page of results is often one
     * family. Only series that keep a chapter are recomputed; re-materializing one right before deleting it
     * would be pure waste. Ids that no longer exist are skipped.
     */
    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public int deleteAll(Collection<Integer> ids)
    {
        List<Chapter> chapters = chapterRepository.findAllById(ids);
        Set<Integer> seriesIds = new LinkedHashSet<>();
        for (Chapter chapter : chapters)
        {
            if (chapter.getSeries() != null)
            {
                seriesIds.add(chapter.getSeries().getId());
            }
        }
        chapterRepository.deleteAll(chapters);
        if (!seriesIds.isEmpty())
        {
            Set<Integer> stillHoldChapters = new HashSet<>();
            for (Object[] row : chapterRepository.countBySeriesIds(seriesIds))
            {
                stillHoldChapters.add((Integer) row[0]);
            }
            for (Integer seriesId : seriesIds)
            {
                if (stillHoldChapters.contains(seriesId))
                {
                    seriesService.recomputeDerived(seriesId);
                }
                else
                {
                    seriesService.deleteIfEmpty(seriesId);
                }
            }
        }
        chapterRemoval.finish(chapters);
        return chapters.size();
    }

    /**
     * Only ever promotes {@code NEW}: a review page left open in another tab must not demote a chapter
     * meanwhile made a favourite. Evicts only when the status really changed.
     */
    @Transactional
    public void markReviewed(int id)
    {
        Chapter chapter = get(id);
        if (chapter.getStatus() == Status.NEW)
        {
            chapter.setStatus(Status.REVIEWED);
            chapterRepository.save(chapter);
            searchCountCache.clear();
        }
    }

    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public void deletePage(int id, String filename) throws IOException
    {
        imageService.deletePage(id, filename);
        syncImageStats(id);
    }

    /**
     * Not on {@code ChapterForm}: it is the pipeline's bookkeeping, and an edit must never promote a
     * half-downloaded chapter to complete.
     */
    @Transactional
    public void setDownloadStatus(int id, DownloadStatus status)
    {
        Chapter chapter = get(id);
        if (chapter.getDownloadStatus() != status)
        {
            chapter.setDownloadStatus(status);
            chapterRepository.save(chapter);
        }
    }

    /**
     * Null means the pages are as the source serves them. <b>Anything that re-encodes a chapter's pages must
     * record it here</b>, or the chapter is shown, and never offered back, as full quality.
     * <p>
     * A chapter deleted meanwhile is skipped, so a finished download or upload is not reported as failed.
     */
    @Transactional
    public void setCompressionMode(int id, String modeKey)
    {
        chapterRepository.findById(id)
                .filter(chapter -> !Objects.equals(chapter.getCompressionMode(), modeKey))
                .ifPresent(chapter ->
                {
                    chapter.setCompressionMode(modeKey);
                    chapterRepository.save(chapter);
                });
    }

    /**
     * What a resync of one chapter found. Unreadable is told apart from in sync, because a caller that would re-read
     * its listing after an in-sync answer gains nothing from it when the folder could not be read.
     */
    public enum StatsSync
    {
        REPAIRED,
        IN_SYNC,
        /** The folder could not be read, so the stored numbers were kept. */
        UNREADABLE
    }

    /** Nothing is written or evicted unless the stats had drifted. */
    @Transactional
    public StatsSync syncImageStats(int id)
    {
        return syncOne(get(id));
    }

    /** Private on purpose: a self-invoked {@code @Transactional} method would ignore its annotation. */
    private StatsSync syncOne(Chapter chapter)
    {
        StatsSync outcome = applyImageStats(chapter);
        if (outcome == StatsSync.REPAIRED)
        {
            seriesService.recomputeDerived(chapter.getSeries() == null ? null : chapter.getSeries().getId());
            searchCountCache.clear();
        }
        return outcome;
    }

    /** Each affected series is recomputed once per slice. */
    @Transactional
    public int syncImageStats(List<Integer> ids)
    {
        int repaired = 0;
        Set<Integer> affectedSeries = new LinkedHashSet<>();
        for (Chapter chapter : chapterRepository.findAllById(ids))
        {
            if (applyImageStats(chapter) == StatsSync.REPAIRED)
            {
                repaired++;
                if (chapter.getSeries() != null)
                {
                    affectedSeries.add(chapter.getSeries().getId());
                }
            }
        }
        affectedSeries.forEach(seriesService::recomputeDerived);
        if (repaired > 0)
        {
            searchCountCache.clear();
        }
        return repaired;
    }

    /**
     * Drops the cached listing unconditionally: the page rendered afterwards must show the files too, even a
     * page overwritten in place under the same name, which no timestamp reveals.
     */
    @Transactional
    public boolean rescanImages(int id)
    {
        // The stats leave a superseded source out, so left on disk it would take space disk_size never reports.
        imageService.removeSupersededPages(id);
        imageService.invalidateListing(id);
        return syncImageStats(id) == StatsSync.REPAIRED;
    }

    /**
     * Reads the disk uncached ({@link ImageService#scanStats(int)}): the listing cache is invalidated only by
     * the app's own writes, so a repair through it would recompute the numbers it already had.
     * <p>
     * An unreadable directory leaves the stored numbers alone: writing a 0 the disk never reported would drop
     * the chapter out of every page-count filter for good. It also keeps one bad directory from aborting a
     * bulk resync.
     */
    private StatsSync applyImageStats(Chapter chapter)
    {
        int id = chapter.getId();
        PageStats stats;
        try
        {
            stats = imageService.scanStats(id);
        }
        catch (UncheckedIOException e)
        {
            log.warn("Chapter {}: could not read its image directory, leaving page count {} and disk size {} "
                    + "as they are", id, chapter.getPageNum(), ImageService.humanReadableSize(chapter.getDiskSize()), e);
            return StatsSync.UNREADABLE;
        }
        if (Objects.equals(chapter.getPageNum(), stats.pageCount())
                && Objects.equals(chapter.getDiskSize(), stats.diskSize()))
        {
            return StatsSync.IN_SYNC;
        }
        chapter.setPageNum(stats.pageCount());
        chapter.setDiskSize(stats.diskSize());
        chapterRepository.save(chapter);
        // A listing cached before this write may be behind the disk too.
        imageService.invalidateListing(id);
        return StatsSync.REPAIRED;
    }

    /**
     * A blank title falls back to {@code titleFull} without its bracket groups but with its numbering
     * ({@code "Comic Hero 2024-06 [Digital]"} -&gt; {@code "Comic Hero 2024-06"}): the number is how a reader
     * tells chapters apart.
     */
    private static String prettyTitle(ChapterForm form, TitleKey key)
    {
        return StringUtils.isNotBlank(form.getTitle()) ? form.getTitle() : key.getPrettyTitle();
    }

    /**
     * Both keys, from the titles already set. Returns the parse, so a caller that also matches the chapter does
     * not parse the title twice.
     */
    private static TitleKey applyMatchKeys(Chapter chapter)
    {
        var key = TitleKey.of(chapter.getTitleFull());
        chapter.setMatchKey(key.getMatchKey());
        chapter.setNativeMatchKey(TitleKey.nativeKey(chapter.getNativeTitle(), key));
        return key;
    }

    private void addGroup(List<ChipGroup> groups, String title, List<? extends Metadata> items, MetadataType type)
    {
        if (items == null || items.isEmpty())
        {
            return;
        }
        Set<String> covered = type == MetadataType.TAG
                ? MetadataService.plainTagsCoveredBy(items.stream().map(Metadata::getName).toList())
                : Set.of();
        List<ChipDto> chips = new ArrayList<>();
        for (Metadata item : items)
        {
            if (covered.contains(item.getName()))
            {
                continue;
            }
            chips.add(new ChipDto(item.getName(),
                    href(SearchType.CHAPTER, type.getSearchParam(), String.valueOf(item.getId())), null));
        }
        groups.add(new ChipGroup(title, chips, false));
    }

    static String href(SearchType type, String param, String value)
    {
        return "/search/results?type=" + type.name() + "&" + param + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Clamped to 1-10; null stays null. */
    static Short toDbScore(Integer score)
    {
        if (score == null)
        {
            return null;
        }
        return (short) Math.clamp(score, 1, 10);
    }

    private static void validateLanguage(String language)
    {
        if (!LanguageService.isKnown(language))
        {
            throw new DuplicateValueException("language", "Please select a language from the suggestions list.");
        }
    }
}
