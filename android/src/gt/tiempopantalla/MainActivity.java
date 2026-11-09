package gt.tiempopantalla;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = 0xff162040;
    private static final int MUTED = 0xff495978;
    private static final int BLUE = 0xff2846bb;
    private static final int PAPER = 0xfff0f3fc;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEE d MMM", new Locale("es", "GT"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, String> labels = new HashMap<>();
    private LinearLayout root;
    private LinearLayout dashboard;
    private LinearLayout history;
    private LinearLayout rankings;
    private TextView clock;
    private TextView status;
    private TextView average;
    private TextView rankingTitle;
    private Button todayButton;
    private Button weekButton;
    private Button monthButton;
    private Usage.Snapshot snapshot;
    private LocalDate selected;
    private int period = 7;
    private int lifecycle;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            int generation = lifecycle;
            worker.execute(() -> {
                Usage.Snapshot next;
                try {
                    next = Usage.read(MainActivity.this);
                    if (next != null) HistoryJob.schedule(MainActivity.this);
                } catch (RuntimeException failure) {
                    next = null;
                }
                Usage.Snapshot result = next;
                handler.post(() -> {
                    if (generation != lifecycle) return;
                    snapshot = result;
                    if (!Usage.allowed(MainActivity.this)) showPermission();
                    else if (snapshot == null) status.setText("Datos no disponibles. Reintentando…");
                    else render();
                });
            });
            handler.postDelayed(this, 5000);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        root = column();
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(dp(20) + bars.left, dp(20) + bars.top,
                    dp(20) + bars.right, dp(20) + bars.bottom);
            return insets;
        });
        // Type-based inset APIs are only available on Android 11 and newer.
        if (android.os.Build.VERSION.SDK_INT < 30) {
            scroll.setOnApplyWindowInsetsListener(null);
            root.setPadding(dp(20), dp(20), dp(20), dp(20));
        }
        setContentView(scroll);
        text(root, "BIENESTAR DIGITAL", 13, BLUE, true);
        text(root, "Tiempo en Pantalla", 30, INK, true);
        text(root, "Conoce cómo usas tu teléfono", 16, MUTED, false);
        Button friends = button("Cuenta y amigos");
        friends.setOnClickListener(view -> startActivity(new Intent(this, SocialActivity.class)));
        root.addView(friends);
        dashboard = column();
        root.addView(dashboard);
    }

    @Override protected void onResume() {
        super.onResume();
        lifecycle++;
        if (Usage.allowed(this)) {
            showDashboard();
            handler.post(refresh);
            handler.post(tick);
        } else {
            showPermission();
        }
    }

    @Override protected void onPause() {
        lifecycle++;
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void showPermission() {
        snapshot = null;
        dashboard.removeAllViews();
        LinearLayout card = card(dashboard, Color.WHITE);
        text(card, "Activa el acceso de uso", 23, INK, true);
        text(card, "En Ajustes, busca Tiempo en Pantalla y permite el acceso de uso. "
                + "Android compartirá los tiempos de pantalla y de las apps. "
                + "Tu historial se guarda en este teléfono.", 16, MUTED, false);
        Button grant = button("Abrir ajustes");
        grant.setOnClickListener(view -> {
            Intent settings = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            try {
                startActivity(settings);
            } catch (android.content.ActivityNotFoundException unavailable) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        card.addView(grant);
        clock = null;
    }

    private void showDashboard() {
        dashboard.removeAllViews();
        LinearLayout hero = card(dashboard, BLUE);
        text(hero, "PANTALLA ACTIVA HOY", 14, Color.WHITE, true);
        clock = text(hero, "—", 42, Color.WHITE, true);
        clock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        status = text(hero, "Leyendo datos de Android…", 14, Color.WHITE, false);

        LinearLayout filters = new LinearLayout(this);
        filters.setPadding(0, dp(10), 0, 0);
        dashboard.addView(filters);
        todayButton = filter(filters, "Hoy", 1);
        weekButton = filter(filters, "7 días", 7);
        monthButton = filter(filters, "30 días", 30);
        LinearLayout historyCard = card(dashboard, Color.WHITE);
        text(historyCard, "Historial de pantalla", 22, INK, true);
        average = text(historyCard, "—", 14, MUTED, false);
        history = column();
        historyCard.addView(history);
        LinearLayout apps = card(dashboard, Color.WHITE);
        rankingTitle = text(apps, "Tiempo por aplicación", 22, INK, true);
        rankings = column();
        apps.addView(rankings);
        text(dashboard, "El total cuenta pantalla activa con el teléfono desbloqueado. "
                + "Los datos anteriores dependen del historial disponible en Android.", 13, MUTED, false);
    }

    private Button filter(LinearLayout filters, String label, int days) {
        Button button = button(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        filters.addView(button, params);
        button.setOnClickListener(view -> {
            period = days;
            selected = null;
            if (snapshot != null) render();
        });
        return button;
    }

    private void updateClock() {
        if (snapshot == null || clock == null) return;
        Usage.Day today = snapshot.days.get(snapshot.today);
        long total = today == null ? 0 : today.screen;
        if (snapshot.active) {
            total += Math.min(5000, Math.max(0, SystemClock.elapsedRealtime() - snapshot.measuredAt));
        }
        long seconds = total / 1000;
        clock.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600,
                seconds / 60 % 60, seconds % 60));
    }

    private void render() {
        if (clock == null) showDashboard();
        updateClock();
        status.setText("En vivo · sincronización cada 5 s");
        todayButton.setSelected(period == 1);
        weekButton.setSelected(period == 7);
        monthButton.setSelected(period == 30);
        for (Button button : new Button[]{todayButton, weekButton, monthButton}) {
            button.setBackground(shape(button.isSelected() ? BLUE : Color.WHITE, 14));
            button.setTextColor(button.isSelected() ? Color.WHITE : BLUE);
        }

        long max = 0;
        long sum = 0;
        int count = 0;
        for (int i = 0; i < period; i++) {
            Usage.Day day = snapshot.days.get(snapshot.today.minusDays(i));
            if (day == null && i > 0) continue;
            long time = day == null ? 0 : day.screen;
            max = Math.max(max, time);
            sum += time;
            count++;
        }
        average.setText(period == 1 ? "Toca un día para ver sus apps"
                : "Promedio diario · " + duration(sum / count)
                    + "  ·  " + count + " de " + period + " días");
        history.removeAllViews();
        for (int i = 0; i < period; i++) {
            LocalDate date = snapshot.today.minusDays(i);
            Usage.Day day = snapshot.days.get(date);
            if (i != 0 && day == null) continue;
            long time = day == null ? 0 : day.screen;
            LinearLayout row = column();
            row.setPadding(dp(6), dp(5), dp(6), dp(9));
            if (date.equals(selected)) row.setBackground(shape(PAPER, 10));
            history.addView(row);
            text(row, (i == 0 ? "Hoy" : DATE.format(date)) + "  ·  " + duration(time), 15, INK, true);
            LinearLayout bar = new LinearLayout(this);
            bar.setBackground(shape(PAPER, 5));
            row.addView(bar, new LinearLayout.LayoutParams(-1, dp(6)));
            if (max > 0 && time > 0) {
                float fraction = (float) time / max;
                View fill = new View(this);
                fill.setBackground(shape(BLUE, 5));
                bar.addView(fill, new LinearLayout.LayoutParams(0, -1, fraction));
                bar.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1 - fraction));
            }
            row.setOnClickListener(view -> {
                selected = date.equals(selected) ? null : date;
                render();
            });
        }
        renderApps();
    }

    private void renderApps() {
        rankings.removeAllViews();
        rankingTitle.setText("Tiempo por aplicación");
        String range = selected == null ? (period == 1 ? "Hoy" : "Últimos " + period + " días")
                : selected.equals(snapshot.today) ? "Hoy" : DATE.format(selected);
        text(rankings, range, 14, MUTED, false);
        Map<String, Long> totals = new HashMap<>();
        for (int i = 0; i < period; i++) {
            LocalDate date = snapshot.today.minusDays(i);
            if (selected != null && !selected.equals(date)) continue;
            Usage.Day day = snapshot.days.get(date);
            if (day != null) day.apps.forEach((pkg, time) -> totals.merge(pkg, time, Long::sum));
        }
        if (totals.isEmpty()) {
            text(rankings, "Aún no hay actividad registrada en este período.", 15, MUTED, false);
            return;
        }
        ArrayList<Map.Entry<String, Long>> entries = new ArrayList<>(totals.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        int position = 1;
        for (Map.Entry<String, Long> entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            rankings.addView(row);
            TextView name = text(row, position++ + ".  " + appName(entry.getKey()), 15, INK, true);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            text(row, duration(entry.getValue()), 14, MUTED, false);
        }
    }

    private String appName(String pkg) {
        return labels.computeIfAbsent(pkg, key -> {
            try {
                PackageManager pm = getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(key, 0)).toString();
            } catch (PackageManager.NameNotFoundException unavailable) {
                return key;
            }
        });
    }

    private static String duration(long millis) {
        long minutes = millis / 60000;
        if (minutes == 0) return millis == 0 ? "0 min" : "< 1 min";
        return minutes < 60 ? minutes + " min" : minutes / 60 + " h " + minutes % 60 + " min";
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private LinearLayout card(LinearLayout parent, int color) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(shape(color, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        parent.addView(card, params);
        return card;
    }

    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setPadding(0, dp(4), 0, dp(4));
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(text);
        return text;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(BLUE);
        button.setBackground(shape(PAPER, 14));
        return button;
    }
}
