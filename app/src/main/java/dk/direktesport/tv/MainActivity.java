package dk.direktesport.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(16, 21, 30);
    private static final int CARD = Color.rgb(30, 39, 53);
    private static final int ACCENT = Color.rgb(244, 197, 69);
    private static final int UNKNOWN_SOURCES_REQUEST = 1;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Video> videos = new ArrayList<>();
    private final List<CatalogClient.Category> categories = new ArrayList<>();
    private GridView grid;
    private VideoAdapter adapter;
    private TextView status;
    private Button categoryButton;
    private EditText searchInput;
    private String mode = "livestream";
    private String categoryId = "";
    private String search = "";
    private int page;
    private int generation;
    private boolean loading;
    private boolean hasMore = true;
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
        super.onDestroy();
    }

    private void buildUi() {
        boolean compact = getResources().getConfiguration().smallestScreenWidthDp < 600;
        int sidePadding = dp(compact ? 12 : 30);
        int topPadding = dp(compact ? 8 : 22);
        int bottomPadding = dp(compact ? 8 : 18);
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
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(compact ? 45 : 58)));
        TextView title = label("DIREKTE SPORT", compact ? 24 : 29, ACCENT);
        title.setTypeface(null, 1);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button updates = button("Tjek opdatering");
        updates.setOnClickListener(v -> checkForUpdates(true));
        Button login = button("Log ind / konto");
        login.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
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
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(compact ? 50 : 64)));
        Button live = button("Live");
        live.setOnClickListener(v -> { mode = "livestream"; search = ""; reload("Live og kommende udsendelser"); });
        Button archive = button("Arkiv");
        archive.setOnClickListener(v -> { mode = "video-on-demand"; search = ""; reload("Arkivvideoer"); });
        if (compact) {
            navigation.addView(live, new LinearLayout.LayoutParams(0, -1, 1));
            navigation.addView(archive, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            navigation.addView(live);
            navigation.addView(archive);
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
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Søg i videoer");
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(Color.LTGRAY);
        searchInput.setBackgroundColor(CARD);
        searchInput.setPadding(dp(14), 0, dp(14), 0);
        searchInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        if (!compact) searchParams.leftMargin = dp(12);
        searchRow.addView(searchInput, searchParams);
        Button searchButton = button("Søg");
        searchButton.setOnClickListener(v -> search());
        searchRow.addView(searchButton);
        searchInput.setOnEditorActionListener((v, action, event) -> { search(); return true; });
        status = label("Henter videoer …", 16, Color.LTGRAY);
        status.setPadding(0, dp(8), 0, dp(12));
        root.addView(status);

        grid = new GridView(this);
        grid.setNumColumns(compact ? 1 : 3);
        grid.setColumnWidth(dp(280));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(14));
        grid.setVerticalSpacing(dp(14));
        grid.setSelector(android.R.color.transparent);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setFocusable(true);
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
        live.requestFocus();
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

    private void search() {
        search = searchInput.getText().toString().trim();
        if (search.isEmpty()) return;
        mode = "search";
        reload("Søger efter “" + search + "”");
        searchInput.clearFocus();
        grid.requestFocus();
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
        generation++;
        page = 0;
        hasMore = true;
        loading = false;
        videos.clear();
        adapter.notifyDataSetChanged();
        status.setText(message + " · henter …");
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
                    page++;
                    hasMore = result.hasMore;
                    loading = false;
                    status.setText(videos.isEmpty() ? "Ingen videoer fundet" :
                            videos.size() + " videoer vist" + (hasMore ? " · rul ned for flere" : ""));
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

    private final class VideoAdapter extends BaseAdapter {
        @Override public int getCount() { return videos.size(); }
        @Override public Object getItem(int position) { return videos.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View previous, ViewGroup parent) {
            Video video = videos.get(position);
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(18), dp(16), dp(18), dp(16));
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[]{android.R.attr.state_selected}, new ColorDrawable(Color.rgb(66, 77, 96)));
            background.addState(new int[]{}, new ColorDrawable(CARD));
            card.setBackground(background);
            TextView badge = label(video.paid ? "ABONNEMENT" : "GRATIS", 13, video.paid ? ACCENT : Color.rgb(105, 224, 174));
            card.addView(badge);
            TextView title = label(video.title, 20, Color.WHITE);
            title.setTypeface(null, 1);
            title.setMaxLines(3);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, 0, 1);
            titleParams.topMargin = dp(10);
            card.addView(title, titleParams);
            String detail = video.category;
            if (video.isLive()) {
                long now = System.currentTimeMillis() / 1000;
                detail = (video.startsAt <= now && "live".equals(video.state) ? "LIVE" :
                        video.startsAt > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(video.startsAt * 1000)) : "Live")
                        + (detail.isEmpty() ? "" : " · " + detail);
            }
            TextView metadata = label(detail, 14, Color.LTGRAY);
            metadata.setMaxLines(1);
            card.addView(metadata);
            card.setLayoutParams(new AbsListView.LayoutParams(-1, dp(180)));
            return card;
        }
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CARD));
        return button;
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
