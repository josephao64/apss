package gt.tiempopantalla;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class SocialApi {
    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("social", Context.MODE_PRIVATE);
    }

    static final class Failure extends IOException {
        final int status;
        Failure(int status, String text) {
            super(text);
            this.status = status;
        }
    }

    static String normalize(String input) throws Exception {
        URL url = new URL(input.trim());
        if (!"https".equals(url.getProtocol()) || url.getHost().isEmpty()
                || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
                || !(url.getPath().isEmpty() || "/".equals(url.getPath()))) {
            throw new Failure(400, "Escribe la dirección HTTPS del servidor, sin rutas.");
        }
        return "https://" + url.getAuthority();
    }

    static JSONObject request(Context context, String path, JSONObject body) throws Exception {
        SharedPreferences prefs = settings(context);
        return request(prefs.getString("server", ""), prefs.getString("token", ""), path, body);
    }

    static JSONObject request(String server, String token, String path, JSONObject body) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(server + path).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(6500);
            connection.setRequestProperty("Accept", "application/json");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(raw.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(raw); }
            }
            int status = connection.getResponseCode();
            boolean ok = status >= 200 && status < 300;
            try (InputStream in = ok ? connection.getInputStream() : connection.getErrorStream()) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                if (in != null) {
                    byte[] buffer = new byte[4096];
                    for (int read; (read = in.read(buffer)) != -1;) {
                        if (result.size() + read > 2_100_000) throw new IOException("Respuesta demasiado grande");
                        result.write(buffer, 0, read);
                    }
                }
                String text = new String(result.toByteArray(), StandardCharsets.UTF_8);
                if (ok) return new JSONObject(text);
                String message = "El servidor devolvió un error (" + status + ").";
                try { message = new JSONObject(text).optString("error", message); }
                catch (org.json.JSONException ignored) {}
                throw new Failure(status, message);
            }
        } finally {
            connection.disconnect();
        }
    }
}
