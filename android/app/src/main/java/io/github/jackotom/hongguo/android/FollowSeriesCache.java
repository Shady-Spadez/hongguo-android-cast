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

    static final class Item {
        final String sid, vid, quality; final int index;
        Item(String sid, String vid, int index, String quality) { this.sid = sid; this.vid = vid; this.index = index; this.quality = quality; }
        String key() { return sid + ":" + vid + ":" + index + ":" + quality; }
    }

    private FollowSeriesCache(Context context) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        // Same mediaserver-readable external tree as the playback cache; a sibling
        // directory keeps the two stores and their budgets fully independent.
        File external = this.context.getExternalFilesDir(null);
        directory = external != null ? new File(external, "follow-cache-v1") : new File(this.context.getCacheDir(), "follow-cache-v1");
        policy = new PlaybackCachePolicy(directory, BUDGET_BYTES, MAX_FILE_BYTES, PlaybackSources::download);
        enabled = preferences().getBoolean("enabled", true);
    }

    static synchronized FollowSeriesCache get(Context context) {
        if (instance == null) instance = new FollowSeriesCache(context);
        return instance;
    }

    private SharedPreferences preferences() { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Add a series' episodes (already ordered by watching priority by the page). Skips
     *  episodes already cached; a no-op while disabled. Returns how many were queued. */
    int enqueue(JSONObject series, JSONArray episodes, String quality) throws JSONException {
        JSONObject clean = LibraryStore.series(series);
        String sid = clean.getString("seriesId");
        String normalized = MediaQuality.normalize(quality);
        int added = 0;
        synchronized (lock) {
            if (!enabled) return 0;
            for (int i = 0; i < episodes.length() && pending.size() < 4000; i++) {
                JSONObject episode;
                try { episode = LibraryStore.episode(episodes.getJSONObject(i)); }
                catch (JSONException invalid) { continue; }
                Item item = new Item(sid, episode.getString("vid"), episode.getInt("index"), normalized);
                if (queuedKeys.contains(item.key()) || cached(item) != null) continue;
                pending.addLast(item); queuedKeys.add(item.key()); added++;
            }
            if (added > 0) {
                registerSeries(clean, episodes.length(), normalized);
                startWorkerLocked();
                lock.notifyAll();
            }
        }
        return added;
    }

    /** Player lookup: the final decrypted file for this episode at this quality, if present. */
    File find(String sid, String vid, int index, String quality) {
        try { return policy.find(sid, MediaQuality.cacheVid(vid, quality), index); }
        catch (Exception error) { return null; }
    }
    void protect(String sid, String vid, int index, String quality) { policy.protect(sid, MediaQuality.cacheVid(vid, quality), index); }
    void release(String sid, String vid, int index, String quality) { policy.release(sid, MediaQuality.cacheVid(vid, quality), index); }

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
        synchronized (lock) { pending.clear(); queuedKeys.clear(); lock.notifyAll(); }
        preferences().edit().remove("registry").commit();
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
            if (cached(item) != null) return;
            PlaybackSources.Plan plan = PlaybackSources.resolvePlayback(item.sid, item.vid, item.quality, 0, false, false);
            if (plan.source.equals("web")) {
                // Same preference as the player: this device renders the App H264 streams
                // cleanly, so swap a web plan for a fresh App plan when one exists.
                try { plan = PlaybackSources.resolvePlayback(item.sid, item.vid, item.quality, 0, true, true); }
                catch (Exception ignored) { }
            }
            if (!item.sid.equals(plan.seriesId) || !item.vid.equals(plan.vid)
                || !MediaQuality.normalize(item.quality).equals(plan.requestedQuality)) {
                Log.w("FollowCache", "episode identity mismatch, skipped " + item.sid + " #" + item.index);
                return;
            }
            final PlaybackSources.Plan transfer = plan;
            File ready = policy.fetch(item.sid, MediaQuality.cacheVid(item.vid, item.quality), item.index, transfer.url,
                (value, file, max) -> PlaybackSources.download(transfer, file, max));
            String problem = ready == null ? "未取得文件" : StagedMediaCheck.firstSampleProblem(ready);
            if (problem == null) {
                synchronized (lock) { failures.remove(item.vid); }
                return;
            }
            policy.invalidate(item.sid, MediaQuality.cacheVid(item.vid, item.quality), item.index);
            retryOrFail(item, "内容校验失败: " + problem);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (lock) { if (enabled) { pending.addFirst(item); queuedKeys.add(item.key()); } }
        } catch (Exception error) {
            retryOrFail(item, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
    }

    private void retryOrFail(Item item, String reason) {
        int hits;
        synchronized (lock) { hits = failures.merge(item.vid, 1, Integer::sum); }
        if (hits >= MAX_FAILURES_PER_EPISODE) {
            Log.w("FollowCache", "giving up on " + item.sid + " #" + item.index + " after " + hits + " tries: " + reason);
            sleepQuietly(20000);
            return;
        }
        Log.i("FollowCache", "retry later " + item.sid + " #" + item.index + " (" + hits + "): " + reason);
        synchronized (lock) { if (enabled) { pending.addLast(item); queuedKeys.add(item.key()); } }
        sleepQuietly(20000);
    }

    private File cached(Item item) {
        try { return policy.find(item.sid, MediaQuality.cacheVid(item.vid, item.quality), item.index); }
        catch (Exception error) { return null; }
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
            preferences().edit().putString("registry", registry.toString()).commit();
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
