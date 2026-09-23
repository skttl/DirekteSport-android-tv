package dk.direktesport.tv;

import android.webkit.CookieManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class CatalogClient {
    static final String BASE = "https://direktesport.dk";
    static final String WORKSPACE = "8de32d80-db7d-47d2-867d-cd1e640f2745";
    static final int PAGE_SIZE = 20;

    private CatalogClient() {}

    static List<Category> categories() throws IOException, JSONException {
        JSONArray json = new JSONArray(get(BASE + "/flowplayer/api/categories?workspaceId=" + WORKSPACE));
        List<Category> result = new ArrayList<>();
        result.add(new Category("", "Alle sportsgrene"));
        for (int i = 0; i < json.length(); i++) {
            JSONObject item = json.getJSONObject(i);
            String name = item.optString("name");
            if (!name.isEmpty() && !"target-import".equals(name)) {
                result.add(new Category(item.optString("id"), name));
            }
        }
        return result;
    }

    static Page videos(String type, String categoryId, String search, int page) throws IOException, JSONException {
        String path = "livestream".equals(type) ? "livestreams" : "videos-on-demand";
        StringBuilder url = new StringBuilder(BASE).append("/flowplayer/api/").append(path)
                .append("?workspaceId=").append(WORKSPACE)
                .append("&page=").append(page)
                .append("&pageSize=").append(PAGE_SIZE);
        if ("livestream".equals(type) && search.isEmpty()) {
            url.append("&showLive=1&daysForward=30");
        }
        if (!categoryId.isEmpty()) {
            url.append("&categoryId=").append(encode(categoryId));
        }
        if (!search.isEmpty()) {
            url.append("&searchInput=").append(encode(search));
        }
        JSONObject response = new JSONObject(get(url.toString()));
        JSONArray items = response.optJSONArray("videos");
        List<Video> result = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                result.add(new Video(items.getJSONObject(i)));
            }
        }
        return new Page(result, page + 1 < response.optInt("totalPages", 0));
    }

    private static String encode(String value) {
        return Uri.encode(value);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        String cookie = CookieManager.getInstance().getCookie(BASE);
        if (cookie != null && !cookie.isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("DirekteSport svarede med HTTP " + code);
            }
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                return body.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    static final class Category {
        final String id;
        final String name;

        Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static final class Page {
        final List<Video> videos;
        final boolean hasMore;

        Page(List<Video> videos, boolean hasMore) {
            this.videos = videos;
            this.hasMore = hasMore;
        }
    }
}
