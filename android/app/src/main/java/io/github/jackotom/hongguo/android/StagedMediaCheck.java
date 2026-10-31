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
