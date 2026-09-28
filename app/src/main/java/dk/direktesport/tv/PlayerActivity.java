package dk.direktesport.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.webkit.CookieManager;

import androidx.mediarouter.app.MediaRouteButton;
import androidx.media3.common.MediaItem;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerActivity extends Activity {
    static ArrayList<Video> queue = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView message;
    private ScrollView chooser;
    private LinearLayout controls;
    private SeekBar progress;
    private TextView time;
    private Button playbackButton;
    private final Runnable hideControls = () -> {
        if (controls != null && chooser == null) controls.setVisibility(View.GONE);
    };
    private final Runnable updateProgress = new Runnable() {
        @Override public void run() {
            if (controls != null && controls.getVisibility() == View.VISIBLE) refreshControls();
            main.postDelayed(this, 1000);
        }
    };
    private int selected;
    private int requestGeneration;
    private boolean compact;
    private boolean isTv;
    private CastContext castContext;
    private String currentSource;
    private Button castPlaybackButton;
    private long resumePosition;
    private boolean castFailed;
    private final SessionManagerListener<CastSession> castListener = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarting(CastSession session) {}
        @Override public void onSessionStarted(CastSession session, String id) { castCurrentVideo(); }
        @Override public void onSessionStartFailed(CastSession session, int error) {}
        @Override public void onSessionSuspended(CastSession session, int reason) {}
        @Override public void onSessionResuming(CastSession session, String id) {}
        @Override public void onSessionResumed(CastSession session, boolean wasSuspended) {
            if (session.getRemoteMediaClient() != null
                    && session.getRemoteMediaClient().getMediaStatus() != null) {
                player.pause();
                updateCastUi();
            } else {
                castCurrentVideo();
            }
        }
        @Override public void onSessionResumeFailed(CastSession session, int error) {}
        @Override public void onSessionEnding(CastSession session) {
            RemoteMediaClient remote = session.getRemoteMediaClient();
            resumePosition = remote == null ? 0 : remote.getApproximateStreamPosition();
        }
        @Override public void onSessionEnded(CastSession session, int error) {
            updateCastUi();
            if (currentSource != null && !castFailed) startLocalPlayback(currentSource, resumePosition);
            castFailed = false;
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Configuration config = getResources().getConfiguration();
        isTv = (config.uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
        compact = !isTv && config.smallestScreenWidthDp < 600;
        getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setUseController(!isTv);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        if (isTv) buildControls();

        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(20);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.addView(message, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) message.setVisibility(View.GONE);
                refreshControls();
            }

            @Override public void onIsPlayingChanged(boolean playing) { refreshControls(); }

            @Override public void onPlayerError(PlaybackException error) {
                message.setText("Kunne ikke afspille videoen: " + error.getMessage());
                message.setVisibility(View.VISIBLE);
            }
        });
        if (isTv) main.post(updateProgress);

        if (!isTv) {
            castContext = CastContext.getSharedInstance(this);
            MediaRouteButton castButton = new MediaRouteButton(this);
            castButton.setContentDescription("Cast til Chromecast");
            CastButtonFactory.setUpMediaRouteButton(this, castButton);
            FrameLayout.LayoutParams castParams = new FrameLayout.LayoutParams(
                    dp(48), dp(48), Gravity.TOP | Gravity.END);
            castParams.setMargins(dp(8), dp(8), dp(8), dp(8));
            root.addView(castButton, castParams);

            castPlaybackButton = new Button(this);
            castPlaybackButton.setText("Pause på TV");
            castPlaybackButton.setAllCaps(false);
            castPlaybackButton.setOnClickListener(v -> {
                RemoteMediaClient remote = remoteClient();
                if (remote == null) return;
                if (remote.isPlaying()) remote.pause(); else remote.play();
                main.postDelayed(this::updateCastUi, 500);
            });
            FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                    -2, dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            controlParams.bottomMargin = dp(24);
            root.addView(castPlaybackButton, controlParams);
            castContext.getSessionManager().addSessionManagerListener(castListener, CastSession.class);
            updateCastUi();
        }

        if (!isTv) {
            Button videosButton = new Button(this);
            videosButton.setText("Videoer");
            videosButton.setAllCaps(false);
            videosButton.setOnClickListener(v -> showChooser());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    -2, dp(48), Gravity.TOP | Gravity.END);
            params.setMargins(dp(8), dp(8), dp(64), dp(8));
            root.addView(videosButton, params);
        }

        selected = getIntent().getIntExtra("index", 0);
        if (!queue.isEmpty() && selected >= 0 && selected < queue.size()) {
            play(queue.get(selected).id);
        } else {
            String id = getIntent().getStringExtra("id");
            if (id == null) finish(); else play(id);
        }
    }

    private void play(String videoId) {
        int request = ++requestGeneration;
        castFailed = false;
        currentSource = null;
        resumePosition = 0;
        player.stop();
        player.clearMediaItems();
        message.setText("Henter video …");
        message.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                String source = VideoSourceClient.hlsUrl(videoId);
                main.post(() -> {
                    if (request != requestGeneration || isFinishing() || isDestroyed()) return;
                    currentSource = source;
                    if (remoteClient() != null) {
                        castCurrentVideo();
                        return;
                    }
                    startLocalPlayback(source, 0);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (request != requestGeneration || isFinishing() || isDestroyed()) return;
                    message.setText("Kunne ikke hente videoen: " + error.getMessage());
                    message.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void startLocalPlayback(String source, long position) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", CatalogClient.BASE + "/");
        String cookie = CookieManager.getInstance().getCookie(source);
        if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
        DefaultHttpDataSource.Factory dataSource = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers);
        HlsMediaSource mediaSource = new HlsMediaSource.Factory(dataSource)
                .createMediaSource(MediaItem.fromUri(Uri.parse(source)));
        player.setMediaSource(mediaSource);
        player.prepare();
        if (position > 0) player.seekTo(position);
        player.play();
    }

    private RemoteMediaClient remoteClient() {
        if (castContext == null) return null;
        CastSession session = castContext.getSessionManager().getCurrentCastSession();
        return session != null && session.isConnected() ? session.getRemoteMediaClient() : null;
    }

    private void castCurrentVideo() {
        RemoteMediaClient remote = remoteClient();
        if (remote == null || currentSource == null) return;
        int request = requestGeneration;
        castFailed = false;
        long position = player.getCurrentPosition();
        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        Video video = selected >= 0 && selected < queue.size() ? queue.get(selected) : null;
        metadata.putString(MediaMetadata.KEY_TITLE, video == null ? "DirekteSport" : video.title);
        boolean live = video != null && video.isLive();
        MediaInfo info = new MediaInfo.Builder(currentSource)
                .setContentType("application/x-mpegURL")
                .setStreamType(live ? MediaInfo.STREAM_TYPE_LIVE : MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(metadata)
                .build();
        MediaLoadRequestData.Builder load = new MediaLoadRequestData.Builder()
                .setMediaInfo(info).setAutoplay(true);
        if (!live && position > 0) load.setCurrentTime(position);
        player.pause();
        message.setText("Afspiller på Chromecast …");
        message.setVisibility(View.VISIBLE);
        updateCastUi();
        remote.load(load.build()).setResultCallback(result -> {
            if (request != requestGeneration || isFinishing() || isDestroyed()) return;
            if (result.getStatus().isSuccess()) {
                message.setVisibility(View.GONE);
            } else {
                castFailed = true;
                message.setText("Chromecast kunne ikke afspille videoen. Fortsætter på enheden.");
                startLocalPlayback(currentSource, position);
            }
            updateCastUi();
        });
    }

    private void updateCastUi() {
        RemoteMediaClient remote = remoteClient();
        boolean casting = remote != null && !castFailed;
        playerView.setVisibility(casting ? View.INVISIBLE : View.VISIBLE);
        if (castPlaybackButton != null) {
            castPlaybackButton.setVisibility(casting ? View.VISIBLE : View.GONE);
            if (casting) castPlaybackButton.setText(remote.isPlaying() ? "Pause på TV" : "Afspil på TV");
        }
    }

    private void buildControls() {
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(48), dp(26), dp(48), dp(30));
        controls.setBackgroundColor(Color.argb(245, 10, 26, 26));
        controls.setVisibility(View.GONE);
        FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(controls, panel);

        TextView title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        controls.addView(title);
        controls.setTag(title);

        progress = new SeekBar(this);
        progress.setMax(1000);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && player.isCurrentMediaItemSeekable() && player.getDuration() > 0)
                    player.seekTo(player.getDuration() * value / 1000);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { scheduleControlsHide(); }
        });
        controls.addView(progress, new LinearLayout.LayoutParams(-1, dp(52)));

        time = new TextView(this);
        time.setTextColor(Color.rgb(181, 192, 189));
        time.setTextSize(16);
        controls.addView(time);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, dp(64));
        row.topMargin = dp(16);
        controls.addView(actions, row);
        Button back = controlButton("−10 sek");
        back.setOnClickListener(v -> seek(-10000));
        actions.addView(back);
        playbackButton = controlButton("Pause");
        playbackButton.setOnClickListener(v -> {
            if (player.isPlaying()) player.pause(); else player.play();
            refreshControls();
        });
        actions.addView(playbackButton);
        Button forward = controlButton("+10 sek");
        forward.setOnClickListener(v -> seek(10000));
        actions.addView(forward);
        Button speed = controlButton("Hastighed");
        speed.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Afspilningshastighed")
                .setSingleChoiceItems(new String[]{"0,75×", "Normal", "1,25×", "1,5×", "2×"},
                        speedIndex(), (dialog, which) -> {
                            player.setPlaybackSpeed(new float[]{0.75f, 1f, 1.25f, 1.5f, 2f}[which]);
                            dialog.dismiss();
                            scheduleControlsHide();
                        }).show());
        actions.addView(speed);
        Button videos = controlButton("Andre videoer");
        videos.setOnClickListener(v -> showChooser());
        actions.addView(videos);
        playbackButton.requestFocus();
    }

    private Button controlButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(17);
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{Color.rgb(10, 26, 26), Color.WHITE}));
        button.setBackgroundTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{Color.rgb(92, 255, 154), Color.rgb(23, 63, 63)}));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(58), 1);
        params.rightMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    private int speedIndex() {
        float speed = player.getPlaybackParameters().speed;
        float[] values = {0.75f, 1f, 1.25f, 1.5f, 2f};
        for (int i = 0; i < values.length; i++) if (speed == values[i]) return i;
        return 1;
    }

    private void refreshControls() {
        if (controls == null) return;
        Video video = selected >= 0 && selected < queue.size() ? queue.get(selected) : null;
        ((TextView) controls.getTag()).setText(video == null ? "DirekteSport" : video.title);
        playbackButton.setText(player.isPlaying() ? "Pause" : "Afspil");
        long duration = player.getDuration();
        boolean seekable = player.isCurrentMediaItemSeekable() && duration > 0 && duration != C.TIME_UNSET;
        progress.setEnabled(seekable);
        progress.setProgress(seekable ? (int) (1000 * player.getCurrentPosition() / duration) : 0);
        time.setText(seekable ? formatTime(player.getCurrentPosition()) + " / " + formatTime(duration)
                : video != null && video.isLive() ? "LIVE · Spoling afhænger af streamen"
                : "Tidslinje ikke tilgængelig");
    }

    private String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d",
                seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private void scheduleControlsHide() {
        main.removeCallbacks(hideControls);
        main.postDelayed(hideControls, 10000);
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        refreshControls();
        playbackButton.requestFocus();
        scheduleControlsHide();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || chooser != null || !isTv)
            return super.dispatchKeyEvent(event);
        if (controls.getVisibility() == View.VISIBLE) {
            scheduleControlsHide();
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (player.isPlaying()) player.pause(); else player.play();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seek(-10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seek(10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (player.isPlaying()) player.pause(); else player.play();
                showControls();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void seek(long milliseconds) {
        if (player.isCurrentMediaItemSeekable()) {
            player.seekTo(Math.max(0, player.getCurrentPosition() + milliseconds));
            refreshControls();
        }
    }

    private void showChooser() {
        if (queue.isEmpty() || chooser != null) return;
        chooser = new ScrollView(this);
        chooser.setBackgroundColor(Color.argb(250, 10, 26, 26));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(isTv ? 48 : 24), dp(isTv ? 28 : 20), dp(24), dp(28));
        chooser.addView(list);
        TextView title = new TextView(this);
        title.setText("Andre videoer · Tilbage lukker listen");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        list.addView(title);
        if (!isTv) {
            Button close = new Button(this);
            close.setText("Luk liste");
            close.setOnClickListener(v -> hideChooser());
            list.addView(close);
        }
        for (int i = 0; i < queue.size(); i++) {
            final int index = i;
            Video video = queue.get(i);
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setText((i == selected ? "▶  " : "") + video.title
                    + (video.paid ? "  · Abonnement" : "  · Gratis"));
            item.setTextSize(18);
            item.setTextColor(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                    new int[]{Color.rgb(10, 26, 26), Color.WHITE}));
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setBackgroundTintList(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                    new int[]{Color.rgb(92, 255, 154), Color.rgb(23, 63, 63)}));
            item.setOnClickListener(v -> {
                selected = index;
                hideChooser();
                play(video.id);
            });
            list.addView(item, new LinearLayout.LayoutParams(-1, dp(72)));
            if (i == selected) main.post(item::requestFocus);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                compact ? -1 : dp(isTv ? 480 : 620), -1, Gravity.START);
        root.addView(chooser, params);
        chooser.bringToFront();
    }

    private void hideChooser() {
        if (chooser == null) return;
        root.removeView(chooser);
        chooser = null;
        if (controls != null && controls.getVisibility() == View.VISIBLE) {
            playbackButton.requestFocus();
            scheduleControlsHide();
        }
    }

    @Override public void onBackPressed() {
        if (chooser != null) { hideChooser(); return; }
        if (controls != null && controls.getVisibility() == View.VISIBLE) {
            controls.setVisibility(View.GONE);
            main.removeCallbacks(hideControls);
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onStop() {
        if (remoteClient() == null || castFailed) player.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        requestGeneration++;
        main.removeCallbacks(updateProgress);
        main.removeCallbacks(hideControls);
        if (castContext != null) {
            castContext.getSessionManager().removeSessionManagerListener(castListener, CastSession.class);
        }
        executor.shutdownNow();
        playerView.setPlayer(null);
        player.release();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
