package io.github.mocchikon.hentie.service.export;

import io.github.mocchikon.hentie.dto.ChapterNumber;
import io.github.mocchikon.hentie.entity.Chapter;
import io.github.mocchikon.hentie.entity.Series;
import io.github.mocchikon.hentie.repository.ChapterRepository;
import io.github.mocchikon.hentie.repository.SeriesRepository;
import io.github.mocchikon.hentie.scrapper.DataDownloaderRegistry;
import io.github.mocchikon.hentie.service.ImageDirectory;
import io.github.mocchikon.hentie.service.export.ExportPages.Processing;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a chapter or a series as CBZ: a ZIP of the pages, named so that sorting by name is reading order, with a
 * {@link ComicInfo}.
 *
 * <p><b>Streamed straight into the response</b>, never built in a file first: a series can be gigabytes, and the
 * browser shows progress from the first byte. Everything a reader needs from the database is read first, in one
 * read-only transaction ({@link #chapter}, {@link #series}), so no connection is held while pages are written.
 *
 * <p><b>Pages are stored, not deflated</b>: images are compressed already, and deflating them again costs CPU for
 * nothing. A stored entry needs its CRC before its bytes, so each page is read twice. A CBZ inside the per-chapter
 * ZIP cannot be read twice, so it is deflated at level 0, which adds a few bytes per 64 KB.
 *
 * <p><b>An archive is finished, never closed, and not at all after a failure</b>: closing would write the central
 * directory and end the response, so an export cut short would arrive as a complete, valid file missing pages.
 * Without its directory the file is unreadable, and the browser reports the download as failed.
 */
@Service
@RequiredArgsConstructor
public class CbzExportService
{
    /** Longer names are cut: Windows refuses a path over 260 characters, and the user's folder takes some. */
    private static final int MAX_NAME_LENGTH = 120;

    private static final int BUFFER = 64 * 1024;

    private final ChapterRepository chapterRepository;
    private final SeriesRepository seriesRepository;
    private final ImageDirectory imageDirectory;
    private final DataDownloaderRegistry registry;
    private final ExportPages pages;

    /** @param name the file name without extension, safe on every system */
    public record ChapterExport(int id, String name, ComicInfo info) {}

    public record SeriesExport(String name, ComicInfo info, List<ChapterExport> chapters) {}

    @Transactional(readOnly = true)
    public ChapterExport chapter(int id)
    {
        Chapter chapter = chapterRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found"));
        Series series = chapter.getSeries();
        // A chapter alone is numbered only by its series; its own title is its series otherwise.
        String seriesTitle = series == null ? titleOf(chapter) : titleOf(series);
        String number = series == null ? null : ChapterNumber.format(chapter.getChapterNum());
        return new ChapterExport(id, fileName(titleOf(chapter), "chapter-" + id),
                chapterInfo(chapter, seriesTitle, number, null));
    }

    /**
     * @param language as picked on the series page; one the series has no chapter in means every chapter, as there
     */
    @Transactional(readOnly = true)
    public SeriesExport series(int id, String language)
    {
        Series series = seriesRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Series not found"));
        boolean filtered = StringUtils.isNotBlank(language)
                && chapterRepository.findDistinctLanguagesBySeriesId(id).contains(language);
        List<Chapter> chapters = filtered
                ? chapterRepository.findBySeriesIdAndLanguageOrderByChapterNumAscIdAsc(id, language)
                : chapterRepository.findBySeriesIdOrderByChapterNumAscIdAsc(id);

        String seriesTitle = titleOf(series);
        int width = digits(chapters.size());
        var exports = new ArrayList<ChapterExport>(chapters.size());
        for (int i = 0; i < chapters.size(); i++)
        {
            Chapter chapter = chapters.get(i);
            // The position when unnumbered: readers order a series by number, and the position is the page's order.
            String number = chapter.getChapterNum() == null
                    ? String.valueOf(i + 1) : ChapterNumber.format(chapter.getChapterNum());
            exports.add(new ChapterExport(chapter.getId(),
                    pad(i + 1, width) + " - " + fileName(titleOf(chapter), "chapter-" + chapter.getId()),
                    chapterInfo(chapter, seriesTitle, number, chapters.size())));
        }

        List<String> languages = chapters.stream().map(Chapter::getLanguage).filter(Objects::nonNull).distinct()
                .toList();
        var info = new ComicInfo(seriesTitle, seriesTitle, null, null, series.getCreatedDate(),
                ComicInfo.names(series.getEffectiveArtists()), ComicInfo.names(series.getEffectiveGroups()),
                ComicInfo.names(series.getEffectiveCategories()), ComicInfo.names(series.getEffectiveTags()),
                ComicInfo.names(series.getEffectiveCharacters()), ComicInfo.names(series.getEffectiveParodies()),
                languages.size() == 1 ? ComicInfo.isoCode(languages.getFirst()) : null, null, null,
                series.getScore() == null ? null : series.getScore().intValue());
        return new SeriesExport(fileName(seriesTitle, "series-" + id), info, List.copyOf(exports));
    }

    /** The disk as it is now, not the listing cache: an export must not miss a page that just arrived. */
    public List<String> pagesOf(int chapterId)
    {
        return imageDirectory.list(chapterId);
    }

    /**
     * @param processing null for the pages as stored
     * @throws ExportPages.ExportFailedException when ComfyUI could not process a page
     */
    public void writeChapter(ChapterExport chapter, Processing processing, ExportProgress.Tracker tracker,
                            OutputStream out)
            throws IOException, InterruptedException
    {
        try
        {
            var zip = new ZipOutputStream(out);
            List<String> names = pagesOf(chapter.id());
            tracker.total(names.size());
            putInfo(zip, chapter.info().withPageCount(names.size()));
            int width = Math.max(3, digits(names.size()));
            for (int i = 0; i < names.size(); i++)
            {
                putPage(zip, chapter.id(), names.get(i), pad(i + 1, width), processing, tracker);
            }
            zip.finish();
        }
        finally
        {
            pages.release(processing);
        }
    }

    /** A ZIP holding one CBZ per chapter, as readers expect a series on disk. */
    public void writeSeriesPerChapter(SeriesExport series, Processing processing, ExportProgress.Tracker tracker,
                            OutputStream out)
            throws IOException, InterruptedException
    {
        try
        {
            var zip = new ZipOutputStream(out);
            // Listed again per chapter when written: the folder may change meanwhile, and the total is only shown.
            tracker.total(series.chapters().stream().mapToInt(c -> pagesOf(c.id()).size()).sum());
            for (ChapterExport chapter : series.chapters())
            {
                zip.setLevel(Deflater.NO_COMPRESSION);
                zip.putNextEntry(new ZipEntry(chapter.name() + ".cbz"));
                // Finished, never closed: closing would close the outer archive.
                var inner = new ZipOutputStream(zip);
                List<String> names = pagesOf(chapter.id());
                putInfo(inner, chapter.info().withPageCount(names.size()));
                int width = Math.max(3, digits(names.size()));
                for (int i = 0; i < names.size(); i++)
                {
                    putPage(inner, chapter.id(), names.get(i), pad(i + 1, width), processing, tracker);
                }
                inner.finish();
                zip.closeEntry();
            }
            zip.finish();
        }
        finally
        {
            pages.release(processing);
        }
    }

    /** Every chapter's pages in one CBZ, chapter by chapter: {@code <chapter>-<page>}. */
    public void writeSeriesOneFile(SeriesExport series, Processing processing, ExportProgress.Tracker tracker,
                            OutputStream out)
            throws IOException, InterruptedException
    {
        try
        {
            var zip = new ZipOutputStream(out);
            List<List<String>> names = series.chapters().stream().map(c -> pagesOf(c.id())).toList();
            int total = names.stream().mapToInt(List::size).sum();
            tracker.total(total);
            putInfo(zip, series.info().withPageCount(total));
            int chapterWidth = Math.max(3, digits(names.size()));
            int pageWidth = Math.max(3, names.stream().mapToInt(n -> digits(n.size())).max().orElse(1));
            for (int c = 0; c < names.size(); c++)
            {
                int chapterId = series.chapters().get(c).id();
                List<String> chapterPages = names.get(c);
                for (int p = 0; p < chapterPages.size(); p++)
                {
                    putPage(zip, chapterId, chapterPages.get(p),
                            pad(c + 1, chapterWidth) + "-" + pad(p + 1, pageWidth), processing, tracker);
                }
            }
            zip.finish();
        }
        finally
        {
            pages.release(processing);
        }
    }

    private ComicInfo chapterInfo(Chapter chapter, String seriesTitle, String number, Integer count)
    {
        return new ComicInfo(titleOf(chapter), seriesTitle, number, count, chapter.getUploadDate(),
                ComicInfo.names(chapter.getArtists()), ComicInfo.names(chapter.getGroups()),
                ComicInfo.names(chapter.getCategories()), ComicInfo.names(chapter.getTags()),
                ComicInfo.names(chapter.getCharacters()), ComicInfo.names(chapter.getParodies()),
                ComicInfo.isoCode(chapter.getLanguage()),
                registry.pageLinkFor(chapter.getGalleryId(), chapter.getSourceSite()).orElse(null), null,
                chapter.getScore() == null ? null : chapter.getScore().intValue());
    }

    private static void putInfo(ZipOutputStream zip, ComicInfo info) throws IOException
    {
        zip.setLevel(Deflater.DEFAULT_COMPRESSION);
        zip.putNextEntry(new ZipEntry(ComicInfo.FILE_NAME));
        zip.write(info.toXml());
        zip.closeEntry();
    }

    private void putPage(ZipOutputStream zip, int chapterId, String filename, String entryName,
                         Processing processing, ExportProgress.Tracker tracker) throws IOException, InterruptedException
    {
        try (ExportPages.Page page = pages.open(chapterId, filename, processing, tracker))
        {
            FileChannel channel = page.channel();
            var buffer = ByteBuffer.allocate(BUFFER);
            var crc = new CRC32();
            long size = 0;
            channel.position(0);
            for (int read; (read = channel.read(buffer.clear())) > 0; size += read)
            {
                crc.update(buffer.flip());
            }
            var entry = new ZipEntry(entryName + "." + page.extension());
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(size);
            entry.setCompressedSize(size);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            channel.position(0);
            for (int read; (read = channel.read(buffer.clear())) > 0; )
            {
                zip.write(buffer.array(), 0, read);
            }
            zip.closeEntry();
        }
        tracker.pageWritten();
    }

    private static String titleOf(Chapter chapter)
    {
        return StringUtils.defaultIfBlank(chapter.getTitle(), chapter.getTitleFull());
    }

    private static String titleOf(Series series)
    {
        return StringUtils.defaultIfBlank(series.getTitle(), series.getTitleFull());
    }

    /** Safe on Windows, the strictest system: no reserved characters, no trailing dot or space. */
    static String fileName(String title, String fallback)
    {
        String name = StringUtils.normalizeSpace(StringUtils.defaultString(title)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_"));
        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH)
        {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME_LENGTH)).strip();
        }
        name = StringUtils.stripEnd(name, ". ");
        return name.isEmpty() ? fallback : name;
    }

    private static int digits(int count)
    {
        return String.valueOf(Math.max(count, 1)).length();
    }

    private static String pad(int value, int width)
    {
        return StringUtils.leftPad(String.valueOf(value), width, '0');
    }
}
