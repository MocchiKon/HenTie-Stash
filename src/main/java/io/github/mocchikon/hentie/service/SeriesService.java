package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.*;
import io.github.mocchikon.hentie.entity.*;
import io.github.mocchikon.hentie.entity.Character;
import io.github.mocchikon.hentie.mapper.EntityMapper;
import io.github.mocchikon.hentie.repository.*;
import io.github.mocchikon.hentie.service.match.TitleKey;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class SeriesService
{
    private final SeriesRepository seriesRepository;
    private final ChapterRepository chapterRepository;
    private final ImageService imageService;
    private final TagRepository tagRepository;
    private final ArtistRepository artistRepository;
    private final CharacterRepository characterRepository;
    private final ParodyRepository parodyRepository;
    private final GroupRepository groupRepository;
    private final CategoryRepository categoryRepository;
    private final EntityMapper entityMapper;
    private final CacheManager cacheManager;
    private final ChapterRemoval chapterRemoval;

    public SeriesService(SeriesRepository seriesRepository, ChapterRepository chapterRepository,
                         ImageService imageService, TagRepository tagRepository,
                         ArtistRepository artistRepository, CharacterRepository characterRepository,
                         ParodyRepository parodyRepository, GroupRepository groupRepository,
                         CategoryRepository categoryRepository, EntityMapper entityMapper, CacheManager cacheManager, ChapterRemoval chapterRemoval)
    {
        this.seriesRepository = seriesRepository;
        this.chapterRepository = chapterRepository;
        this.imageService = imageService;
        this.tagRepository = tagRepository;
        this.artistRepository = artistRepository;
        this.characterRepository = characterRepository;
        this.parodyRepository = parodyRepository;
        this.groupRepository = groupRepository;
        this.categoryRepository = categoryRepository;
        this.entityMapper = entityMapper;
        this.cacheManager = cacheManager;
        this.chapterRemoval = chapterRemoval;
    }

    @Transactional(readOnly = true)
    public Series get(int id)
    {
        return seriesRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found"));
    }

    @Transactional(readOnly = true)
    public SeriesForm toForm(int id)
    {
        return entityMapper.toForm(get(id));
    }

    @Transactional(readOnly = true)
    public SeriesViewModel buildView(int id, String languageFilter)
    {
        Series series = get(id);
        List<Chapter> chapters = chapterRepository.findBySeriesIdOrderByChapterNumAscIdAsc(id);
        List<String> languages = chapterRepository.findDistinctLanguagesBySeriesId(id);

        String selectedLanguage = StringUtils.isNotBlank(languageFilter) && languages.contains(languageFilter)
                ? languageFilter : null;

        List<Chapter> visible = selectedLanguage == null
                ? chapters
                : chapters.stream().filter(c -> selectedLanguage.equals(c.getLanguage())).toList();

        List<CardDto> cards = visible.stream()
                .map(c -> new CardDto(c.getId(), "/chapter/" + c.getId(), imageService.thumbnailUrl(c.getId()), c.getTitleFull()))
                .toList();

        String thumbnailUrl = chapters.isEmpty() ? ImageService.PLACEHOLDER
                : imageService.thumbnailUrl(chapters.getFirst().getId());

        List<ChipGroup> groups = new ArrayList<>();
        groups.add(facetGroup("Tags", series.getTags(), MetadataType.TAG, chapters, Chapter::getTags));
        groups.add(facetGroup("Artists", series.getArtists(), MetadataType.ARTIST, chapters, Chapter::getArtists));
        groups.add(facetGroup("Characters", series.getCharacters(), MetadataType.CHARACTER, chapters, Chapter::getCharacters));
        groups.add(facetGroup("Parodies", series.getParodies(), MetadataType.PARODY, chapters, Chapter::getParodies));
        groups.add(facetGroup("Groups", series.getGroups(), MetadataType.GROUP, chapters, Chapter::getGroups));
        groups.add(facetGroup("Categories", series.getCategories(), MetadataType.CATEGORY, chapters,
                Chapter::getCategories));

        // The materialized effective score, so the view agrees with search and sort.
        Integer score = series.getScore() == null ? null : series.getScore().intValue();
        boolean scoreDerived = series.getScoreSource() != ScoreSource.USER_SET;

        String effectiveLanguage = languages.isEmpty() ? null : String.join(", ", languages);

        return SeriesViewModel.builder()
                .series(series)
                .thumbnailUrl(thumbnailUrl)
                .detailGroups(groups)
                .languages(languages)
                .selectedLanguage(selectedLanguage)
                .chapterCards(cards)
                .chapterCount(chapters.size())
                .pageCount(series.getPageNum())
                .diskSizeDisplay(ImageService.humanReadableSize(series.getDiskSize()))
                .score(score)
                .scoreDerived(scoreDerived)
                .createdDate(series.getCreatedDate())
                .effectiveLanguage(effectiveLanguage)
                .build();
    }

    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public int create(SeriesForm form)
    {
        Series series = new Series();
        applyForm(series, form);
        series.setCreatedDate(LocalDate.now());   // never user-editable
        series = seriesRepository.save(series);
        attachChapters(series, form.getChapterIds());
        recomputeDerived(series);
        return series.getId();
    }

    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public void update(SeriesForm form)
    {
        Series series = get(form.getId());
        applyForm(series, form);
        seriesRepository.save(series);
        attachChapters(series, form.getChapterIds());
        saveChapterNums(form.getChapterNums());
        recomputeDerived(series);
    }

    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public void delete(int id)
    {
        unlinkAndDelete(get(id));
    }

    /**
     * Unannotated: if {@link #deleteIfEmpty} called {@link #delete(int)} instead, the self-invocation would
     * silently skip its eviction.
     */
    private void unlinkAndDelete(Series series)
    {
        List<Chapter> chapters = chapterRepository.findBySeriesId(series.getId());

        for (Chapter chapter : chapters)
        {
            chapter.setSeries(null);
            chapter.setChapterNum(null);
        }
        chapterRepository.saveAll(chapters);

        clearMetadataLinks(series);
        seriesRepository.delete(series);
    }

    /**
     * The one deliberate exception to "a series operation only unlinks" ({@link #delete(int)}): its own,
     * separately confirmed button, not a stronger form of delete.
     *
     * @return how many chapters were deleted
     */
    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public int deleteWithChapters(int id)
    {
        List<Chapter> chapters = deleteSeriesAndChapters(get(id));
        chapterRemoval.finish(chapters);
        return chapters.size();
    }

    /** Ids that no longer exist are skipped. */
    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public DeletedCount deleteWithChapters(Collection<Integer> ids)
    {
        List<Series> found = seriesRepository.findAllById(ids);
        List<Chapter> chapters = new ArrayList<>();
        for (Series series : found)
        {
            chapters.addAll(deleteSeriesAndChapters(series));
        }
        chapterRemoval.finish(chapters);
        return new DeletedCount(found.size(), chapters.size());
    }

    /** Unannotated, like {@link #unlinkAndDelete}. The caller must pass the result to {@link ChapterRemoval#finish}. */
    private List<Chapter> deleteSeriesAndChapters(Series series)
    {
        List<Chapter> chapters = chapterRepository.findBySeriesId(series.getId());

        for (Chapter chapter : chapters)
        {
            chapter.setSeries(null);   // drop the FK before the series row is deleted
        }
        chapterRepository.deleteAll(chapters);
        chapterRepository.flush();     // the chapter deletes must reach SQLite before the series delete

        clearMetadataLinks(series);
        seriesRepository.delete(series);
        return chapters;
    }

    /** Only promotes NEW, like {@link ChapterService#markReviewed}. */
    @Transactional
    public void markReviewed(int id)
    {
        Series series = get(id);
        if (series.getStatus() == Status.NEW)
        {
            series.setStatus(Status.REVIEWED);
            seriesRepository.save(series);
            evictSearchCount();
        }
    }

    /** Both the override and the effective sets, so deleting the row cannot trip their FKs. */
    private void clearMetadataLinks(Series series)
    {
        series.getTags().clear();
        series.getArtists().clear();
        series.getCharacters().clear();
        series.getParodies().clear();
        series.getGroups().clear();
        series.getCategories().clear();
        series.getEffectiveTags().clear();
        series.getEffectiveArtists().clear();
        series.getEffectiveCharacters().clear();
        series.getEffectiveParodies().clear();
        series.getEffectiveGroups().clear();
        series.getEffectiveCategories().clear();
        series.getEffectiveLanguages().clear();
    }

    /** @return how many joined; a chapter already in this series is not counted */
    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public int addChapters(int seriesId, Collection<Integer> chapterIds)
    {
        Series series = get(seriesId);
        if (chapterIds == null || chapterIds.isEmpty())
        {
            return 0;
        }
        List<Chapter> joining = chapterRepository.findAllById(chapterIds).stream()
                .filter(chapter -> chapter.getSeries() == null || !chapter.getSeries().getId().equals(seriesId))
                .toList();
        if (joining.isEmpty())
        {
            return 0;
        }
        attachLoaded(series, joining, Map.of());
        recomputeDerived(series);
        return joining.size();
    }

    /**
     * Skips the recompute so a caller linking many chapters into one series recomputes it once. The caller
     * <b>must</b> call {@link #recomputeDerived(Integer)} for every series it touched and evict the search
     * count.
     */
    @Transactional
    public void addChaptersDeferred(int seriesId, Map<Integer, Float> chapterNums)
    {
        attachChapters(get(seriesId), chapterNums);
    }

    /**
     * Same caller obligation as {@link #addChaptersDeferred}.
     * <p>
     * Deliberately bare, with no overrides, so everything stays derived and grows as chapters join. An
     * override copied from the first chapter would freeze the facets at what that one carried.
     */
    @Transactional
    public int createForMatchDeferred(String titleFull, String titlePretty, Map<Integer, Float> chapterNums)
    {
        var series = new Series();
        series.setTitleFull(titleFull);
        series.setTitle(StringUtils.isNotBlank(titlePretty) ? titlePretty : titleFull);
        series.setStatus(Status.NEW);
        series.setScoreSource(ScoreSource.DERIVED);
        series.setCreatedDate(LocalDate.now());
        applyMatchKeys(series);
        series = seriesRepository.save(series);

        attachChapters(series, chapterNums);
        return series.getId();
    }

    @Transactional
    public void updateChapterNum(int seriesId, int chapterId, float chapterNum)
    {
        Chapter chapter = chapterRepository.findById(chapterId).orElse(null);
        if (chapter != null && chapter.getSeries() != null && chapter.getSeries().getId().equals(seriesId))
        {
            chapter.setChapterNum(chapterNum);
            chapterRepository.save(chapter);
        }
    }

    @CacheEvict(value = CacheConfig.SEARCH_COUNT, allEntries = true)
    @Transactional
    public void removeChapter(int seriesId, int chapterId)
    {
        Chapter chapter = chapterRepository.findById(chapterId).orElse(null);
        if (chapter != null && chapter.getSeries() != null && chapter.getSeries().getId().equals(seriesId))
        {
            chapter.setSeries(null);
            chapter.setChapterNum(null);
            chapterRepository.save(chapter);
            recomputeDerived(seriesId);
            deleteIfEmpty(seriesId);
        }
    }

    @Transactional(readOnly = true)
    public List<ChapterCardDto> editChapterCards(int seriesId)
    {
        return chapterRepository.findBySeriesIdOrderByChapterNumAscIdAsc(seriesId).stream()
                .map(c -> new ChapterCardDto(c.getId(), imageService.thumbnailUrl(c.getId()), c.getTitleFull(), c.getChapterNum()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SeriesForm prefillFromChapter(int chapterId)
    {
        Chapter chapter = chapterRepository.findById(chapterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found"));
        SeriesForm form = new SeriesForm();
        form.setTitle(chapter.getTitle());
        form.setTitleFull(chapter.getTitleFull());
        form.setNativeTitle(chapter.getNativeTitle());
        form.setArtistIds(chapter.getArtists().stream().map(Artist::getId).toList());
        form.setTagIds(chapter.getTags().stream().map(Tag::getId).toList());
        form.setCharacterIds(chapter.getCharacters().stream().map(Character::getId).toList());
        form.setParodyIds(chapter.getParodies().stream().map(Parody::getId).toList());
        form.setGroupIds(chapter.getGroups().stream().map(Group::getId).toList());
        form.setCategoryIds(chapter.getCategories().stream().map(Category::getId).toList());
        form.setChapterIds(new ArrayList<>(List.of(chapterId)));
        return form;
    }

    private void applyForm(Series series, SeriesForm form)
    {
        var titleFull = StringUtils.trim(form.getTitleFull());
        series.setTitleFull(titleFull);
        // title_pretty is NOT NULL.
        series.setTitle(StringUtils.isNotBlank(form.getTitle()) ? StringUtils.trim(form.getTitle()) : titleFull);
        series.setNativeTitle(StringUtils.trimToNull(form.getNativeTitle()));
        applyMatchKeys(series);
        applyScore(series, form.getScore());
        series.setStatus(form.getStatus());
        series.setTags(tagRepository.findAllById(form.getTagIds()));
        series.setArtists(artistRepository.findAllById(form.getArtistIds()));
        series.setCharacters(characterRepository.findAllById(form.getCharacterIds()));
        series.setParodies(parodyRepository.findAllById(form.getParodyIds()));
        series.setGroups(groupRepository.findAllById(form.getGroupIds()));
        series.setCategories(categoryRepository.findAllById(form.getCategoryIds()));
    }

    private void attachChapters(Series series, List<Integer> chapterIds)
    {
        if (chapterIds == null || chapterIds.isEmpty())
        {
            return;
        }
        var withoutNumbers = new LinkedHashMap<Integer, Float>();
        chapterIds.forEach(id -> withoutNumbers.put(id, null));
        attachChapters(series, withoutNumbers);
    }

    /**
     * A null number keeps the chapter's own (it may have been set by hand); a chapter with none gets the one
     * its title implies. A constant fallback would give every chapter linked by hand the same number.
     */
    private void attachChapters(Series series, Map<Integer, Float> chapterNums)
    {
        if (chapterNums == null || chapterNums.isEmpty())
        {
            return;
        }
        attachLoaded(series, chapterRepository.findAllById(chapterNums.keySet()), chapterNums);
    }

    private void attachLoaded(Series series, List<Chapter> chapters, Map<Integer, Float> chapterNums)
    {
        Set<Integer> formerSeriesIds = new HashSet<>();
        for (Chapter chapter : chapters)
        {
            Series former = chapter.getSeries();
            if (former != null && !former.getId().equals(series.getId()))
            {
                formerSeriesIds.add(former.getId());
            }
            chapter.setSeries(series);
            Float matched = chapterNums.get(chapter.getId());
            if (matched != null)
            {
                chapter.setChapterNum(matched);
            }
            else if (chapter.getChapterNum() == null)
            {
                chapter.setChapterNum(TitleKey.of(chapter.getTitleFull()).getChapterNum());
            }
        }
        chapterRepository.saveAll(chapters);
        for (Integer formerId : formerSeriesIds)
        {
            recomputeDerived(formerId);
            deleteIfEmpty(formerId);
        }
    }

    /**
     * An empty series describes nothing and is dead weight in browse and search. <b>Anything that unlinks or
     * deletes a chapter must call this.</b> No exemption for a curated series: emptying one is an explicit act.
     * <p>
     * Evicts programmatically, not with {@code @CacheEvict}: it is often self-invoked, where an annotation
     * would not fire, and it should evict only when a series really went.
     *
     * @return whether the series was deleted, so a caller can skip recomputing it
     */
    @Transactional
    public boolean deleteIfEmpty(Integer seriesId)
    {
        if (seriesId == null)
        {
            return false;
        }
        Series series = seriesRepository.findById(seriesId).orElse(null);
        if (series == null || chapterRepository.countBySeriesId(seriesId) > 0)
        {
            return false;
        }
        unlinkAndDelete(series);
        evictSearchCount();
        return true;
    }

    private void evictSearchCount()
    {
        Cache cache = cacheManager.getCache(CacheConfig.SEARCH_COUNT);
        if (cache != null)
        {
            cache.clear();
        }
    }

    /**
     * From {@code titleFull} only: the pretty title is display-only and {@code nativeTitle} takes no part in
     * matching. Must run on every path that writes a title.
     */
    public static void applyMatchKeys(Series series)
    {
        var key = TitleKey.of(series.getTitleFull());
        series.setMatchKey(key.getMatchKey());
        series.setMatchBlock(key.getMatchBlock());
    }

    private void applyScore(Series series, Integer formScore)
    {
        if (formScore != null)
        {
            series.setScoreSource(ScoreSource.USER_SET);
            series.setScore(ChapterService.toDbScore(formScore));
        }
        else
        {
            series.setScoreSource(ScoreSource.DERIVED);
            // recomputeDerived fills the score once chapters are attached
        }
    }

    /**
     * <b>Must be called from every path that changes a series's chapter scores, image stats, metadata,
     * language or membership</b>, or what search reads goes stale.
     */
    @Transactional
    public void recomputeDerived(Integer seriesId)
    {
        if (seriesId == null)
        {
            return;
        }
        seriesRepository.findById(seriesId).ifPresent(this::recomputeDerived);
    }

    private void recomputeDerived(Series series)
    {
        if (series.getScoreSource() != ScoreSource.USER_SET)
        {
            Double avg = chapterRepository.averageScoreBySeriesId(series.getId());
            series.setScore(avg == null ? null : (short) Math.round(avg));
        }
        series.setPageNum((int) chapterRepository.sumPageNumBySeriesId(series.getId()));
        series.setDiskSize(chapterRepository.sumDiskSizeBySeriesId(series.getId()));
        recomputeEffectiveMetadata(series);
        seriesRepository.save(series);
    }

    private void recomputeEffectiveMetadata(Series series)
    {
        int id = series.getId();
        series.setEffectiveTags(effective(series.getTags(),
                () -> tagRepository.findAllById(chapterRepository.distinctTagIdsBySeriesId(id))));
        series.setEffectiveArtists(effective(series.getArtists(),
                () -> artistRepository.findAllById(chapterRepository.distinctArtistIdsBySeriesId(id))));
        series.setEffectiveCharacters(effective(series.getCharacters(),
                () -> characterRepository.findAllById(chapterRepository.distinctCharacterIdsBySeriesId(id))));
        series.setEffectiveParodies(effective(series.getParodies(),
                () -> parodyRepository.findAllById(chapterRepository.distinctParodyIdsBySeriesId(id))));
        series.setEffectiveGroups(effective(series.getGroups(),
                () -> groupRepository.findAllById(chapterRepository.distinctGroupIdsBySeriesId(id))));
        series.setEffectiveCategories(effective(series.getCategories(),
                () -> categoryRepository.findAllById(chapterRepository.distinctCategoryIdsBySeriesId(id))));
        series.setEffectiveLanguages(new ArrayList<>(chapterRepository.findDistinctLanguagesBySeriesId(id)));
    }

    private <T> List<T> effective(List<T> override, Supplier<List<T>> derived)
    {
        return (override != null && !override.isEmpty()) ? new ArrayList<>(override) : derived.get();
    }

    private void saveChapterNums(Map<Integer, Float> chapterNums)
    {
        if (chapterNums == null || chapterNums.isEmpty())
        {
            return;
        }
        List<Integer> ids = chapterNums.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(Map.Entry::getKey)
                .toList();
        List<Chapter> chapters = chapterRepository.findAllById(ids);
        for (Chapter c : chapters)
        {
            Float num = chapterNums.get(c.getId());
            if (num != null)
            {
                c.setChapterNum(num);
            }
        }
        chapterRepository.saveAll(chapters);
    }

    private ChipGroup facetGroup(String title, List<? extends Metadata> override, MetadataType type,
                                 List<Chapter> chapters, Function<Chapter, List<? extends Metadata>> extractor)
    {
        if (override != null && !override.isEmpty())
        {
            Set<String> covered = type == MetadataType.TAG
                    ? MetadataService.plainTagsCoveredBy(override.stream().map(Metadata::getName).toList())
                    : Set.of();
            List<ChipDto> chips = override.stream()
                    .filter(m -> !covered.contains(m.getName()))
                    .map(m -> new ChipDto(m.getName(),
                            ChapterService.href(SearchType.SERIES, type.getSearchParam(), String.valueOf(m.getId())), null))
                    .toList();
            return new ChipGroup(title, chips, false);
        }

        Map<Integer, Long> counts = new LinkedHashMap<>();
        Map<Integer, String> names = new HashMap<>();
        // Per plain tag, the chapters carrying a version of it as well.
        Map<Integer, Long> covered = new HashMap<>();
        for (Chapter chapter : chapters)
        {
            List<? extends Metadata> items = extractor.apply(chapter);
            Set<String> coveredHere = type == MetadataType.TAG
                    ? MetadataService.plainTagsCoveredBy(items.stream().map(Metadata::getName).toList())
                    : Set.of();
            for (Metadata item : items)
            {
                counts.merge(item.getId(), 1L, Long::sum);
                names.putIfAbsent(item.getId(), item.getName());
                if (coveredHere.contains(item.getName()))
                {
                    covered.merge(item.getId(), 1L, Long::sum);
                }
            }
        }

        // A plain tag's chip is left out only when each of its chapters carries a version of it too; otherwise it
        // counts chapters no version shows (from a source without gender, such as nhentai).
        List<ChipDto> chips = counts.entrySet().stream()
                .filter(e -> !e.getValue().equals(covered.get(e.getKey())))
                .sorted(Comparator.<Map.Entry<Integer, Long>>comparingLong(Map.Entry::getValue).reversed()
                        .thenComparing(e -> names.get(e.getKey()), String.CASE_INSENSITIVE_ORDER))
                .map(e -> new ChipDto(names.get(e.getKey()),
                        ChapterService.href(SearchType.SERIES, type.getSearchParam(), String.valueOf(e.getKey())),
                        e.getValue()))
                .toList();
        return new ChipGroup(title, chips, true);
    }
}
