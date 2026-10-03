package io.github.mocchikon.hentie.service.scratch;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

/**
 * @param root       this app's own folder on the mount, never the mount itself
 * @param totalBytes the mount's size limit, which caps the caches there
 */
public record RamDisk(Path root, long totalBytes)
{
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    /** Looked up once: resolving a user name reads the password database. */
    private static volatile UserPrincipal currentUser;

    /**
     * Read fresh on every call, because other processes share the mount. {@code File.getUsableSpace} is one
     * {@code statvfs}; {@code Files.getFileStore} would re-read the mount table.
     */
    public long usableBytes()
    {
        return root.toFile().getUsableSpace();
    }

    /**
     * Checked on every use, not only at startup: {@code systemd-tmpfiles} can delete the folder while the app
     * runs, and a plain {@code createDirectories} would then recreate it with umask permissions, or write
     * through a folder another user planted. Every thumbnail asks, so it must stay one {@code lstat}.
     *
     * @throws IOException when the folder is not a private directory of this user
     */
    public void ensurePrivate() throws IOException
    {
        claim(root);
    }

    /**
     * Accepts {@code root} only as a real directory, owned by this user and owner-only. The mount is
     * world-writable, so otherwise another local user could read the images or plant the folder (or a
     * symlink) first.
     *
     * <p>The owner is checked, not only the bits: an app running as root could write into a planted
     * {@code 0700} folder.
     */
    static void claim(Path root) throws IOException
    {
        // No POSIX attributes: only a test using a plain folder as a RAM disk gets here.
        if (!root.getFileSystem().supportedFileAttributeViews().contains("posix"))
        {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS))
            {
                createDirectory(root, false);
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(root))
            {
                throw new IOException(root + " is not a writable directory");
            }
            return;
        }
        PosixFileAttributes attributes;
        try
        {
            attributes = attributesOf(root);
        }
        catch (NoSuchFileException gone)
        {
            createDirectory(root, true);
            attributes = attributesOf(root);
        }
        if (!attributes.isDirectory() || !OWNER_ONLY.equals(attributes.permissions())
                || !attributes.owner().equals(currentUser(root)))
        {
            throw new IOException(root + " is not a private directory of this user");
        }
    }

    private static PosixFileAttributes attributesOf(Path root) throws IOException
    {
        return Files.readAttributes(root, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void createDirectory(Path root, boolean posix) throws IOException
    {
        try
        {
            if (posix)
            {
                Files.createDirectory(root, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
                // The umask is applied to the attribute above, so set the bits explicitly.
                Files.setPosixFilePermissions(root, OWNER_ONLY);
            }
            else
            {
                Files.createDirectory(root);
            }
        }
        catch (FileAlreadyExistsException raced)
        {
            // Whoever made it, the caller's check decides whether it is usable.
        }
    }

    /** A name the system cannot resolve (an arbitrary container uid) throws, so the RAM disk stays unused. */
    private static UserPrincipal currentUser(Path root) throws IOException
    {
        UserPrincipal user = currentUser;
        if (user == null)
        {
            user = root.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            currentUser = user;
        }
        return user;
    }
}
