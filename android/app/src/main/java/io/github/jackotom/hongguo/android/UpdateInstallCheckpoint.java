package io.github.jackotom.hongguo.android;

import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import java.io.IOException;
import java.util.List;

/** Bounded, conservative install barrier. Never stops a user's playback, cast or download. */
final class UpdateInstallCheckpoint {
    private static final UpdateOperationGate GATE = new UpdateOperationGate();
    private static volatile long pendingPlayerUntil;
    static void playerLaunching() { pendingPlayerUntil = System.nanoTime() + 5_000_000_000L; }
    static UpdateOperationGate.Lease operation() throws IOException { return GATE.operation(); }
    static void release(Object owner) { GATE.release(owner); }
    static void prepare(Context context, Object owner) throws Exception {
        GATE.install(owner);
        boolean ready = false;
        try {
            if (System.nanoTime() < pendingPlayerUntil || PlayerActivity.hasLivePlayer()) throw new IOException("请先返回并结束播放器，再安装更新");
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) throw new IOException("业务状态读取未完成，请稍后重试");
            // API26+ returns this application's services; a running cast service is conservatively busy.
            List<ActivityManager.RunningServiceInfo> running = manager.getRunningServices(100);
            if (running == null) throw new IOException("投屏状态读取未完成，请稍后重试");
            for (ActivityManager.RunningServiceInfo service : running) {
                String name = service.service == null ? "" : service.service.getClassName();
                if (name.equals(DlnaCastService.class.getName()) || name.equals(BrowserCastService.class.getName()))
                    throw new IOException("请先结束投屏及合并任务，再安装更新");
            }
            DownloadManager downloads = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloads == null) throw new IOException("下载状态读取未完成，请稍后重试");
            try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterByStatus(
                    DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING | DownloadManager.STATUS_PAUSED))) {
                if (cursor == null) throw new IOException("下载状态读取未完成，请稍后重试");
                if (cursor.moveToFirst()) throw new IOException("请等待下载完成或取消下载后再安装更新");
            }
            LibraryStore.checkpoint(context);
            OfficialLibrary.checkpointAll(context);
            ready = true;
        } finally { if (!ready) GATE.release(owner); }
    }
}
