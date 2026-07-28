package com.joshua.sp1loader;

import java.util.ArrayList;

/**
 * SP-1 wire protocol: CRC8, COBS framing, packet build/parse, and the CMD
 * opcode table. Ported directly from sp-1_protocol.js - pure byte
 * manipulation, no USB/IO code here on purpose, so it can be tested and
 * reasoned about independently of the transport.
 *
 * Packet shape (before COBS encoding):
 *   [0x51, seq, cmd, payloadLen, ...payload, crc8]
 * That whole thing gets COBS-encoded (which also appends a trailing 0x00
 * delimiter) before being written to the serial port.
 *
 * Response cmd values follow the pattern (request cmd + 1), e.g. ECHO
 * (0x01) gets back cmd 0x02, GET_DEV_STATE (0x52/'R') gets back 'S'
 * (0x53). An error response uses cmd 0xFF with a human-readable payload.
 */
public class Sp1Protocol {

    // ---------------------------------------------------------------
    // CMD opcode table
    // ---------------------------------------------------------------

    public static final int CMD_ECHO = 0x01;
    public static final int CMD_CLEAR_FOR_WRITE = 0x37; // '7' - clears write counter
    public static final int CMD_WRITE = 0x39;            // '9'
    public static final int CMD_GET_WRITE_COUNTER = 0x43; // 'C'
    public static final int CMD_EXIT_BOOTLOADER = 0x50;   // 'P'
    public static final int CMD_RESET = 0x51;             // 'Q'
    public static final int CMD_GET_DEV_STATE = 0x52;     // 'R'
    public static final int CMD_GET_ALBUM_TITLE = 0x58;   // 'X'
    public static final int CMD_GET_DEV_ID = 0x54;        // 'T'
    public static final int CMD_SET_LEDS = 0x5A;          // 'Z'
    public static final int CMD_GET_BUTTONS = 0x5C;       // '\'
    public static final int CMD_PING = 0x62;              // 'b'
    public static final int CMD_GET_FADERS = 0x64;        // 'd'
    public static final int CMD_VERIFY_DATA = 0x66;       // 'f'
    public static final int CMD_SET_DEV_STATE = 0x70;     // 'p'
    public static final int CMD_GET_LADDERS = 0x74;       // 't'
    public static final int CMD_GET_CHARGE_STATE = 0x7a;  // 'z'
    public static final int CMD_FREQ_SWEEP = 0x56;        // 'V'
    public static final int CMD_GET_BATT_LEVEL = 0x60;    // '`'

    public static final int CMD_ERROR = 0xFF;

    // Data transfer constants (used once we get to WRITE)
    public static final int SECTOR_SIZE = 8192;
    public static final int CHUNK_SIZE = 128;
    public static final int CHUNKS_PER_SECTOR = 64;
    public static final int PAYLOAD_SIZE = 136;
    public static final int MAX_SECTORS = 0x76000; // per SP-1-dev wiki: eMMC capacity in sectors

    /** Writes a little-endian uint32 into buf at pos (matches DataView.setUint32(pos, v, true)). */
    public static void writeLE32(byte[] buf, int pos, int value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >>> 8) & 0xFF);
        buf[pos + 2] = (byte) ((value >>> 16) & 0xFF);
        buf[pos + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    /** Reads a little-endian uint32 from buf at pos (matches DataView.getUint32(pos, true)). */
    public static int readLE32(byte[] buf, int pos) {
        return (buf[pos] & 0xFF) | ((buf[pos + 1] & 0xFF) << 8)
                | ((buf[pos + 2] & 0xFF) << 16) | ((buf[pos + 3] & 0xFF) << 24);
    }

    // ---------------------------------------------------------------
    // Album metadata read errors (returned by VERIFY_DATA)
    // ---------------------------------------------------------------

    public static final int ALBUM_ERR_OK = 0;
    public static final int ALBUM_ERR_MAGIC_NOT_FOUND = 1;
    public static final int ALBUM_ERR_ALBUM_TOO_SHORT = 2;
    public static final int ALBUM_ERR_ALBUM_TOO_LONG = 3;
    public static final int ALBUM_ERR_TOO_MANY_SONGS = 4;
    public static final int ALBUM_ERR_ALBUM_TITLE_TOO_LONG = 5;
    public static final int ALBUM_ERR_ALBUM_TITLE_ALLOC_FAILED = 6;
    public static final int ALBUM_ERR_SONG_INDEX_ALLOC_FAILED = 7;
    public static final int ALBUM_ERR_SONG_OFFSET_INVALID = 8;
    public static final int ALBUM_ERR_SONG_LENGTH_TOO_LARGE = 9;
    public static final int ALBUM_ERR_SONG_EXCEEDS_ALBUM = 10;
    public static final int ALBUM_ERR_ARTIST_NAME_TOO_LONG = 11;
    public static final int ALBUM_ERR_ARTIST_NAME_ALLOC_FAILED = 12;
    public static final int ALBUM_ERR_SONG_TITLE_TOO_LONG = 13;
    public static final int ALBUM_ERR_SONG_TITLE_ALLOC_FAILED = 14;
    public static final int ALBUM_ERR_MAGIC_NOT_FOUND_AT_END = 15;

    public static String describeAlbumError(int code) {
        switch (code) {
            case ALBUM_ERR_OK: return "OK";
            case ALBUM_ERR_MAGIC_NOT_FOUND: return "Album not present";
            case ALBUM_ERR_ALBUM_TOO_SHORT: return "Album too short";
            case ALBUM_ERR_ALBUM_TOO_LONG: return "Album too long";
            case ALBUM_ERR_TOO_MANY_SONGS: return "Too many songs";
            case ALBUM_ERR_ALBUM_TITLE_TOO_LONG: return "Album title too long";
            case ALBUM_ERR_ALBUM_TITLE_ALLOC_FAILED: return "Album title error";
            case ALBUM_ERR_SONG_INDEX_ALLOC_FAILED: return "Song index error";
            case ALBUM_ERR_SONG_OFFSET_INVALID: return "Invalid song offset";
            case ALBUM_ERR_SONG_LENGTH_TOO_LARGE: return "Song too large";
            case ALBUM_ERR_SONG_EXCEEDS_ALBUM: return "Song exceeds album";
            case ALBUM_ERR_ARTIST_NAME_TOO_LONG: return "Artist name too long";
            case ALBUM_ERR_ARTIST_NAME_ALLOC_FAILED: return "Artist name error";
            case ALBUM_ERR_SONG_TITLE_TOO_LONG: return "Song title too long";
            case ALBUM_ERR_SONG_TITLE_ALLOC_FAILED: return "Song title error";
            case ALBUM_ERR_MAGIC_NOT_FOUND_AT_END: return "No magic at end";
            default: return "Unknown data error (" + code + ")";
        }
    }

    // ---------------------------------------------------------------
    // CRC8
    // ---------------------------------------------------------------

    private static final byte[] CRC8_TABLE = {
        (byte) 0xea, (byte) 0xd4, (byte) 0x96, (byte) 0xa8, (byte) 0x12, (byte) 0x2c, (byte) 0x6e, (byte) 0x50, (byte) 0x7f, (byte) 0x41, (byte) 0x03, (byte) 0x3d, (byte) 0x87, (byte) 0xb9, (byte) 0xfb, (byte) 0xc5,
        (byte) 0xa5, (byte) 0x9b, (byte) 0xd9, (byte) 0xe7, (byte) 0x5d, (byte) 0x63, (byte) 0x21, (byte) 0x1f, (byte) 0x30, (byte) 0x0e, (byte) 0x4c, (byte) 0x72, (byte) 0xc8, (byte) 0xf6, (byte) 0xb4, (byte) 0x8a,
        (byte) 0x74, (byte) 0x4a, (byte) 0x08, (byte) 0x36, (byte) 0x8c, (byte) 0xb2, (byte) 0xf0, (byte) 0xce, (byte) 0xe1, (byte) 0xdf, (byte) 0x9d, (byte) 0xa3, (byte) 0x19, (byte) 0x27, (byte) 0x65, (byte) 0x5b,
        (byte) 0x3b, (byte) 0x05, (byte) 0x47, (byte) 0x79, (byte) 0xc3, (byte) 0xfd, (byte) 0xbf, (byte) 0x81, (byte) 0xae, (byte) 0x90, (byte) 0xd2, (byte) 0xec, (byte) 0x56, (byte) 0x68, (byte) 0x2a, (byte) 0x14,
        (byte) 0xb3, (byte) 0x8d, (byte) 0xcf, (byte) 0xf1, (byte) 0x4b, (byte) 0x75, (byte) 0x37, (byte) 0x09, (byte) 0x26, (byte) 0x18, (byte) 0x5a, (byte) 0x64, (byte) 0xde, (byte) 0xe0, (byte) 0xa2, (byte) 0x9c,
        (byte) 0xfc, (byte) 0xc2, (byte) 0x80, (byte) 0xbe, (byte) 0x04, (byte) 0x3a, (byte) 0x78, (byte) 0x46, (byte) 0x69, (byte) 0x57, (byte) 0x15, (byte) 0x2b, (byte) 0x91, (byte) 0xaf, (byte) 0xed, (byte) 0xd3,
        (byte) 0x2d, (byte) 0x13, (byte) 0x51, (byte) 0x6f, (byte) 0xd5, (byte) 0xeb, (byte) 0xa9, (byte) 0x97, (byte) 0xb8, (byte) 0x86, (byte) 0xc4, (byte) 0xfa, (byte) 0x40, (byte) 0x7e, (byte) 0x3c, (byte) 0x02,
        (byte) 0x62, (byte) 0x5c, (byte) 0x1e, (byte) 0x20, (byte) 0x9a, (byte) 0xa4, (byte) 0xe6, (byte) 0xd8, (byte) 0xf7, (byte) 0xc9, (byte) 0x8b, (byte) 0xb5, (byte) 0x0f, (byte) 0x31, (byte) 0x73, (byte) 0x4d,
        (byte) 0x58, (byte) 0x66, (byte) 0x24, (byte) 0x1a, (byte) 0xa0, (byte) 0x9e, (byte) 0xdc, (byte) 0xe2, (byte) 0xcd, (byte) 0xf3, (byte) 0xb1, (byte) 0x8f, (byte) 0x35, (byte) 0x0b, (byte) 0x49, (byte) 0x77,
        (byte) 0x17, (byte) 0x29, (byte) 0x6b, (byte) 0x55, (byte) 0xef, (byte) 0xd1, (byte) 0x93, (byte) 0xad, (byte) 0x82, (byte) 0xbc, (byte) 0xfe, (byte) 0xc0, (byte) 0x7a, (byte) 0x44, (byte) 0x06, (byte) 0x38,
        (byte) 0xc6, (byte) 0xf8, (byte) 0xba, (byte) 0x84, (byte) 0x3e, (byte) 0x00, (byte) 0x42, (byte) 0x7c, (byte) 0x53, (byte) 0x6d, (byte) 0x2f, (byte) 0x11, (byte) 0xab, (byte) 0x95, (byte) 0xd7, (byte) 0xe9,
        (byte) 0x89, (byte) 0xb7, (byte) 0xf5, (byte) 0xcb, (byte) 0x71, (byte) 0x4f, (byte) 0x0d, (byte) 0x33, (byte) 0x1c, (byte) 0x22, (byte) 0x60, (byte) 0x5e, (byte) 0xe4, (byte) 0xda, (byte) 0x98, (byte) 0xa6,
        (byte) 0x01, (byte) 0x3f, (byte) 0x7d, (byte) 0x43, (byte) 0xf9, (byte) 0xc7, (byte) 0x85, (byte) 0xbb, (byte) 0x94, (byte) 0xaa, (byte) 0xe8, (byte) 0xd6, (byte) 0x6c, (byte) 0x52, (byte) 0x10, (byte) 0x2e,
        (byte) 0x4e, (byte) 0x70, (byte) 0x32, (byte) 0x0c, (byte) 0xb6, (byte) 0x88, (byte) 0xca, (byte) 0xf4, (byte) 0xdb, (byte) 0xe5, (byte) 0xa7, (byte) 0x99, (byte) 0x23, (byte) 0x1d, (byte) 0x5f, (byte) 0x61,
        (byte) 0x9f, (byte) 0xa1, (byte) 0xe3, (byte) 0xdd, (byte) 0x67, (byte) 0x59, (byte) 0x1b, (byte) 0x25, (byte) 0x0a, (byte) 0x34, (byte) 0x76, (byte) 0x48, (byte) 0xf2, (byte) 0xcc, (byte) 0x8e, (byte) 0xb0,
        (byte) 0xd0, (byte) 0xee, (byte) 0xac, (byte) 0x92, (byte) 0x28, (byte) 0x16, (byte) 0x54, (byte) 0x6a, (byte) 0x45, (byte) 0x7b, (byte) 0x39, (byte) 0x07, (byte) 0xbd, (byte) 0x83, (byte) 0xc1, (byte) 0xff,
    };

    public static int crc8(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc = CRC8_TABLE[(crc ^ b) & 0xFF] & 0xFF;
        }
        return crc;
    }

    // ---------------------------------------------------------------
    // COBS
    // ---------------------------------------------------------------

    public static byte[] cobsEncode(byte[] data) {
        ArrayList<Byte> out = new ArrayList<>();
        int idx = 0;
        while (idx <= data.length) {
            int end = idx;
            while (end < data.length && data[end] != 0) end++;
            out.add((byte) (end - idx + 1));
            for (int i = idx; i < end; i++) out.add(data[i]);
            if (end < data.length) {
                idx = end + 1;
            } else {
                break;
            }
        }
        out.add((byte) 0x00);
        byte[] result = new byte[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    public static byte[] cobsDecode(byte[] raw) {
        byte[] data = raw;
        if (data.length > 0 && data[data.length - 1] == 0) {
            byte[] trimmed = new byte[data.length - 1];
            System.arraycopy(data, 0, trimmed, 0, trimmed.length);
            data = trimmed;
        }
        ArrayList<Byte> out = new ArrayList<>();
        int idx = 0;
        while (idx < data.length) {
            int code = data[idx++] & 0xFF;
            if (code == 0) break;
            for (int i = 0; i < code - 1; i++) {
                if (idx < data.length) out.add(data[idx++]);
            }
            if (code < 0xFF && idx < data.length) out.add((byte) 0);
        }
        byte[] result = new byte[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    // ---------------------------------------------------------------
    // Packet build / parse
    // ---------------------------------------------------------------

    /** Builds a full COBS-encoded, CRC8-checked packet ready to write to the serial port. */
    public static byte[] buildPacket(int cmd, byte[] payload, int seq) {
        if (payload == null) payload = new byte[0];
        byte[] body = new byte[4 + payload.length];
        body[0] = 0x51;
        body[1] = (byte) (seq & 0xFF);
        body[2] = (byte) (cmd & 0xFF);
        body[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, body, 4, payload.length);

        byte crc = (byte) crc8(body);
        byte[] withCrc = new byte[body.length + 1];
        System.arraycopy(body, 0, withCrc, 0, body.length);
        withCrc[body.length] = crc;

        return cobsEncode(withCrc);
    }

    /** Parsed response from the SP-1. */
    public static class Response {
        public final int cmd;
        public final int seq;
        public final byte[] payload;
        public final boolean crcOk;

        Response(int cmd, int seq, byte[] payload, boolean crcOk) {
            this.cmd = cmd;
            this.seq = seq;
            this.payload = payload;
            this.crcOk = crcOk;
        }
    }

    /**
     * Parses one raw COBS-encoded packet (as returned by a read-until-0x00
     * loop) into a Response. Returns null if the packet is malformed/too short.
     */
    public static Response parseResponse(byte[] data) {
        if (data == null || data.length < 3) return null;
        byte[] decoded = cobsDecode(data);
        if (decoded.length < 5) return null;
        int seq = decoded[1] & 0xFF;
        int cmd = decoded[2] & 0xFF;
        int plen = decoded[3] & 0xFF;
        if (decoded.length < 5 + plen) return null;
        byte[] payload = new byte[plen];
        System.arraycopy(decoded, 4, payload, 0, plen);
        int gotCrc = decoded[4 + plen] & 0xFF;
        byte[] forCrc = new byte[4 + plen];
        System.arraycopy(decoded, 0, forCrc, 0, 4 + plen);
        int expCrc = crc8(forCrc);
        return new Response(cmd, seq, payload, gotCrc == expCrc);
    }
}
