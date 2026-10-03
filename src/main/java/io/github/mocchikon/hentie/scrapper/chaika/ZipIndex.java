package io.github.mocchikon.hentie.scrapper.chaika;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads a zip archive's index and single entries from <b>parts</b> of the file, so an archive on a server that
 * answers byte ranges can be read page by page without downloading it. Only parsing: fetching the parts is the
 * caller's.
 * <p>
 * Sizes and offsets come from the <b>central directory</b>, never the local headers: an archive written as a stream
 * (flag bit 3) has zeros there. ZIP64 is supported, since a big image set passes 4 GB.
 */
final class ZipIndex
{
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int EOCD_SIZE = 22;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int ZIP64_LOCATOR_SIZE = 20;
    private static final int ZIP64_EOCD_SIGNATURE = 0x06064b50;
    static final int ZIP64_EOCD_SIZE = 56;
    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int LOCAL_SIGNATURE = 0x04034b50;
    static final int LOCAL_HEADER_SIZE = 30;
    private static final long FFFFFFFF = 0xFFFFFFFFL;

    /** The end record plus the longest comment it can have: the tail to fetch to be sure to find it. */
    static final int MAX_TAIL = EOCD_SIZE + 0xFFFF;

    static final int STORED = 0;
    static final int DEFLATED = 8;

    private ZipIndex()
    {
    }

    /** One file of the archive, as the central directory describes it. */
    record Entry(String name, int method, long compressedSize, long size, long crc, long localHeaderOffset,
                 boolean encrypted, boolean directory)
    {
    }

    /**
     * Where the central directory is. {@code zip64RecordOffset} is set instead when the end record points to a
     * ZIP64 record outside the fetched tail, which the caller then fetches ({@link #ZIP64_EOCD_SIZE} bytes).
     */
    record Directory(long offset, long size, long zip64RecordOffset)
    {
        boolean needsZip64Record()
        {
            return zip64RecordOffset >= 0;
        }
    }

    /**
     * @param tail      the last bytes of the archive
     * @param totalSize the archive's size, to place the tail
     * @throws IOException when the tail holds no end record: not a zip, or one cut short
     */
    static Directory locate(byte[] tail, long totalSize) throws IOException
    {
        ByteBuffer buffer = little(tail);
        long tailStart = totalSize - tail.length;
        for (int pos = tail.length - EOCD_SIZE; pos >= 0; pos--)
        {
            if (buffer.getInt(pos) != EOCD_SIGNATURE)
            {
                continue;
            }
            int commentLength = Short.toUnsignedInt(buffer.getShort(pos + 20));
            if (pos + EOCD_SIZE + commentLength != tail.length)
            {
                continue;   // a signature inside the comment or the data, not the record
            }
            long size = Integer.toUnsignedLong(buffer.getInt(pos + 12));
            long offset = Integer.toUnsignedLong(buffer.getInt(pos + 16));
            int entries = Short.toUnsignedInt(buffer.getShort(pos + 10));
            boolean zip64 = size == FFFFFFFF || offset == FFFFFFFF || entries == 0xFFFF;
            int locator = pos - ZIP64_LOCATOR_SIZE;
            if (locator >= 0 && buffer.getInt(locator) == ZIP64_LOCATOR_SIGNATURE)
            {
                zip64 = true;
            }
            if (!zip64)
            {
                return new Directory(offset, size, -1);
            }
            if (locator < 0 || buffer.getInt(locator) != ZIP64_LOCATOR_SIGNATURE)
            {
                throw new IOException("The archive says it is ZIP64 but has no ZIP64 locator.");
            }
            long recordOffset = buffer.getLong(locator + 8);
            long inTail = recordOffset - tailStart;
            if (inTail >= 0 && inTail + ZIP64_EOCD_SIZE <= tail.length)
            {
                return zip64Directory(slice(tail, (int) inTail, ZIP64_EOCD_SIZE));
            }
            return new Directory(-1, -1, recordOffset);
        }
        throw new IOException("No end of a zip archive found: the file is not a zip, or it is cut short.");
    }

    /** From the ZIP64 end record, {@link #ZIP64_EOCD_SIZE} bytes. */
    static Directory zip64Directory(byte[] record) throws IOException
    {
        ByteBuffer buffer = little(record);
        if (record.length < ZIP64_EOCD_SIZE || buffer.getInt(0) != ZIP64_EOCD_SIGNATURE)
        {
            throw new IOException("The archive's ZIP64 end record is damaged.");
        }
        return new Directory(buffer.getLong(48), buffer.getLong(40), -1);
    }

    static List<Entry> parseCentralDirectory(byte[] directory) throws IOException
    {
        ByteBuffer buffer = little(directory);
        var entries = new ArrayList<Entry>();
        int pos = 0;
        while (pos + 46 <= directory.length && buffer.getInt(pos) == CENTRAL_SIGNATURE)
        {
            int flags = Short.toUnsignedInt(buffer.getShort(pos + 8));
            int method = Short.toUnsignedInt(buffer.getShort(pos + 10));
            long crc = Integer.toUnsignedLong(buffer.getInt(pos + 16));
            long compressed = Integer.toUnsignedLong(buffer.getInt(pos + 20));
            long size = Integer.toUnsignedLong(buffer.getInt(pos + 24));
            int nameLength = Short.toUnsignedInt(buffer.getShort(pos + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(pos + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(pos + 32));
            long offset = Integer.toUnsignedLong(buffer.getInt(pos + 42));
            int nameStart = pos + 46;
            int extraStart = nameStart + nameLength;
            int next = extraStart + extraLength + commentLength;
            if (next > directory.length)
            {
                throw new IOException("The archive's index is cut short.");
            }
            Charset charset = (flags & 0x800) != 0 ? StandardCharsets.UTF_8 : cp437();
            String name = new String(directory, nameStart, nameLength, charset);
            // ZIP64: the values stored as 0xFFFFFFFF follow in this order in extra field 0x0001.
            int extra = extraStart;
            while (extra + 4 <= extraStart + extraLength)
            {
                int id = Short.toUnsignedInt(buffer.getShort(extra));
                int length = Short.toUnsignedInt(buffer.getShort(extra + 2));
                int field = extra + 4;
                if (id == 0x0001)
                {
                    if (size == FFFFFFFF && field + 8 <= extra + 4 + length)
                    {
                        size = buffer.getLong(field);
                        field += 8;
                    }
                    if (compressed == FFFFFFFF && field + 8 <= extra + 4 + length)
                    {
                        compressed = buffer.getLong(field);
                        field += 8;
                    }
                    if (offset == FFFFFFFF && field + 8 <= extra + 4 + length)
                    {
                        offset = buffer.getLong(field);
                    }
                }
                extra += 4 + length;
            }
            entries.add(new Entry(name, method, compressed, size, crc, offset, (flags & 1) != 0, name.endsWith("/")));
            pos = next;
        }
        // Stopping early quietly would make a damaged index a gallery with fewer pages.
        if (pos != directory.length)
        {
            throw new IOException("The archive's index is damaged or cut short.");
        }
        return entries;
    }

    private static Charset cp437()
    {
        try
        {
            return Charset.forName("IBM437");
        }
        catch (RuntimeException e)
        {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /**
     * Where an entry's data starts within bytes fetched from its local header on, or -1 when more of them are
     * needed to tell.
     *
     * @throws IOException when the bytes are no local header
     */
    static int dataStart(byte[] fromLocalHeader) throws IOException
    {
        if (fromLocalHeader.length < LOCAL_HEADER_SIZE)
        {
            return -1;
        }
        ByteBuffer buffer = little(fromLocalHeader);
        if (buffer.getInt(0) != LOCAL_SIGNATURE)
        {
            throw new IOException("The archive has no file where its index says one starts.");
        }
        return LOCAL_HEADER_SIZE + Short.toUnsignedInt(buffer.getShort(26)) + Short.toUnsignedInt(buffer.getShort(28));
    }

    /**
     * The entry's bytes from its stored data, checked against the index: a wrong size or checksum is a transfer
     * that went wrong, or an archive that changed meanwhile.
     */
    static byte[] content(Entry entry, byte[] data, long maxBytes) throws IOException
    {
        if (entry.size() > maxBytes)
        {
            throw new IOException(entry.name() + " is " + entry.size() + " bytes, more than a page may be.");
        }
        byte[] content = switch (entry.method())
        {
            case STORED -> data;
            case DEFLATED -> inflate(data, (int) entry.size());
            default -> throw new IOException(entry.name() + " uses a compression this app cannot read (method "
                    + entry.method() + ").");
        };
        if (content.length != entry.size())
        {
            throw new IOException(entry.name() + " arrived with " + content.length + " bytes instead of "
                    + entry.size() + ".");
        }
        var crc = new CRC32();
        crc.update(content);
        if (crc.getValue() != entry.crc())
        {
            throw new IOException(entry.name() + " arrived damaged (its checksum does not match the archive's).");
        }
        return content;
    }

    private static byte[] inflate(byte[] data, int size) throws IOException
    {
        var inflater = new Inflater(true);
        try
        {
            inflater.setInput(data);
            byte[] out = new byte[size];
            int read = 0;
            while (read < size && !inflater.finished())
            {
                int n = inflater.inflate(out, read, size - read);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary()))
                {
                    break;
                }
                read += n;
            }
            if (read < size)
            {
                throw new IOException("A compressed page does not have the size the archive says.");
            }
            return out;
        }
        catch (DataFormatException e)
        {
            throw new IOException("A compressed page is damaged: " + e.getMessage(), e);
        }
        finally
        {
            inflater.end();
        }
    }

    /**
     * Natural order: runs of digits compare as numbers ({@code 9.jpg} before {@code 10.jpg}), equal numbers with
     * more leading zeros last, everything else without regard to case. How a reader expects pages to follow.
     */
    static final Comparator<String> NATURAL_ORDER = ZipIndex::compareNatural;

    private static int compareNatural(String a, String b)
    {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length())
        {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb))
            {
                int endA = digitsEnd(a, i);
                int endB = digitsEnd(b, j);
                String numA = stripZeros(a.substring(i, endA));
                String numB = stripZeros(b.substring(j, endB));
                int byValue = numA.length() != numB.length() ? Integer.compare(numA.length(), numB.length())
                        : numA.compareTo(numB);
                if (byValue != 0)
                {
                    return byValue;
                }
                int byZeros = Integer.compare(endA - i, endB - j);
                if (byZeros != 0)
                {
                    return byZeros;
                }
                i = endA;
                j = endB;
                continue;
            }
            int byChar = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (byChar != 0)
            {
                return byChar;
            }
            i++;
            j++;
        }
        int byLength = Integer.compare(a.length() - i, b.length() - j);
        return byLength != 0 ? byLength : a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT));
    }

    private static int digitsEnd(String s, int from)
    {
        int end = from;
        while (end < s.length() && Character.isDigit(s.charAt(end)))
        {
            end++;
        }
        return end;
    }

    private static String stripZeros(String digits)
    {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0')
        {
            start++;
        }
        return digits.substring(start);
    }

    private static ByteBuffer little(byte[] bytes)
    {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] slice(byte[] bytes, int from, int length)
    {
        byte[] part = new byte[length];
        System.arraycopy(bytes, from, part, 0, length);
        return part;
    }
}
