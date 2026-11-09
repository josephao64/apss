package gt.tiempopantalla;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import static android.app.usage.UsageEvents.Event.*;

final class Usage {
    static final int HISTORY_DAYS = 30;

    static final class Day {
        long screen;
        final Map<String, Long> apps = new HashMap<>();
    }

    static final class Snapshot {
        final Map<LocalDate, Day> days = new HashMap<>();
        final LocalDate today;
        final long measuredAt = SystemClock.elapsedRealtime();
        boolean active;
        Snapshot(LocalDate today) { this.today = today; }
    }

    static boolean allowed(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private static long midnight(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static LocalDate date(long time, ZoneId zone) {
        return Instant.ofEpochMilli(time).atZone(zone).toLocalDate();
    }

    private static void add(Snapshot result, ZoneId zone, long start, long end,
                            Map<String, String> visible) {
        while (start < end) {
            LocalDate day = date(start, zone);
            long stop = Math.min(end, midnight(day.plusDays(1), zone));
            long time = stop - start;
            Day entry = result.days.computeIfAbsent(day, ignored -> new Day());
            entry.screen += time;
            for (String pkg : new java.util.HashSet<>(visible.values())) {
                entry.apps.merge(pkg, time, Long::sum);
            }
            start = stop;
        }
    }

    static synchronized Snapshot read(Context context) {
        if (!allowed(context)) return null;
        ZoneId zone = ZoneId.systemDefault();
        long now = System.currentTimeMillis();
        LocalDate today = date(now, zone);
        Snapshot result = new Snapshot(today);
        UsageEvents events = context.getSystemService(UsageStatsManager.class)
                .queryEvents(midnight(today.minusDays(4), zone), now);
        if (events == null) return null;

        boolean interactive = false;
        boolean unlocked = false;
        boolean keyguardKnown = false;
        long cursor = -1;
        LocalDate first = null;
        Map<String, String> visible = new HashMap<>();
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.getNextEvent(event)) {
            long time = event.getTimeStamp();
            if (cursor >= 0 && interactive && unlocked) add(result, zone, cursor, time, visible);
            cursor = time;
            if (first == null) first = date(time, zone);
            String pkg = event.getPackageName();
            String component = pkg + "/" + event.getClassName();
            switch (event.getEventType()) {
                case SCREEN_INTERACTIVE:
                    interactive = true;
                    break;
                case SCREEN_NON_INTERACTIVE:
                    interactive = false;
                    break;
                case KEYGUARD_SHOWN:
                    keyguardKnown = true;
                    unlocked = false;
                    break;
                case KEYGUARD_HIDDEN:
                    keyguardKnown = true;
                    unlocked = true;
                    break;
                case ACTIVITY_RESUMED:
                    if (interactive && !keyguardKnown) unlocked = true;
                    if (pkg != null) visible.put(component, pkg);
                    break;
                case ACTIVITY_PAUSED:
                case ACTIVITY_STOPPED:
                    visible.remove(component);
                    break;
                case DEVICE_SHUTDOWN:
                case DEVICE_STARTUP:
                    interactive = false;
                    unlocked = false;
                    keyguardKnown = false;
                    visible.clear();
                    break;
                default:
                    break;
            }
        }
        result.active = interactive && unlocked;
        if (result.active && cursor >= 0) add(result, zone, cursor, now, visible);

        SharedPreferences prefs = context.getSharedPreferences("daily_usage", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (int offset = 1; offset < HISTORY_DAYS; offset++) {
            LocalDate day = today.minusDays(offset);
            if (first != null && day.isAfter(first)) {
                Day record = result.days.computeIfAbsent(day, ignored -> new Day());
                JSONObject json = new JSONObject();
                try {
                    json.put("screen", record.screen);
                    json.put("apps", new JSONObject(record.apps));
                    String saved = json.toString();
                    if (!saved.equals(prefs.getString(day.toString(), null))) {
                        editor.putString(day.toString(), saved);
                    }
                } catch (org.json.JSONException ignored) {}
            } else {
                Day saved = load(prefs, day);
                if (saved != null) result.days.put(day, saved);
                else result.days.remove(day);
            }
        }
        for (String key : prefs.getAll().keySet()) {
            if (key.compareTo(today.minusDays(HISTORY_DAYS - 1).toString()) < 0) editor.remove(key);
        }
        editor.apply();
        return result;
    }

    private static Day load(SharedPreferences prefs, LocalDate date) {
        String stored = prefs.getString(date.toString(), null);
        if (stored == null) return null;
        try {
            JSONObject json = new JSONObject(stored);
            Day day = new Day();
            day.screen = json.getLong("screen");
            JSONObject apps = json.getJSONObject("apps");
            apps.keys().forEachRemaining(pkg -> day.apps.put(pkg, apps.optLong(pkg)));
            return day;
        } catch (org.json.JSONException ignored) {
            return null;
        }
    }
}
