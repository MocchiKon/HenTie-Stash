package io.github.mocchikon.hentie.scrapper.chaika;

import io.github.mocchikon.hentie.FakeChaika;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

/** Reading a zip from parts of it, as the chaika source does over byte ranges. */
class ZipIndexTest
{
    @Test
    void shouldReadEveryEntryFromTheTailAndTheEntrysOwnBytes() throws IOException
    {
        for (boolean deflate : List.of(false, true))
        {
            // GIVEN an archive
            var files = new LinkedHashMap<String, byte[]>();
            files.put("10.jpg", "page ten".repeat(50).getBytes(StandardCharsets.UTF_8));
            files.put("9.jpg", "page nine".getBytes(StandardCharsets.UTF_8));
            byte[] zip = FakeChaika.zip(files, deflate);

            // WHEN its index is read from the tail alone
            byte[] tail = Arrays.copyOfRange(zip, Math.max(0, zip.length - ZipIndex.MAX_TAIL), zip.length);
            ZipIndex.Directory directory = ZipIndex.locate(tail, zip.length);
            byte[] central = Arrays.copyOfRange(zip, (int) directory.offset(), (int) (directory.offset() + directory.size()));
            List<ZipIndex.Entry> entries = ZipIndex.parseCentralDirectory(central);

            // THEN every entry reads back from its own bytes, checked against its checksum.
            assertThat(entries).extracting(ZipIndex.Entry::name).containsExactly("10.jpg", "9.jpg");
            for (ZipIndex.Entry entry : entries)
            {
                byte[] fromHeader = Arrays.copyOfRange(zip, (int) entry.localHeaderOffset(), zip.length);
                int start = ZipIndex.dataStart(fromHeader);
                byte[] data = Arrays.copyOfRange(fromHeader, start, start + (int) entry.compressedSize());
                assertThat(ZipIndex.content(entry, data, 1 << 20)).isEqualTo(files.get(entry.name()));
            }
        }
    }

    @Test
    void shouldRefuseAnEntryThatArrivedDamaged() throws IOException
    {
        // GIVEN a stored entry whose bytes changed on the way
        byte[] zip = FakeChaika.zip(Map.of("1.png", "original".getBytes(StandardCharsets.UTF_8)), false);
        byte[] tail = Arrays.copyOfRange(zip, 0, zip.length);
        ZipIndex.Directory directory = ZipIndex.locate(tail, zip.length);
        ZipIndex.Entry entry = ZipIndex.parseCentralDirectory(
                Arrays.copyOfRange(zip, (int) directory.offset(), (int) (directory.offset() + directory.size()))).getFirst();

        // WHEN + THEN
        assertThatIOException().isThrownBy(() ->
                        ZipIndex.content(entry, "0riginal".getBytes(StandardCharsets.UTF_8), 1 << 20))
                .withMessageContaining("damaged");
        assertThatIOException().isThrownBy(() -> ZipIndex.content(entry, "orig".getBytes(StandardCharsets.UTF_8), 1 << 20))
                .withMessageContaining("instead of");
        assertThatIOException().isThrownBy(() -> ZipIndex.content(entry, new byte[8], 4))
                .withMessageContaining("more than a page may be");
    }

    @Test
    void shouldFindTheDirectoryThroughAZip64EndRecord() throws IOException
    {
        // GIVEN an archive whose end record defers to a ZIP64 one, as an archive past 4 GB has
        byte[] plain = FakeChaika.zip(Map.of("1.jpg", "one".getBytes(StandardCharsets.UTF_8)), false);
        ByteBuffer eocd = ByteBuffer.wrap(plain, plain.length - 22, 22).order(ByteOrder.LITTLE_ENDIAN);
        long cdSize = Integer.toUnsignedLong(eocd.getInt(plain.length - 22 + 12));
        long cdOffset = Integer.toUnsignedLong(eocd.getInt(plain.length - 22 + 16));
        byte[] body = Arrays.copyOf(plain, plain.length - 22);
        ByteBuffer tail = ByteBuffer.allocate(56 + 20 + 22).order(ByteOrder.LITTLE_ENDIAN);
        long recordOffset = body.length;
        tail.putInt(0x06064b50).putLong(44).putShort((short) 45).putShort((short) 45).putInt(0).putInt(0)
                .putLong(1).putLong(1).putLong(cdSize).putLong(cdOffset);
        tail.putInt(0x07064b50).putInt(0).putLong(recordOffset).putInt(1);
        tail.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) 0xFFFF)
                .putShort((short) 0xFFFF).putInt(0xFFFFFFFF).putInt(0xFFFFFFFF).putShort((short) 0);
        byte[] zip64 = concat(body, tail.array());

        // WHEN the whole file is the tail
        ZipIndex.Directory inTail = ZipIndex.locate(zip64, zip64.length);
        // AND when only the end record and the locator are
        byte[] shortTail = Arrays.copyOfRange(zip64, zip64.length - 42, zip64.length);
        ZipIndex.Directory pointed = ZipIndex.locate(shortTail, zip64.length);

        // THEN
        assertThat(inTail.offset()).isEqualTo(cdOffset);
        assertThat(inTail.size()).isEqualTo(cdSize);
        assertThat(pointed.needsZip64Record()).isTrue();
        assertThat(pointed.zip64RecordOffset()).isEqualTo(recordOffset);
        byte[] record = Arrays.copyOfRange(zip64, (int) recordOffset, (int) recordOffset + ZipIndex.ZIP64_EOCD_SIZE);
        assertThat(ZipIndex.zip64Directory(record).offset()).isEqualTo(cdOffset);
    }

    @Test
    void shouldSaySoWhenTheTailIsNoZip()
    {
        assertThatIOException().isThrownBy(() -> ZipIndex.locate("not a zip at all".getBytes(StandardCharsets.UTF_8), 16))
                .withMessageContaining("not a zip");
    }

    @Test
    void shouldOrderNamesAsAReaderExpects()
    {
        // GIVEN chaika's names, in archive order
        var names = new ArrayList<>(List.of("236.jpg", "110.jpg", "9.jpg", "010.jpg", "10.jpg", "a/2.png", "a/10.png",
                "Cover.jpg", "b.jpg"));

        // WHEN
        names.sort(ZipIndex.NATURAL_ORDER);

        // THEN numbers by value, more leading zeros after, letters without regard to case
        assertThat(names).containsExactly("9.jpg", "10.jpg", "010.jpg", "110.jpg", "236.jpg", "a/2.png", "a/10.png",
                "b.jpg", "Cover.jpg");
    }

    private static byte[] concat(byte[] a, byte[] b)
    {
        byte[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}
