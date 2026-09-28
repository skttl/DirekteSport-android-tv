package dk.direktesport.tv;

import android.webkit.CookieManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class AuthClient {
    private AuthClient() {}

    static boolean isLoggedIn() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                CatalogClient.BASE + "/api/authentication/user").openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        String cookie = CookieManager.getInstance().getCookie(CatalogClient.BASE);
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        try {
            if (connection.getResponseCode() != 200)
                throw new IOException("Loginstatus svarede med HTTP " + connection.getResponseCode());
            StringBuilder body = new StringBuilder();
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return new JSONObject(body.toString()).optBoolean("is_logged_in", false);
        } finally {
            connection.disconnect();
        }
    }
}
