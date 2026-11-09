package gt.tiempopantalla;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SocialActivity extends Activity {
    private interface Request { JSONObject run() throws Exception; }
    private interface Success { void accept(JSONObject data) throws Exception; }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root, content, results;
    private TextView status;
    private Button sharingButton;
    private long friendId;
    private String friendName = "";
    private String selectedDay;
    private int screen;
    private boolean pending, loading, resumed, destroyed;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (screen == 1 || screen == 2) refresh();
            handler.postDelayed(this, 8000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = SocialApi.settings(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xfff0f3fc);
        root = column();
        root.setPadding(dp(18), dp(16), dp(18), dp(22));
        scroll.addView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(dp(18) + bars.left, dp(16) + bars.top,
                        dp(18) + bars.right, dp(22) + bars.bottom);
                return insets;
            });
        }
        setContentView(scroll);
        label(root, "Tiempo en Pantalla", 15);
        label(root, "Cuenta y amigos", 30);
        status = label(root, "", 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content = column();
        root.addView(content);
        button(root, "Volver a mi uso", this::finish);
        if (prefs.getString("token", "").isEmpty()) login();
        else home();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.post(poll);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        super.onDestroy();
    }

    private void run(boolean manual, Request action, Success success) {
        if (destroyed || (manual && pending) || (!manual && (pending || loading))) return;
        if (manual) {
            pending = true;
            status.setText("Conectando…");
        } else loading = true;
        worker.execute(() -> {
            try {
                JSONObject answer = action.run();
                handler.post(() -> {
                    if (destroyed) return;
                    if (manual) pending = false;
                    else loading = false;
                    try {
                        if (manual || screen != 0) success.accept(answer);
                        if (manual && status.getText().toString().equals("Conectando…")) status.setText("");
                    } catch (Exception failure) { error(failure); }
                });
            } catch (Exception failure) {
                handler.post(() -> {
                    if (destroyed) return;
                    if (manual) pending = false;
                    else loading = false;
                    error(failure);
                    if (!manual && screen == 2 && results != null) results.removeAllViews();
                });
            }
        });
    }

    private void error(Exception failure) {
        if (failure instanceof SocialApi.Failure) {
            SocialApi.Failure response = (SocialApi.Failure) failure;
            if (response.status == 401 && screen != 0) {
                stopService(new Intent(this, SharingService.class));
                prefs.edit().remove("token").remove("name").remove("admin").apply();
                login();
            }
            status.setText(response.getMessage());
        } else status.setText("No se pudo conectar. Revisa la dirección HTTPS o tu conexión.");
    }

    private void login() {
        screen = 0;
        content.removeAllViews();
        label(content, "Usa un usuario y una contraseña.", 17);
        EditText server = field(content, "Dirección HTTPS del servidor", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(prefs.getString("server", "https://appscontrolsss.netlify.app"));
        EditText name = field(content, "Usuario", InputType.TYPE_CLASS_TEXT);
        EditText pass = field(content, "Contraseña", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Runnable signIn = () -> authenticate(server, name, pass, false);
        button(content, "Iniciar sesión", signIn);
        button(content, "Crear cuenta", () -> authenticate(server, name, pass, true));
    }

    private void authenticate(EditText server, EditText name, EditText pass, boolean create) {
        String address = server.getText().toString();
        String username = name.getText().toString();
        String password = pass.getText().toString();
        run(true, () -> {
            String normalized = SocialApi.normalize(address);
            JSONObject body = new JSONObject().put("name", username).put("password", password);
            JSONObject response = SocialApi.request(normalized, "", create ? "/register" : "/login", body);
            return response.put("server", normalized);
        }, response -> {
            JSONObject user = response.getJSONObject("user");
            prefs.edit().putString("server", response.getString("server"))
                    .putString("token", response.getString("token")).putString("name", user.getString("name"))
                    .putBoolean("admin", user.getBoolean("admin")).apply();
            pass.setText("");
            home();
        });
    }

    private void home() {
        screen = 1;
        friendId = 0;
        content.removeAllViews();
        boolean admin = prefs.getBoolean("admin", false);
        label(content, "@" + prefs.getString("name", "") + (admin ? "  ·  Administrador" : ""), 21);
        label(content, "Al compartir, todos tus amigos aceptados podrán ver tu tiempo de pantalla y el historial por aplicación.", 15);
        sharingButton = button(content, "", this::toggleSharing);
        button(content, "Ocultar mis estadísticas compartidas", () -> new AlertDialog.Builder(this)
                .setMessage("Se detendrá la transmisión y se retirará la copia de tus estadísticas del servidor.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Ocultar", (dialog, which) -> {
                    stopService(new Intent(this, SharingService.class));
                    run(true, () -> SocialApi.request(this, "/usage/hide", new JSONObject()), data -> {
                        status.setText("Estadísticas ocultas.");
                        updateSharingButton();
                    });
                }).show());
        button(content, "Generar mi código", () -> run(true,
                () -> SocialApi.request(this, "/code", new JSONObject()), response -> showCode(response.getString("code"))));
        button(content, "Agregar amigo con código", this::addFriend);
        if (admin) button(content, "Administrar usuarios", this::admin);
        button(content, "Cerrar sesión", () -> {
            stopService(new Intent(this, SharingService.class));
            run(true, () -> SocialApi.request(this, "/logout", new JSONObject()), ignored -> {
                prefs.edit().remove("token").remove("name").remove("admin").apply();
                login();
            });
        });
        label(content, "Mis amigos", 23);
        results = column();
        content.addView(results);
        refresh();
    }

    private void updateSharingButton() {
        if (sharingButton != null) sharingButton.setText(
                SharingService.running ? "Pausar transmisión en vivo" : "Compartir en vivo");
    }

    private void toggleSharing() {
        if (SharingService.running) {
            stopService(new Intent(this, SharingService.class));
            status.setText("Transmisión pausada. Tus amigos aún pueden ver el último historial compartido.");
            return;
        }
        if (!Usage.allowed(this)) {
            status.setText("Activa el acceso de uso de Tiempo en Pantalla.");
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 31);
            status.setText("Permite las notificaciones y toca Compartir en vivo.");
            return;
        }
        if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            status.setText("Habilita las notificaciones para iniciar una transmisión.");
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        }
        new AlertDialog.Builder(this).setTitle("Compartir en vivo")
                .setMessage("Tus amigos podrán ver tus tiempos de uso por aplicación y el historial disponible. "
                        + "La transmisión seguirá al cambiar de app y podrás pausarla desde la notificación.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Compartir", (dialog, which) -> run(true,
                        () -> SocialApi.request(this, "/usage/start", new JSONObject()), response -> {
                            if (!resumed) {
                                status.setText("Vuelve a tocar Compartir en vivo.");
                                return;
                            }
                            startForegroundService(new Intent(this, SharingService.class)
                                    .putExtra("lease", response.getString("lease")));
                            status.setText("Transmisión iniciada.");
                            handler.postDelayed(this::updateSharingButton, 500);
                        })).show();
    }

    private void showCode(String code) {
        new AlertDialog.Builder(this).setTitle("Tu código de amistad").setMessage(code
                        + "\n\nVence en 24 horas y se puede usar una vez. Al usarlo, ambos quedarán agregados.")
                .setNegativeButton("Cerrar", null).setPositiveButton("Copiar", (dialog, which) ->
                        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Código", code)))
                .show();
    }

    private void addFriend() {
        EditText code = new EditText(this);
        code.setHint("Código de 10 caracteres");
        code.setSingleLine();
        new AlertDialog.Builder(this).setTitle("Agregar amigo").setView(code)
                .setNegativeButton("Cancelar", null).setPositiveButton("Agregar", (dialog, which) -> {
                    String value = code.getText().toString().trim().toUpperCase(Locale.ROOT);
                    run(true, () -> SocialApi.request(this, "/friends/add", new JSONObject().put("code", value)), data -> {
                        status.setText("Amigo agregado.");
                        refresh();
                    });
                }).show();
    }

    private void refresh() {
        int current = screen;
        long id = friendId;
        if (current == 1) {
            updateSharingButton();
            run(false, () -> SocialApi.request(this, "/friends", null), data -> {
                if (screen != current) return;
                JSONArray friends = data.getJSONArray("friends");
                results.removeAllViews();
                if (friends.length() == 0) label(results, "Todavía no tienes amigos agregados.", 15);
                for (int i = 0; i < friends.length(); i++) {
                    JSONObject friend = friends.getJSONObject(i);
                    long key = friend.getLong("id");
                    String name = friend.getString("name");
                    button(results, "@" + name + "  ·  " + freshness(friend, data.optLong("now")),
                            () -> detail(key, name));
                }
            });
        } else if (current == 2) {
            run(false, () -> SocialApi.request(this, "/friends/" + id, null), data -> {
                if (screen == current && friendId == id) showStats(data);
            });
        }
    }

    private void detail(long id, String name) {
        screen = 2;
        friendId = id;
        friendName = name;
        selectedDay = null;
        content.removeAllViews();
        button(content, "Volver a mis amigos", this::home);
        label(content, "@" + friendName, 23);
        button(content, "Eliminar amistad", () -> new AlertDialog.Builder(this)
                .setMessage("Ambos perderán el acceso a las estadísticas del otro.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar", (dialog, which) ->
                    run(true, () -> SocialApi.request(this, "/friends/remove", new JSONObject().put("id", id)),
                        ignored -> home())).show());
        results = column();
        content.addView(results);
        refresh();
    }

    private void showStats(JSONObject data) throws Exception {
        results.removeAllViews();
        label(results, freshness(data, data.optLong("now")), 15);
        JSONArray days = data.getJSONArray("days");
        if (days.length() == 0) {
            label(results, "Tu amigo aún no tiene estadísticas compartidas.", 16);
            return;
        }
        JSONObject chosen = days.getJSONObject(0);
        ArrayList<String> dates = new ArrayList<>();
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.getJSONObject(i);
            String date = day.getString("date");
            dates.add(date + "  ·  " + duration(day.getLong("screen")));
            if (date.equals(selectedDay)) chosen = day;
        }
        label(results, chosen.getString("date") + "  ·  " + duration(chosen.getLong("screen")), 20);
        button(results, "Elegir día del historial (" + days.length() + ")", () ->
                new AlertDialog.Builder(this).setTitle("Historial")
                        .setItems(dates.toArray(new String[0]), (dialog, which) -> {
                            selectedDay = dates.get(which).substring(0, 10);
                            try { showStats(data); } catch (Exception failure) { error(failure); }
                        }).show());
        label(results, "Tiempo por aplicación", 19);
        JSONObject apps = chosen.getJSONObject("apps");
        ArrayList<String> names = new ArrayList<>();
        Iterator<String> keys = apps.keys();
        keys.forEachRemaining(names::add);
        names.sort(Comparator.<String>comparingLong(apps::optLong).reversed().thenComparing(name -> name));
        if (names.isEmpty()) label(results, "Sin aplicaciones registradas.", 15);
        for (String name : names) {
            int separator = name.lastIndexOf(" · ");
            label(results, (separator < 0 ? name : name.substring(0, separator))
                    + "   " + duration(apps.optLong(name)), 16);
        }
    }

    private String freshness(JSONObject data, long now) {
        long update = data.optLong("updated", 0);
        if (update <= 0) return "Sin transmisión en vivo";
        long age = Math.max(0, now - update);
        if (age < 30) return "En vivo · actualizado hace " + age + " s";
        if (age < 60) return "Última actualización hace " + age + " s";
        if (age < 3600) return "Última actualización hace " + age / 60 + " min";
        if (age < 86400) return "Última actualización hace " + age / 3600 + " h";
        return "Última actualización hace " + age / 86400 + " d";
    }

    private void admin() {
        screen = 3;
        content.removeAllViews();
        button(content, "Volver a mis amigos", this::home);
        label(content, "Administrar usuarios", 23);
        results = column();
        content.addView(results);
        run(true, () -> SocialApi.request(this, "/admin/users", null), data -> {
            if (screen != 3) return;
            JSONArray users = data.getJSONArray("users");
            for (int i = 0; i < users.length(); i++) {
                JSONObject user = users.getJSONObject(i);
                boolean enabled = user.getBoolean("enabled");
                String name = user.getString("name");
                long id = user.getLong("id");
                if (user.getBoolean("admin")) label(results, "@" + name + "  ·  Administrador", 16);
                else button(results, "@" + name + (enabled ? "  ·  Desactivar" : "  ·  Reactivar"),
                        () -> new AlertDialog.Builder(this).setMessage((enabled ? "¿Desactivar @" : "¿Reactivar @") + name + "?")
                            .setNegativeButton("Cancelar", null).setPositiveButton("Confirmar", (dialog, which) ->
                                run(true, () -> SocialApi.request(this, "/admin/users",
                                        new JSONObject().put("id", id).put("enabled", !enabled)), ignored -> admin())).show());
            }
        });
    }

    private static String duration(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(LinearLayout parent, String value, int size) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(0xff162040);
        label.setTextSize(size);
        label.setPadding(dp(4), dp(6), dp(4), dp(6));
        parent.addView(label);
        return label;
    }

    private EditText field(LinearLayout parent, String hint, int type) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setInputType(type);
        field.setHint(hint);
        parent.addView(field);
        return field;
    }

    private Button button(LinearLayout parent, String title, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(40, 70, 187));
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }
}
