package dk.direktesport.tv;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.OutputStream;

final class HomeRecommendations {
    private static final String TAG = "HomeRecommendations";
    private static final String CHANNEL_KEY = "favorite_live";
    private static final int JOB_ID = 78431;

    private HomeRecommendations() {}

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }

    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                sync(app);
            } catch (Exception error) {
                Log.w(TAG, "Could not update TV recommendations", error);
            }
        }, "tv-recommendations").start();
    }

    private static synchronized void sync(Context context) throws Exception {
        SharedPreferences favoritesPrefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        Set<String> favorites = new HashSet<>(favoritesPrefs.getStringSet("favorite_sports", new HashSet<>()));
        ContentResolver resolver = context.getContentResolver();
        long channelId = channelId(resolver);
        if (favorites.isEmpty() && channelId == -1) return;

        List<Video> live = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> dismissed = context.getSharedPreferences("recommendations", Context.MODE_PRIVATE)
                .getStringSet("dismissed", new HashSet<>());
        List<CatalogClient.Category> categories = favorites.isEmpty()
                ? Collections.emptyList() : CatalogClient.categories();
        long now = System.currentTimeMillis() / 1000;
        for (CatalogClient.Category sport : CatalogClient.siteSports()) {
            if (!favorites.contains(sport.id)) continue;
            String categoryId = "";
            for (CatalogClient.Category category : categories) {
                if (sport.name.equalsIgnoreCase(category.name)) {
                    categoryId = category.id;
                    break;
                }
            }
            if (categoryId.isEmpty()) continue;
            for (int page = 0; ; page++) {
                CatalogClient.Page result = CatalogClient.liveToday(categoryId, page);
                for (Video video : result.videos) {
                    if ("live".equals(video.state) && video.startsAt <= now
                            && !video.id.isEmpty() && !dismissed.contains(video.id)
                            && seen.add(video.id)) live.add(video);
                }
                if (!result.hasMore) break;
            }
        }

        if (channelId == -1) {
            ContentValues channel = new ContentValues();
            channel.put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW);
            channel.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Live i dine favoritter");
            channel.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, CHANNEL_KEY);
            channel.put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
                    new Intent(context, MainActivity.class).toUri(Intent.URI_INTENT_SCHEME));
            Uri uri = resolver.insert(TvContract.Channels.CONTENT_URI, channel);
            if (uri == null) return;
            channelId = ContentUris.parseId(uri);
            storeChannelLogo(context, channelId);
            TvContract.requestChannelBrowsable(context, channelId);
        }

        Map<String, Long> existing = new HashMap<>();
        try (Cursor cursor = resolver.query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{TvContract.PreviewPrograms._ID,
                        TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
                        TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID},
                null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                if (cursor.getLong(1) == channelId) existing.put(cursor.getString(2), cursor.getLong(0));
            }
        }
        for (Video video : live) {
            ContentValues program = new ContentValues();
            program.put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, video.id);
            program.put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_EVENT);
            program.put(TvContract.PreviewPrograms.COLUMN_TITLE, video.title);
            program.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                    video.category.isEmpty() ? "LIVE" : "LIVE · " + video.category);
            program.put(TvContract.PreviewPrograms.COLUMN_AVAILABILITY,
                    video.paid ? TvContract.PreviewPrograms.AVAILABILITY_FREE_WITH_SUBSCRIPTION
                            : TvContract.PreviewPrograms.AVAILABILITY_AVAILABLE);
            program.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                    video.imageUrl.isEmpty() ? TvContract.buildChannelLogoUri(channelId).toString()
                            : video.imageUrl);
            program.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    video.imageUrl.isEmpty() ? TvContract.PreviewPrograms.ASPECT_RATIO_1_1
                            : TvContract.PreviewPrograms.ASPECT_RATIO_16_9);
            Intent open = new Intent(context, MainActivity.class);
            open.setAction(Intent.ACTION_VIEW);
            open.putExtra("recommended_video_id", video.id);
            open.putExtra("recommended_video_paid", video.paid);
            program.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, open.toUri(Intent.URI_INTENT_SCHEME));
            if (Build.VERSION.SDK_INT >= 33) {
                program.put(TvContract.PreviewPrograms.COLUMN_LIVE, 1);
                program.put(TvContract.PreviewPrograms.COLUMN_START_TIME_UTC_MILLIS,
                        video.startsAt * 1000);
            }
            Long id = existing.remove(video.id);
            if (id == null) {
                program.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId);
                resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, program);
            }
            else resolver.update(TvContract.buildPreviewProgramUri(id), program, null, null);
        }
        for (long id : existing.values())
            resolver.delete(TvContract.buildPreviewProgramUri(id), null, null);
    }

    private static long channelId(ContentResolver resolver) {
        try (Cursor cursor = resolver.query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID},
                null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                if (CHANNEL_KEY.equals(cursor.getString(1))) return cursor.getLong(0);
            }
        }
        return -1;
    }

    private static void storeChannelLogo(Context context, long channelId) throws Exception {
        int size = Math.round(80 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Drawable icon = context.getDrawable(R.drawable.ds_play_icon);
        icon.setBounds(0, 0, size, size);
        icon.draw(new Canvas(bitmap));
        try (OutputStream output = context.getContentResolver()
                .openOutputStream(TvContract.buildChannelLogoUri(channelId))) {
            if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                throw new IllegalStateException("Could not write TV channel logo");
        } finally {
            bitmap.recycle();
        }
    }

    public static final class SyncJob extends JobService {
        @Override public boolean onStartJob(JobParameters params) {
            new Thread(() -> {
                boolean retry = false;
                try {
                    sync(getApplicationContext());
                } catch (Exception error) {
                    Log.w(TAG, "Could not update TV recommendations", error);
                    retry = true;
                }
                jobFinished(params, retry);
            }, "tv-recommendations").start();
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) { return true; }
    }

    public static final class Removed extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(TvContract.EXTRA_PREVIEW_PROGRAM_ID, -1);
            if (id == -1) return;
            ContentResolver resolver = context.getContentResolver();
            try (Cursor cursor = resolver.query(TvContract.buildPreviewProgramUri(id),
                    new String[]{TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID},
                    null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return;
                String videoId = cursor.getString(0);
                SharedPreferences prefs = context.getSharedPreferences("recommendations", Context.MODE_PRIVATE);
                Set<String> dismissed = new HashSet<>(prefs.getStringSet("dismissed", new HashSet<>()));
                dismissed.add(videoId);
                prefs.edit().putStringSet("dismissed", dismissed).apply();
            }
            resolver.delete(TvContract.buildPreviewProgramUri(id), null, null);
        }
    }
}
