package io.github.mocchikon.hentie;

import io.github.mocchikon.hentie.config.CacheConfig;
import io.github.mocchikon.hentie.dto.*;
import io.github.mocchikon.hentie.entity.Category;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.CategoryRepository;
import io.github.mocchikon.hentie.service.ChapterService;
import io.github.mocchikon.hentie.service.MetadataService;
import io.github.mocchikon.hentie.service.SearchService;
import io.github.mocchikon.hentie.service.SeriesService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Category is the sixth metadata kind; this suite checks it reaches everywhere the other five do: chapter and
 * series forms and views, the series' effective set, search, and the Manage actions with their rules. The
 * generic code (merge, rules, native search) is covered for every kind elsewhere; here it is checked to know
 * about this one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryIT
{
    @Autowired ChapterService chapterService;
    @Autowired SeriesService seriesService;
    @Autowired SearchService searchService;
    @Autowired MetadataService metadataService;
    @Autowired CategoryRepository categoryRepository;
    @Autowired CacheManager cacheManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void clearCaches()
    {
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.METADATA)).clear();
        // Rolled-back ids are handed out again, so a count cached by an earlier test could match a new filter.
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.SEARCH_COUNT)).clear();
    }

    @Test
    void shouldStoreAChaptersCategoriesAndShowThemAsChipsThatSearchForThem()
    {
        // GIVEN
        Category manga = category("cat-manga");

        // WHEN
        int chapterId = chapter("cat chapter", manga);

        // THEN
        assertThat(chapterService.toForm(chapterId).getCategoryIds()).containsExactly(manga.getId());
        ChipGroup chips = group(chapterService.buildView(chapterId).getDetailGroups(), "Categories");
        assertThat(chips.getChips()).extracting(ChipDto::getLabel).containsExactly("cat-manga");
        assertThat(chips.getChips().getFirst().getHref()).contains("categoryIds=" + manga.getId());
    }

    @Test
    void shouldDeriveASeriesCategoriesFromItsChaptersUnlessItHasItsOwn()
    {
        // GIVEN a series of one manga chapter.
        Category manga = category("cat-manga");
        Category doujinshi = category("cat-doujinshi");
        int chapterId = chapter("cat series chapter", manga);
        int seriesId = seriesService.create(series("cat series", List.of(), chapterId));

        // THEN its effective set, which series search reads, is the chapter's.
        assertThat(effectiveCategories(seriesId)).containsExactly(manga.getId());
        ChipGroup derived = group(seriesService.buildView(seriesId, null).getDetailGroups(), "Categories");
        assertThat(derived.isDerived()).isTrue();
        assertThat(derived.getChips()).extracting(ChipDto::getLabel).containsExactly("cat-manga");

        // WHEN the series is given its own.
        SeriesForm form = seriesService.toForm(seriesId);
        form.setCategoryIds(new ArrayList<>(List.of(doujinshi.getId())));
        seriesService.update(form);

        // THEN the override wins.
        assertThat(effectiveCategories(seriesId)).containsExactly(doujinshi.getId());
        assertThat(group(seriesService.buildView(seriesId, null).getDetailGroups(), "Categories").isDerived())
                .isFalse();
    }

    @Test
    void shouldFindAndExcludeByCategoryWhenSearching()
    {
        // GIVEN
        Category manga = category("cat-manga");
        Category doujinshi = category("cat-doujinshi");
        int mangaChapter = chapter("cat search manga", manga);
        int doujinshiChapter = chapter("cat search doujinshi", doujinshi);
        int mangaSeries = seriesService.create(series("cat search series", List.of(), mangaChapter));

        // WHEN + THEN - chapters, included and excluded.
        assertThat(search(SearchType.CHAPTER, c -> c.setCategoryIds(ids(manga)))).containsExactly(mangaChapter);
        assertThat(search(SearchType.CHAPTER, c ->
        {
            c.setTitle("cat search");
            c.setExcludedCategoryIds(ids(manga));
        })).containsExactly(doujinshiChapter);

        // WHEN + THEN - series, through their effective categories.
        assertThat(search(SearchType.SERIES, c -> c.setCategoryIds(ids(manga)))).containsExactly(mangaSeries);
        assertThat(search(SearchType.SERIES, c -> c.setCategoryIds(ids(doujinshi)))).isEmpty();
    }

    /** Merge must move the links in all three join tables, and its rule must rewrite the next import. */
    @Test
    void shouldMoveEveryLinkAndRecordARuleWhenMergingACategory()
    {
        // GIVEN a misspelled category on a chapter, its series' override and so its effective set.
        Category typo = category("cat-mnaga");
        Category manga = category("cat-manga");
        int chapterId = chapter("cat merge chapter", typo);
        int seriesId = seriesService.create(series("cat merge series", List.of(typo.getId()), chapterId));

        // WHEN
        metadataService.merge(MetadataType.CATEGORY, typo.getId(), manga.getId(), true);

        // THEN
        assertThat(categoryRepository.findById(typo.getId())).isEmpty();
        assertThat(chapterService.toForm(chapterId).getCategoryIds()).containsExactly(manga.getId());
        assertThat(seriesService.toForm(seriesId).getCategoryIds()).containsExactly(manga.getId());
        assertThat(effectiveCategories(seriesId)).containsExactly(manga.getId());
        // AND the next import that says "cat-mnaga" gets the merged category.
        assertThat(metadataService.resolveOrCreate(MetadataType.CATEGORY, List.of("Cat-Mnaga")))
                .containsExactly(manga.getId());
    }

    @Test
    void shouldDropTheLinksAndBlockTheNameWhenRemovingACategory()
    {
        // GIVEN
        Category unwanted = category("cat-unwanted");
        int chapterId = chapter("cat remove chapter", unwanted);

        // WHEN
        metadataService.remove(MetadataType.CATEGORY, unwanted.getId(), true);

        // THEN
        assertThat(chapterService.toForm(chapterId).getCategoryIds()).isEmpty();
        assertThat(metadataService.resolveOrCreate(MetadataType.CATEGORY, List.of("cat-unwanted"))).isEmpty();
    }

    @Test
    void shouldOfferCategoriesOnTheSearchEditAndManagePages() throws Exception
    {
        // GIVEN
        Category manga = category("cat-manga");
        int chapterId = chapter("cat page chapter", manga);

        // WHEN + THEN - search can include and exclude them.
        mvc.perform(get("/search").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Categories"),
                        containsString("data-excluded-name=\"excludedCategoryIds\""))));
        // AND the edit form shows the chapter's.
        mvc.perform(get("/chapter/" + chapterId + "/edit").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Categories"), containsString("cat-manga"))));
        // AND Manage lists them like the other kinds.
        mvc.perform(get("/manage").with(user("user")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("Categories"), containsString("cat-manga"))));
    }

    // ---- fixture -----------------------------------------------------------

    private Category category(String name)
    {
        var category = new Category();
        category.setName(name);
        return categoryRepository.save(category);
    }

    private int chapter(String titleFull, Category category)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setLanguage("English");
        form.setCategoryIds(new ArrayList<>(List.of(category.getId())));
        return chapterService.create(form);
    }

    private static SeriesForm series(String titleFull, List<Integer> categoryIds, int chapterId)
    {
        var form = new SeriesForm();
        form.setTitleFull(titleFull);
        form.setStatus(Status.REVIEWED);
        form.setCategoryIds(new ArrayList<>(categoryIds));
        form.setChapterIds(new ArrayList<>(List.of(chapterId)));
        return form;
    }

    /** Flushed first: JDBC does not see what Hibernate still holds. */
    private List<Integer> effectiveCategories(int seriesId)
    {
        em.flush();
        return jdbc.queryForList("select category_id from series_effective_categories where series_id = ?",
                Integer.class, seriesId);
    }

    private List<Integer> search(SearchType type, Consumer<SearchCriteria> setup)
    {
        var criteria = new SearchCriteria();
        criteria.setType(type);
        setup.accept(criteria);
        criteria.normalize();
        return searchService.search(criteria).getContent().stream().map(CardDto::getId).toList();
    }

    private static List<Integer> ids(Category... categories)
    {
        return new ArrayList<>(Arrays.stream(categories).map(Category::getId).toList());
    }

    private static ChipGroup group(List<ChipGroup> groups, String title)
    {
        return groups.stream().filter(g -> g.getTitle().equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError("no chip group " + title + " in " + groups.stream()
                        .map(ChipGroup::getTitle).toList()));
    }
}
