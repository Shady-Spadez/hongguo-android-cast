package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.util.Log;
import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Silent whole-series cache for followed dramas. The playback cache intentionally keeps only a
 * sliding window around the current episode, so a "watch anything instantly" library needs its
 * own store: a second PlaybackCachePolicy over a separate directory with a much larger LRU
 * budget and no queue window, filled one episode at a time by a single background thread while
 * the app is open. Playback consults it before staging anything from the network.
 */
final class FollowSeriesCache {
    static final long BUDGET_BYTES = 2L * 1024 * 1024 * 1024;
    static final long MAX_FILE_BYTES = PlaybackCache.MAX_FILE_BYTES;
    private static final String PREFS = "follow_cache_v1";
    private static final Pattern OWNED = Pattern.compile("([a-f0-9]{64})_([0-9]{1,10})_[a-f0-9]{64}\\.mp4");
    private static final int MAX_FAILURES_PER_EPISODE = 3;
    /** Storage preference order: the library keeps the best tier a series offers. */
    private static final String[] TIERS = { "1080p", "720p", "auto" };

    private static FollowSeriesCache instance;

    private final Context context;
    private final PlaybackCachePolicy policy;
    private final File directory;
    /** Pending episodes plus the worker handshake. Guarded by itself. */
    private final Object lock = new Object();
    private final ArrayDeque<Item> pending = new ArrayDeque<>();
    private final Set<String> queuedKeys = new HashSet<>();
    /** Counters survive only this process: content incidents are transient upstream state. */
    private final Map<String, Integer> failures = new HashMap<>();
    private boolean enabled;
    private volatile int holds;
    private Thread worker;
    /** Bumped by clear(); queued items from an older epoch are dropped, not re-downloaded. */
    private volatile int epoch;

    static final class Item {
        final String sid, vid, quality; final int index; final int epoch;
        Item(String sid, String vid, int index, String quality, int epoch) { this.sid = sid; this.vid = vid; this.index = index; this.quality = quality; this.epoch = epoch; }
        String key() { return sid + ":" + vid + ":" + index + ":" + quality; }
    }

    /** A fully transferred file whose decrypted content failed the sample check. */
    static final class ContentCheckException extends java.io.IOException { ContentCheckException(String message) { super(message); } }
    /** The player's own fetch needs the link; the item requeues without counting. */
    static final class YieldException extends java.io.IOException { YieldException() { super("追剧缓存让路播放取源"); } }
    /** A tier this device can never decode (frame edge above the codec cap): try the
     *  next tier without burning a failure slot. */
    static final class UnplayableException extends java.io.IOException { UnplayableException() { super("尺寸超出本机解码上限"); } }

    private FollowSeriesCache(Context context) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        // Same mediaserver-readable external tree as the playback cache; a sibling
        // directory keeps the two stores and their budgets fully independent.
        File external = this.context.getExternalFilesDir(null);
        directory = external != null ? new File(external, "follow-cache-v1") : new File(this.context.getCacheDir(), "follow-cache-v1");
        policy = new PlaybackCachePolicy(directory, BUDGET_BYTES, MAX_FILE_BYTES, PlaybackSources::downloadProgressive);
        enabled = preferences().getBoolean("enabled", true);
    }

    static synchronized FollowSeriesCache get(Context context) {
        if (instance == null) instance = new FollowSeriesCache(context);
        return instance;
    }

    private SharedPreferences preferences() { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Add a series' episodes (already ordered by watching priority by the page). Skips
     *  episodes already cached; a no-op while disabled. Returns how many were queued. */
    int enqueue(JSONObject series, JSONArray episodes, String quality, JSONArray favorites) throws JSONException {
        JSONObject clean = LibraryStore.series(series);
        String sid = clean.getString("seriesId");
        String normalized = MediaQuality.normalize(quality);
        pruneRegistry(favorites); // Stale rows for unfollowed series outlive their display value.
        int added = 0, stamp;
        synchronized (lock) {
            if (!enabled) return 0;
            stamp = epoch;
            for (int i = 0; i < episodes.length() && pending.size() < 4000; i++) {
                JSONObject episode;
                try { episode = LibraryStore.episode(episodes.getJSONObject(i)); }
                catch (JSONException invalid) { continue; }
                String vid = episode.getString("vid");
                if (givenUp(vid)) continue; // Already poisoned this process; retry next app session.
                Item item = new Item(sid, vid, episode.getInt("index"), normalized, stamp);
                if (queuedKeys.contains(item.key()) || cached(item) != null) continue;
                pending.addLast(item); queuedKeys.add(item.key()); added++;
            }
            if (added > 0) { startWorkerLocked(); lock.notifyAll(); }
        }
        if (added > 0) registerSeries(clean, episodes.length(), normalized); // Disk write outside the queue lock.
        return added;
    }

    /** Registry rows are display-only; drop rows for series no longer followed. Files
     *  stay and age out through the LRU budget. A null list prunes nothing. */
    private void pruneRegistry(JSONArray favorites) {
        if (favorites == null) return;
        try {
            JSONObject registry = readRegistry();
            boolean changed = false;
            for (Iterator<String> ids = registry.keys(); ids.hasNext();) {
                String sid = ids.next();
                boolean found = false;
                for (int i = 0; i < favorites.length(); i++) if (favorites.optString(i, "").equals(sid)) { found = true; break; }
                if (!found) { ids.remove(); changed = true; }
            }
            if (changed) preferences().edit().putString("registry", registry.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** A located follow-cache file plus the quality tier it was stored under. */
    static final class Entry {
        final File file; final String quality;
        Entry(File file, String quality) { this.file = file; this.quality = quality; }
    }

    /** Player lookup: the final decrypted file for this episode at this exact quality. */
    File find(String sid, String vid, int index, String quality) {
        try { return policy.find(sid, MediaQuality.cacheVid(vid, quality), index); }
        catch (Exception error) { return null; }
    }
    /** Automatic playback prefers whatever the library holds, best tier first. An
     *  explicitly chosen quality keeps using {@link #find} so a manual 720p choice
     *  is never silently upgraded. */
    Entry locateBest(String sid, String vid, int index) {
        for (String quality : TIERS) {
            File file = find(sid, vid, index, quality);
            if (file != null) return new Entry(file, quality);
        }
        return null;
    }
    void protect(String sid, String vid, int index, String quality) { policy.protect(sid, MediaQuality.cacheVid(vid, quality), index); }
    void release(String sid, String vid, int index, String quality) { policy.release(sid, MediaQuality.cacheVid(vid, quality), index); }
    /** Drop one bad file found at play time; the network path replaces it. */
    void invalidate(String sid, String vid, int index, String quality) {
        try { policy.invalidate(sid, MediaQuality.cacheVid(vid, quality), index); }
        catch (Exception ignored) { }
    }

    /** Pause the queue while the player itself is fetching the episode the user just opened. */
    void hold(boolean active) {
        synchronized (lock) {
            holds = Math.max(0, holds + (active ? 1 : -1));
            lock.notifyAll();
        }
    }

    boolean enabled() { synchronized (lock) { return enabled; } }
    void setEnabled(boolean value) {
        synchronized (lock) {
            if (enabled == value) return;
            enabled = value;
            if (!value) { pending.clear(); queuedKeys.clear(); }
            preferences().edit().putBoolean("enabled", value).commit();
            lock.notifyAll();
        }
    }

    JSONObject status() throws Exception {
        PlaybackCachePolicy.Stats stats = policy.status();
        JSONObject out = new JSONObject().put("enabled", enabled()).put("files", stats.files)
            .put("bytes", stats.bytes).put("budgetBytes", BUDGET_BYTES).put("maxFileBytes", MAX_FILE_BYTES);
        JSONObject registry = readRegistry();
        JSONObject series = new JSONObject();
        Map<String, Set<Integer>> onDisk = scanDirectory();
        Map<String, Integer> queuedPerSeries = new HashMap<>();
        synchronized (lock) { for (Item item : pending) queuedPerSeries.merge(item.sid, 1, Integer::sum); }
        Iterator<String> ids = registry.keys();
        while (ids.hasNext()) {
            String sid = ids.next();
            JSONObject row = registry.optJSONObject(sid);
            if (row == null) continue;
            Set<Integer> indexes = onDisk.get(seriesHash(sid));
            series.put(sid, new JSONObject()
                .put("title", row.optString("title"))
                .put("episodeCount", row.optInt("episodeCount"))
                .put("total", row.optInt("total"))
                .put("quality", row.optString("quality"))
                .put("cached", indexes == null ? 0 : indexes.size())
                .put("queued", queuedPerSeries.getOrDefault(sid, 0)));
        }
        return out.put("series", series);
    }

    JSONObject clear() throws Exception {
        synchronized (lock) { pending.clear(); queuedKeys.clear(); epoch++; lock.notifyAll(); }
        preferences().edit().remove("registry").apply();
        PlaybackCachePolicy.Stats stats = policy.clear();
        return new JSONObject().put("removed", stats.removed).put("bytes", stats.bytes);
    }

    // ---------------------------------------------------------------- worker

    private void startWorkerLocked() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(this::run, "follow-cache");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        for (;;) {
            Item item;
            synchronized (lock) {
                for (;;) {
                    if (!enabled) { pending.clear(); queuedKeys.clear(); }
                    if (enabled && !pending.isEmpty()) {
                        item = pending.pollFirst();
                        queuedKeys.remove(item.key());
                        break;
                    }
                    try { lock.wait(); } catch (InterruptedException error) { return; }
                }
            }
            if (item.epoch != epoch) continue; // Superseded by clear(); drop, don't download.
            // Yield to the player's own startup fetch, and to metered links.
            while (item != null && (holds > 0 || metered())) {
                synchronized (lock) { pending.addFirst(item); queuedKeys.add(item.key()); }
                if (!sleepQuietly(4000)) return;
                synchronized (lock) {
                    if (enabled && !pending.isEmpty()) { item = pending.pollFirst(); queuedKeys.remove(item.key()); }
                    else item = null;
                }
            }
            if (item != null) process(item);
        }
    }

    private void process(Item item) {
        try {
            if (item.epoch != epoch || cached(item) != null) return;
            for (String quality : TIERS) {
                if (givenUp(item.vid)) return;
                try { store(item, quality); return; }
                catch (InterruptedException interrupted) { throw interrupted; }
                catch (Exception error) {
                    if (error instanceof YieldException) {
                        // The player's own fetch owns the link right now; come back later
                        // without burning a failure slot.
                        synchronized (lock) { if (enabled && item.epoch == epoch) { pending.addFirst(item); queuedKeys.add(item.key()); } }
                        return;
                    }
                    if (error instanceof UnplayableException) continue; // Deterministic: try the next tier.
                    countFailure(item, error); // Every full download counts toward the cap.
                    // A corrupt source is tier-independent; descending only re-downloads garbage.
                    if (error instanceof ContentCheckException) break;
                }
            }
            sleepQuietly(20000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (lock) { if (enabled && item.epoch == epoch) { pending.addFirst(item); queuedKeys.add(item.key()); } }
        }
    }

    /** Resolve, download and sample-verify one episode at one quality tier. Any failure throws. */
    private void store(Item item, String quality) throws Exception {
        PlaybackSources.Plan plan = PlaybackSources.resolvePlayback(item.sid, item.vid, quality, 0, false, false);
        if (plan.source.equals("web")) {
            // Same preference as the player: this device renders the App H264 streams
            // cleanly, so swap a web plan for a fresh App plan when one exists.
            try { plan = PlaybackSources.resolvePlayback(item.sid, item.vid, quality, 0, true, true); }
            catch (Exception ignored) { }
        }
        if (!item.sid.equals(plan.seriesId) || !item.vid.equals(plan.vid) || !quality.equals(plan.requestedQuality))
            throw new java.io.IOException("片源身份不匹配");
        // Never store a frame size the codec will hard-reject (e.g. 1080x1924 variants).
        if (PlaybackSources.beyondDecoderEdge(plan.width, plan.height)) throw new UnplayableException();
        final PlaybackSources.Plan transfer = plan;
        File ready = policy.fetch(item.sid, MediaQuality.cacheVid(item.vid, quality), item.index, transfer.url,
            (value, file, max) -> {
                // Yield late arrivals too: the player may have asked for the link after the
                // worker's own hold check but before this transfer started.
                if (holds > 0) throw new YieldException();
                PlaybackSources.downloadProgressive(transfer, file, max);
            });
        String problem = ready == null ? "未取得文件" : StagedMediaCheck.firstSampleProblem(ready);
        int edge = ready == null ? 0 : StagedMediaCheck.codedVideoEdge(ready);
        if (problem == null && edge > PlaybackSources.MAX_DECODER_EDGE) problem = "尺寸超上限";
        if (problem == null) {
            synchronized (lock) { failures.remove(item.vid); }
            return;
        }
        policy.invalidate(item.sid, MediaQuality.cacheVid(item.vid, quality), item.index);
        throw new ContentCheckException("内容校验失败: " + problem);
    }

    /** Count one failed download; requeue for a later retry unless the episode gave up. */
    private void countFailure(Item item, Exception error) {
        int hits;
        synchronized (lock) { hits = failures.merge(item.vid, 1, Integer::sum); }
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (hits >= MAX_FAILURES_PER_EPISODE) {
            Log.w("FollowCache", "giving up on " + item.sid + " #" + item.index + " after " + hits + " tries: " + reason);
            return;
        }
        Log.i("FollowCache", "retry later " + item.sid + " #" + item.index + " (" + hits + "): " + reason);
        synchronized (lock) { if (enabled && item.epoch == epoch) { pending.addLast(item); queuedKeys.add(item.key()); } }
    }

    private boolean givenUp(String vid) {
        synchronized (lock) { return failures.getOrDefault(vid, 0) >= MAX_FAILURES_PER_EPISODE; }
    }

    /** Already stored at any tier? The library never re-downloads a lower copy of an
     *  episode it already holds, whatever tier that copy is. */
    private File cached(Item item) {
        for (String quality : TIERS) {
            try {
                File file = policy.find(item.sid, MediaQuality.cacheVid(item.vid, quality), item.index);
                if (file != null) return file;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private boolean metered() {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return manager != null && manager.isActiveNetworkMetered();
        } catch (Exception error) { return false; }
    }

    private static boolean sleepQuietly(long millis) {
        try { Thread.sleep(millis); return true; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
    }

    // ---------------------------------------------------------------- registry

    private JSONObject readRegistry() {
        try { return new JSONObject(preferences().getString("registry", "{}")); }
        catch (JSONException error) { return new JSONObject(); }
    }

    private void registerSeries(JSONObject series, int total, String quality) {
        try {
            JSONObject registry = readRegistry();
            registry.put(series.getString("seriesId"), new JSONObject()
                .put("title", series.optString("title"))
                .put("episodeCount", series.optInt("episodeCount"))
                .put("total", total)
                .put("quality", quality)
                .put("updatedAt", System.currentTimeMillis()));
            while (registry.length() > 200) { Iterator<String> ids = registry.keys(); String oldest = null; long oldestAt = Long.MAX_VALUE;
                while (ids.hasNext()) { String id = ids.next(); long at = registry.optJSONObject(id) == null ? 0 : registry.optJSONObject(id).optLong("updatedAt"); if (at < oldestAt) { oldestAt = at; oldest = id; } }
                if (oldest == null) break; registry.remove(oldest); }
            preferences().edit().putString("registry", registry.toString()).apply();
        } catch (JSONException ignored) { }
    }

    /** Distinct cached episode indexes per series hash, from the directory itself. */
    private Map<String, Set<Integer>> scanDirectory() {
        Map<String, Set<Integer>> result = new HashMap<>();
        File[] children = directory.listFiles();
        if (children == null) return result;
        for (File file : children) {
            Matcher match = OWNED.matcher(file.getName());
            if (!match.matches() || !file.isFile()) continue;
            try { result.computeIfAbsent(match.group(1), key -> new HashSet<>()).add(Integer.parseInt(match.group(2))); }
            catch (NumberFormatException ignored) { /* A foreign name never breaks status. */ }
        }
        return result;
    }

    /** Same digest the policy folds into filenames, so status rows match directory names. */
    private static String seriesHash(String sid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
