package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;
import android.view.TextureView;

import java.io.IOException;
import java.util.Map;

/**
 * Drop-in replacement for android.widget.VideoView that renders through a GPU-composited
 * texture instead of a SurfaceView sideband layer.
 *
 * On the Hisilicon hi3751 display stack the vendor MediaPlayer never finds a video-output
 * handle for this app's SurfaceView windows ("Can not find a valid vo handle") and then
 * freezes the panel output, so SurfaceView playback decoded fine but never showed motion
 * on screen. TextureView goes through the regular SurfaceFlinger composition path (the
 * same one the app UI itself uses) and avoids the sideband channel entirely.
 */
final class TextureVideoView extends TextureView implements TextureView.SurfaceTextureListener {
    private MediaPlayer player;
    private Uri source;
    private Map<String, String> headers;
    private Surface surface;
    private boolean surfaceReady;
    private boolean wantPlaying = true;
    private boolean prepared;
    private int seekOnPrepare;
    private int videoWidth, videoHeight;
    private MediaPlayer.OnPreparedListener onPreparedListener;
    private MediaPlayer.OnCompletionListener onCompletionListener;
    private MediaPlayer.OnErrorListener onErrorListener;
    private MediaPlayer.OnInfoListener onInfoListener;

    TextureVideoView(Context context) {
        super(context);
        setSurfaceTextureListener(this);
    }

    public void setVideoURI(Uri uri) { setVideoURI(uri, null); }

    public void setVideoURI(Uri uri, Map<String, String> requestHeaders) {
        source = uri; headers = requestHeaders; wantPlaying = true; prepared = false; seekOnPrepare = 0;
        open();
    }

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener listener) { onPreparedListener = listener; }
    public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) { onCompletionListener = listener; }
    public void setOnErrorListener(MediaPlayer.OnErrorListener listener) { onErrorListener = listener; }
    public void setOnInfoListener(MediaPlayer.OnInfoListener listener) { onInfoListener = listener; }

    public void start() {
        wantPlaying = true;
        if (player != null && prepared) try { player.start(); } catch (IllegalStateException ignored) { }
        else if (player == null) open();
    }

    public void pause() {
        wantPlaying = false;
        if (player != null && prepared) try { player.pause(); } catch (IllegalStateException ignored) { }
    }

    public void seekTo(int position) {
        if (player != null && prepared) try { player.seekTo(position); } catch (IllegalStateException ignored) { }
        else seekOnPrepare = position;
    }

    public void stopPlayback() { release(); }

    public boolean isPlaying() {
        try { return player != null && prepared && player.isPlaying(); } catch (IllegalStateException ignored) { return false; }
    }

    public int getCurrentPosition() {
        try { return player != null && prepared ? player.getCurrentPosition() : 0; } catch (IllegalStateException ignored) { return 0; }
    }

    public int getDuration() {
        try { return player != null && prepared ? player.getDuration() : 0; } catch (IllegalStateException ignored) { return 0; }
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

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        surface = new Surface(texture);
        surfaceReady = true;
        if (player == null && source != null) open();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        surfaceReady = false;
        if (surface != null) { surface.release(); surface = null; }
        release();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }

    private void open() {
        release();
        if (source == null || !surfaceReady) return;
        try {
            MediaPlayer fresh = new MediaPlayer();
            fresh.setAudioStreamType(AudioManager.STREAM_MUSIC);
            fresh.setSurface(surface);
            fresh.setDataSource(getContext(), source, headers);
            fresh.setOnVideoSizeChangedListener((mediaPlayer, width, height) -> {
                if (width > 0 && height > 0) { videoWidth = width; videoHeight = height; requestLayout(); }
            });
            fresh.setOnPreparedListener(mediaPlayer -> {
                prepared = true;
                if (seekOnPrepare != 0) { mediaPlayer.seekTo(seekOnPrepare); seekOnPrepare = 0; }
                if (wantPlaying) mediaPlayer.start();
                MediaPlayer.OnPreparedListener listener = onPreparedListener;
                if (listener != null) listener.onPrepared(mediaPlayer);
            });
            fresh.setOnCompletionListener(mediaPlayer -> {
                MediaPlayer.OnCompletionListener listener = onCompletionListener;
                if (listener != null) listener.onCompletion(mediaPlayer);
            });
            fresh.setOnErrorListener((mediaPlayer, what, extra) -> {
                MediaPlayer.OnErrorListener listener = onErrorListener;
                return listener != null && listener.onError(mediaPlayer, what, extra);
            });
            fresh.setOnInfoListener((mediaPlayer, what, extra) -> {
                MediaPlayer.OnInfoListener listener = onInfoListener;
                return listener != null && listener.onInfo(mediaPlayer, what, extra);
            });
            player = fresh;
            fresh.prepareAsync();
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException error) {
            release();
            MediaPlayer.OnErrorListener listener = onErrorListener;
            if (listener != null) listener.onError(player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
        }
    }

    private void release() {
        prepared = false;
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            try { player.setOnPreparedListener(null); player.setOnCompletionListener(null); player.setOnErrorListener(null); player.setOnInfoListener(null); player.setOnVideoSizeChangedListener(null); player.setSurface(null); player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }
}
