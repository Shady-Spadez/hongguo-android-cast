package io.github.jackotom.hongguo.android;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;
import android.view.TextureView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.video.VideoSize;

import java.util.Map;

/**
 * Drop-in replacement for android.widget.VideoView that plays through ExoPlayer and
 * renders into a GPU-composited TextureView.
 *
 * On the Hisilicon hi3751 display stack the vendor MediaPlayer only outputs video
 * through its proprietary sideband channel, which never associates with this app's
 * windows ("Can not find a valid vo handle"): SurfaceView playback froze the panel
 * while decoding, and a plain SurfaceTexture target received no frames at all (black
 * video, position stuck at 0). ExoPlayer's standard MediaCodec pipeline releases
 * frames into the surface buffer queue like any normal Android view, which this
 * panel displays through the regular composition path.
 *
 * The listener shapes mirror VideoView's so call sites keep their lambdas; the
 * player parameter is this view instead of a MediaPlayer.
 */
final class TextureVideoView extends TextureView implements TextureView.SurfaceTextureListener {
    interface OnPreparedListener { void onPrepared(TextureVideoView view); }
    interface OnCompletionListener { void onCompletion(TextureVideoView view); }
    interface OnErrorListener { boolean onError(TextureVideoView view, int what, int extra); }
    interface OnInfoListener { boolean onInfo(TextureVideoView view, int what, int extra); }
    interface OnSeekCompleteListener { void onSeekComplete(TextureVideoView view); }
    interface OnVideoSizeChangedListener { void onVideoSizeChanged(TextureVideoView view, int width, int height); }

    private SimpleExoPlayer player;
    private Uri source;
    private Map<String, String> headers;
    private Surface surface;
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

    TextureVideoView(Context context) {
        super(context);
        setSurfaceTextureListener(this);
    }

    public void setVideoURI(Uri uri) { setVideoURI(uri, null); }

    public void setVideoURI(Uri uri, Map<String, String> requestHeaders) {
        source = uri; headers = requestHeaders; prepared = false; seekOnPrepare = 0;
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

    /** Mirrors PlayerOptions.observedSpeed/setVerifiedSpeed semantics on ExoPlayer, whose
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
        SimpleExoPlayer fresh = new SimpleExoPlayer.Builder(getContext()).build();
        fresh.setVideoSurface(surface);
        fresh.setMediaItem(MediaItem.fromUri(source));
        fresh.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) notifyInfo(MediaPlayer.MEDIA_INFO_BUFFERING_START);
                else if (state == Player.STATE_READY) {
                    notifyInfo(MediaPlayer.MEDIA_INFO_BUFFERING_END);
                    if (!prepared) {
                        prepared = true;
                        if (seekOnPrepare != 0) { fresh.seekTo(seekOnPrepare); seekOnPrepare = 0; }
                        OnPreparedListener preparedCallback = onPreparedListener;
                        if (preparedCallback != null) preparedCallback.onPrepared(TextureVideoView.this);
                    }
                } else if (state == Player.STATE_ENDED) {
                    OnCompletionListener completionCallback = onCompletionListener;
                    if (completionCallback != null) completionCallback.onCompletion(TextureVideoView.this);
                }
            }

            @Override public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    videoWidth = size.width; videoHeight = size.height;
                    requestLayout();
                    OnVideoSizeChangedListener sizeCallback = onVideoSizeChangedListener;
                    if (sizeCallback != null) sizeCallback.onVideoSizeChanged(TextureVideoView.this, size.width, size.height);
                }
            }

            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    OnSeekCompleteListener seekCallback = onSeekCompleteListener;
                    if (seekCallback != null) seekCallback.onSeekComplete(TextureVideoView.this);
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
