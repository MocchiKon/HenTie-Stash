package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.ChapterForm;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.DownloadStatus;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.entity.Status;
import io.github.mocchikon.hentie.repository.DownloadQueueRepository;
import io.github.mocchikon.hentie.service.compress.ImageCompressionService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * Splits a compilation into one chapter per part. The leading run of pages stays in the original chapter.
 *
 * <p><b>Not transactional</b>: a file move cannot be rolled back, so each part is created, filled and synced in
 * steps of its own.
 *
 * <p><b>The pages that stay are never renamed</b>: for a downloaded chapter the names are the source's page numbers,
 * which a full-quality re-download relies on. A new part has no gallery id, so its pages are numbered from 1.
 */
@Service
@RequiredArgsConstructor
public class ChapterDivisionService
{
    public static final String TOO_FEW_PAGES = "A chapter needs at least two pages to be divided.";

    static final String RUN_IN_PROGRESS = "An Image Compression run is in progress (possibly in another tab), and it "
            + "may be re-encoding the pages that would move. Wait for it to finish, then divide the chapter.";

    private static final Logger log = LoggerFactory.getLogger(ChapterDivisionService.class);

    /** SQLite does not enforce the {@code VARCHAR(255)} of {@code title_full}. */
    private static final int MAX_TITLE_LENGTH = 255;

    private final ChapterService chapterService;
    private final ImageService imageService;
    private final ImageDirectory imageDirectory;
    private final ImageCompressionService compressionService;
    private final DownloadQueueRepository downloadQueueRepository;
    private final WriteGate writeGate;

    /** Runs from {@code firstPage} up to the next part's first page. A null title means the chapter's own. */
    public record NewPart(String firstPage, String titleFull) {}

    public record CreatedPart(int chapterId, String titleFull, int pageCount, Integer seriesId, String seriesTitle) {}

    /** {@code stoppedBecause} is null when every part was created; otherwise the rest are still in the original. */
    public record Outcome(List<CreatedPart> created, String stoppedBecause)
    {
        public boolean complete()
        {
            return stoppedBecause == null;
        }
    }

    /** Thrown before anything changed. The message is for the user. */
    public static class Refused extends RuntimeException
    {
        public Refused(String message)
        {
            super(message);
        }
    }

    /** Ends the division at this part. The message is for the user. */
    private static class PartFailed extends Exception
    {
        PartFailed(String message)
        {
            super(message);
        }
    }

    /** Positions in the chapter's listing, {@code from} inclusive. */
    private record Range(String name, int from, int to, String titleFull) {}

    /**
     * The divide page asks this before showing the form, so nobody marks a whole compilation only to be refused.
     * <ul>
     *   <li><b>An unfinished download</b>: re-queueing it fetches every page number the chapter lacks, i.e. the
     *       moved ones.</li>
     *   <li><b>A waiting queue row</b>: a full-quality re-download picks its pages when it starts and publishes
     *       minutes later, so it would put the moved pages back.</li>
     *   <li><b>An unknown language</b>: the new chapters need one, and get this chapter's.</li>
     * </ul>
     */
    public Optional<String> refusal(Chapter chapter)
    {
        if (chapter.getDownloadStatus() == DownloadStatus.PENDING)
        {
            return Optional.of("This chapter's download never finished. Queue its link again to complete it first: "
                    + "that download would otherwise fetch the pages that move back into this chapter.");
        }
        if (chapter.getGalleryId() != null
                && downloadQueueRepository.existsByGalleryIdAndErrorIsNull(chapter.getGalleryId()))
        {
            return Optional.of("A download of this chapter is waiting in the download queue. Let it finish, or remove "
                    + "it from the queue, before dividing the chapter: it could put the pages that move back.");
        }
        if (!LanguageService.isKnown(chapter.getLanguage()))
        {
            return Optional.of("This chapter's language \"" + chapter.getLanguage() + "\" is not one the app knows, "
                    + "and the new chapters are created with it. Correct it on the edit page first.");
        }
        return Optional.empty();
    }

    /**
     * {@code keptTitle} null keeps the chapter's title; {@code partStatus} null gives the parts the chapter's status.
     * <p>
     * Runs under the Image Compression run lock, refused rather than waited for: a run encoding a page while it
     * moves would write its output back beside where the page was, putting the page in two chapters.
     * <p>
     * Each part's chapter is committed before its pages move (its folder is named after its id). A part whose pages
     * cannot all move is taken back and the division stops there, so every page is always in exactly one chapter.
     * The new kept title is applied only once every part was made, since until then it still holds their pages.
     *
     * @throws Refused when nothing was changed, because the division cannot be done as asked
     */
    public Outcome divide(int chapterId, String keptTitle, Status partStatus, List<NewPart> parts)
    {
        Chapter chapter = chapterService.get(chapterId);
        Optional<String> refused = refusal(chapter);
        if (refused.isPresent())
        {
            throw new Refused(refused.get());
        }
        String kept = keptTitle == null ? null : checkedTitle(keptTitle, "the part that stays");
        try
        {
            return compressionService.exclusively(() -> divideLocked(chapterId, kept, partStatus, parts));
        }
        catch (ImageCompressionService.RunInProgress busy)
        {
            throw new Refused(RUN_IN_PROGRESS);
        }
    }

    private Outcome divideLocked(int chapterId, String keptTitle, Status partStatus, List<NewPart> parts)
    {
        // Read again under the lock: a run that just released it may have renamed pages and set the mode.
        Chapter chapter = chapterService.get(chapterId);
        List<String> pages = imageDirectory.list(chapterId);
        List<Range> ranges = ranges(chapter, pages, parts);
        // Before the first change, so a busy library refuses the division before any part exists. After it, each
        // part's writes wait their turn: stopping between parts for waiting would leave the division half done.
        writeGate.claimTurn();
        // A superseded source left on disk would become a page again once its replacement moved out.
        imageService.removeSupersededPages(chapterId);
        ChapterForm shared = chapterService.toForm(chapterId);
        if (partStatus != null)
        {
            shared.setStatus(partStatus);
        }

        var created = new ArrayList<CreatedPart>();
        try
        {
            for (Range range : ranges)
            {
                created.add(createPart(chapter, shared, range, pages.subList(range.from(), range.to())));
            }
        }
        catch (PartFailed e)
        {
            return new Outcome(List.copyOf(created), e.getMessage());
        }
        finally
        {
            syncKept(chapter);
        }
        if (keptTitle != null && !keptTitle.equals(chapter.getTitleFull()))
        {
            chapterService.retitle(chapterId, keptTitle);
        }
        return new Outcome(List.copyOf(created), null);
    }

    /**
     * Pages are named by file name, not position: a position means another page once one was added or deleted
     * since the form was rendered. A name that is gone is refused, not guessed at.
     */
    private static List<Range> ranges(Chapter chapter, List<String> pages, List<NewPart> parts)
    {
        if (pages.size() < 2)
        {
            throw new Refused(TOO_FEW_PAGES);
        }
        if (parts.isEmpty())
        {
            throw new Refused("Mark the first page of at least one chapter inside this one.");
        }
        var byPosition = new TreeMap<Integer, NewPart>();
        for (NewPart part : parts)
        {
            int at = pages.indexOf(part.firstPage());
            if (at < 0)
            {
                throw new Refused("The pages of this chapter changed after this page was opened: \"" + part.firstPage()
                        + "\" is not one of them any more. Look over the parts again, then divide.");
            }
            if (at == 0)
            {
                throw new Refused("The first page always stays with this chapter, so it cannot start a new one.");
            }
            if (byPosition.put(at, part) != null)
            {
                throw new Refused("Page " + (at + 1) + " is marked twice.");
            }
        }
        var starts = new ArrayList<Map.Entry<Integer, NewPart>>(byPosition.entrySet());
        var ranges = new ArrayList<Range>(starts.size());
        for (int i = 0; i < starts.size(); i++)
        {
            int from = starts.get(i).getKey();
            int to = i + 1 < starts.size() ? starts.get(i + 1).getKey() : pages.size();
            // Part 1 is the one that stays.
            String name = "part " + (i + 2) + " (from page " + (from + 1) + ")";
            String title = starts.get(i).getValue().titleFull();
            ranges.add(new Range(name, from, to, checkedTitle(title == null ? chapter.getTitleFull() : title, name)));
        }
        return ranges;
    }

    private CreatedPart createPart(Chapter chapter, ChapterForm shared, Range range, List<String> pageNames)
            throws PartFailed
    {
        int partId;
        try
        {
            // Through create, so a part gets the same pretty title, language, match key and matching as any chapter.
            partId = chapterService.create(formFor(shared, range.titleFull()));
        }
        catch (RuntimeException e)
        {
            log.error("Chapter {}: could not create the chapter for {}", chapter.getId(), range.name(), e);
            throw new PartFailed(StringUtils.capitalize(range.name()) + " could not be created: " + e.getMessage());
        }
        List<String> moved;
        try
        {
            moved = imageService.movePages(chapter.getId(), pageNames, partId);
        }
        catch (IOException | RuntimeException e)
        {
            log.error("Chapter {}: could not move the pages of {} into chapter {}", chapter.getId(), range.name(),
                    partId, e);
            throw new PartFailed(StringUtils.capitalize(range.name()) + " was not made, because its pages could not "
                    + "be moved (" + e.getMessage() + ")." + takeBack(partId));
        }
        try
        {
            chapterService.syncImageStats(partId);
            if (chapter.getCompressionMode() != null && moved.stream().anyMatch(ImageDirectory::isEncoderOutput))
            {
                // The label just tells the truth about its files; with no gallery id no re-download is offered.
                chapterService.setCompressionMode(partId, chapter.getCompressionMode());
            }
            Chapter part = chapterService.get(partId);
            Series series = part.getSeries();
            return new CreatedPart(partId, part.getTitleFull(), part.getPageNum(),
                    series == null ? null : series.getId(), series == null ? null : series.getTitle());
        }
        catch (RuntimeException e)
        {
            // Not taken back: its pages are already in it. Its detail page heals the page count.
            log.error("Chapter {}: could not finish chapter {} for {} after its pages moved", chapter.getId(), partId,
                    range.name(), e);
            throw new PartFailed(StringUtils.capitalize(range.name()) + " was created as chapter #" + partId
                    + " with its pages, but could not be finished (" + e.getMessage() + ").");
        }
    }

    /**
     * The part's chapter is not deleted when pages that could not be moved back are still in it, or they would go
     * with it. Returns where the pages are, for the user.
     */
    private String takeBack(int partId)
    {
        try
        {
            if (imageDirectory.pageNumbers(partId).isEmpty())
            {
                chapterService.delete(partId);
                return " Its pages are still in this chapter.";
            }
            chapterService.syncImageStats(partId);
            return " The pages that could not be moved back are in chapter #" + partId
                    + "; the rest are still in this chapter.";
        }
        catch (RuntimeException e)
        {
            log.error("Could not take back chapter {} after its pages failed to move", partId, e);
            return " Chapter #" + partId + " was created for it and could not be removed again.";
        }
    }

    /** Called from a {@code finally}, so it must not throw over whatever is propagating. */
    private void syncKept(Chapter chapter)
    {
        int id = chapter.getId();
        try
        {
            chapterService.syncImageStats(id);
            if (chapter.getCompressionMode() != null && !imageService.encodedPageSurvives(id, List.of()))
            {
                // Every compressed page left with the parts, so a re-download would have nothing to replace.
                chapterService.setCompressionMode(id, null);
            }
        }
        catch (RuntimeException e)
        {
            log.error("Chapter {}: could not sync its page count and size after dividing it", id, e);
        }
    }

    /** No gallery id: it is unique and names the original's gallery. The pretty title is derived from the part's. */
    private static ChapterForm formFor(ChapterForm shared, String titleFull)
    {
        var form = new ChapterForm();
        form.setTitleFull(titleFull);
        form.setNativeTitle(shared.getNativeTitle());
        form.setStatus(shared.getStatus());
        form.setLanguage(shared.getLanguage());
        form.setScore(shared.getScore());
        form.setTagIds(new ArrayList<>(shared.getTagIds()));
        form.setArtistIds(new ArrayList<>(shared.getArtistIds()));
        form.setCharacterIds(new ArrayList<>(shared.getCharacterIds()));
        form.setParodyIds(new ArrayList<>(shared.getParodyIds()));
        form.setGroupIds(new ArrayList<>(shared.getGroupIds()));
        form.setCategoryIds(new ArrayList<>(shared.getCategoryIds()));
        return form;
    }

    private static String checkedTitle(String title, String whose)
    {
        String stripped = StringUtils.strip(title);
        if (StringUtils.isEmpty(stripped))
        {
            throw new Refused("Give " + whose + " a title.");
        }
        if (stripped.length() > MAX_TITLE_LENGTH)
        {
            throw new Refused("The title of " + whose + " is too long: " + MAX_TITLE_LENGTH + " characters at most.");
        }
        return stripped;
    }
}
