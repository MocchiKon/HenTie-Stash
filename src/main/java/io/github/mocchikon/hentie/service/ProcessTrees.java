package io.github.mocchikon.hentie.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Stopping the external programs the app starts (ComfyUI, gallery-dl), and finding one a killed app left running.
 * One place, so every program is stopped by the same safety rules.
 */
public final class ProcessTrees
{
    private ProcessTrees()
    {
    }

    /** What a record of a started program stores beside its pid; 0 when the system does not say. */
    public static long startMillis(ProcessHandle handle)
    {
        return handle.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
    }

    /**
     * The recorded process, only if its start time matches: pids are reused, so a pid alone could name anything.
     * A record without a start time never matches.
     */
    public static Optional<ProcessHandle> stillRunning(long pid, long startMillis)
    {
        if (startMillis <= 0)
        {
            return Optional.empty();
        }
        return ProcessHandle.of(pid)
                .filter(handle -> handle.info().startInstant().map(Instant::toEpochMilli).orElse(-1L) == startMillis);
    }

    /**
     * Descendants are listed before anything is stopped: once a parent is gone, its children cannot be found.
     * Whatever is still alive after {@code grace} is killed.
     *
     * @param childrenFirst kill the children at once and give only the root {@code grace} to exit by itself, so a
     *                      one-file bootloader can clean up after its child; otherwise ask the whole tree to stop
     */
    public static void killTree(ProcessHandle root, boolean childrenFirst, Duration grace)
    {
        List<ProcessHandle> children = root.descendants().toList();
        List<ProcessHandle> tree = new ArrayList<>(children);
        tree.add(root);
        if (childrenFirst)
        {
            children.forEach(ProcessHandle::destroyForcibly);
            // No child: the root is the program itself, not a bootloader that would end with it.
            if (children.isEmpty())
            {
                root.destroy();
            }
        }
        else
        {
            tree.forEach(ProcessHandle::destroy);
        }
        List<ProcessHandle> waitedFor = childrenFirst ? List.of(root) : tree;
        try
        {
            CompletableFuture.allOf(waitedFor.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new))
                    .get(grace.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (TimeoutException | ExecutionException e)
        {
            // Still alive after its grace; forced below.
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }
}
