package io.github.jackotom.hongguo.android;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.webkit.JavascriptInterface;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

final class NativeBridge {
    private final MainActivity activity;
    private volatile boolean closed;
    private final LibraryStore store;
    private final DownloadManager downloads;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    private final ThreadPoolExecutor downloadWorker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    // Slow search/registration must not occupy playback's request workers.
    // No queued searches: reject overload promptly rather than exceed the JS deadline.
    private final ThreadPoolExecutor searchWorker = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    // Account IO has its own bounded lane; it never delays playback/source requests.
    private final ThreadPoolExecutor accountWorker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    private static final Set<String> METHODS = new HashSet<>(Arrays.asList("getInfo", "httpGet", "appRequest", "appResolveResult", "getLibrary", "toggleFavorite", "play", "download", "getDownloads", "playDownload", "getPlaybackCache", "clearPlaybackCache", "getPlaybackOptions", "setPlaybackOptions", "getSearchHistory", "addSearchHistory", "removeSearchHistory", "clearSearchHistory", "searchQuery", "openExternal", "getAccount", "accountLogin", "accountLogout", "syncHistory", "importGuestHistory"));
    NativeBridge(MainActivity activity) { this.activity = activity; store = new LibraryStore(activity); downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE); }
    @JavascriptInterface public void postMessage(String message) {
        if (message == null || message.length() > 256 * 1024 || message.getBytes(StandardCharsets.UTF_8).length > 256 * 1024) return;
        final JSONObject request;
        final Object id;
        final String method;
        final JSONObject params;
        try {
            request = new JSONObject(message); id = request.get("id"); method = request.getString("method");
            if (!(id instanceof String || id instanceof Number) || String.valueOf(id).length() > 128 || !METHODS.contains(method)) throw new JSONException("请求无效");
            params = request.optJSONObject("params") == null ? new JSONObject() : request.getJSONObject("params");
        } catch (JSONException error) { return; }
        activity.runOnUiThread(() -> {
            if (!activity.trusted()) return;
            // Completion callbacks must not wait behind HTTP or full-media preparation.
            if (method.equals("appResolveResult")) {
                try { handle(id, method, params); } catch (Exception error) { respond(id, null, "分集结果不匹配"); }
                return;
            }
            ThreadPoolExecutor executor = method.equals("accountLogin") || method.equals("accountLogout") || method.equals("importGuestHistory") ? accountWorker : method.equals("searchQuery") ? searchWorker : method.equals("download") ? downloadWorker : worker;
            try { executor.execute(() -> { try { handle(id, method, params); } catch (Exception error) { respond(id, null, error.getMessage() == null ? "操作未成功，请重试" : error.getMessage()); } }); }
            catch (RejectedExecutionException error) { respond(id, null, "请求较多，请稍后重试"); }
        });
    }
    private void handle(Object id, String method, JSONObject params) throws Exception {
        if (closed) return;
        switch (method) {
            case "getAccount": respond(id, AccountSync.get().getAccount(), null); break;
            case "accountLogin": respond(id, AccountSync.get().login(params), null); break;
            case "accountLogout": respond(id, AccountSync.get().logout(), null); break;
            case "syncHistory": respond(id, AccountSync.get().syncNow(), null); break;
            case "importGuestHistory": respond(id, AccountSync.get().importGuestHistory(), null); break;
            case "getPlaybackOptions": respond(id, PlayerOptions.read(activity), null); break;
            case "setPlaybackOptions": respond(id, PlayerOptions.update(activity, params), null); break;
            case "getSearchHistory": respond(id, store.searchHistory(), null); break;
            case "addSearchHistory": respond(id, store.addSearchHistory(params), null); break;
            case "removeSearchHistory": respond(id, store.removeSearchHistory(params), null); break;
            case "clearSearchHistory": respond(id, store.clearSearchHistory(), null); break;
            case "searchQuery": respond(id, SearchClient.search(activity, params), null); break;
            case "getInfo": respond(id, new JSONObject().put("version", BuildConfig.VERSION_NAME).put("isTv", BuildConfig.IS_TV).put("platform", "android"), null); break;
            case "httpGet": {
                NetworkGuard.Reply reply = NetworkGuard.get(LibraryStore.text(params, "url", 4096));
                respond(id, new JSONObject().put("body", reply.body).put("status", reply.status).put("url", reply.url), null); break;
            }
            case "appRequest": respond(id, AppTransport.request(params), null); break;
            case "appResolveResult": PlaybackSources.result(params); respond(id, new JSONObject().put("accepted", true), null); break;
            // Player/cast stores intentionally retain their original account scope.
            // UI reads must instead use the newly selected account after login/logout.
            case "getLibrary": respond(id, new LibraryStore(activity).library(), null); break;
            case "toggleFavorite": store.toggle(LibraryStore.series(params.getJSONObject("series"))); respond(id, new LibraryStore(activity).library(), null); break;
            case "getDownloads": respond(id, downloadRows(), null); break;
            case "getPlaybackCache": respond(id, PlaybackCache.get(activity).status(), null); break;
            case "clearPlaybackCache": respond(id, PlaybackCache.get(activity).clear(), null); break;
            case "play": {
                JSONObject series = LibraryStore.series(params.getJSONObject("series")), episode = LibraryStore.episode(params.getJSONObject("episode"));
                Object rawQueue = params.opt("episodes");
                if (rawQueue != null && rawQueue != JSONObject.NULL && !(rawQueue instanceof JSONArray)) throw new JSONException("连续播放队列无效");
                JSONArray queue = LibraryStore.episodes(params.optJSONArray("episodes"), episode);
                if (params.has("playlist")) {
                    if (!(params.opt("playlist") instanceof JSONObject) || !params.optBoolean("resolveInPlayer", false)) throw new JSONException("跨季播放清单无效");
                    if (params.toString().getBytes(StandardCharsets.UTF_8).length > 240 * 1024) throw new JSONException("播放清单超过大小上限，请减少连播季数");
                    PlaybackPlaylist.parse(params.getJSONObject("playlist"), series, episode);
                }
                // Source preparation belongs to the player so first play, episode selection
                // and continuous playback share the same bounded quality recovery.
                if (params.has("resolveInPlayer") && !(params.opt("resolveInPlayer") instanceof Boolean)) throw new JSONException("播放准备方式无效");
                if (params.optBoolean("resolveInPlayer", false)) {
                    if (params.has("url") || params.has("playInfo")) throw new JSONException("播放准备参数冲突");
                    JSONObject defaults = PlayerOptions.read(activity);
                    Object requested = params.has("quality") ? params.get("quality") : defaults.get("quality");
                    if (!(requested instanceof String)) throw new JSONException("清晰度无效");
                    params.put("quality", MediaQuality.normalize((String) requested));
                    if (!params.has("speed")) params.put("speed", defaults.getDouble("speed"));
                    if (!(params.opt("speed") instanceof Number)) throw new JSONException("倍速无效");
                    PlayerOptions.validateSpeed(params.getDouble("speed"));
                    play(id, series, episode, queue, null, false, params); break;
                }
                JSONObject info = params.optJSONObject("playInfo");
                if (info == null) info = new JSONObject().put("url", LibraryStore.text(params, "url", 4096)).put("vid", episode.getString("vid")).put("source", "web");
                String planSid = info.optString("seriesId", series.getString("seriesId"));
                if (!planSid.equals(series.getString("seriesId"))) throw new JSONException("片源与剧集不一致");
                info.put("seriesId", planSid);
                JSONObject mediaOptions = PlayerOptions.read(activity);
                String requestedQuality = MediaQuality.normalize(params.optString("quality", info.optString("requestedQuality", "auto")));
                if (info.has("requestedQuality") && !requestedQuality.equals(info.getString("requestedQuality"))) throw new JSONException("清晰度结果不匹配");
                info.put("requestedQuality", requestedQuality);
                params.put("quality", requestedQuality);
                if (!params.has("speed")) params.put("speed", mediaOptions.getDouble("speed"));
                // Validate playback rate even when supplied directly over the bridge.
                if (!(params.opt("speed") instanceof Number)) throw new JSONException("倍速无效");
                PlayerOptions.validateSpeed(params.getDouble("speed"));
                PlaybackSources.Plan plan = PlaybackSources.registerInitial(info, series.getString("seriesId"), episode.getString("vid"), requestedQuality);
                params.put("playInfo", plan.json());
                play(id, series, episode, queue, plan.url, false, params); break;
            }
            case "download": {
                JSONObject series = LibraryStore.series(params.getJSONObject("series")), episode = LibraryStore.episode(params.getJSONObject("episode"));
                if (store.downloads().length() >= 200) throw new Exception("下载记录已达 200 条上限");
                JSONObject info = params.optJSONObject("playInfo");
                if (info == null) info = new JSONObject().put("url", LibraryStore.text(params, "url", 4096)).put("vid", episode.getString("vid")).put("source", "web");
                String planSid = info.optString("seriesId", series.getString("seriesId"));
                if (!planSid.equals(series.getString("seriesId"))) throw new JSONException("片源与剧集不一致");
                info.put("seriesId", planSid);
                JSONObject mediaOptions = PlayerOptions.read(activity);
                String requestedQuality = MediaQuality.normalize(params.optString("quality", info.optString("requestedQuality", "auto")));
                if (info.has("requestedQuality") && !requestedQuality.equals(info.getString("requestedQuality"))) throw new JSONException("清晰度结果不匹配");
                info.put("requestedQuality", requestedQuality);
                params.put("quality", requestedQuality);
                if (!params.has("speed")) params.put("speed", mediaOptions.getDouble("speed"));
                // Validate playback rate even when supplied directly over the bridge.
                if (!(params.opt("speed") instanceof Number)) throw new JSONException("倍速无效");
                PlayerOptions.validateSpeed(params.getDouble("speed"));
                PlaybackSources.Plan plan = PlaybackSources.register(info, episode.getString("vid"), true);
                String url = plan.url;
                if (!plan.source.equals("web")) { downloadPrepared(id, series, episode, plan); break; }
                if (Uri.parse(url).getPath() != null && Uri.parse(url).getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")) throw new Exception("当前为流式播放列表，请在线播放；下载支持完整视频文件");
                if (closed) return;
                String title = series.getString("title") + " · 第 " + episode.getInt("index") + " 集";
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url)).setTitle(title).setDescription("红果 · 视频下载")
                    .addRequestHeader("User-Agent", plan.userAgent).addRequestHeader("Referer", plan.referer)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, series.getString("seriesId") + "_" + episode.getInt("index") + "_" + System.currentTimeMillis() + ".mp4");
                long downloadId = downloads.enqueue(request);
                try { store.addDownload(downloadId, series, episode); } catch (Exception error) { downloads.remove(downloadId); throw error; }
                respond(id, new JSONObject().put("id", downloadId), null); break;
            }
            case "playDownload": {
                long downloadId = params.optLong("id", -1); JSONObject row = store.download(downloadId);
                if (row == null) throw new Exception("下载记录不存在");
                Uri uri = downloadedUri(downloadId);
                if (uri == null) throw new Exception("下载尚未完成，或文件已被移除");
                JSONObject series = LibraryStore.series(row.getJSONObject("series")), episode = LibraryStore.episode(row.getJSONObject("episode"));
                JSONArray queue = downloadedEpisodes(series, episode);
                play(id, series, episode, queue, uri.toString(), true, params); break;
            }
            case "openExternal": {
                String url = NetworkGuard.external(LibraryStore.text(params, "url", 4096));
                activity.runOnUiThread(() -> { if (closed || activity.isFinishing() || activity.isDestroyed()) return; try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); respond(id, new JSONObject().put("opened", true), null); } catch (Exception error) { respond(id, null, "设备上未找到浏览器"); } }); break;
            }
            default: throw new Exception("操作无效");
        }
    }
    private void play(Object id, JSONObject series, JSONObject episode, JSONArray episodes, String url, boolean local, JSONObject params) {
        activity.runOnUiThread(() -> {
            if (closed || activity.isFinishing() || activity.isDestroyed()) return;
            try {
                JSONObject defaults = PlayerOptions.read(activity);
                String quality = MediaQuality.normalize(params.optString("quality", defaults.getString("quality")));
                double speed = params.optDouble("speed", defaults.getDouble("speed"));
                PlayerOptions.validateSpeed(speed);
                Intent intent = new Intent(activity, PlayerActivity.class).putExtra("series", series.toString()).putExtra("episode", episode.toString())
                    .putExtra("episodes", episodes.toString()).putExtra("url", url).putExtra("download", local)
                    .putExtra("quality", quality).putExtra("speed", speed)
                    .putExtra("autoplay", params.optBoolean("autoplay", true)).putExtra("cleanupPlaybackCache", params.optBoolean("cleanupPlaybackCache", true))
                    .putExtra("keepPreviousEpisodes", 1).putExtra("playInfo", params.optJSONObject("playInfo") == null ? "" : params.getJSONObject("playInfo").toString());
                // The compact descriptor crosses Binder once. Decorated per-episode owners
                // exist only inside the player/services, never duplicated in Intent extras.
                if (!local && params.optJSONObject("playlist") != null) intent.putExtra("playlist", params.getJSONObject("playlist").toString());
                activity.startActivity(intent); respond(id, new JSONObject().put("started", true), null);
            } catch (Exception error) { respond(id, null, "播放器启动未成功"); }
        });
    }
    /** Encrypted App media is prepared before it becomes a completed manual download. */
    private void downloadPrepared(Object id, JSONObject series, JSONObject episode, PlaybackSources.Plan plan) throws Exception {
        PlaybackCache cache = PlaybackCache.get(activity); String sid = series.getString("seriesId"), vid = episode.getString("vid"); int index = episode.getInt("index");
        java.io.File target = null; long downloadId = -1;
        cache.protect(sid, vid, index, plan.requestedQuality);
        try {
            java.io.File prepared = cache.fetch(sid, vid, index, plan.url, plan.requestedQuality);
            java.io.File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new Exception("下载目录尚未准备");
            target = new java.io.File(directory, sid + "_" + index + "_" + UUID.randomUUID() + ".mp4");
            java.nio.file.Files.copy(prepared.toPath(), target.toPath());
            String title = series.getString("title") + " · 第 " + index + " 集";
            downloadId = downloads.addCompletedDownload(title, "红果 · 已准备的视频", false, "video/mp4", target.getAbsolutePath(), target.length(), true);
            if (downloadId < 0) throw new Exception("系统下载记录准备失败");
            store.addPreparedDownload(downloadId, series, episode, directory, target);
            respond(id, new JSONObject().put("id", downloadId).put("prepared", true), null);
        } catch (Exception error) {
            if (downloadId >= 0) downloads.remove(downloadId);
            if (target != null) target.delete();
            throw error;
        } finally { cache.release(sid, vid, index, plan.requestedQuality); }
    }
    private Uri downloadedUri(long downloadId) {
        try {
            JSONObject row=store.download(downloadId);if(row==null)return null;
            if(row.has("preparedFile")) { java.io.File owned=LibraryStore.preparedFile(activity,row);return owned==null?null:Uri.fromFile(owned); }
            try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(downloadId))) {
                if (cursor == null || !cursor.moveToFirst() || cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) return null;
                java.io.File owned=recoverPreparedFile(row,cursor);if(owned!=null)return Uri.fromFile(owned);
            }
            return managedDownloadedUri(downloadId);
        } catch (Exception ignored) { return null; }
    }
    private Uri managedDownloadedUri(long downloadId) {
        try {
            Uri uri = downloads.getUriForDownloadedFile(downloadId);
            if (uri == null || !"content".equals(uri.getScheme())) return null;
            try (ParcelFileDescriptor file = activity.getContentResolver().openFileDescriptor(uri, "r")) {
                if (file == null) return null;
            }
            return uri;
        } catch (Exception ignored) { return null; }
    }
    private static String cursorText(Cursor cursor,String column) {
        try {int index=cursor.getColumnIndex(column);return index<0||cursor.isNull(index)?"":cursor.getString(index);}catch(Exception ignored){return "";}
    }
    private java.io.File recoverPreparedFile(JSONObject row,Cursor cursor) {
        try {
            java.io.File directory=activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            long bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            java.io.File recovered=LibraryStore.recoverPreparedFile(directory,row,cursorText(cursor,DownloadManager.COLUMN_LOCAL_URI),bytes,cursorText(cursor,DownloadManager.COLUMN_DESCRIPTION));
            if(recovered==null)return null;
            store.bindPreparedDownload(row.getLong("id"),directory,recovered);
            return recovered;
        }catch(Exception ignored){return null;}
    }
    /** Never mix stream cache, failed downloads or another series into an offline queue. */
    private JSONArray downloadedEpisodes(JSONObject series, JSONObject current) throws Exception {
        JSONArray records = store.downloads(), queue = new JSONArray();
        String sid = series.getString("seriesId");
        for (int i = 0; i < records.length(); i++) {
            JSONObject row = records.optJSONObject(i);
            if (row == null || row.optJSONObject("series") == null || !sid.equals(row.getJSONObject("series").optString("seriesId"))) continue;
            if (downloadedUri(row.optLong("id", -1)) == null) continue;
            try { queue.put(LibraryStore.episode(row.getJSONObject("episode"))); } catch (JSONException ignored) { }
        }
        return LibraryStore.episodes(queue, current);
    }
    private JSONArray downloadRows() throws Exception {
        JSONArray records = store.downloads(), result = new JSONArray();
        for (int i = 0; i < records.length(); i++) {
            JSONObject row = records.optJSONObject(i); if (row == null) continue;
            long id = row.getLong("id"); String status = "failed"; long current = 0, total = -1; int reason = DownloadManager.ERROR_FILE_ERROR;
            java.io.File owned=LibraryStore.preparedFile(activity,row);
            if(owned!=null) { status="success";current=owned.length();total=current;reason=0; }
            else if(!row.has("preparedFile")) {
                try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        status = state == DownloadManager.STATUS_PENDING ? "pending" : state == DownloadManager.STATUS_RUNNING ? "running" : state == DownloadManager.STATUS_PAUSED ? "paused" : state == DownloadManager.STATUS_SUCCESSFUL ? "success" : "failed";
                        current = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)); total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)); reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                        if(state==DownloadManager.STATUS_SUCCESSFUL) {
                            owned=recoverPreparedFile(row,cursor);
                            if(owned!=null) {current=owned.length();total=current;reason=0;}
                            else if(managedDownloadedUri(id)==null) {status="failed";reason=DownloadManager.ERROR_FILE_ERROR;}
                        }
                    }
                } catch(Exception ignored) {status="failed";reason=DownloadManager.ERROR_FILE_ERROR;}
            }
            result.put(new JSONObject().put("id", id).put("title", row.optString("title")).put("index", row.optInt("index")).put("status", status).put("downloadedBytes", current).put("totalBytes", total).put("reason", reason));
        }
        return result;
    }
    private void respond(Object id, Object result, String error) {
        if (closed) return;
        try {
            JSONObject response = new JSONObject().put("id", id).put("result", result == null ? JSONObject.NULL : result).put("error", error == null ? JSONObject.NULL : error);
            final String source = "window.__androidResponse && window.__androidResponse(" + response.toString() + ")";
            // Retry may replace the WebView while a worker response waits in the
            // UI queue. Check this bridge's lifetime on that queue, not only here.
            activity.runOnUiThread(() -> { if (!closed) activity.script(source); });
        } catch (JSONException ignored) { }
    }
    void close() { closed = true; worker.shutdownNow(); downloadWorker.shutdownNow(); searchWorker.shutdownNow(); accountWorker.shutdownNow(); }
}
