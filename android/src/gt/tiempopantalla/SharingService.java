package gt.tiempopantalla;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SharingService extends Service {
    static volatile boolean running;
    private static final String CHANNEL = "live_usage";
    private ScheduledExecutorService worker;
    private final Map<String, String> names = new HashMap<>();

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null) return START_NOT_STICKY;
        String lease = intent.getStringExtra("lease");
        if (lease == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Uso compartido",
                NotificationManager.IMPORTANCE_LOW));
        int immutable = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, SocialActivity.class), immutable);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, SharingService.class).setAction("STOP"), immutable);
        Notification note = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Compartiendo tiempo en pantalla")
                .setContentText("Tus amigos pueden ver tus estadísticas de uso.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Pausar", stop).build())
                .build();
        startForeground(11, note);
        running = true;
        worker = Executors.newSingleThreadScheduledExecutor();
        String server = SocialApi.settings(this).getString("server", "");
        String token = SocialApi.settings(this).getString("token", "");
        worker.scheduleWithFixedDelay(() -> sync(server, token, lease), 0, 10, TimeUnit.SECONDS);
        return START_NOT_STICKY;
    }

    private void sync(String server, String token, String lease) {
        try {
            if (!Usage.allowed(this)) {
                SocialApi.request(server, token, "/usage/hide", new JSONObject());
                stopSelf();
                return;
            }
            Usage.Snapshot sample = Usage.read(this);
            if (sample == null || Thread.currentThread().isInterrupted()) return;
            JSONArray days = new JSONArray();
            for (int offset = 0; offset < Usage.HISTORY_DAYS; offset++) {
                LocalDate date = sample.today.minusDays(offset);
                Usage.Day day = sample.days.get(date);
                if (day == null && offset != 0) continue;
                JSONObject apps = new JSONObject();
                if (day != null) {
                    for (Map.Entry<String, Long> app : day.apps.entrySet()) {
                        String name = names.computeIfAbsent(app.getKey(), this::appName);
                        apps.put(name, app.getValue());
                    }
                }
                days.put(new JSONObject().put("date", date.toString())
                        .put("screen", day == null ? 0 : day.screen).put("apps", apps));
            }
            if (Thread.currentThread().isInterrupted()) return;
            SocialApi.request(server, token, "/usage", new JSONObject().put("lease", lease).put("days", days));
        } catch (SocialApi.Failure failure) {
            if (failure.status == 401 || failure.status == 409) stopSelf();
        } catch (Exception unavailable) {
            // Retry after a temporary network or device failure.
        }
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            String label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            String display = label + " · " + pkg;
            return display.substring(0, Math.min(300, display.length()));
        } catch (PackageManager.NameNotFoundException unavailable) {
            return pkg;
        }
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }
}
