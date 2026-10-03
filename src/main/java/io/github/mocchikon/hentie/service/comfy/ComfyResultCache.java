package io.github.mocchikon.hentie.service.comfy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.ImageService;
import io.github.mocchikon.hentie.service.scratch.CacheFolder;
import io.github.mocchikon.hentie.service.scratch.PageDerivedCache;
import io.github.mocchikon.hentie.service.scratch.ScratchSpace;

/**
 * Processed pages, so turning back to one is a file read instead of a ComfyUI run that can take minutes.
 *
 * <p><b>An entry is named after exactly what it was made from</b>:
 * {@code 3.jxl.<mtime>-<size>.<name hash>-<version hash>.png}. Validity is "an entry for these versions
 * exists", never "the entry is newer than the page", so a page replaced outside the app or a re-exported
 * workflow is never answered with an old result. The workflow name is hashed because it may hold slashes;
 * it is hashed apart from the version so one workflow's old results for a page can be found and deleted.
 */
@Component
public class ComfyResultCache implements PageDerivedCache
{
    /** Short file names; a collision would need billions of workflows. */
    private static final int HASH_LENGTH = 12;

    private static final String PART_SUFFIX = ".part";

    private static final String ANY_HASH = "[0-9a-f]{" + HASH_LENGTH + "}";

    private static final Pattern ENTRY = Pattern.compile(
            entryName(".+", ANY_HASH) + "(\\.[0-9a-f-]+" + Pattern.quote(PART_SUFFIX) + ")?", Pattern.CASE_INSENSITIVE);

    private final CacheFolder folder;

    public record PageVersion(long modifiedMillis, long size)
    {
        public static PageVersion of(Path page) throws IOException
        {
            var attrs = Files.readAttributes(page, BasicFileAttributes.class);
            return new PageVersion(attrs.lastModifiedTime().toMillis(), attrs.size());
        }
    }

    public ComfyResultCache(ScratchSpace scratchSpace, AppProperties appProperties)
    {
        // A result is written in one go, so a temp file older than two request timeouts is a dead write's.
        this.folder = new CacheFolder("ComfyUI results cache",
                () -> scratchSpace.comfyResults().root(),
                scratchSpace::comfyResultsCapBytes,
                ENTRY, PART_SUFFIX,
                () -> 2L * Math.max(1, appProperties.getComfyui().getRequestTimeoutSeconds()) * 1000);
    }

    public Optional<Path> find(int chapterId, String filename, PageVersion page, String workflowName, String workflowVersion)
    {
        Path entry = entryFor(chapterId, filename, page, workflowName, workflowVersion);
        return Files.isRegularFile(entry) ? Optional.of(entry) : Optional.empty();
    }

    /** Also drops older results of the page under this workflow: they can never be served again. */
    public Path store(int chapterId, String filename, PageVersion page, String workflowName, String workflowVersion,
                      byte[] png) throws IOException
    {
        Path entry = entryFor(chapterId, filename, page, workflowName, workflowVersion);
        Files.createDirectories(entry.getParent());
        Path part = folder.partFor(entry);
        try
        {
            Files.write(part, png);
            ImageService.moveInto(part, entry);
        }
        finally
        {
            CacheFolder.deleteQuietly(part);
        }
        folder.deleteVersions(entry.getParent(), versionsOf(filename, hash(workflowName)),
                entry.getFileName().toString());
        folder.wrote(png.length);
        return entry;
    }

    @Override
    public void evict(int chapterId)
    {
        folder.evict(chapterId);
    }

    @Override
    public void evict(int chapterId, String filename)
    {
        folder.deleteVersions(folder.chapterDir(chapterId), versionsOf(filename, ANY_HASH), null);
    }

    public Path root()
    {
        return folder.root();
    }

    /** Empties a folder the cache moved away from, or the current one when the user asks for it. */
    public void clear(Path root)
    {
        folder.clear(root);
    }

    /** Walks the folder, so for the Settings page only. */
    public long sizeBytes()
    {
        return folder.sizeBytes(root());
    }

    private Path entryFor(int chapterId, String filename, PageVersion page, String workflowName, String workflowVersion)
    {
        return folder.chapterDir(chapterId).resolve(filename + "." + page.modifiedMillis() + "-" + page.size() + "."
                + hash(workflowName) + "-" + hash(workflowVersion) + ".png");
    }

    /** Finished entries only, never a write still in progress. */
    private static Pattern versionsOf(String filename, String nameHash)
    {
        return Pattern.compile(entryName(Pattern.quote(filename), nameHash));
    }

    /** The one spelling of an entry name; {@code page} and {@code nameHash} are regexes. */
    private static String entryName(String page, String nameHash)
    {
        return page + "\\.\\d+-\\d+\\." + nameHash + "-" + ANY_HASH + "\\.png";
    }

    private static String hash(String value)
    {
        return ApiWorkflow.sha256(value).substring(0, HASH_LENGTH);
    }
}
