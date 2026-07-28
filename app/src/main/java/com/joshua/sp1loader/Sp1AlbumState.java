package com.joshua.sp1loader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent track-list state, mirroring what the website keeps in
 * localStorage: which songs are queued, their computed offsets/lengths,
 * and how much of each has actually been transferred. The SP-1 itself
 * can't be queried for its song list (no such command exists), so this
 * local record is the only source of truth for "what's already there."
 *
 * Survives app restarts (SharedPreferences), same as the website surviving
 * page reloads - though unlike the website, we can often keep real access
 * to the picked files too (see MainActivity's use of
 * takePersistableUriPermission), so "file missing" should be rarer here.
 */
public class Sp1AlbumState {

    private static final String PREFS_NAME = "sp1_album_state";
    private static final String KEY_ALBUM_TITLE = "album_title";
    private static final String KEY_SONGS = "songs";

    /** One song's persisted state. */
    public static class Song {
        public String fileUri;          // content:// URI as a string, for re-reading the file
        public String fileName;         // display name, used as a title fallback
        public String title;
        public String artist;
        public double bpm;
        public int offsetSectors;       // computed by recomputeOffsets(), not user-set
        public int lengthSectors;       // from the WAV header
        public int transferredSectors;  // 0 = not started, == lengthSectors = fully done

        public boolean isDone() {
            return lengthSectors > 0 && transferredSectors >= lengthSectors;
        }
    }

    private final SharedPreferences prefs;
    private String albumTitle;
    private List<Song> songs;

    public Sp1AlbumState(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        albumTitle = prefs.getString(KEY_ALBUM_TITLE, "untitled");
        songs = new ArrayList<Song>();
        String json = prefs.getString(KEY_SONGS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Song s = new Song();
                s.fileUri = o.optString("fileUri", "");
                s.fileName = o.optString("fileName", "");
                s.title = o.optString("title", "untitled");
                s.artist = o.optString("artist", "unknown");
                s.bpm = o.optDouble("bpm", 80.0);
                s.offsetSectors = o.optInt("offsetSectors", 0);
                s.lengthSectors = o.optInt("lengthSectors", 0);
                s.transferredSectors = o.optInt("transferredSectors", 0);
                songs.add(s);
            }
        } catch (JSONException e) {
            songs = new ArrayList<Song>();
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Song s : songs) {
                JSONObject o = new JSONObject();
                o.put("fileUri", s.fileUri);
                o.put("fileName", s.fileName);
                o.put("title", s.title);
                o.put("artist", s.artist);
                o.put("bpm", s.bpm);
                o.put("offsetSectors", s.offsetSectors);
                o.put("lengthSectors", s.lengthSectors);
                o.put("transferredSectors", s.transferredSectors);
                arr.put(o);
            }
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_ALBUM_TITLE, albumTitle);
            editor.putString(KEY_SONGS, arr.toString());
            editor.apply();
        } catch (JSONException e) {
            // shouldn't happen with the fixed fields we write above
        }
    }

    public String getAlbumTitle() {
        return albumTitle;
    }

    public void setAlbumTitle(String title) {
        this.albumTitle = (title == null || title.isEmpty()) ? "untitled" : title;
        save();
    }

    public List<Song> getSongs() {
        return songs;
    }

    /** Adds a new song at the end of the list and recomputes offsets. */
    /**
     * Adds a new song at the end of the list, unless doing so would push the
     * album past the SP-1's storage capacity - matching the reference
     * site's own "Can't add song - not enough memory on SP-1" check.
     * Returns null (and adds nothing) if there isn't room.
     */
    public Song addSong(String fileUri, String fileName, String title, String artist, double bpm, int lengthSectors) {
        // +1 for the album-end sector, matching the reference site's own capacity check
        if (getAlbumLengthSectors() + lengthSectors + 1 >= Sp1Protocol.MAX_SECTORS) {
            return null;
        }
        Song s = new Song();
        s.fileUri = fileUri;
        s.fileName = fileName;
        s.title = (title == null || title.isEmpty()) ? fileName : title;
        s.artist = (artist == null || artist.isEmpty()) ? "unknown" : artist;
        s.bpm = bpm;
        s.lengthSectors = lengthSectors;
        s.transferredSectors = 0;
        songs.add(s);
        recomputeOffsets();
        save();
        return s;
    }

    /**
     * Removes the song at the given index. Per the reference site's own
     * documented behavior, every song AFTER the removed one shifts to a new
     * offset, so those get marked as needing retransfer.
     */
    public void removeSong(int index) {
        if (index < 0 || index >= songs.size()) return;
        songs.remove(index);
        recomputeOffsets();
        save();
    }

    /** Call after writing sector `sectorIndex` (0-based, within the song) to persist resume progress. */
    public void markSectorTransferred(Song song, int sectorIndexWithinSong) {
        song.transferredSectors = Math.max(song.transferredSectors, sectorIndexWithinSong + 1);
        save();
    }

    /**
     * Recomputes each song's offset in sequence (sector 1 onward, sector 0
     * is metadata). Any song whose offset changes as a result gets marked
     * not-transferred, since whatever bytes were previously written there
     * now belong to a different song's territory.
     */
    private void recomputeOffsets() {
        int cursor = 1;
        for (Song s : songs) {
            if (s.offsetSectors != cursor) {
                s.transferredSectors = 0;
            }
            s.offsetSectors = cursor;
            cursor += s.lengthSectors;
        }
    }

    public int getAlbumLengthSectors() {
        int total = 1; // metadata sector
        for (Song s : songs) total += s.lengthSectors;
        return total;
    }
}
