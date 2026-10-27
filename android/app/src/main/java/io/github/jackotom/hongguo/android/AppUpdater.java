package io.github.jackotom.hongguo.android;

import android.app.AlertDialog;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** A user-confirmed updater for this Android application, independent of media transport/cache. */
final class AppUpdater {
    private final MainActivity host;
    private final SharedPreferences prefs;
    private final File directory, apk, partial;
    private final int installedCode;
    private final String installedName;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), r -> { Thread t = new Thread(r, "hongguo-updates"); t.setDaemon(true); return t; });
    private volatile boolean destroyed, foreground, busy;
    private volatile long epoch;
    private volatile HttpURLConnection connection;
    private Future<?> task;
    private boolean started, pageReady, manualPrompt;
    private int promptedCode;
    private String state = "idle", message = "尚未检查更新";
    private Candidate candidate, verifiedCandidate;
    private long downloaded;
    private AlertDialog prompt;

    AppUpdater(MainActivity host) {
        this.host = host;
        int currentCode = 0; String currentName = BuildConfig.VERSION_NAME;
        try { PackageInfo installed = host.getPackageManager().getPackageInfo(host.getPackageName(), 0); currentCode = installed.versionCode; currentName = installed.versionName; } catch (PackageManager.NameNotFoundException ignored) { }
        installedCode = currentCode; installedName = currentName;
        prefs = host.getSharedPreferences("hongguo_android_updates_v1", Context.MODE_PRIVATE);
        directory = new File(host.getFilesDir(), "updates"); apk = new File(directory, "verified.apk"); partial = new File(directory, "download.part");
    }
    public final class Bridge {
        @JavascriptInterface public void postMessage(String source) {
            if (source == null || source.length() > 4096) return;
            try {
                JSONObject request = new JSONObject(source); Object id = request.opt("id"); String method = request.getString("method");
                if (!(id instanceof String) || ((String) id).length() > 128 || !Arrays.asList("status", "check", "download", "cancel", "install", "later").contains(method)) return;
                host.runOnUiThread(() -> {
                    if (destroyed || !host.trusted()) return;
                    try {
                        if (method.equals("check")) check(true);
                        else if (method.equals("download")) download();
                        else if (method.equals("cancel")) cancel();
                        else if (method.equals("install")) install();
                        else if (method.equals("later") && candidate != null) prefs.edit().putInt("skipped_code", candidate.code).apply();
                        respond((String) id, null);
                    } catch (Exception error) { respond((String) id, userError(error)); }
                });
            } catch (JSONException ignored) { }
        }
    }
    private void submit(long operation, Runnable job) {
        try { task = worker.submit(job); }
        catch (RejectedExecutionException overloaded) { failed(operation, new IOException("更新操作正在切换，请稍后重试")); }
    }
    private void respond(String id, String error) {
        if (destroyed) return;
        try { host.script("window.__androidUpdatesResponse && window.__androidUpdatesResponse(" + new JSONObject().put("id", id).put("result", snapshot()).put("error", error == null ? JSONObject.NULL : error) + ")"); }
        catch (JSONException ignored) { }
    }
    private JSONObject snapshot() throws JSONException {
        JSONObject value = new JSONObject().put("state", state).put("message", message).put("busy", busy)
            .put("installedVersion", installedName).put("installedCode", installedCode)
            .put("flavor", BuildConfig.IS_TV ? "tv" : "phone").put("downloaded", downloaded)
            .put("lastCheckedAt", prefs.getLong("last_checked", 0));
        if (candidate != null) value.put("versionName", candidate.name).put("versionCode", candidate.code).put("size", candidate.bytes).put("notes", candidate.notes);
        return value;
    }
    private void publish() {
        if (destroyed) return;
        try { host.script("window.__androidUpdatesChanged && window.__androidUpdatesChanged(" + snapshot() + ")"); } catch (JSONException ignored) { }
    }
    void pageReady() { pageReady = true; maybePrompt(); }
    void pageUnavailable() { pageReady = false; dismissPrompt(); }
    void resume() {
        foreground = true;
        if (!started) { start(); return; }
        if (!busy && prefs.getBoolean("waiting_permission", false)) {
            prefs.edit().putBoolean("waiting_permission", false).apply();
            state = "ready";
            if (host.getPackageManager().canRequestPackageInstalls()) install();
            else { state = "ready"; message = "安装权限尚未开启，可稍后继续安装"; publish(); }
        } else if (!busy && state.equals("installing")) {
            state = "ready"; message = "安装尚未完成，已保留验证过的更新包"; publish();
        }
        maybePrompt();
    }
    void pause() { foreground = false; dismissPrompt(); }
    private void start() {
        started = true;
        if (BuildConfig.DEBUG || installedCode <= 0) { state = "unsupported"; message = installedCode <= 0 ? "当前版本身份读取失败，请重启后重试" : "调试包不使用正式更新渠道"; publish(); return; }
        busy = true; state = "restoring"; message = "正在读取更新状态…"; publish();
        final long operation = ++epoch;
        submit(operation, () -> {
            Candidate restored = null;
            try {
                directory.mkdirs(); partial.delete();
                String saved = prefs.getString("manifest", "");
                if (!saved.isEmpty() && apk.isFile() && System.currentTimeMillis() - apk.lastModified() <= UpdatePolicy.CHECK_INTERVAL) {
                    Candidate parsed = Candidate.parse(new JSONObject(saved), installedCode, installedName);
                    verify(parsed, apk); restored = parsed;
                } else apk.delete();
            } catch (Exception invalid) { restored = null; apk.delete(); }
            final Candidate result = restored;
            finish(operation, () -> {
                busy = false; candidate = result; verifiedCandidate = result;
                if (result != null) { state = "ready"; downloaded = result.bytes; message = "更新包已验证，可继续安装"; }
                else { prefs.edit().remove("manifest").remove("waiting_permission").apply(); downloaded = 0; state = "idle"; message = "尚未检查更新"; }
                publish();
                if (prefs.getBoolean("waiting_permission", false) && foreground) resume();
                else if (result == null && UpdatePolicy.automaticCheckDue(System.currentTimeMillis(), prefs.getLong("last_auto_attempt", 0))) check(false);
            });
        });
    }
    private void check(boolean manual) {
        if (destroyed || busy || state.equals("installing") || state.equals("unsupported")) return;
        if (!manual) prefs.edit().putLong("last_auto_attempt", System.currentTimeMillis()).apply();
        busy = true; manualPrompt = manual; state = "checking"; message = "正在检查更新…"; publish();
        final long operation = ++epoch;
        submit(operation, () -> {
            try {
                Candidate latest = Candidate.parse(new JSONObject(new String(fetchManifest(operation), StandardCharsets.UTF_8)), installedCode, installedName);
                finish(operation, () -> {
                    busy = false;
                    prefs.edit().putLong("last_checked", System.currentTimeMillis()).apply();
                    if (latest.code == installedCode) { state = "current"; message = "已是最新版本"; candidate = null; }
                    else if (apk.isFile() && candidate != null && latest.same(candidate) && stateWasReady()) {
                        candidate = latest; state = "ready"; message = "更新包已下载，可继续安装";
                    } else { candidate = latest; downloaded = 0; state = "available"; message = "发现新版本 " + latest.name; }
                    publish(); maybePrompt();
                });
            } catch (Exception error) { failed(operation, error); }
        });
    }
    private boolean stateWasReady() { return candidate != null && candidate.same(verifiedCandidate) && apk.isFile() && apk.length() == candidate.bytes; }
    private void download() {
        if (destroyed || busy || candidate == null || !state.equals("available") && !state.equals("error")) return;
        Candidate target = candidate;
        if (target.code <= installedCode) return;
        dismissPrompt(); busy = true; state = "downloading"; message = "正在下载更新…"; downloaded = 0; publish();
        final long operation = ++epoch;
        submit(operation, () -> {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("更新目录创建失败");
                if (directory.getUsableSpace() < target.bytes + 8L * 1024 * 1024) throw new IOException("存储空间不足，请先释放空间");
                partial.delete();
                transfer(target, operation);
                finish(operation, () -> { state = "verifying"; message = "正在校验更新包…"; publish(); });
                verify(target, partial); live(operation);
                Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (!prefs.edit().putString("manifest", target.json.toString()).commit()) { apk.delete(); throw new IOException("更新状态保存失败，请重新下载"); }
                finish(operation, () -> { busy = false; verifiedCandidate = target; state = "ready"; downloaded = target.bytes; message = "校验通过，确认后安装 " + target.name; publish(); });
            } catch (Exception error) { partial.delete(); failed(operation, error); }
        });
    }
    private void cancel() {
        if (!busy || state.equals("saving")) return;
        ++epoch; HttpURLConnection active = connection; if (active != null) active.disconnect();
        if (task != null) task.cancel(true); worker.purge();
        busy = false; state = stateWasReady() ? "ready" : candidate == null ? "idle" : "available"; downloaded = stateWasReady() ? candidate.bytes : 0; message = "已取消，当前版本继续可用"; publish();
    }
    private void install() {
        if (destroyed || busy || candidate == null || !state.equals("ready") || !foreground) return;
        Candidate target = candidate;
        busy = true; state = "saving"; message = "正在保存本机记录并复核安装包…"; publish();
        final long operation = ++epoch;
        submit(operation, () -> {
            boolean archiveVerified = false;
            try {
                verify(target, apk); archiveVerified = true; live(operation);
                UpdateInstallCheckpoint.prepare(host.getApplicationContext(), this); live(operation);
                finish(operation, () -> {
                    busy = false;
                    try { handoff(target); }
                    catch (Exception error) { state = "ready"; message = userError(error); publish(); }
                    finally { UpdateInstallCheckpoint.release(this); }
                });
            } catch (Exception error) {
                UpdateInstallCheckpoint.release(this);
                final boolean validArchive = archiveVerified;
                if (!validArchive) { apk.delete(); prefs.edit().remove("manifest").apply(); }
                finish(operation, () -> { busy = false; if (!validArchive) verifiedCandidate = null; state = validArchive ? "ready" : "error"; message = userError(error); publish(); });
            }
        });
    }
    private void handoff(Candidate target) throws Exception {
        if (!foreground || destroyed) throw new IOException("请回到应用后继续安装");
        if (!host.getPackageManager().canRequestPackageInstalls()) {
            if (!prefs.edit().putBoolean("waiting_permission", true).commit()) throw new IOException("安装状态保存失败");
            state = "waiting_permission"; message = "请允许红果安装更新，返回后继续"; publish();
            try { host.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + host.getPackageName()))); }
            catch (ActivityNotFoundException missing) { prefs.edit().remove("waiting_permission").apply(); throw new IOException("这台设备没有安装来源设置入口，请在系统设置中操作"); }
            return;
        }
        Uri uri = Uri.parse("content://" + host.getPackageName() + ".updates/apk");
        Intent installer = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive");
        installer.setClipData(ClipData.newUri(host.getContentResolver(), "Hongguo update", uri));
        installer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        state = "installing"; message = "请在系统界面确认安装；当前记录已保存"; publish();
        try { host.startActivity(installer); }
        catch (ActivityNotFoundException missing) { throw new IOException("这台设备没有可用的APK安装器，更新包已保留"); }
    }
    private void maybePrompt() {
        if (destroyed || !foreground || !pageReady || !host.trusted() || busy || !state.equals("available") || candidate == null || prompt != null) return;
        if (!manualPrompt && (prefs.getInt("skipped_code", 0) == candidate.code || promptedCode == candidate.code)) return;
        manualPrompt = false; promptedCode = candidate.code;
        Candidate target = candidate;
        prompt = new AlertDialog.Builder(host).setTitle("发现新版本 " + target.name)
            .setMessage(target.notes.isEmpty() ? "下载校验后，由系统确认安装；观看记录会保留。" : target.notes)
            .setNegativeButton("稍后", (d, w) -> prefs.edit().putInt("skipped_code", target.code).apply())
            .setPositiveButton("下载更新", (d, w) -> download()).create();
        prompt.setOnDismissListener(d -> prompt = null); prompt.show();
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }
    private void dismissPrompt() { if (prompt != null) { prompt.dismiss(); prompt = null; } }
    private void failed(long operation, Exception error) { finish(operation, () -> { busy = false; state = stateWasReady() ? "ready" : "error"; message = userError(error); publish(); }); }
    private static String userError(Exception error) {
        if (error instanceof SocketTimeoutException) return "检查或下载超时，请稍后重试";
        if (error instanceof UnknownHostException) return "更新服务暂未连接，请检查网络";
        String text = error.getMessage();
        if (text != null && !text.contains("://") && text.length() <= 160 && !text.startsWith("java.")) return text;
        return "更新未完成，请稍后重试；当前版本和记录保持不变";
    }
    private void finish(long operation, Runnable action) { host.runOnUiThread(() -> { if (!destroyed && epoch == operation) action.run(); }); }
    private void live(long operation) throws IOException { if (destroyed || epoch != operation || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("更新操作已取消"); }
    private HttpURLConnection open(String address, long operation, long deadline, boolean asset) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            live(operation);
            if (SystemClock.elapsedRealtime() >= deadline) throw new SocketTimeoutException();
            URL url = new URL(address); HttpURLConnection current = (HttpURLConnection) url.openConnection(); connection = current;
            current.setInstanceFollowRedirects(false); current.setConnectTimeout(6000); current.setReadTimeout(8000);
            current.setRequestProperty("Accept-Encoding", "identity"); current.setRequestProperty("Cache-Control", "no-cache"); current.setRequestProperty("User-Agent", "HongguoAndroid/" + BuildConfig.VERSION_NAME);
            int status;
            try { status = current.getResponseCode(); live(operation); }
            catch (Exception failure) { current.disconnect(); connection = null; throw failure; }
            if (status >= 300 && status < 400) {
                String location = current.getHeaderField("Location"); current.disconnect();
                if (!asset || location == null) throw new IOException("更新清单发生意外跳转");
                address = url.toURI().resolve(location).toString();
                if (!UpdatePolicy.allowedRedirect(address)) throw new IOException("更新包跳转地址不受支持");
                continue;
            }
            if (status != 200) { current.disconnect(); throw new IOException(status == 404 ? "当前渠道的更新清单或安装包尚未发布" : "更新服务暂未返回有效内容，请稍后重试"); }
            return current;
        }
        throw new IOException("更新包跳转次数过多");
    }
    private byte[] fetchManifest(long operation) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        HttpURLConnection current = open(UpdatePolicy.manifestUrl(BuildConfig.IS_TV), operation, deadline, false);
        try (InputStream input = current.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) { live(operation); if (SystemClock.elapsedRealtime() >= deadline) throw new SocketTimeoutException(); if (output.size() + n > 128 * 1024) throw new IOException("更新清单过大"); output.write(buffer, 0, n); }
            return output.toByteArray();
        } finally { current.disconnect(); connection = null; }
    }
    private void transfer(Candidate target, long operation) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 180000; final long[] lastProgress = {0};
        HttpURLConnection current = open(target.url, operation, deadline, true);
        try (InputStream input = current.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
            long declared = current.getContentLengthLong(); if (declared >= 0 && declared != target.bytes) throw new IOException("更新包大小与清单不一致");
            UpdateIntegrity.copy(input, output, target.bytes, () -> {
                live(operation); if (SystemClock.elapsedRealtime() >= deadline) throw new SocketTimeoutException();
            }, count -> {
                long now = SystemClock.elapsedRealtime();
                if (now - lastProgress[0] >= 250) { lastProgress[0] = now; finish(operation, () -> { downloaded = count; publish(); }); }
            });
            output.getFD().sync();
        } finally { current.disconnect(); connection = null; }
    }
    private void verify(Candidate target, File file) throws Exception {
        if (!file.isFile() || file.length() != target.bytes || !hashFile(file).equals(target.hash)) throw new IOException("更新包完整性校验失败，请重新下载");
        PackageManager manager = host.getPackageManager(); int flag = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = manager.getPackageInfo(host.getPackageName(), flag), archive = manager.getPackageArchiveInfo(file.getPath(), flag);
        if (archive == null || !UpdatePolicy.matchingArchive(installed.packageName, installed.versionCode, archive.packageName, archive.versionCode, archive.versionName, target.code, target.name, signerHashes(installed), signerHashes(archive))
                || archive.applicationInfo == null || archive.applicationInfo.minSdkVersion != target.minSdk || archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) throw new IOException("安装包的应用、版本、系统要求或签名不匹配");
    }
    private static String hashFile(File file) throws Exception {
        return UpdateIntegrity.hash(file, () -> { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("校验已取消"); });
    }
    private static String[] signerHashes(PackageInfo value) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && value.signingInfo != null ? value.signingInfo.getApkContentsSigners() : value.signatures;
        if (signatures == null) return new String[0]; String[] result = new String[signatures.length];
        for (int i = 0; i < signatures.length; i++) result[i] = hex(MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray())); return result;
    }
    private static String hex(byte[] data) { StringBuilder out = new StringBuilder(data.length * 2); for (byte b : data) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
    void destroy() { destroyed = true; foreground = false; ++epoch; dismissPrompt(); HttpURLConnection active = connection; if (active != null) active.disconnect(); if (task != null) task.cancel(true); worker.shutdownNow(); UpdateInstallCheckpoint.release(this); }

    private static final class Candidate {
        final JSONObject json; final int code, minSdk; final long bytes; final String name, url, hash, notes;
        Candidate(JSONObject json, int code, String name, int minSdk, long bytes, String url, String hash, String notes) { this.json = json; this.code = code; this.name = name; this.minSdk = minSdk; this.bytes = bytes; this.url = url; this.hash = hash; this.notes = notes; }
        static long integer(JSONObject value, String key) throws JSONException { Object v = value.get(key); if (!(v instanceof Number)) throw new JSONException("更新清单数字字段无效"); double n = ((Number) v).doubleValue(); if (!Double.isFinite(n) || n != Math.rint(n) || n < 0 || n > Integer.MAX_VALUE) throw new JSONException("更新清单数字字段无效"); return ((Number) v).longValue(); }
        static Candidate parse(JSONObject value, int installedCode, String installedName) throws Exception {
            int schema = (int) integer(value, "schemaVersion"), code = (int) integer(value, "versionCode"), min = (int) integer(value, "minSdk"); long bytes = integer(value, "size");
            String flavor = value.getString("flavor"), pkg = value.getString("packageName"), name = value.getString("versionName"), hash = value.getString("sha256").toLowerCase(Locale.ROOT), url = value.getString("apkUrl");
            UpdatePolicy.validate(schema, value.getString("channel"), flavor, pkg, code, name, min, bytes, hash, url, BuildConfig.IS_TV ? "io.github.jackotom.hongguo.android.tv" : "io.github.jackotom.hongguo.android", installedCode, installedName, Build.VERSION.SDK_INT);
            String notes = value.optString("notes", ""); if (notes.length() > 4000) notes = notes.substring(0, 4000);
            return new Candidate(new JSONObject(value.toString()), code, name, min, bytes, url, hash, notes);
        }
        boolean same(Candidate other) { return other != null && code == other.code && name.equals(other.name) && hash.equals(other.hash) && bytes == other.bytes && url.equals(other.url); }
    }
}
