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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CatalogClient {
    static final String BASE = "https://direktesport.dk";
    static final String WORKSPACE = "8de32d80-db7d-47d2-867d-cd1e640f2745";
    static final int PAGE_SIZE = 20;
    private static final Pattern BLOCK = Pattern.compile(
            "<div\\b[^>]*class=\"[^\"]*(?:jfm-video-list-block|flowplayer-teaser-block)[^\"]*\"[^>]*>",
            Pattern.DOTALL);
    private static final String[][] SITE_SPORTS = {
            {"Basketball", "basketball"}, {"Floorball", "floorball"},
            {"Fodbold", "fodbold"}, {"Håndbold", "haandbold"},
            {"Ishockey", "ishockey"}, {"Kampsport", "kampsport"},
            {"Speedway", "speedway"}, {"Volleyball", "volleyball"},
            {"Øvrig sport", "oevrigsport"}
    };

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
        return videos(type, categoryId, search, page, 30);
    }

    static Page liveToday(String categoryId, int page) throws IOException, JSONException {
        return videos("livestream", categoryId, "", page, 0);
    }

    private static Page videos(String type, String categoryId, String search, int page, int daysForward) throws IOException, JSONException {
        String path = "livestream".equals(type) ? "livestreams" : "videos-on-demand";
        StringBuilder url = new StringBuilder(BASE).append("/flowplayer/api/").append(path)
                .append("?workspaceId=").append(WORKSPACE)
                .append("&page=").append(page)
                .append("&pageSize=").append(PAGE_SIZE);
        if ("livestream".equals(type) && search.isEmpty()) {
            url.append("&showLive=1&daysForward=").append(daysForward);
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

    static List<Category> siteSports() {
        List<Category> result = new ArrayList<>();
        for (String[] sport : SITE_SPORTS) result.add(new Category(sport[1], sport[0]));
        return result;
    }

    static List<Block> pageBlocks(String slug) throws IOException {
        if (!slug.isEmpty()) {
            boolean known = false;
            for (String[] sport : SITE_SPORTS) if (sport[1].equals(slug)) known = true;
            if (!known) throw new IOException("Ukendt sportsgren");
        }
        String html = get(BASE + (slug.isEmpty() ? "/" : "/" + slug));
        List<Block> result = new ArrayList<>();
        Matcher blocks = BLOCK.matcher(html);
        while (blocks.find()) {
            String tag = blocks.group();
            boolean banner = tag.contains("flowplayer-teaser-block");
            String type = attribute(tag, "data-content-type");
            String api = attribute(tag, banner ? "data-api-endpoint" :
                    "livestream".equals(type) ? "data-livestream-list-api" : "data-vod-list-api")
                    .replace("&amp;", "&");
            if (!api.startsWith("/flowplayer/api/")) continue;
            result.add(new Block(attribute(tag, "data-list-title"),
                    banner ? "banner" : "carousel".equals(attribute(tag, "data-variant"))
                            ? "hero" : "row", api));
        }
        return result;
    }

    static List<Video> blockVideos(Block block) throws IOException, JSONException {
        String api = block.api.replaceAll("pageSize=\\d+", "pageSize=" +
                ("hero".equals(block.kind) ? 6 : 12));
        JSONArray items = new JSONObject(get(BASE + api)).optJSONArray("videos");
        List<Video> result = new ArrayList<>();
        if (items != null) {
            if ("banner".equals(block.kind)) {
                Video selected = null;
                for (int i = 0; i < items.length(); i++) {
                    Video candidate = new Video(items.getJSONObject(i));
                    if (selected == null) selected = candidate;
                    if (!candidate.description.isEmpty()) { selected = candidate; break; }
                }
                if (selected != null) result.add(selected);
            } else {
                for (int i = 0; i < items.length(); i++) result.add(new Video(items.getJSONObject(i)));
            }
        }
        return result;
    }

    private static String attribute(String tag, String name) {
        Matcher value = Pattern.compile(Pattern.quote(name) + "=\"([^\"]*)\"").matcher(tag);
        return value.find() ? value.group(1) : "";
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

    static final class Block {
        final String title;
        final String kind;
        final String api;

        Block(String title, String kind, String api) {
            this.title = title;
            this.kind = kind;
            this.api = api;
        }
    }
}
