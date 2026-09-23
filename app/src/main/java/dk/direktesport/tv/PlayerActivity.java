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
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public final class PlayerActivity extends Activity {
    static ArrayList<Video> queue = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView webView;
    private VideoView nativePlayer;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ScrollView chooser;
    private int selected;
    private boolean nativeAttempted;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894 | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new SourceBridge(), "AndroidVideoSource");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (url.startsWith("https://direktesport.dk/video/")) injectTvLayout();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                view.bringToFront();
            }
            @Override public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                customCallback.onCustomViewHidden();
                customCallback = null;
            }
        });
        nativePlayer = new VideoView(this);
        nativePlayer.setVisibility(View.GONE);
        root.addView(nativePlayer, new FrameLayout.LayoutParams(-1, -1));
        selected = getIntent().getIntExtra("index", 0);
        String fallbackUrl = getIntent().getStringExtra("url");
        if (!queue.isEmpty() && selected >= 0 && selected < queue.size()) {
            play(queue.get(selected).pageUrl());
        } else if (fallbackUrl != null) {
            play(fallbackUrl);
        } else finish();
    }

    private void play(String url) {
        nativeAttempted = false;
        nativePlayer.stopPlayback();
        nativePlayer.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void injectTvLayout() {
        String script = "(function(){"
                + "if(!document.getElementById('ds-tv-style')){var s=document.createElement('style');s.id='ds-tv-style';"
                + "s.textContent='html,body,main,.jfm-video-video-page{margin:0!important;padding:0!important;max-width:none!important;width:100vw!important;height:100vh!important;background:#000!important;overflow:hidden!important}'"
                + "+'header,footer,.video__share,.video__description,.video__date,.video__title{display:none!important}'"
                + "+'.video,.video__inner,.video__inner-player,.flowplayer{width:100vw!important;height:100vh!important;max-width:none!important;margin:0!important;padding:0!important;background:#000!important}';document.head.appendChild(s)}"
                + "setInterval(function(){var v=document.querySelector('video');if(v&&v.currentSrc)AndroidVideoSource.offer(v.currentSrc)},2000)"
                + "})()";
        webView.evaluateJavascript(script, null);
    }

    private final class SourceBridge {
        @JavascriptInterface public void offer(String source) {
            main.post(() -> tryNative(source));
        }
    }

    private void tryNative(String source) {
        if (nativeAttempted || source == null || !source.startsWith("https://")) return;
        Uri uri = Uri.parse(source);
        String path = uri.getPath();
        if (path == null || !(path.endsWith(".m3u8") || path.endsWith(".mp4"))) return;
        nativeAttempted = true;
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://direktesport.dk/");
        String cookie = CookieManager.getInstance().getCookie(source);
        if (cookie != null) headers.put("Cookie", cookie);
        nativePlayer.setOnPreparedListener(player -> {
            webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.pause()})()", null);
            webView.setVisibility(View.GONE);
            nativePlayer.setVisibility(View.VISIBLE);
            nativePlayer.start();
        });
        nativePlayer.setOnErrorListener((player, what, extra) -> {
            nativePlayer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.play()})()", null);
            return true;
        });
        nativePlayer.setVideoURI(uri, headers);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || chooser != null) return super.dispatchKeyEvent(event);
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
                togglePlay();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void seek(int milliseconds) {
        if (nativePlayer.getVisibility() == View.VISIBLE) {
            nativePlayer.seekTo(Math.max(0, nativePlayer.getCurrentPosition() + milliseconds));
        } else {
            int seconds = milliseconds / 1000;
            webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(v&&v.seekable.length)v.currentTime=Math.max(0,v.currentTime+" + seconds + ")})()", null);
        }
    }

    private void togglePlay() {
        if (nativePlayer.getVisibility() == View.VISIBLE) {
            if (nativePlayer.isPlaying()) nativePlayer.pause(); else nativePlayer.start();
        } else {
            webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){if(v.paused)v.play();else v.pause()}})()", null);
        }
    }

    private void showChooser() {
        if (queue.isEmpty()) return;
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
        for (int i = 0; i < queue.size(); i++) {
            final int index = i;
            Video video = queue.get(i);
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setText((i == selected ? "▶  " : "") + video.title + (video.paid ? "  · Abonnement" : "  · Gratis"));
            item.setTextColor(Color.WHITE);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setOnClickListener(v -> {
                selected = index;
                hideChooser();
                play(video.pageUrl());
            });
            list.addView(item, new LinearLayout.LayoutParams(-1, dp(64)));
            if (i == selected) main.post(item::requestFocus);
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(620), -1, Gravity.START);
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

    @Override protected void onDestroy() {
        nativePlayer.stopPlayback();
        if (webView != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
