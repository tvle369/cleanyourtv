package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Plays a theme's local, looped background.mp4 behind Home (config video_background), muted unless
 * video_background_sound is on. Native MediaPlayer and TextureView only, no other media dependency.
 *
 * Added above the wallpaper at construction time (index 1 of root: directly over the wallpaper's own
 * ImageView at index 0, below sky/clock/greeting/dock/glass, which are already in root by then) and left
 * invisible whenever the file is absent, invalid or playback fails, so the wallpaper (untouched by this
 * class) shows through exactly as it already does with video_background off.
 */
final class VideoBackgroundPlayer implements TextureView.SurfaceTextureListener {
    private final FrameLayout root;
    private final TextureView texture;
    private final java.io.File file;
    private final boolean soundEnabled;
    private MediaPlayer player;
    /** The Surface wrapping the TextureView's current SurfaceTexture; released only after the player that
     *  may still be drawing into it is itself released, never before, and never left to leak. */
    private Surface surface;
    private int viewWidth, viewHeight;
    private boolean active;
    private boolean closed;
    private boolean prepared;
    /** True only between starting a prepare and its prepared/error callback, so a second SurfaceTexture
     *  callback (or releaseForMemoryPressure racing a callback) cannot start a second MediaPlayer. */
    private boolean preparing;
    /** Set once playback cannot continue (missing file, decode or playback error); never retried this run. */
    private boolean failed;

    VideoBackgroundPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        this.soundEnabled = config.videoBackgroundSound;
        this.file = config.videoBackground ? config.videoBackgroundFile : null;
        texture = new TextureView(context);
        texture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        texture.setOpaque(false);
        if (file != null) {
            texture.setSurfaceTextureListener(this);
            root.addView(texture, Math.min(1, root.getChildCount()),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private boolean enabled() {
        return file != null && !closed && !failed;
    }

    void start() {
        active = true;
        if (!enabled()) return;
        if (prepared) {
            resume();
        } else if (player == null && !preparing && surface != null) {
            // Home came back after releaseForMemoryPressure() tore the player down while the surface (and
            // Home itself) stayed alive: prepare again from the retained surface instead of staying silent.
            prepare();
        }
    }

    /** Pauses when Home loses the foreground; resume() restarts it from the same position, not from the top. */
    void stop() {
        active = false;
        pausePlayer();
    }

    /**
     * Frees the MediaPlayer's native buffers under memory pressure, same as WallpaperPlayer drops its
     * bitmap - but, like WallpaperPlayer, never touches `active`: onTrimMemory can fire while Home is still
     * genuinely foregrounded (an accessibility service keeps the process alive), and this must not leave
     * video_background silently stuck off until some unrelated pause/resume happens to come along. The
     * surface itself is kept, so a later start() can prepare again without waiting for a new SurfaceTexture.
     */
    void releaseForMemoryPressure() {
        releasePlayer();
    }

    void close() {
        active = false;
        closed = true;
        releasePlayer();
        releaseSurface();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        viewWidth = width;
        viewHeight = height;
        // A new SurfaceTexture without a prior onSurfaceTextureDestroyed (TextureView re-attached) must not
        // leak the one already held.
        releaseSurface();
        surface = new Surface(surfaceTexture);
        if (enabled() && player == null && !preparing) prepare();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        viewWidth = width;
        viewHeight = height;
        applyTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        // The player must be released before the surface it draws into: releasing the surface first and
        // then handing the player a torn-down target on its next frame is exactly the leak/crash this order
        // avoids.
        releasePlayer();
        releaseSurface();
        return true;   // nothing more holds this SurfaceTexture; the TextureView may release it now
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // Nothing to do per frame; the SurfaceTexture already drives the TextureView's own redraw.
    }

    private void prepare() {
        if (preparing || player != null || surface == null) return;
        preparing = true;
        MediaPlayer mp = new MediaPlayer();
        // Assigned before prepareAsync(): a callback (prepared, error or video-size) can only run once this
        // method returns and the main Looper resumes, but assigning after the call would leave a window,
        // real or not, where such a callback could observe `player` as null or a previous instance.
        player = mp;
        try {
            mp.setDataSource(file.getPath());
            mp.setSurface(surface);
            mp.setLooping(true);
            mp.setVolume(soundEnabled ? 1f : 0f, soundEnabled ? 1f : 0f);
            mp.setOnPreparedListener(mediaPlayer -> {
                // A surface destroyed mid-prepareAsync() already released this exact instance and nulled
                // `player`; its callback can still fire afterward. Without this check it would re-assert
                // prepared=true against a null player and wedge playback until the next full teardown.
                if (mediaPlayer != player) return;
                preparing = false;
                prepared = true;
                applyTransform();
                texture.setAlpha(1f);
                if (active) resume();
            });
            mp.setOnVideoSizeChangedListener((mediaPlayer, width, height) -> {
                if (mediaPlayer == player) applyTransform();
            });
            mp.setOnErrorListener((mediaPlayer, what, extra) -> {
                if (mediaPlayer != player) return true;   // a superseded instance; already handled
                preparing = false;
                onPlaybackFailed("playback error " + what + "/" + extra);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception | OutOfMemoryError failure) {
            preparing = false;
            onPlaybackFailed(String.valueOf(failure));
        }
    }

    private void resume() {
        if (player == null || closed) return;
        try {
            player.start();
        } catch (Exception failure) {
            onPlaybackFailed(String.valueOf(failure));
        }
    }

    private void pausePlayer() {
        if (player == null) return;
        try {
            player.pause();
        } catch (Exception ignored) {
            // Already stopped, or the player is between states; nothing to undo.
        }
    }

    /** Falls back to the wallpaper: hide the video layer for the rest of this run and release the player. */
    private void onPlaybackFailed(String why) {
        if (failed) return;
        failed = true;
        Log.w("TvHome.VideoBackground", "Falling back to the wallpaper: " + why);
        releasePlayer();
        root.post(() -> texture.setVisibility(View.GONE));
    }

    private void releasePlayer() {
        preparing = false;
        if (player == null) return;
        try {
            player.reset();
            player.release();
        } catch (Exception ignored) {
            // Already released or never fully prepared.
        }
        player = null;
        prepared = false;
    }

    private void releaseSurface() {
        if (surface == null) return;
        surface.release();
        surface = null;
    }

    /**
     * Centre-crop: a TextureView stretches its content to fill its own bounds non-uniformly by default
     * (like fitXY), so this divides that stretch back out on each axis and reapplies one uniform scale
     * that covers the view, cropping the overflow instead of distorting the picture.
     */
    private void applyTransform() {
        if (player == null || viewWidth <= 0 || viewHeight <= 0) return;
        int videoWidth, videoHeight;
        try {
            videoWidth = player.getVideoWidth();
            videoHeight = player.getVideoHeight();
        } catch (Exception failure) {
            return;
        }
        if (videoWidth <= 0 || videoHeight <= 0) return;
        float scaleX = (float) viewWidth / videoWidth;
        float scaleY = (float) viewHeight / videoHeight;
        float scale = Math.max(scaleX, scaleY);
        Matrix matrix = new Matrix();
        matrix.setScale(scale / scaleX, scale / scaleY, viewWidth / 2f, viewHeight / 2f);
        texture.setTransform(matrix);
    }
}
