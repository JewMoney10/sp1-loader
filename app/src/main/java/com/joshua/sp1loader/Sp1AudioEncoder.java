package com.joshua.sp1loader;

import java.io.IOException;
import java.io.InputStream;

/**
 * Streaming WAV parser + SP-1 sector encoder.
 *
 * Reads just the WAV header up front (to learn format + total length, and
 * any embedded title/artist tags), then encodes one 8192-byte SP-1 sector
 * at a time as audio bytes are streamed in - constant memory regardless of
 * song length. A single ~4 minute song at 8ch/24bit/48kHz is 250+ MB raw;
 * buffering that (or the several full-size copies a whole-file encode
 * needs) blows through Android's per-app heap limit. This never holds more
 * than one sector's worth of data at a time.
 */
public class Sp1AudioEncoder {

    private static final int FRAME_SIZE = 24;
    private static final int STEM_FRAME_SIZE = 6;
    private static final int BLOCK_SIZE = 2048;
    public static final int FRAMES_PER_SECTOR = 340;
    private static final int[] BLOCK_ORDER = {0, 2, 1, 3};
    private static final int CLOCK_MAX = 49152;
    private static final int CLOCK_INCR = 512;
    private static final int OFFSET_LEDS = 2044;
    private static final int OFFSET_TEMPO = 2042;
    private static final int OFFSET_CLOCK = 2040;

    /** Format + length info read from the WAV header, without touching audio data. */
    public static class WavHeader {
        public int numChannels;
        public int sampleRate;
        public int bitsPerSample;
        public int dataSize;     // bytes of raw PCM audio data
        public int totalFrames;
        public int totalSectors;
        public String titleFromMetadata;  // from a LIST/INFO/INAM tag, if present
        public String artistFromMetadata; // from a LIST/INFO/IART tag, if present
    }

    /**
     * Reads just the RIFF/fmt/LIST/data chunk headers from the stream,
     * validating the format matches what the SP-1 expects (24-bit, 48kHz,
     * PCM), and picking up any embedded title/artist tags along the way. On
     * return, the stream is positioned exactly at the start of the raw PCM
     * audio data, ready for sequential reads via SectorEncoder. Throws
     * IllegalArgumentException on a bad/unsupported format, IOException on
     * a stream error.
     */
    public static WavHeader readWavHeader(InputStream in) throws IOException {
        byte[] tagBuf = new byte[4];

        readFully(in, tagBuf, 4); // "RIFF"
        if (!tagEquals(tagBuf, "RIFF")) {
            throw new IllegalArgumentException("Not a RIFF file (first 4 bytes were: "
                    + describeBytes(tagBuf) + ")");
        }
        readFully(in, tagBuf, 4); // RIFF chunk size (unused)
        readFully(in, tagBuf, 4); // "WAVE"
        if (!tagEquals(tagBuf, "WAVE")) throw new IllegalArgumentException("Not a WAVE file");

        Integer numChannels = null, sampleRate = null, bitsPerSample = null, audioFormat = null, subFormat = null;
        Integer dataSize = null;
        String titleFromMetadata = null;
        String artistFromMetadata = null;

        while (dataSize == null) {
            readFully(in, tagBuf, 4);
            String id = new String(tagBuf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            byte[] sizeBuf = new byte[4];
            readFully(in, sizeBuf, 4);
            long size = readU32LE(sizeBuf, 0);

            if ("fmt ".equals(id)) {
                byte[] fmtChunk = new byte[(int) size];
                readFully(in, fmtChunk, (int) size);
                if (size % 2 == 1) skipFully(in, 1); // chunk padding byte
                int af = readU16LE(fmtChunk, 0);
                audioFormat = af;
                subFormat = (af == 0xFFFE && size >= 40) ? readU16LE(fmtChunk, 24) : null;
                numChannels = readU16LE(fmtChunk, 2);
                sampleRate = (int) readU32LE(fmtChunk, 4);
                bitsPerSample = readU16LE(fmtChunk, 14);
            } else if ("LIST".equals(id)) {
                byte[] listChunk = new byte[(int) size];
                readFully(in, listChunk, (int) size);
                if (size % 2 == 1) skipFully(in, 1);
                String[] info = parseListInfo(listChunk);
                if (info[0] != null) titleFromMetadata = info[0];
                if (info[1] != null) artistFromMetadata = info[1];
            } else if ("data".equals(id)) {
                dataSize = (int) size;
                // stream is now positioned right at the start of audio data - stop here,
                // don't read/skip anything else even if size is odd.
            } else {
                skipFully(in, size + (size % 2));
            }
        }

        if (audioFormat == null) throw new IllegalArgumentException("No fmt chunk");
        int effectiveFormat = (audioFormat == 0xFFFE) ? (subFormat == null ? -1 : subFormat) : audioFormat;
        if (effectiveFormat != 1) {
            throw new IllegalArgumentException("Audio format must be PCM (got 0x"
                    + Integer.toHexString(effectiveFormat) + ")");
        }
        if (bitsPerSample != 24) {
            throw new IllegalArgumentException("Bit depth must be 24 (got " + bitsPerSample + ")");
        }
        if (sampleRate != 48000) {
            throw new IllegalArgumentException("Sample rate must be 48000 Hz (got " + sampleRate + ")");
        }
        if (numChannels == 0) {
            throw new IllegalArgumentException("Must have at least 1 channel");
        }

        WavHeader header = new WavHeader();
        header.numChannels = numChannels;
        header.sampleRate = sampleRate;
        header.bitsPerSample = bitsPerSample;
        header.dataSize = dataSize;
        header.totalFrames = dataSize / (numChannels * 3);
        header.totalSectors = (int) Math.ceil((double) header.totalFrames / FRAMES_PER_SECTOR);
        header.titleFromMetadata = titleFromMetadata;
        header.artistFromMetadata = artistFromMetadata;
        return header;
    }

    /**
     * Parses a WAV LIST/INFO chunk's bytes for INAM (title) and IART
     * (artist) sub-chunks. Returns {title, artist}, either may be null.
     */
    private static String[] parseListInfo(byte[] listChunk) {
        String title = null, artist = null;
        if (listChunk.length < 4) return new String[]{null, null};
        String type = new String(listChunk, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
        if (!"INFO".equals(type)) return new String[]{null, null};

        int pos = 4;
        while (pos + 8 <= listChunk.length) {
            String subId = new String(listChunk, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long subSize = readU32LE(listChunk, pos + 4);
            int dataStart = pos + 8;
            int dataEnd = (int) Math.min(listChunk.length, dataStart + subSize);
            if (dataEnd < dataStart) break; // malformed - bail out rather than loop forever

            String value = new String(listChunk, dataStart, dataEnd - dataStart,
                    java.nio.charset.StandardCharsets.US_ASCII);
            int nul = value.indexOf('\0');
            if (nul >= 0) value = value.substring(0, nul);
            value = value.trim();

            if ("INAM".equals(subId) && value.length() > 0) title = value;
            if ("IART".equals(subId) && value.length() > 0) artist = value;

            pos = dataStart + (int) subSize + (int) (subSize % 2);
        }
        return new String[]{title, artist};
    }

    /**
     * Stateful per-song encoder: carries the tempo/clock state across
     * sectors (it depends on prior sectors, same as the reference JS) while
     * encoding one 8192-byte sector at a time from raw WAV bytes read
     * sequentially off the stream. Create one instance per song.
     */
    public static class SectorEncoder {
        private final int numChannels;
        private final double samplesPerTick;
        private final int tempo;
        private int clock = 0;
        private double clockAcc = 0;
        private int lastTickSector = 0;
        private double nextTickSample = 0;
        private boolean firstTick = true;

        public SectorEncoder(int numChannels, double bpm) {
            this.numChannels = numChannels;
            if (Double.isNaN(bpm)) bpm = 80;
            bpm = Math.max(20, Math.min(300, bpm));
            this.samplesPerTick = (48000.0 * 60.0) / (24.0 * bpm);
            this.tempo = (int) Math.round(samplesPerTick);
        }

        /**
         * Reads exactly framesInSector frames' worth of raw WAV bytes from
         * `in` and encodes them into one 8192-byte SP-1 sector. sectorIndex
         * is the song-relative sector number (0-based), needed for the
         * tempo/clock timing math (which depends on elapsed sectors).
         */
        public byte[] encodeNextSector(InputStream in, int framesInSector, int sectorIndex) throws IOException {
            byte[] rawFrameBytes = new byte[framesInSector * numChannels * 3];
            readFully(in, rawFrameBytes, rawFrameBytes.length);

            byte[] output = new byte[Sp1Protocol.SECTOR_SIZE];
            int sectorFrameEnd = (sectorIndex + 1) * FRAMES_PER_SECTOR;
            int[] envelopes = new int[4];

            for (int frame = 0; frame < framesInSector; frame++) {
                int blockId = BLOCK_ORDER[frame % 4];
                int byteOffset = BLOCK_SIZE * blockId + FRAME_SIZE * (frame / 4);
                int frameBase = frame * numChannels * 3;

                for (int stem = 0; stem < 4; stem++) {
                    int L = readSample(rawFrameBytes, frameBase, numChannels, stem * 2);
                    int R = readSample(rawFrameBytes, frameBase, numChannels, stem * 2 + 1);
                    int base = byteOffset + stem * STEM_FRAME_SIZE;

                    envelopes[stem] = Math.max(Math.max(envelopes[stem], Math.abs(L)), Math.abs(R));

                    // SP-1 stem byte order: [L_MB, L_MSB, R_MSB, L_LSB, R_LSB, R_MB]
                    output[base] = (byte) ((L >> 8) & 0xFF);
                    output[base + 1] = (byte) ((L >> 16) & 0xFF);
                    output[base + 2] = (byte) ((R >> 16) & 0xFF);
                    output[base + 3] = (byte) (L & 0xFF);
                    output[base + 4] = (byte) (R & 0xFF);
                    output[base + 5] = (byte) ((R >> 8) & 0xFF);
                }
            }

            for (int i = 0; i < 4; i++) {
                int ledValue = (int) (255.0 * envelopes[i] / 0x800000);
                output[OFFSET_LEDS + i] = (byte) (ledValue & 0xFF);
            }

            boolean clockTick = false;
            while (nextTickSample < sectorFrameEnd) {
                clockTick = true;
                if (firstTick) {
                    clock = 1;
                    firstTick = false;
                } else {
                    int elapsedSamples = (sectorIndex - lastTickSector) * FRAMES_PER_SECTOR;
                    clockAcc += (CLOCK_INCR + samplesPerTick - elapsedSamples);
                    int increment = (int) Math.floor(clockAcc);
                    clockAcc -= increment;
                    clock = (clock + increment) % CLOCK_MAX;
                }
                lastTickSector = sectorIndex;
                nextTickSample += samplesPerTick;
            }

            if (clockTick) {
                output[OFFSET_CLOCK] = (byte) (clock & 0xFF);
                output[OFFSET_CLOCK + 1] = (byte) ((clock >>> 8) & 0xFF);
                output[OFFSET_TEMPO] = (byte) (tempo & 0xFF);
                output[OFFSET_TEMPO + 1] = (byte) ((tempo >>> 8) & 0xFF);
            } else {
                output[OFFSET_CLOCK] = (byte) 0xFF;
                output[OFFSET_CLOCK + 1] = (byte) 0xFF;
                output[OFFSET_TEMPO] = 0x00;
                output[OFFSET_TEMPO + 1] = 0x00;
            }

            return output;
        }
    }

    private static int readSample(byte[] rawFrameBytes, int frameBase, int numChannels, int channel) {
        if (channel >= numChannels) return 0;
        int b = frameBase + channel * 3;
        int s = (rawFrameBytes[b] & 0xFF) | ((rawFrameBytes[b + 1] & 0xFF) << 8)
                | ((rawFrameBytes[b + 2] & 0xFF) << 16);
        if ((s & 0x800000) != 0) s |= 0xFF000000; // sign-extend 24-bit -> 32-bit
        return s;
    }

    // ---------------------------------------------------------------
    // Small stream/byte helpers
    // ---------------------------------------------------------------

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, total, len - total);
            if (n < 0) throw new IOException("Unexpected end of file while reading WAV data");
            total += n;
        }
    }

    private static void skipFully(InputStream in, long len) throws IOException {
        long total = 0;
        byte[] scratch = new byte[4096];
        while (total < len) {
            int toRead = (int) Math.min(scratch.length, len - total);
            int n = in.read(scratch, 0, toRead);
            if (n < 0) throw new IOException("Unexpected end of file while skipping WAV chunk");
            total += n;
        }
    }

    private static boolean tagEquals(byte[] buf, String tag) {
        return new String(buf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals(tag);
    }

    private static String describeBytes(byte[] buf) {
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) {
            sb.append(String.format("%02x ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    private static long readU32LE(byte[] buf, int offset) {
        return (buf[offset] & 0xFFL) | ((buf[offset + 1] & 0xFFL) << 8)
                | ((buf[offset + 2] & 0xFFL) << 16) | ((buf[offset + 3] & 0xFFL) << 24);
    }

    private static int readU16LE(byte[] buf, int offset) {
        return (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
    }
}
