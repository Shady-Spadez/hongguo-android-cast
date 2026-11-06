package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Drop-in replacement for android.widget.VideoView that plays through Media3 (ExoPlayer)
 * and renders into a classic SurfaceView.
 *
 * On this hi3751 box the vendor MediaPlayer only outputs video through its proprietary
 * sideband channel, which never associates with third-party windows ("Can not find a
 * valid vo handle"), and TextureView rendering on this old Mali GPU never shows frames
 * either. The combination that does display here is the standard one used by every
 * mainstream video app: MediaCodec through Media3 releasing GraphicBuffers into a
 * SurfaceView, which the hardware composer overlays like a normal video layer.
 *
 * The listener shapes mirror VideoView's so call sites keep their lambdas; the
 * player parameter is this view instead of a MediaPlayer.
 */
final class MediaVideoView extends SurfaceView implements SurfaceHolder.Callback {
    interface OnPreparedListener { void onPrepared(MediaVideoView view); }
    interface OnCompletionListener { void onCompletion(MediaVideoView view); }
    interface OnErrorListener { boolean onError(MediaVideoView view, int what, int extra); }
    interface OnInfoListener { boolean onInfo(MediaVideoView view, int what, int extra); }
    interface OnSeekCompleteListener { void onSeekComplete(MediaVideoView view); }
    interface OnVideoSizeChangedListener { void onVideoSizeChanged(MediaVideoView view, int width, int height); }

    private ExoPlayer player;
    private Uri source;
    private Map<String, String> headers;
    private Future<?> staging;
    private boolean surfaceReady;
    private boolean prepared;
    private int seekOnPrepare;
    private int videoWidth, videoHeight;
    private OnPreparedListener onPreparedListener;
    private OnCompletionListener onCompletionListener;
    private OnErrorListener onErrorListener;
    private OnInfoListener onInfoListener;
    private OnSeekCompleteListener onSeekCompleteListener;
    private OnVideoSizeChangedListener onVideoSizeChangedListener;

    MediaVideoView(Context context) {
        super(context);
        getHolder().addCallback(this);
    }

    public void setVideoURI(Uri uri) { setVideoURI(uri, null, null); }

    public void setVideoURI(Uri uri, Map<String, String> requestHeaders) { setVideoURI(uri, requestHeaders, null); }

    /** @param stagingCompletion when non-null the URI points at a .part file that the given
     *  future is still appending to; playback reads block at the staged head until the
     *  future completes (or fail if it completes exceptionally). */
    public void setVideoURI(Uri uri, Map<String, String> requestHeaders, Future<?> stagingCompletion) {
        source = uri; headers = requestHeaders; staging = stagingCompletion; prepared = false; seekOnPrepare = 0;
        open();
    }

    public void setOnPreparedListener(OnPreparedListener listener) { onPreparedListener = listener; }
    public void setOnCompletionListener(OnCompletionListener listener) { onCompletionListener = listener; }
    public void setOnErrorListener(OnErrorListener listener) { onErrorListener = listener; }
    public void setOnInfoListener(OnInfoListener listener) { onInfoListener = listener; }
    public void setOnSeekCompleteListener(OnSeekCompleteListener listener) { onSeekCompleteListener = listener; }
    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) { onVideoSizeChangedListener = listener; }

    public void start() {
        if (player != null) player.setPlayWhenReady(true);
        else if (source != null) open();
    }

    public void pause() {
        if (player != null) player.setPlayWhenReady(false);
    }

    public void seekTo(int position) {
        if (player != null) player.seekTo(position);
        else seekOnPrepare = position;
    }

    public void stopPlayback() { release(); }

    public boolean isPlaying() { return player != null && player.isPlaying(); }

    public int getCurrentPosition() { return player == null ? 0 : (int) player.getCurrentPosition(); }

    public int getDuration() { return player == null ? 0 : (int) player.getDuration(); }

    public int getVideoWidth() { return videoWidth; }

    public int getVideoHeight() { return videoHeight; }

    public void setVolume(float left, float right) { if (player != null) player.setVolume(Math.min(left, right)); }

    /** Mirrors PlayerOptions.observedSpeed/setVerifiedSpeed semantics on Media3, whose
     *  setPlaybackParameters neither throws spuriously nor resumes a paused player. */
    public float playbackSpeed() { return player == null ? 1f : player.getPlaybackParameters().speed; }

    public void setPlaybackSpeed(float speed) {
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(speed));
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Same aspect-fit logic as android.widget.VideoView.
        int width = getDefaultSize(videoWidth, widthMeasureSpec);
        int height = getDefaultSize(videoHeight, heightMeasureSpec);
        if (videoWidth > 0 && videoHeight > 0) {
            int widthSpec = MeasureSpec.getSize(widthMeasureSpec);
            int heightSpec = MeasureSpec.getSize(heightMeasureSpec);
            width = widthSpec;
            height = heightSpec;
            float specRatio = (float) widthSpec / heightSpec;
            float videoRatio = (float) videoWidth / videoHeight;
            if (specRatio > videoRatio) width = (int) (heightSpec * videoRatio + 0.5f);
            else height = (int) (widthSpec / videoRatio + 0.5f);
        }
        setMeasuredDimension(width, height);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (player == null && source != null) open();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        release();
    }

    private void open() {
        release();
        if (source == null || !surfaceReady) return;
        Surface surface = getHolder().getSurface();
        if (surface == null || !surface.isValid()) return;
        if ("file".equals(source.getScheme())) {
            if (staging != null) awaitStagedHead(source.getPath(), 768 * 1024);
            patchHevcLevelClaim(source.getPath());
        }
        ExoPlayer fresh = new ExoPlayer.Builder(getContext()).build();
        fresh.setVideoSurface(surface);
        if (staging != null && "file".equals(source.getScheme()))
            fresh.setMediaSource(new ProgressiveMediaSource.Factory(() -> new GrowingFileDataSource(new java.io.File(source.getPath()), staging)).createMediaSource(MediaItem.fromUri(source)));
        else fresh.setMediaItem(MediaItem.fromUri(source));
        fresh.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) notifyInfo(MediaPlayer.MEDIA_INFO_BUFFERING_START);
                else if (state == Player.STATE_READY) {
                    notifyInfo(MediaPlayer.MEDIA_INFO_BUFFERING_END);
                    if (!prepared) {
                        prepared = true;
                        if (seekOnPrepare != 0) { fresh.seekTo(seekOnPrepare); seekOnPrepare = 0; }
                        OnPreparedListener preparedCallback = onPreparedListener;
                        if (preparedCallback != null) preparedCallback.onPrepared(MediaVideoView.this);
                    }
                } else if (state == Player.STATE_ENDED) {
                    OnCompletionListener completionCallback = onCompletionListener;
                    if (completionCallback != null) completionCallback.onCompletion(MediaVideoView.this);
                }
            }

            @Override public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    videoWidth = size.width; videoHeight = size.height;
                    requestLayout();
                    OnVideoSizeChangedListener sizeCallback = onVideoSizeChangedListener;
                    if (sizeCallback != null) sizeCallback.onVideoSizeChanged(MediaVideoView.this, size.width, size.height);
                }
            }

            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    OnSeekCompleteListener seekCallback = onSeekCompleteListener;
                    if (seekCallback != null) seekCallback.onSeekComplete(MediaVideoView.this);
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                int code = error.errorCode;
                boolean unsupported = code == PlaybackException.ERROR_CODE_DECODING_FAILED
                        || code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                        || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                        || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                        || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED;
                notifyError(unsupported ? MediaPlayer.MEDIA_ERROR_UNSUPPORTED : MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            }
        });
        player = fresh;
        fresh.prepare();
    }

    private void notifyInfo(int what) {
        OnInfoListener listener = onInfoListener;
        if (listener != null) listener.onInfo(this, what, 0);
    }

    /** Block until the growing file has at least minBytes staged (enough for the moov plus
     *  the level patch), the staging future completes, or a generous timeout — after which
     *  the data source itself blocks on reads instead. */
    private void awaitStagedHead(String path, long minBytes) {
        java.io.File file = new java.io.File(path);
        long deadline = System.currentTimeMillis() + 20000;
        while (!file.isFile() || file.length() < minBytes) {
            if (staging == null || staging.isDone() || System.currentTimeMillis() >= deadline) return;
            try { Thread.sleep(200); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** Reads a file that is still being appended to by the staging download. Reads at the
     *  staged head wait for more bytes; end-of-input is only reported once the staging
     *  future has completed successfully. The fd is opened once, so the policy's atomic
     *  .part → final rename mid-read is transparent. */
    private static final class GrowingFileDataSource extends BaseDataSource {
        private final java.io.File file;
        private final Future<?> staging;
        private RandomAccessFile data;
        private long position;
        private Uri uri;

        GrowingFileDataSource(java.io.File file, Future<?> staging) {
            super(false);
            this.file = file;
            this.staging = staging;
        }

        @Override public long open(DataSpec spec) throws IOException {
            transferInitializing(spec);
            data = new RandomAccessFile(file, "r");
            uri = Uri.fromFile(file);
            position = spec.position;
            transferStarted(spec);
            return C.LENGTH_UNSET;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            for (;;) {
                if (data == null) throw new IOException("缓存数据源已关闭");
                long staged = data.length();
                if (position >= staged) {
                    if (staging.isDone()) {
                        try { staging.get(); } catch (Exception failure) { throw new IOException("缓存下载未完成", failure); }
                        return C.RESULT_END_OF_INPUT;
                    }
                    try { Thread.sleep(100); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("缓存读取已取消", error); }
                    continue;
                }
                int readable = (int) Math.min(length, staged - position);
                data.seek(position);
                int read = data.read(buffer, offset, readable);
                if (read < 0) return C.RESULT_END_OF_INPUT;
                if (read > 0) { position += read; bytesTransferred(read); }
                return read;
            }
        }

        @Override public Uri getUri() { return uri; }

        @Override public void close() throws IOException {
            uri = null;
            if (data != null) { try { data.close(); } finally { data = null; transferEnded(); } }
        }
    }

    /**
     * The CDN's HEVC encodes carry an inflated general_level_idc (observed L186 = level 6.2
     * on 1080p30 content that only needs level 4), and this box's decoder refuses to init —
     * or inits but never emits frames — for claims beyond its declared maximum, so MediaCodec
     * logs NoSupport and every surface stays blank. The staged file's actual content is well
     * within Main@L120 capability, so rewrite the hvcC level byte down to 120 before playback;
     * decoders treat level_idc as advisory once the profile matches.
     */
    private static void patchHevcLevelClaim(String path) {
        if (path == null) return;
        java.io.RandomAccessFile file = null;
        try {
            file = new java.io.RandomAccessFile(path, "rw");
            int window = 512 * 1024;
            long length = file.length();
            byte[] buffer = new byte[(int) Math.min(window, length)];
            file.readFully(buffer);
            // Locate the hvcC box and confine every rewrite strictly inside it: the
            // SPS-pattern bytes also occur by chance inside mdat sample data (emulation
            // prevention runs), and touching those corrupts NAL boundaries ("Invalid NAL
            // length" from the extractor). Media3 derives the codec string from the hvcC
            // only, so that box is both sufficient and the only safe place.
            int boxStart = -1, boxEnd = buffer.length;
            for (int i = 0; i + 20 <= buffer.length; i++) {
                if (buffer[i] != 'h' || buffer[i + 1] != 'v' || buffer[i + 2] != 'c' || buffer[i + 3] != 'C') continue;
                if (buffer[i + 4] != 1) continue; // configurationVersion
                boxStart = i;
                if (i >= 4) {
                    long boxSize = ((buffer[i - 4] & 0xffL) << 24) | ((buffer[i - 3] & 0xffL) << 16) | ((buffer[i - 2] & 0xffL) << 8) | (buffer[i - 1] & 0xffL);
                    if (boxSize >= 8 && boxSize <= buffer.length) boxEnd = (int) boxSize + i - 4;
                }
                break;
            }
            if (boxStart < 0) return;
            int levelOffset = boxStart + 4 + 12; // general_level_idc within hvcC payload
            if (levelOffset < boxEnd && (buffer[levelOffset] & 0xff) > 123) {
                buffer[levelOffset] = (byte) 120;
                file.seek(levelOffset);
                file.write(buffer[levelOffset]);
            }
            // The SPS NAL inside the hvcC arrays repeats the level inside its
            // profile_tier_level — with this encoder the zero run carries three
            // emulation-prevention triples, so the level byte sits right after the
            // fixed 00 00 03 00 00 03 00 00 03 00 tail. Media3 derives its codec
            // string from there, so patch it too — inside the box only.
            for (int i = boxStart + 18; i + 10 < boxEnd; i++) {
                if (buffer[i] != 0 || buffer[i + 1] != 0 || buffer[i + 2] != 3) continue;
                if (buffer[i + 3] != 0 || buffer[i + 4] != 0 || buffer[i + 5] != 3) continue;
                if (buffer[i + 6] != 0 || buffer[i + 7] != 0 || buffer[i + 8] != 3 || buffer[i + 9] != 0) continue;
                int level = buffer[i + 10] & 0xff;
                if (level > 123) {
                    buffer[i + 10] = (byte) 120;
                    file.seek(i + 10);
                    file.write(buffer[i + 10]);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (file != null) try { file.close(); } catch (Exception ignored) { }
        }
    }

    private void notifyError(int what, int extra) {
        OnErrorListener listener = onErrorListener;
        if (listener != null) listener.onError(this, what, extra);
    }

    private void release() {
        prepared = false;
        if (player != null) {
            try { player.setVideoSurface(null); player.stop(); player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }
}
