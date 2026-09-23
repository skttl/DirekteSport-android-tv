package dk.direktesport.tv;

import android.app.Activity;
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
import android.widget.TextView;
import android.webkit.CookieManager;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

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
    private int selected;
    private int requestGeneration;
    private boolean compact;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        compact = getResources().getConfiguration().smallestScreenWidthDp < 600;
        getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setUseController(compact);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(20);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.addView(message, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) message.setVisibility(View.GONE);
            }

            @Override public void onPlayerError(PlaybackException error) {
                message.setText("Kunne ikke afspille videoen: " + error.getMessage());
                message.setVisibility(View.VISIBLE);
            }
        });

        if (compact) {
            Button videosButton = new Button(this);
            videosButton.setText("Videoer");
            videosButton.setAllCaps(false);
            videosButton.setOnClickListener(v -> showChooser());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    -2, dp(48), Gravity.TOP | Gravity.END);
            params.setMargins(dp(8), dp(8), dp(8), dp(8));
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
        player.stop();
        player.clearMediaItems();
        message.setText("Henter video …");
        message.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                String source = VideoSourceClient.hlsUrl(videoId);
                main.post(() -> {
                    if (request != requestGeneration || isFinishing() || isDestroyed()) return;
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
                    player.play();
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

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || chooser != null || compact)
            return super.dispatchKeyEvent(event);
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showChooser();
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
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void seek(long milliseconds) {
        if (player.isCurrentMediaItemSeekable()) {
            player.seekTo(Math.max(0, player.getCurrentPosition() + milliseconds));
        }
    }

    private void showChooser() {
        if (queue.isEmpty() || chooser != null) return;
        chooser = new ScrollView(this);
        chooser.setBackgroundColor(Color.argb(245, 16, 21, 30));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(32), dp(20), dp(32), dp(20));
        chooser.addView(list);
        TextView title = new TextView(this);
        title.setText("Vælg video · Tilbage lukker listen");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        list.addView(title);
        if (compact) {
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
            item.setTextColor(Color.WHITE);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setOnClickListener(v -> {
                selected = index;
                hideChooser();
                play(video.id);
            });
            list.addView(item, new LinearLayout.LayoutParams(-1, dp(64)));
            if (i == selected) main.post(item::requestFocus);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                compact ? -1 : dp(620), -1, Gravity.START);
        root.addView(chooser, params);
        chooser.bringToFront();
    }

    private void hideChooser() {
        if (chooser == null) return;
        root.removeView(chooser);
        chooser = null;
    }

    @Override public void onBackPressed() {
        if (chooser != null) { hideChooser(); return; }
        super.onBackPressed();
    }

    @Override protected void onStop() {
        player.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        requestGeneration++;
        executor.shutdownNow();
        playerView.setPlayer(null);
        player.release();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
