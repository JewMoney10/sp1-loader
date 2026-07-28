package com.joshua.sp1loader;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the SP-1's album metadata sector (sector 0) and the album-end
 * sector, given a list of songs. Ported from createMetaData()/
 * createAlbumEnd() in stemloader.js.
 */
public class Sp1AlbumBuilder {

    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_ALBUM_LEN = 13;
    private static final int OFFSET_NUM_SONGS = 17;
    private static final int OFFSET_ALBUM_TITLE = 18;
    private static final int OFFSET_SONGS = 82;
    private static final int STRING_LENGTH = 63;
    private static final int SONG_INDEX_LENGTH = 136;
    private static final byte[] MAGIC = "ALBUM_PRESENT".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** One song's metadata. offsetSectors/lengthSectors get filled in by build(). */
    public static class Song {
        public String artist;
        public String title;
        public int lengthSectors;  // set by caller, based on the encoded audio size
        public int offsetSectors;  // filled in by build()

        public Song(String artist, String title, int lengthSectors) {
            this.artist = (artist == null || artist.isEmpty()) ? "unknown" : artist;
            this.title = (title == null || title.isEmpty()) ? "untitled" : title;
            this.lengthSectors = lengthSectors;
        }
    }

    /** Everything needed to actually write the album to the device. */
    public static class BuiltAlbum {
        public byte[] metadataSector;   // write at sector 0
        public byte[] albumEndSector;   // write at albumEndSectorIndex
        public int albumEndSectorIndex;
        public List<Song> songs;        // each song's offsetSectors is now filled in
    }

    /**
     * Lays out a fresh album (starting at sector 1, right after the metadata
     * sector) containing the given songs in order, and builds the metadata
     * + album-end sector bytes to match.
     */
    public static BuiltAlbum build(String albumTitle, List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            throw new IllegalArgumentException("Can't build an empty album");
        }
        if (songs.size() > 60) {
            throw new IllegalArgumentException("Too many songs in album (max 60)");
        }

        // Assign offsets: metadata sector is sector 0, songs start at sector 1.
        int cursor = 1;
        for (Song song : songs) {
            song.offsetSectors = cursor;
            cursor += song.lengthSectors;
        }
        int albumLength = cursor; // = 1 (metadata) + sum of song lengths

        if (albumLength >= Sp1Protocol.MAX_SECTORS) {
            throw new IllegalArgumentException("Album too big for device memory");
        }

        BuiltAlbum result = new BuiltAlbum();
        result.songs = songs;
        result.albumEndSectorIndex = albumLength;
        result.metadataSector = buildMetadataSector(albumTitle, songs, albumLength);
        result.albumEndSector = buildAlbumEndSector();
        return result;
    }

    private static byte[] buildMetadataSector(String albumTitle, List<Song> songs, int albumLength) {
        byte[] metaData = new byte[Sp1Protocol.SECTOR_SIZE];
        java.util.Arrays.fill(metaData, (byte) 0x58); // pad entire sector with 'X'

        System.arraycopy(MAGIC, 0, metaData, OFFSET_MAGIC, MAGIC.length);

        String title = (albumTitle == null || albumTitle.isEmpty()) ? "untitled" : albumTitle;
        if (title.length() > 57) title = title.substring(0, 57);

        Sp1Protocol.writeLE32(metaData, OFFSET_ALBUM_LEN, albumLength + 1); // +1 for the end-of-album sector
        metaData[OFFSET_NUM_SONGS] = (byte) songs.size();
        writeTerminatedString(metaData, OFFSET_ALBUM_TITLE, title);

        int songOffset = OFFSET_SONGS;
        for (Song song : songs) {
            Sp1Protocol.writeLE32(metaData, songOffset, song.offsetSectors);
            Sp1Protocol.writeLE32(metaData, songOffset + 4, song.lengthSectors);
            writeTerminatedString(metaData, songOffset + 8, truncate(song.artist, STRING_LENGTH - 1));
            writeTerminatedString(metaData, songOffset + 8 + STRING_LENGTH, truncate(song.title, STRING_LENGTH - 1));
            songOffset += SONG_INDEX_LENGTH;
        }

        return metaData;
    }

    private static byte[] buildAlbumEndSector() {
        byte[] end = new byte[Sp1Protocol.SECTOR_SIZE];
        // left as zeros, magic bytes right-aligned at the very end of the sector
        System.arraycopy(MAGIC, 0, end, Sp1Protocol.SECTOR_SIZE - MAGIC.length, MAGIC.length);
        return end;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    /** Writes a null-terminated ASCII string at the given offset. */
    private static void writeTerminatedString(byte[] buf, int offset, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buf, offset, bytes.length);
        buf[offset + bytes.length] = 0; // null terminator
    }

    /** Convenience for building a single-song album. */
    public static BuiltAlbum buildSingleSong(String albumTitle, String songArtist, String songTitle,
                                              int songLengthSectors) {
        List<Song> songs = new ArrayList<Song>();
        songs.add(new Song(songArtist, songTitle, songLengthSectors));
        return build(albumTitle, songs);
    }
}
