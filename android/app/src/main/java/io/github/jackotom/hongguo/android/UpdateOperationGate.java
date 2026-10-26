package io.github.jackotom.hongguo.android;

import java.io.IOException;

/** Serializes an update hand-off against source preparation / manual download work. */
final class UpdateOperationGate {
    private int operations;
    private Object installOwner;
    synchronized Lease operation() throws IOException {
        if (installOwner != null) throw new IOException("正在准备安装更新，请稍后再播放或下载");
        operations++;
        return new Lease();
    }
    synchronized void install(Object owner) throws IOException {
        if (owner == null) throw new IllegalArgumentException("installation owner is required");
        if (installOwner != null || operations != 0) throw new IOException("请等待当前播放准备或下载任务完成后再安装");
        installOwner = owner;
    }
    synchronized void release(Object owner) { if (installOwner == owner) installOwner = null; }
    final class Lease implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            synchronized (UpdateOperationGate.this) {
                if (!closed) { closed = true; operations--; }
            }
        }
    }
}
