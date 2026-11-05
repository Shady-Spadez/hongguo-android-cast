// SPDX-License-Identifier: GPL-3.0-only
package io.github.jackotom.hongguo.android;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Sanity check for staged media before it reaches the player.
 *
 * During upstream content incidents the CDN serves complete files whose moov index
 * is perfectly valid but whose mdat payload is scrambled (observed first NAL length
 * ~6e8 bytes). The player then dies with "Invalid NAL length" and the quality
 * descent retries forever, while the broken file also poisons the playback cache.
 * This walks the moov to the first video sample and rejects absurd NAL lengths
 * early, so callers can invalidate the cache and surface a clear reason.
 */
final class StagedMediaCheck {
    /** @return null when the first video sample looks sane; a human-readable reason otherwise. */
    static String firstSampleProblem(File file) {
        if (file == null || !file.isFile()) return null;
        RandomAccessFile data = null;
        try {
            data = new RandomAccessFile(file, "r");
            long length = data.length();
            long[] moov = findBox(data, 0, length, "moov");
            if (moov == null) return null; // unknown structure is not this check's business
            long[] trak = findBox(data, moov[0] + 8, moov[0] + moov[1], "trak");
            while (trak != null) {
                Sample sample = videoFirstSample(data, trak);
                if (sample != null) {
                    if (sample.offset + sample.size > length) return null; // still staging: bytes not arrived yet
                    data.seek(sample.offset);
                    int nalLength = data.readInt();
                    if (nalLength < 4 || nalLength > sample.size - 4) {
                        return String.format(Locale.ROOT, "片源内容校验未通过（首样本 %d/%d）", nalLength, sample.size);
                    }
                    return null;
                }
                trak = findBox(data, trak[0] + trak[1], moov[0] + moov[1], "trak");
            }
            return null; // no video track found: let the player report it
        } catch (Exception ignored) {
            return null; // a malformed read never blocks playback on its own
        } finally {
            if (data != null) try { data.close(); } catch (Exception ignored) { }
        }
    }

    /** Declared video dimensions of the vide track (tkhd width/height), or null when
     *  unreadable. The hi3751 codec rejects frames whose larger edge exceeds its
     *  declared 1920 cap (some CDN 1080p variants are 1080x1924), which Media3 turns
     *  into a hard NoSupport with no codec ever initializing. */
    static int[] videoSize(File file) {
        if (file == null || !file.isFile()) return null;
        RandomAccessFile data = null;
        try {
            data = new RandomAccessFile(file, "r");
            long[] moov = findBox(data, 0, data.length(), "moov");
            if (moov == null) return null;
            long[] trak = findBox(data, moov[0] + 8, moov[0] + moov[1], "trak");
            while (trak != null) {
                int[] size = trackVideoSize(data, trak);
                if (size != null) return size;
                trak = findBox(data, trak[0] + trak[1], moov[0] + moov[1], "trak");
            }
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (data != null) try { data.close(); } catch (Exception ignored) { }
        }
    }

    /** Declared video dimensions as Media3 will see them: the MP4 extractor overrides
     *  the container size from the SPS inside hvcC, and the hi3751 codec hard-rejects
     *  frames above its declared 1920 edge. Returns the coded edge, or 0 when unknown. */
    static int codedVideoEdge(File file) {
        if (file == null || !file.isFile()) return 0;
        RandomAccessFile data = null;
        try {
            data = new RandomAccessFile(file, "r");
            long[] moov = findBox(data, 0, data.length(), "moov");
            if (moov == null) return 0;
            long[] trak = findBox(data, moov[0] + 8, moov[0] + moov[1], "trak");
            while (trak != null) {
                int edge = trackVideoEdge(data, trak);
                if (edge > 0) return edge;
                trak = findBox(data, trak[0] + trak[1], moov[0] + moov[1], "trak");
            }
            return 0;
        } catch (Exception ignored) {
            return 0;
        } finally {
            if (data != null) try { data.close(); } catch (Exception ignored) { }
        }
    }

    /** Media3's own SPS-derived size for a vide track's hvcC (authoritative for what the
     *  capability query will check), or the stsd entry size as a fallback. */
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private static int trackVideoEdge(RandomAccessFile data, long[] trak) throws Exception {
        long[] mdia = findBox(data, trak[0] + 8, trak[0] + trak[1], "mdia");
        if (mdia == null) return 0;
        long[] hdlr = findBox(data, mdia[0] + 8, mdia[0] + mdia[1], "hdlr");
        if (hdlr == null) return 0;
        data.seek(hdlr[0] + 8 + 8);
        byte[] handler = new byte[4]; data.readFully(handler);
        if (!(handler[0] == 'v' && handler[1] == 'i' && handler[2] == 'd' && handler[3] == 'e')) return 0;
        long[] minf = findBox(data, mdia[0] + 8, mdia[0] + mdia[1], "minf");
        if (minf == null) return 0;
        long[] stbl = findBox(data, minf[0] + 8, minf[0] + minf[1], "stbl");
        if (stbl == null) return 0;
        long[] stsd = findBox(data, stbl[0] + 8, stbl[0] + stbl[1], "stsd");
        if (stsd == null) return 0;
        long[] entry = findBox(data, stsd[0] + 16, stsd[0] + stsd[1], "hvc1");
        if (entry == null) entry = findBox(data, stsd[0] + 16, stsd[0] + stsd[1], "hev1");
        if (entry == null) entry = findBox(data, stsd[0] + 16, stsd[0] + stsd[1], "avc1");
        if (entry == null) return 0;
        // VisualSampleEntry: 6 reserved + data_ref_index(2) + pre_defined(2) + reserved(2)
        // + pre_defined(12) → width(2), height(2) at body+24/26.
        int stsdWidth = readUnsignedShort(data, entry[0] + 8 + 24);
        int stsdHeight = readUnsignedShort(data, entry[0] + 8 + 26);
        int edge = Math.max(stsdWidth, stsdHeight);
        long[] hvcC = findBox(data, entry[0] + 8, entry[0] + entry[1], "hvcC");
        if (hvcC != null && hvcC[1] > 8 && hvcC[1] <= 1 << 20) {
            byte[] record = new byte[(int) (hvcC[1] - 8)];
            data.seek(hvcC[0] + 8);
            data.readFully(record);
            try {
                androidx.media3.extractor.mp4.HevcConfig config =
                    androidx.media3.extractor.mp4.HevcConfig.parse(new androidx.media3.common.util.ParsableByteArray(record));
                if (config.width > 0 && config.height > 0) edge = Math.max(config.width, config.height);
            } catch (Exception ignored) { /* Container claims win when the SPS is unreadable. */ }
        }
        return edge > 0 && edge <= 16384 ? edge : 0;
    }

    private static int readUnsignedShort(RandomAccessFile data, long at) throws Exception {
        data.seek(at);
        return (data.read() << 8) | data.read();
    }

    private static final class Sample { final long offset; final int size; Sample(long offset, int size) { this.offset = offset; this.size = size; } }

    /** First chunk offset + first sample size of the vide track inside a trak box, or null. */
    private static Sample videoFirstSample(RandomAccessFile data, long[] trak) throws Exception {
        long[] mdia = findBox(data, trak[0] + 8, trak[0] + trak[1], "mdia");
        if (mdia == null) return null;
        long[] hdlr = findBox(data, mdia[0] + 8, mdia[0] + mdia[1], "hdlr");
        if (hdlr == null) return null;
        data.seek(hdlr[0] + 8 + 8); // version+flags, pre_defined, then handler_type
        byte[] handler = new byte[4]; data.readFully(handler);
        if (!(handler[0] == 'v' && handler[1] == 'i' && handler[2] == 'd' && handler[3] == 'e')) return null;
        long[] minf = findBox(data, mdia[0] + 8, mdia[0] + mdia[1], "minf");
        if (minf == null) return null;
        long[] stbl = findBox(data, minf[0] + 8, minf[0] + minf[1], "stbl");
        if (stbl == null) return null;
        long limit = stbl[0] + stbl[1];
        Long firstChunk = null; Integer firstSize = null;
        long[] box = findBox(data, stbl[0] + 8, limit, "stco");
        if (box != null) {
            data.seek(box[0] + 8 + 4); // version+flags, entry_count
            int entries = data.readInt();
            if (entries > 0) firstChunk = (long) data.readInt() & 0xffffffffL;
        } else {
            box = findBox(data, stbl[0] + 8, limit, "co64");
            if (box != null) {
                data.seek(box[0] + 8 + 4);
                if (data.readInt() > 0) firstChunk = data.readLong();
            }
        }
        box = findBox(data, stbl[0] + 8, limit, "stsz");
        if (box != null) {
            data.seek(box[0] + 8 + 4); // version+flags, sample_size, sample_count
            long uniform = (long) data.readInt() & 0xffffffffL;
            int count = data.readInt();
            if (count > 0) firstSize = (int) (uniform > 0 ? uniform : (long) data.readInt() & 0xffffffffL);
        }
        if (firstChunk == null || firstSize == null || firstSize < 4) return null;
        return new Sample(firstChunk, firstSize);
    }

    /** Top-level box scan for the next tag within [from, limit); returns {offset, size} or null. */
    private static long[] findBox(RandomAccessFile data, long from, long limit, String tag) throws Exception {
        for (long pos = from; pos + 8 <= limit; ) {
            data.seek(pos);
            long size = (long) data.readInt() & 0xffffffffL;
            byte[] name = new byte[4]; data.readFully(name);
            if (size < 8 || pos + size > limit + 7) return null; // malformed or truncated container
            if (name[0] == tag.charAt(0) && name[1] == tag.charAt(1) && name[2] == tag.charAt(2) && name[3] == tag.charAt(3)) return new long[]{pos, size};
            pos += size;
        }
        return null;
    }
}
