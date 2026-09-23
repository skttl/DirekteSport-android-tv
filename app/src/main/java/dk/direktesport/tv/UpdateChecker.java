package dk.direktesport.tv;

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

final class UpdateChecker {
    private static final String HISTORY =
            "https://skttl.github.io/DirekteSport-android-tv/history.json";
    private static final String RELEASE_PREFIX =
            "https://github.com/skttl/DirekteSport-android-tv/releases/download/";

    private UpdateChecker() {}

    static Update latest() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(HISTORY + "?t=" + System.currentTimeMillis()).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("Versionslisten svarede med HTTP " + connection.getResponseCode());
            }
            StringBuilder body = new StringBuilder();
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            JSONArray entries = new JSONArray(body.toString());
            Update latest = null;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String build = entry.optString("build");
                String name = entry.optString("name");
                String url = entry.optString("url");
                if (!build.matches("build-[1-9][0-9]*")
                        || !name.matches("DirekteSport-TV-[0-9a-f]{12}\\.apk")
                        || !url.equals(RELEASE_PREFIX + build + "/" + name)) continue;
                int code;
                try {
                    code = Integer.parseInt(build.substring(6));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (latest == null || code > latest.versionCode) {
                    latest = new Update(code, url);
                }
            }
            if (latest == null) throw new IOException("Ingen gyldige APK-versioner fundet");
            return latest;
        } finally {
            connection.disconnect();
        }
    }

    static final class Update {
        final int versionCode;
        final String url;

        Update(int versionCode, String url) {
            this.versionCode = versionCode;
            this.url = url;
        }
    }
}
