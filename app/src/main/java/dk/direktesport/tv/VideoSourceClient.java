package dk.direktesport.tv;

import android.net.Uri;
import android.webkit.CookieManager;

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

final class VideoSourceClient {
    private static final String CONFIG_BASE =
            "https://ljsp.lwcdn.com/web/public/native/config/7e165983-ccb1-453f-bc68-0d8ee7199e66/";

    private VideoSourceClient() {}

    static String hlsUrl(String videoId) throws IOException, JSONException {
        if (videoId == null || videoId.isEmpty()) throw new IOException("Video-id mangler");
        String url = CONFIG_BASE + Uri.encode(videoId);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Referer", CatalogClient.BASE + "/");
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("Videokonfiguration svarede med HTTP " + code);
            StringBuilder body = new StringBuilder();
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONArray sources = new JSONObject(body.toString()).optJSONArray("src");
            if (sources != null) {
                for (int i = 0; i < sources.length(); i++) {
                    String source = sources.optString(i, "");
                    if (source.startsWith("//")) source = "https:" + source;
                    Uri uri = Uri.parse(source);
                    if ("https".equals(uri.getScheme()) && uri.getPath() != null
                            && uri.getPath().endsWith(".m3u8")) return source;
                }
            }
            throw new IOException("Ingen HLS-stream fundet for videoen");
        } finally {
            connection.disconnect();
        }
    }
}
