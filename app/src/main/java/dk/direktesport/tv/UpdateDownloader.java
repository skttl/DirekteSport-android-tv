package dk.direktesport.tv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class UpdateDownloader {
    private static final long MAX_APK_BYTES = 200L * 1024 * 1024;

    private UpdateDownloader() {}

    static File download(String url, File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Kunne ikke oprette downloadmappen");
        }
        File partial = new File(directory, "update.apk.part");
        File apk = new File(directory, "update.apk");
        if (partial.exists() && !partial.delete()) throw new IOException("Kunne ikke rydde gammel download");

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("APK-download svarede med HTTP " + connection.getResponseCode());
            }
            if (!"https".equals(connection.getURL().getProtocol())) {
                throw new IOException("APK-download kræver HTTPS");
            }
            long expected = connection.getContentLengthLong();
            if (expected > MAX_APK_BYTES) throw new IOException("APK-filen er for stor");
            long total = 0;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Download afbrudt");
                    total += count;
                    if (total > MAX_APK_BYTES) throw new IOException("APK-filen er for stor");
                    output.write(buffer, 0, count);
                }
            }
            if (total == 0 || (expected >= 0 && total != expected)) {
                throw new IOException("APK-filen blev ikke hentet helt");
            }
            if (apk.exists() && !apk.delete()) throw new IOException("Kunne ikke erstatte gammel APK");
            if (!partial.renameTo(apk)) throw new IOException("Kunne ikke gemme APK-filen");
            return apk;
        } finally {
            connection.disconnect();
            if (partial.exists()) partial.delete();
        }
    }
}
