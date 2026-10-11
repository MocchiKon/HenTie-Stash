package io.github.mocchikon.hentie.service.comfy;

import io.github.mocchikon.hentie.service.SettingsService;
import io.github.mocchikon.hentie.service.comfy.ComfyUiException.Reason;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The workflows in ComfyUI's user folder, listed through its API so the app never needs to know where or how
 * ComfyUI is installed.
 *
 * <p><b>Each file is fetched and checked once per (size, mtime)</b>, so the dropdowns can say which workflows
 * cannot run before anyone picks one, without re-reading every file on every listing.
 *
 * <p><b>The listing is believed for a few seconds, and kept when ComfyUI stops answering</b>: every page turn
 * needs the workflow's version, and a cached result is still correct while ComfyUI is down.
 *
 * <p><b>A stale listing is answered while a background refresh runs</b> ({@link #listing}). Refreshing in the
 * request would queue every page turn behind ComfyUI, and a refused connection costs ~2 s on Windows. Only a
 * caller that runs the workflow waits for a current listing ({@link #currentListing}).
 */
@Service
@RequiredArgsConstructor
public class WorkflowCatalog
{
    private static final Logger log = LoggerFactory.getLogger(WorkflowCatalog.class);

    /** Short, because it is also how long a newly exported workflow stays invisible. */
    public static final long LISTING_TTL_MILLIS = 5_000;

    /** Short enough that the settings column is never the limit. */
    static final int MAX_NAME_LENGTH = 255;

    private static final String JSON_SUFFIX = ".json";

    private final ComfyUiClient client;
    private final SettingsService settingsService;

    /** So two callers asking together do not list the folder twice. */
    private final ReentrantLock refreshing = new ReentrantLock();

    /** Never more than one background refresh. */
    private final AtomicBoolean refreshingInBackground = new AtomicBoolean();

    private volatile Snapshot snapshot;

    /** Keyed by the exact file version read; entries no longer listed are dropped. */
    private final Map<FileVersion, Checked> checked = new ConcurrentHashMap<>();

    /** {@code problem} is null for a workflow that can run. */
    public record WorkflowInfo(String name, String version, String problem)
    {
        public boolean valid()
        {
            return problem == null;
        }
    }

    /**
     * {@code problem} says why nothing could be listed (null when current); {@code workflows} are then the
     * last ones known.
     */
    public record Listing(boolean reachable, String problem, List<WorkflowInfo> workflows)
    {
        public Optional<WorkflowInfo> find(String name)
        {
            return workflows.stream().filter(workflow -> workflow.name().equals(name)).findFirst();
        }
    }

    private record Snapshot(String baseUrl, String folder, long takenAtMillis, Listing listing) {}

    private record FileVersion(String baseUrl, String path, long size, long modifiedMillis) {}

    private record Checked(ApiWorkflow workflow, String problem) {}

    /**
     * Waits for ComfyUI only when there is no listing for the current address and folder (the first ask, or
     * the first after {@link #invalidate}).
     */
    public Listing listing() throws InterruptedException
    {
        Snapshot current = snapshot;
        if (isFresh(current))
        {
            return current.listing();
        }
        if (isSameTarget(current) && current.takenAtMillis() > 0)
        {
            refreshInBackground();
            return current.listing();
        }
        return currentListing();
    }

    public Listing currentListing() throws InterruptedException
    {
        Snapshot current = snapshot;
        if (isFresh(current))
        {
            return current.listing();
        }
        refreshing.lockInterruptibly();
        try
        {
            current = snapshot;
            if (isFresh(current))
            {
                return current.listing();
            }
            return refresh(current).listing();
        }
        finally
        {
            refreshing.unlock();
        }
    }

    /**
     * @throws ComfyUiException {@code UNREACHABLE}, {@code WORKFLOW_NOT_FOUND}, or {@code WORKFLOW_INVALID}
     *                          saying what is wrong with the file
     */
    public ApiWorkflow workflow(String name) throws ComfyUiException, InterruptedException
    {
        Listing listing = currentListing();
        if (!listing.reachable())
        {
            throw new ComfyUiException(Reason.UNREACHABLE, listing.problem());
        }
        WorkflowInfo info = listing.find(name).orElseThrow(() -> new ComfyUiException(Reason.WORKFLOW_NOT_FOUND,
                "Workflow '" + name + "' is not in ComfyUI's '" + settingsService.getComfyUiWorkflowDir() + "' folder."));
        if (!info.valid())
        {
            throw new ComfyUiException(Reason.WORKFLOW_INVALID, info.problem());
        }
        return checked.values().stream()
                .map(Checked::workflow)
                .filter(workflow -> workflow != null && workflow.name().equals(name) && workflow.version().equals(info.version()))
                .findFirst()
                .orElseThrow(() -> new ComfyUiException(Reason.PROTOCOL, "Workflow '" + name + "' changed while it was being read."));
    }

    /** From the last listing, current or not, so cached results stay reachable while ComfyUI is down. */
    public Optional<String> knownVersion(String name)
    {
        Snapshot current = snapshot;
        if (current == null || !current.baseUrl().equals(client.baseUrl())
                || !current.folder().equals(settingsService.getComfyUiWorkflowDir()))
        {
            return Optional.empty();
        }
        return current.listing().find(name).filter(WorkflowInfo::valid).map(WorkflowInfo::version);
    }

    /**
     * Makes the next request wait for a fresh listing. The old one is kept, since it is what is shown if
     * ComfyUI does not answer.
     */
    public void invalidate()
    {
        Snapshot current = snapshot;
        if (current != null)
        {
            snapshot = new Snapshot(current.baseUrl(), current.folder(), 0, current.listing());
        }
    }

    private boolean isFresh(Snapshot current)
    {
        return isSameTarget(current) && System.currentTimeMillis() - current.takenAtMillis() < LISTING_TTL_MILLIS;
    }

    private boolean isSameTarget(Snapshot current)
    {
        return current != null
                && current.baseUrl().equals(client.baseUrl())
                && current.folder().equals(settingsService.getComfyUiWorkflowDir());
    }

    private void refreshInBackground()
    {
        if (!refreshingInBackground.compareAndSet(false, true))
        {
            return;
        }
        Thread.ofVirtual().name("comfyui-workflow-listing").start(() ->
        {
            try
            {
                currentListing();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            catch (RuntimeException e)
            {
                log.warn("Refreshing the list of ComfyUI workflows failed", e);
            }
            finally
            {
                refreshingInBackground.set(false);
            }
        });
    }

    private Snapshot refresh(Snapshot previous) throws InterruptedException
    {
        String baseUrl = client.baseUrl();
        String folder = settingsService.getComfyUiWorkflowDir();
        List<WorkflowInfo> known = previous != null && previous.baseUrl().equals(baseUrl) && previous.folder().equals(folder)
                ? previous.listing().workflows()
                : List.of();
        Listing listing;
        try
        {
            listing = list(baseUrl, folder);
        }
        catch (ComfyUiException e)
        {
            listing = new Listing(false, e.getMessage(), known);
        }
        var taken = new Snapshot(baseUrl, folder, System.currentTimeMillis(), listing);
        snapshot = taken;
        return taken;
    }

    private Listing list(String baseUrl, String folder) throws ComfyUiException, InterruptedException
    {
        Optional<List<ComfyUiClient.UserFile>> files = client.listUserFiles(folder);
        if (files.isEmpty())
        {
            checked.keySet().removeIf(key -> key.baseUrl().equals(baseUrl));
            return new Listing(true, "ComfyUI has no '" + folder + "' folder in its user folder yet - export a "
                    + "workflow into it (see Tutorial → ComfyUI).", List.of());
        }
        var workflows = new ArrayList<WorkflowInfo>();
        var listed = new HashSet<FileVersion>();
        for (ComfyUiClient.UserFile file : files.get())
        {
            if (!file.path().toLowerCase(Locale.ROOT).endsWith(JSON_SUFFIX))
            {
                continue;
            }
            String name = file.path().substring(0, file.path().length() - JSON_SUFFIX.length());
            var key = new FileVersion(baseUrl, folder + "/" + file.path(), file.size(), file.modifiedMillis());
            listed.add(key);
            Checked result = checked.get(key);
            if (result == null)
            {
                try
                {
                    result = check(name, key.path());
                    checked.put(key, result);
                }
                catch (ComfyUiException e)
                {
                    // One unreadable file must not fail the whole listing. Not remembered, so the next one retries.
                    result = new Checked(null, "Workflow '" + name + "' could not be read: " + e.getMessage());
                }
            }
            workflows.add(result.workflow() != null
                    ? new WorkflowInfo(name, result.workflow().version(), null)
                    : new WorkflowInfo(name, null, result.problem()));
        }
        Set<FileVersion> stale = new HashSet<>(checked.keySet());
        stale.removeAll(listed);
        stale.forEach(checked::remove);
        workflows.sort(Comparator.comparing(WorkflowInfo::name, String.CASE_INSENSITIVE_ORDER));
        return new Listing(true, null, List.copyOf(workflows));
    }

    private Checked check(String name, String path) throws ComfyUiException, InterruptedException
    {
        if (!isValidName(name))
        {
            return new Checked(null, "Workflow '" + name + "' has a name the app cannot pass on - rename the file.");
        }
        try
        {
            return new Checked(ApiWorkflow.parse(name, client.readUserFile(path)), null);
        }
        catch (ComfyUiException e)
        {
            if (e.reason() == Reason.WORKFLOW_INVALID)
            {
                return new Checked(null, e.getMessage());
            }
            throw e;
        }
    }

    /**
     * A relative path of plain segments. ComfyUI guards its user folder itself; refusing {@code ..} here means
     * the app never even asks, whatever a URL or stored setting says.
     */
    public static boolean isValidName(String name)
    {
        return StringUtils.isNotBlank(name) && name.length() <= MAX_NAME_LENGTH && isRelativePath(name);
    }

    /**
     * A backslash counts as a separator, since Windows users write one. Blank means the default.
     *
     * @return empty when the value cannot be a folder inside ComfyUI's user folder
     */
    public static Optional<String> normalizeFolder(String raw)
    {
        String value = StringUtils.strip(StringUtils.defaultString(raw)).replace('\\', '/');
        value = StringUtils.strip(value, "/");
        if (value.isEmpty())
        {
            return Optional.of(SettingsService.DEFAULT_COMFYUI_WORKFLOW_DIR);
        }
        return value.length() <= MAX_NAME_LENGTH && isRelativePath(value) ? Optional.of(value) : Optional.empty();
    }

    private static boolean isRelativePath(String path)
    {
        if (path.startsWith("/") || path.contains("\\") || path.contains(":"))
        {
            return false;
        }
        for (String segment : path.split("/", -1))
        {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.chars().anyMatch(c -> c < 0x20 || c == 0x7f))
            {
                return false;
            }
        }
        return true;
    }
}
