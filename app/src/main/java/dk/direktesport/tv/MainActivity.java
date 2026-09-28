package dk.direktesport.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(10, 26, 26);
    private static final int CARD = Color.rgb(23, 63, 63);
    private static final int ACCENT = Color.rgb(92, 255, 154);
    private static final int MUTED = Color.rgb(181, 192, 189);
    private static final int UNKNOWN_SOURCES_REQUEST = 1;
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
    private int generation;
    private boolean loading;
    private boolean hasMore = true;
    private boolean focusResults;
    private boolean downloadingUpdate;
    private AlertDialog downloadDialog;
    private File pendingApk;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (state != null && state.getString("pendingApk") != null) {
            pendingApk = new File(state.getString("pendingApk"));
        }
        loadCategories();
        reload("Live og kommende udsendelser");
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
        TextView title = label("DIREKTE SPORT", compact ? 24 : 32, ACCENT);
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
        grid.setOnItemClickListener((parent, view, position, id) -> {
            PlayerActivity.queue = new ArrayList<>(videos);
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.putExtra("index", position);
            intent.putExtra("id", videos.get(position).id);
            startActivity(intent);
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {}
            @Override public void onScroll(AbsListView view, int first, int count, int total) {
                if (total > 0 && first + count >= total - 6) loadMore();
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        liveButton.requestFocus();
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
                    .setMessage("Android skal have tilladelse til at installere apps fra DirekteSport TV. Giv tilladelsen i indstillinger, og gå tilbage hertil.")
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
        if (requestCode == UNKNOWN_SOURCES_REQUEST && pendingApk != null
                && getPackageManager().canRequestPackageInstalls()) {
            installUpdate(pendingApk);
        }
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
        focusResults = true;
        reload("Søgning: “" + search + "”");
        if (searchInput != null) searchInput.clearFocus();
    }

    private void chooseCategory() {
        String[] names = new String[categories.size()];
        for (int i = 0; i < categories.size(); i++) names[i] = categories.get(i).name;
        new AlertDialog.Builder(this).setTitle("Sportsgren")
                .setItems(names, (dialog, index) -> {
                    CatalogClient.Category category = categories.get(index);
                    categoryId = category.id;
                    categoryButton.setText(category.name + " ▾");
                    reload(category.name);
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
        liveButton.setActivated("livestream".equals(mode));
        archiveButton.setActivated("video-on-demand".equals(mode));
        searchButton.setActivated("search".equals(mode));
        generation++;
        page = 0;
        hasMore = true;
        loading = false;
        videos.clear();
        adapter.notifyDataSetChanged();
        status.setText(sectionTitle + " · henter …");
        loadMore();
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
                    videos.addAll(result.videos);
                    adapter.notifyDataSetChanged();
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
        grid.requestFocusFromTouch();
        grid.setSelection(0);
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
        if (isTv && grid.hasFocus()) {
            grid.smoothScrollToPosition(0);
            ("search".equals(mode) ? searchButton :
                    "video-on-demand".equals(mode) ? archiveButton : liveButton).requestFocus();
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
