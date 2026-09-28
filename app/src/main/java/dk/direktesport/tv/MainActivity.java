package dk.direktesport.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.framework.CastButtonFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(10, 26, 26);
    private static final int CARD = Color.rgb(23, 63, 63);
    private static final int ACCENT = Color.rgb(92, 255, 154);
    private static final int MUTED = Color.rgb(181, 192, 189);
    private static final int UNKNOWN_SOURCES_REQUEST = 1;
    private static final int VIDEO_LOGIN_REQUEST = 2;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Video> videos = new ArrayList<>();
    private final List<CatalogClient.Category> categories = new ArrayList<>();
    private GridView grid;
    private LinearLayout tvRows;
    private LinearLayout tvList;
    private ScrollView tvScroll;
    private LinearLayout favoriteNav;
    private TextView favoriteToggle;
    private View homeNav;
    private View searchNav;
    private View categoryNav;
    private String pageSlug = "";
    private boolean compact;
    private boolean isTv;
    private VideoAdapter adapter;
    private TextView status;
    private Button categoryButton;
    private Button liveButton;
    private Button archiveButton;
    private Button searchButton;
    private EditText searchInput;
    private String sectionTitle = "Live og kommende udsendelser";
    private String mode = "livestream";
    private String categoryId = "";
    private String search = "";
    private int page;
    private volatile int generation;
    private boolean loading;
    private boolean hasMore = true;
    private boolean focusResults;
    private boolean downloadingUpdate;
    private AlertDialog downloadDialog;
    private File pendingApk;
    private int pendingVideoIndex = -1;
    private boolean checkingVideoAccess;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (state != null && state.getString("pendingApk") != null) {
            pendingApk = new File(state.getString("pendingApk"));
        }
        if (!isTv) loadCategories();
        if (isTv) mode = "home";
        reload(isTv ? "Forside" : "Live og kommende udsendelser");
        checkForUpdates(false);
    }

    @Override protected void onDestroy() {
        if (downloadDialog != null) downloadDialog.dismiss();
        executor.shutdownNow();
        updateExecutor.shutdownNow();
        images.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        Configuration configuration = getResources().getConfiguration();
        isTv = (configuration.uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
        compact = !isTv && configuration.smallestScreenWidthDp < 600;
        if (isTv) {
            buildTvUi();
            return;
        }
        int sidePadding = dp(compact ? 12 : 48);
        int topPadding = dp(compact ? 8 : 28);
        int bottomPadding = dp(compact ? 8 : 28);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(sidePadding, topPadding, sidePadding, bottomPadding);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(sidePadding + bars.left, topPadding + bars.top,
                        sidePadding + bars.right, bottomPadding + bars.bottom);
                return insets;
            });
        }
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(compact ? 45 : 60)));
        TextView title = label("DS PLAY", compact ? 24 : 32, ACCENT);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (!isTv) {
            MediaRouteButton castButton = new MediaRouteButton(this);
            castButton.setContentDescription("Cast til Chromecast");
            CastButtonFactory.setUpMediaRouteButton(this, castButton);
            header.addView(castButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        Button updates = button("Tjek opdatering");
        updates.setOnClickListener(v -> checkForUpdates(true));
        Button login = button("Log ind / konto");
        login.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
        if (isTv) {
            styleHeaderAction(updates);
            styleHeaderAction(login);
        }
        if (compact) {
            LinearLayout actions = new LinearLayout(this);
            root.addView(actions, new LinearLayout.LayoutParams(-1, dp(50)));
            actions.addView(login, new LinearLayout.LayoutParams(0, -1, 1));
            actions.addView(updates, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            header.addView(updates);
            header.addView(login);
        }

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(compact ? 50 : 56)));
        liveButton = button("Live");
        liveButton.setOnClickListener(v -> { mode = "livestream"; search = ""; reload("Live og kommende udsendelser"); });
        archiveButton = button("Arkiv");
        archiveButton.setOnClickListener(v -> { mode = "video-on-demand"; search = ""; reload("Arkivvideoer"); });
        if (compact) {
            navigation.addView(liveButton, new LinearLayout.LayoutParams(0, -1, 1));
            navigation.addView(archiveButton, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            navigation.addView(liveButton);
            navigation.addView(archiveButton);
        }
        categoryButton = button("Alle sportsgrene ▾");
        categoryButton.setOnClickListener(v -> chooseCategory());
        if (compact) {
            root.addView(categoryButton, new LinearLayout.LayoutParams(-1, dp(48)));
        } else {
            navigation.addView(categoryButton);
        }

        LinearLayout searchRow = compact ? new LinearLayout(this) : navigation;
        if (compact) root.addView(searchRow, new LinearLayout.LayoutParams(-1, dp(50)));
        if (isTv) {
            navigation.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        } else {
            searchInput = new EditText(this);
            searchInput.setSingleLine(true);
            searchInput.setHint("Søg i videoer");
            searchInput.setTextColor(Color.WHITE);
            searchInput.setHintTextColor(MUTED);
            StateListDrawable searchBackground = new StateListDrawable();
            GradientDrawable searchFocused = new GradientDrawable();
            searchFocused.setColor(CARD);
            searchFocused.setStroke(dp(2), ACCENT);
            searchBackground.addState(new int[]{android.R.attr.state_focused}, searchFocused);
            searchBackground.addState(new int[]{}, new ColorDrawable(CARD));
            searchInput.setBackground(searchBackground);
            searchInput.setPadding(dp(14), 0, dp(14), 0);
            searchInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(46), 1);
            if (!compact) searchParams.leftMargin = dp(12);
            searchRow.addView(searchInput, searchParams);
            searchInput.setOnEditorActionListener((v, action, event) -> { search(searchInput.getText().toString()); return true; });
        }
        searchButton = button("Søg");
        searchButton.setOnClickListener(v -> {
            if (isTv) showSearchDialog(); else search(searchInput.getText().toString());
        });
        searchRow.addView(searchButton);
        status = label("Henter videoer …", compact ? 16 : 23, Color.WHITE);
        if (isTv) status.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        if (isTv) {
            status.setSingleLine(true);
            status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        status.setPadding(0, dp(compact ? 8 : 10), 0, dp(8));
        root.addView(status);

        grid = new GridView(this);
        grid.setNumColumns(compact ? 1 : 3);
        grid.setColumnWidth(dp(280));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(compact ? 14 : 22));
        grid.setVerticalSpacing(dp(compact ? 14 : 22));
        grid.setSelector(android.R.color.transparent);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setFocusable(true);
        if (isTv) grid.setFocusableInTouchMode(true);
        adapter = new VideoAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> openVideo(position, false));
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {}
            @Override public void onScroll(AbsListView view, int first, int count, int total) {
                if (total > 0 && first + count >= total - 6) loadMore();
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        liveButton.requestFocus();
    }

    private void buildTvUi() {
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BACKGROUND);
        setContentView(root);

        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(7), dp(12), dp(7), dp(12));
        sidebar.setBackgroundColor(Color.rgb(15, 39, 39));
        root.addView(sidebar, new LinearLayout.LayoutParams(dp(118), -1));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ds_play_icon);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setFocusable(true);
        logo.setContentDescription("Forside");
        logo.setOnClickListener(v -> openTvPage("", "Forside"));
        tvFocus(logo);
        sidebar.addView(logo, new LinearLayout.LayoutParams(-1, dp(82)));
        homeNav = logo;

        ScrollView navScroll = new ScrollView(this);
        navScroll.setVerticalScrollBarEnabled(false);
        sidebar.addView(navScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout navItems = new LinearLayout(this);
        navItems.setOrientation(LinearLayout.VERTICAL);
        navScroll.addView(navItems);
        navItems.addView(tvNavItem(R.drawable.nav_account, "Log ind",
                v -> startActivity(new Intent(this, LoginActivity.class))), tvNavParams());
        searchNav = tvNavItem(R.drawable.nav_search, "Søg", v -> showSearchDialog());
        navItems.addView(searchNav, tvNavParams());
        categoryNav = tvNavItem(R.drawable.nav_sports, "Sportsgrene", v -> chooseCategory());
        navItems.addView(categoryNav, tvNavParams());
        favoriteNav = new LinearLayout(this);
        favoriteNav.setOrientation(LinearLayout.VERTICAL);
        navItems.addView(favoriteNav);
        refreshFavoriteLinks();
        sidebar.addView(tvNavItem(R.drawable.nav_update, "Opdater",
                v -> checkForUpdates(true)), tvNavParams());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), 0, 0);
        root.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(heading);
        status = label("Henter videoer …", 28, Color.WHITE);
        status.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        status.setPadding(dp(6), 0, dp(30), dp(12));
        heading.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        favoriteToggle = label("☆  Favorit", 16, ACCENT);
        favoriteToggle.setFocusable(true);
        favoriteToggle.setPadding(dp(16), dp(8), dp(20), dp(8));
        favoriteToggle.setOnClickListener(v -> toggleFavorite());
        tvFocus(favoriteToggle);
        heading.addView(favoriteToggle);
        favoriteToggle.setVisibility(View.GONE);
        tvScroll = new ScrollView(this);
        tvScroll.setFillViewport(true);
        tvScroll.setVerticalScrollBarEnabled(false);
        tvScroll.setClipChildren(false);
        content.addView(tvScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        tvRows = new LinearLayout(this);
        tvRows.setOrientation(LinearLayout.VERTICAL);
        tvRows.setPadding(0, 0, 0, dp(36));
        tvRows.setClipChildren(false);
        tvScroll.addView(tvRows);
        homeNav.requestFocus();
    }

    private LinearLayout.LayoutParams tvNavParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(70));
        params.bottomMargin = dp(6);
        return params;
    }

    private View tvNavItem(int icon, String title, View.OnClickListener action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setFocusable(true);
        item.setContentDescription(title);
        item.setOnClickListener(action);
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        item.addView(image, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView caption = label(title, 11, MUTED);
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-1, -2);
        captionParams.topMargin = dp(3);
        item.addView(caption, captionParams);
        item.setOnFocusChangeListener((view, focused) -> {
            view.setBackgroundColor(focused ? CARD : Color.TRANSPARENT);
            image.setImageTintList(ColorStateList.valueOf(focused ? ACCENT : Color.WHITE));
            caption.setTextColor(focused ? Color.WHITE : MUTED);
            if (ValueAnimator.areAnimatorsEnabled()) image.animate()
                    .scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                    .setDuration(150).start();
        });
        return item;
    }

    private void tvFocus(View view) {
        view.setOnFocusChangeListener((item, focused) -> {
            item.setBackgroundColor(focused ? CARD : Color.TRANSPARENT);
        });
    }

    private void refreshFavoriteLinks() {
        favoriteNav.removeAllViews();
        Set<String> favorites = getPreferences(MODE_PRIVATE).getStringSet("favorite_sports", new HashSet<>());
        for (CatalogClient.Category sport : CatalogClient.siteSports()) {
            if (!favorites.contains(sport.id)) continue;
            favoriteNav.addView(tvNavItem(sportIcon(sport.id), sport.name,
                    v -> openTvPage(sport.id, sport.name)), tvNavParams());
        }
    }

    private int sportIcon(String slug) {
        switch (slug) {
            case "basketball": return R.drawable.sport_basketball;
            case "floorball": return R.drawable.sport_floorball;
            case "fodbold": return R.drawable.sport_fodbold;
            case "haandbold": return R.drawable.sport_haandbold;
            case "ishockey": return R.drawable.sport_ishockey;
            case "kampsport": return R.drawable.sport_kampsport;
            case "speedway": return R.drawable.sport_speedway;
            case "volleyball": return R.drawable.sport_volleyball;
            case "oevrigsport": return R.drawable.sport_oevrigsport;
            default: return R.drawable.nav_star;
        }
    }

    private void toggleFavorite() {
        if (pageSlug.isEmpty()) return;
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        Set<String> favorites = new HashSet<>(prefs.getStringSet("favorite_sports", new HashSet<>()));
        if (!favorites.add(pageSlug)) favorites.remove(pageSlug);
        prefs.edit().putStringSet("favorite_sports", favorites).apply();
        updateFavoriteToggle();
        refreshFavoriteLinks();
    }

    private void updateFavoriteToggle() {
        favoriteToggle.setVisibility(pageSlug.isEmpty() ? View.GONE : View.VISIBLE);
        if (!pageSlug.isEmpty()) {
            boolean selected = getPreferences(MODE_PRIVATE)
                    .getStringSet("favorite_sports", new HashSet<>()).contains(pageSlug);
            favoriteToggle.setText(selected ? "★  Favorit" : "☆  Favorit");
            favoriteToggle.setContentDescription(selected ? "Fjern fra favoritter" : "Føj til favoritter");
        }
    }

    private void openTvPage(String slug, String title) {
        pageSlug = slug;
        mode = slug.isEmpty() ? "home" : "page";
        search = "";
        reload(title);
    }

    private LinearLayout addTvRow(String heading) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setClipChildren(false);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(-1, -2);
        sectionParams.bottomMargin = dp(28);
        tvRows.addView(section, sectionParams);
        if (!heading.isEmpty()) {
            TextView title = label(heading, 24, Color.WHITE);
            title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            title.setPadding(dp(6), 0, 0, dp(12));
            section.addView(title);
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        section.addView(scroll);
        LinearLayout items = new LinearLayout(this);
        items.setPadding(dp(10), dp(10), dp(36), dp(10));
        items.setClipChildren(false);
        scroll.addView(items);
        return items;
    }

    private void addTvCards(LinearLayout row, int first, int last, boolean canLoadMore) {
        for (int index = first; index < last; index++) {
            Video video = videos.get(index);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setBackgroundColor(CARD);
            card.setForeground(focusOutline());
            card.setContentDescription(video.title);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(164), dp(158));
            cardParams.rightMargin = dp(12);
            row.addView(card, cardParams);
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Color.rgb(31, 55, 55));
            card.addView(image, new LinearLayout.LayoutParams(-1, dp(100)));
            loadImage(image, video.imageUrl);
            TextView title = label(video.title, 16, Color.WHITE);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setMarqueeRepeatLimit(-1);
            title.setPadding(dp(10), dp(7), dp(10), 0);
            card.addView(title);
            String detail = video.category;
            if (video.isLive()) {
                long now = System.currentTimeMillis() / 1000;
                String time = video.startsAt <= now && "live".equals(video.state) ? "LIVE NU"
                        : video.startsAt > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT,
                        DateFormat.SHORT, Locale.forLanguageTag("da-DK"))
                        .format(new Date(video.startsAt * 1000)) : "Live";
                detail = time + (detail.isEmpty() ? "" : "  ·  " + detail);
            }
            TextView meta = label(detail, 12, MUTED);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            meta.setPadding(dp(10), dp(3), dp(10), 0);
            card.addView(meta);
            final int videoIndex = index;
            card.setOnClickListener(v -> openVideo(videoIndex, false));
            card.setOnFocusChangeListener((view, focused) -> {
                title.setEllipsize(focused ? android.text.TextUtils.TruncateAt.MARQUEE
                        : android.text.TextUtils.TruncateAt.END);
                title.setSelected(focused);
                if (ValueAnimator.areAnimatorsEnabled()) {
                    view.animate().scaleX(focused ? 1.035f : 1f)
                            .scaleY(focused ? 1.035f : 1f).setDuration(150).start();
                }
                if (focused && canLoadMore && videoIndex >= videos.size() - 4) loadMore();
            });
        }
    }

    private StateListDrawable focusOutline() {
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(3), ACCENT);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, border);
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    private FrameLayout artwork(Video video) {
        FrameLayout frame = new FrameLayout(this);
        frame.setFocusable(true);
        frame.setContentDescription(video.title);
        frame.setForeground(focusOutline());
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(CARD);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        loadImage(image, video.imageUrl);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(240, 3, 16, 16), Color.argb(90, 3, 16, 16), Color.TRANSPARENT}));
        frame.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private void addHero(int first, int last) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setClipChildren(false);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(-1, -2);
        sectionParams.bottomMargin = dp(28);
        tvRows.addView(section, sectionParams);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        section.addView(scroll);
        LinearLayout strip = new LinearLayout(this);
        strip.setClipChildren(false);
        strip.setPadding(dp(10), dp(10), dp(30), dp(10));
        scroll.addView(strip);
        TextView dots = label("", 18, ACCENT);
        dots.setGravity(Gravity.CENTER);
        if (last - first > 1) section.addView(dots);
        int available = getResources().getDisplayMetrics().widthPixels - dp(174);
        for (int index = first; index < last; index++) {
            Video video = videos.get(index);
            FrameLayout card = artwork(video);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    last - first == 1 ? available : dp(440), dp(265));
            params.rightMargin = dp(12);
            strip.addView(card, params);
            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(dp(20), dp(20), dp(20), dp(20));
            card.addView(copy, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            String timing = video.isLive() && video.startsAt > 0
                    ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
                    Locale.forLanguageTag("da-DK")).format(new Date(video.startsAt * 1000)) : "";
            TextView meta = label(video.category + (timing.isEmpty() ? "" : "   |   " + timing), 13, ACCENT);
            copy.addView(meta);
            TextView title = label(video.title, 25, Color.WHITE);
            title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setMarqueeRepeatLimit(-1);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
            titleParams.topMargin = dp(8);
            copy.addView(title, titleParams);
            if (!video.description.isEmpty()) {
                TextView description = label(video.description, 14, Color.WHITE);
                description.setMaxLines(2);
                description.setEllipsize(android.text.TextUtils.TruncateAt.END);
                copy.addView(description);
            }
            final int videoIndex = index;
            final int dotIndex = index - first;
            card.setOnClickListener(v -> openVideo(videoIndex, false));
            card.setOnFocusChangeListener((view, focused) -> {
                title.setEllipsize(focused ? android.text.TextUtils.TruncateAt.MARQUEE
                        : android.text.TextUtils.TruncateAt.END);
                title.setSelected(focused);
                if (focused && last - first > 1) {
                    StringBuilder marks = new StringBuilder();
                    for (int i = 0; i < last - first; i++) marks.append(i == dotIndex ? "●  " : "○  ");
                    dots.setText(marks.toString());
                }
                if (ValueAnimator.areAnimatorsEnabled()) view.animate()
                        .scaleX(focused ? 1.015f : 1f).scaleY(focused ? 1.015f : 1f)
                        .setDuration(150).start();
            });
        }
        if (last - first > 1) {
            StringBuilder marks = new StringBuilder("●  ");
            for (int i = first + 1; i < last; i++) marks.append("○  ");
            dots.setText(marks.toString());
        }
    }

    private void addBanner(int index) {
        Video video = videos.get(index);
        FrameLayout banner = artwork(video);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(300));
        params.leftMargin = dp(10);
        params.rightMargin = dp(30);
        params.bottomMargin = dp(34);
        tvRows.addView(banner, params);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(dp(45), dp(12), dp(45), dp(24));
        banner.addView(copy, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        TextView category = label(video.category, 13, ACCENT);
        category.setGravity(Gravity.CENTER);
        copy.addView(category);
        TextView title = label(video.title, 26, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMarqueeRepeatLimit(-1);
        copy.addView(title);
        if (!video.description.isEmpty()) {
            TextView description = label(video.description, 14, Color.WHITE);
            description.setGravity(Gravity.CENTER);
            description.setMaxLines(2);
            description.setEllipsize(android.text.TextUtils.TruncateAt.END);
            copy.addView(description);
        }
        banner.setOnClickListener(v -> openVideo(index, false));
        banner.setOnFocusChangeListener((view, focused) -> {
            title.setEllipsize(focused ? android.text.TextUtils.TruncateAt.MARQUEE
                    : android.text.TextUtils.TruncateAt.END);
            title.setSelected(focused);
            if (ValueAnimator.areAnimatorsEnabled()) view.animate()
                    .scaleX(focused ? 1.015f : 1f).scaleY(focused ? 1.015f : 1f)
                    .setDuration(150).start();
        });
    }

    private void checkForUpdates(boolean manual) {
        updateExecutor.execute(() -> {
            try {
                UpdateChecker.Update update = UpdateChecker.latest();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (update.versionCode <= BuildConfig.VERSION_CODE) {
                        if (manual) new AlertDialog.Builder(this)
                                .setTitle("Ingen opdatering")
                                .setMessage("Du har den nyeste version.")
                                .setPositiveButton("OK", null).show();
                        return;
                    }
                    String notes = update.notes.isEmpty()
                            ? "Ingen versionsnoter til denne version."
                            : update.notes;
                    new AlertDialog.Builder(this)
                            .setTitle("Ny version tilgængelig")
                            .setMessage("Build " + update.versionCode + "\n\nVersionsnoter:\n" + notes)
                            .setPositiveButton("Hent og installer", (dialog, which) -> downloadUpdate(update))
                            .setNegativeButton("Senere", null).show();
                });
            } catch (Exception error) {
                if (manual) main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("Kunne ikke tjekke opdateringer")
                            .setMessage(error.getMessage())
                            .setPositiveButton("OK", null).show();
                });
            }
        });
    }

    private void downloadUpdate(UpdateChecker.Update update) {
        if (downloadingUpdate) return;
        downloadingUpdate = true;
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Henter opdatering")
                .setMessage("APK-filen hentes. Det kan tage et øjeblik.")
                .setView(new ProgressBar(this))
                .setCancelable(false).create();
        progress.show();
        downloadDialog = progress;
        updateExecutor.execute(() -> {
            try {
                File apk = UpdateDownloader.download(update.url, new File(getCacheDir(), "updates"));
                PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                if (info == null || !getPackageName().equals(info.packageName)
                        || info.versionCode != update.versionCode) {
                    apk.delete();
                    throw new IOException("Den hentede APK matcher ikke den forventede version");
                }
                main.post(() -> {
                    progress.dismiss();
                    downloadDialog = null;
                    downloadingUpdate = false;
                    if (!isFinishing() && !isDestroyed()) installUpdate(apk);
                });
            } catch (Exception error) {
                main.post(() -> {
                    progress.dismiss();
                    downloadDialog = null;
                    downloadingUpdate = false;
                    if (!isFinishing() && !isDestroyed()) {
                        showUpdateError("Kunne ikke hente opdateringen", error.getMessage());
                    }
                });
            }
        });
    }

    private void installUpdate(File apk) {
        if (!apk.isFile()) {
            showUpdateError("Kunne ikke installere", "APK-filen findes ikke længere. Prøv igen.");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            new AlertDialog.Builder(this)
                    .setTitle("Tillad installation")
                    .setMessage("Android skal have tilladelse til at installere apps fra DS Play. Giv tilladelsen i indstillinger, og gå tilbage hertil.")
                    .setPositiveButton("Åbn indstillinger", (dialog, which) -> {
                        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        try {
                            startActivityForResult(settings, UNKNOWN_SOURCES_REQUEST);
                        } catch (android.content.ActivityNotFoundException error) {
                            showUpdateError("Kunne ikke åbne indstillinger", error.getMessage());
                        }
                    })
                    .setNegativeButton("Senere", null).show();
            return;
        }
        pendingApk = null;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", apk);
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(install);
        } catch (android.content.ActivityNotFoundException error) {
            showUpdateError("Kunne ikke åbne installationen", error.getMessage());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VIDEO_LOGIN_REQUEST) {
            int index = pendingVideoIndex;
            pendingVideoIndex = -1;
            if (resultCode == RESULT_OK && index >= 0) openVideo(index, true);
            return;
        }
        if (requestCode == UNKNOWN_SOURCES_REQUEST && pendingApk != null
                && getPackageManager().canRequestPackageInstalls()) {
            installUpdate(pendingApk);
        }
    }

    private void openVideo(int index, boolean afterLogin) {
        if (index < 0 || index >= videos.size() || checkingVideoAccess) return;
        Video video = videos.get(index);
        if (!video.paid) {
            startPlayer(index);
            return;
        }
        checkingVideoAccess = true;
        executor.execute(() -> {
            try {
                boolean loggedIn = AuthClient.isLoggedIn();
                main.post(() -> {
                    checkingVideoAccess = false;
                    if (isFinishing() || isDestroyed() || index >= videos.size()
                            || videos.get(index) != video) return;
                    if (loggedIn) startPlayer(index);
                    else if (afterLogin) showUpdateError("Login kunne ikke bekræftes", "Prøv at logge ind igen.");
                    else {
                        pendingVideoIndex = index;
                        startActivityForResult(new Intent(this, LoginActivity.class), VIDEO_LOGIN_REQUEST);
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    checkingVideoAccess = false;
                    if (!isFinishing() && !isDestroyed())
                        showUpdateError("Kunne ikke kontrollere login", "Kontrollér forbindelsen og prøv igen.");
                });
            }
        });
    }

    private void startPlayer(int index) {
        PlayerActivity.queue = new ArrayList<>(videos);
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("index", index);
        intent.putExtra("id", videos.get(index).id);
        startActivity(intent);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (pendingApk != null) state.putString("pendingApk", pendingApk.getAbsolutePath());
        super.onSaveInstanceState(state);
    }

    private void showUpdateError(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage(message == null ? "Prøv igen senere." : message)
                .setPositiveButton("OK", null).show();
    }

    private void showSearchDialog() {
        boolean[] submitted = {false};
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(search);
        input.setHint("Søg i videoer");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        AlertDialog searchDialog = new AlertDialog.Builder(this)
                .setTitle("Søg i videoer")
                .setView(input)
                .setPositiveButton("Søg", (popup, which) -> {
                    submitted[0] = true;
                    search(input.getText().toString());
                })
                .setNegativeButton("Annuller", null)
                .create();
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            submitted[0] = true;
            search(input.getText().toString());
            searchDialog.dismiss();
            return true;
        });
        searchDialog.setOnDismissListener(dialog -> {
            if (submitted[0] && !videos.isEmpty()) focusFirstResult();
        });
        searchDialog.show();
    }

    private void search(String query) {
        search = query.trim();
        if (search.isEmpty()) return;
        mode = "search";
        if (isTv) pageSlug = "";
        focusResults = true;
        reload("Søgning: “" + search + "”");
        if (searchInput != null) searchInput.clearFocus();
    }

    private void chooseCategory() {
        List<CatalogClient.Category> choices = isTv ? CatalogClient.siteSports() : categories;
        String[] names = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) names[i] = choices.get(i).name;
        new AlertDialog.Builder(this).setTitle("Sportsgren")
                .setItems(names, (dialog, index) -> {
                    CatalogClient.Category category = choices.get(index);
                    if (isTv) openTvPage(category.id, category.name);
                    else {
                        categoryId = category.id;
                        categoryButton.setText(category.name + " ▾");
                        reload(category.name);
                    }
                }).show();
    }

    private void loadCategories() {
        executor.execute(() -> {
            try {
                List<CatalogClient.Category> result = CatalogClient.categories();
                main.post(() -> { categories.clear(); categories.addAll(result); });
            } catch (Exception error) {
                main.post(() -> status.setText("Kunne ikke hente sportsgrene: " + error.getMessage()));
            }
        });
    }

    private void reload(String message) {
        if (!"search".equals(mode)) focusResults = false;
        sectionTitle = message;
        if (!isTv) {
            liveButton.setActivated("livestream".equals(mode));
            archiveButton.setActivated("video-on-demand".equals(mode));
            searchButton.setActivated("search".equals(mode));
        }
        generation++;
        page = 0;
        hasMore = true;
        loading = false;
        videos.clear();
        if (isTv) {
            tvRows.removeAllViews();
            tvList = null;
            tvScroll.scrollTo(0, 0);
            status.setVisibility(View.VISIBLE);
            updateFavoriteToggle();
        } else {
            adapter.notifyDataSetChanged();
        }
        status.setText(sectionTitle + " · henter …");
        if (isTv && ("home".equals(mode) || "page".equals(mode))) loadTvPage();
        else loadMore();
    }

    private void loadTvPage() {
        loading = true;
        final int expectedGeneration = generation;
        final String requestedSlug = pageSlug;
        executor.execute(() -> {
            try {
                List<CatalogClient.Block> blocks = CatalogClient.pageBlocks(requestedSlug);
                for (CatalogClient.Block block : blocks) {
                    if (generation != expectedGeneration) return;
                    try {
                        List<Video> items = CatalogClient.blockVideos(block);
                        if (items.isEmpty()) continue;
                        main.post(() -> {
                            if (generation != expectedGeneration) return;
                            int first = videos.size();
                            videos.addAll(items);
                            if ("banner".equals(block.kind)) addBanner(first);
                            else if ("hero".equals(block.kind)) addHero(first, videos.size());
                            else addTvCards(addTvRow(block.title), first, videos.size(), false);
                            status.setVisibility(requestedSlug.isEmpty() ? View.GONE : View.VISIBLE);
                            status.setText(sectionTitle);
                        });
                    } catch (Exception ignored) { /* A failed site block must not hide later rows. */ }
                }
                main.post(() -> {
                    if (generation != expectedGeneration) return;
                    loading = false;
                    if (videos.isEmpty()) status.setText("Ingen videoer fundet");
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (generation != expectedGeneration) return;
                    loading = false;
                    status.setText("Kunne ikke hente siden: " + error.getMessage());
                });
            }
        });
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        loading = true;
        final int expectedGeneration = generation;
        final int requestedPage = page;
        final String requestedMode = mode;
        final String requestedCategory = categoryId;
        final String requestedSearch = search;
        executor.execute(() -> {
            try {
                CatalogClient.Page result;
                if ("search".equals(requestedMode)) {
                    CatalogClient.Page vod = CatalogClient.videos("video-on-demand", requestedCategory, requestedSearch, requestedPage);
                    CatalogClient.Page live = CatalogClient.videos("livestream", requestedCategory, requestedSearch, requestedPage);
                    List<Video> merged = new ArrayList<>(live.videos);
                    merged.addAll(vod.videos);
                    result = new CatalogClient.Page(merged, live.hasMore || vod.hasMore);
                } else {
                    result = CatalogClient.videos(requestedMode, requestedCategory, "", requestedPage);
                }
                main.post(() -> {
                    if (generation != expectedGeneration) return;
                    int first = videos.size();
                    videos.addAll(result.videos);
                    if (isTv) {
                        if (tvList == null && !videos.isEmpty()) tvList = addTvRow(sectionTitle);
                        if (tvList != null) addTvCards(tvList, first, videos.size(), result.hasMore);
                    } else {
                        adapter.notifyDataSetChanged();
                    }
                    if (focusResults && !videos.isEmpty()) {
                        focusResults = false;
                        focusFirstResult();
                    }
                    page++;
                    hasMore = result.hasMore;
                    loading = false;
                    status.setText(videos.isEmpty() ? sectionTitle + " · ingen videoer fundet" :
                            sectionTitle + " · " + videos.size() + " videoer");
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (generation != expectedGeneration) return;
                    loading = false;
                    status.setText("Kunne ikke hente videoer: " + error.getMessage());
                });
            }
        });
    }

    private void focusFirstResult() {
        if (isTv) {
            if (tvList != null && tvList.getChildCount() > 0) tvList.getChildAt(0).requestFocus();
        } else {
            grid.requestFocusFromTouch();
            grid.setSelection(0);
        }
    }

    private final class VideoAdapter extends BaseAdapter {
        @Override public int getCount() { return videos.size(); }
        @Override public Object getItem(int position) { return videos.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View previous, ViewGroup parent) {
            Video video = videos.get(position);
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(3), dp(3), dp(3), dp(3));
            StateListDrawable background = new StateListDrawable();
            GradientDrawable focused = new GradientDrawable();
            focused.setColor(CARD);
            focused.setStroke(dp(3), ACCENT);
            background.addState(new int[]{android.R.attr.state_selected}, focused);
            background.addState(new int[]{}, new ColorDrawable(CARD));
            card.setBackground(background);
            ImageView image = new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Color.rgb(16, 45, 45));
            card.addView(image, new LinearLayout.LayoutParams(-1, dp(compact ? 140 : 150)));
            loadImage(image, video.imageUrl);
            LinearLayout text = new LinearLayout(MainActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(14), dp(8), dp(14), dp(10));
            card.addView(text, new LinearLayout.LayoutParams(-1, 0, 1));
            TextView badge = label(video.paid ? "ABONNEMENT" : "GRATIS", 12, ACCENT);
            text.addView(badge);
            TextView title = label(video.title, compact ? 19 : 21, Color.WHITE);
            title.setTypeface(null, 1);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, 0, 1);
            titleParams.topMargin = dp(4);
            text.addView(title, titleParams);
            String detail = video.category;
            if (video.isLive()) {
                long now = System.currentTimeMillis() / 1000;
                detail = (video.startsAt <= now && "live".equals(video.state) ? "LIVE" :
                        video.startsAt > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
                                Locale.forLanguageTag("da-DK")).format(new Date(video.startsAt * 1000)) : "Live")
                        + (detail.isEmpty() ? "" : " · " + detail);
            }
            TextView metadata = label(detail, 14, MUTED);
            metadata.setMaxLines(1);
            text.addView(metadata);
            card.setLayoutParams(new AbsListView.LayoutParams(-1, dp(compact ? 250 : 270)));
            return card;
        }
    }

    private void loadImage(ImageView view, String url) {
        if (url.isEmpty()) return;
        view.setTag(url);
        Bitmap cached = imageCache.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        images.execute(() -> {
            try {
                Bitmap bitmap;
                URLConnection connection = new URL(url).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                try (InputStream stream = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(stream);
                }
                if (bitmap == null) return;
                imageCache.put(url, bitmap);
                main.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(bitmap); });
            } catch (IOException ignored) { }
        });
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        if (isTv) button.setTextSize(17);
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_activated}, new int[]{}},
                new int[]{BACKGROUND, ACCENT, Color.WHITE}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(ACCENT));
        background.addState(new int[]{android.R.attr.state_activated}, new ColorDrawable(Color.rgb(46, 82, 82)));
        background.addState(new int[]{}, new ColorDrawable(CARD));
        button.setBackground(background);
        button.setPadding(dp(18), 0, dp(18), 0);
        return button;
    }

    private void styleHeaderAction(Button button) {
        button.setTextSize(15);
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{BACKGROUND, MUTED}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, new ColorDrawable(ACCENT));
        background.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        button.setBackground(background);
    }

    @Override public void onBackPressed() {
        if (isTv && tvRows.hasFocus()) {
            tvScroll.smoothScrollTo(0, 0);
            ("search".equals(mode) ? searchNav :
                    "page".equals(mode) ? categoryNav : homeNav).requestFocus();
            return;
        }
        super.onBackPressed();
    }

    private TextView label(String value, int size, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
