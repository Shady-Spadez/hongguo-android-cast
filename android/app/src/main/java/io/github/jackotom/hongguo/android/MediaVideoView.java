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
import androidx.media3.exoplayer.ExoPlayer;

import java.util.Map;

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
        if ("file".equals(source.getScheme())) patchHevcLevelClaim(source.getPath());
        ExoPlayer fresh = new ExoPlayer.Builder(getContext()).build();
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
            for (int i = 0; i + 20 <= buffer.length; i++) {
                if (buffer[i] != 'h' || buffer[i + 1] != 'v' || buffer[i + 2] != 'c' || buffer[i + 3] != 'C') continue;
                if (buffer[i + 4] != 1) continue; // configurationVersion
                int levelOffset = i + 4 + 12; // general_level_idc within hvcC payload
                int level = buffer[levelOffset] & 0xff;
                if (level > 123) {
                    buffer[levelOffset] = (byte) 120;
                    file.seek(levelOffset);
                    file.write(buffer[levelOffset]);
                }
                break;
            }
            // The SPS NAL inside the hvcC arrays (and any in-band copy) repeats the
            // level inside its profile_tier_level — with this encoder the zero run
            // carries three emulation-prevention triples, so the level byte sits
            // right after the fixed 00 00 03 00 00 03 00 00 03 00 tail. Media3
            // derives its codec string from there, so patch it too.
            for (int i = 0; i + 10 < buffer.length; i++) {
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
