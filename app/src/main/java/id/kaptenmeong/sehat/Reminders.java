package id.kaptenmeong.sehat;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Pengingat (buka puasa, minum air, langkah malam, timbang mingguan)
 * dan pencatatan langkah berkala di latar belakang.
 */
public class Reminders extends BroadcastReceiver {

    private static final String CHANNEL = "pengingat";

    static SharedPreferences cfgPrefs(Context c) {
        return c.getSharedPreferences("pengingat", Context.MODE_PRIVATE);
    }

    static void configure(Context c, String json) {
        cfgPrefs(c).edit().putString("cfg", json).apply();
        scheduleAll(c);
    }

    static JSONObject conf(Context c) {
        try {
            return new JSONObject(cfgPrefs(c).getString("cfg", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONObject summary(Context c) {
        try {
            return new JSONObject(c.getSharedPreferences("store", Context.MODE_PRIVATE).getString("summary", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static PendingIntent pending(Context c, String type, int req) {
        Intent i = new Intent(c, Reminders.class);
        i.setAction("id.kaptenmeong.sehat.REMIND." + type);
        i.putExtra("type", type);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void set(Context c, String type, int req, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = pending(c, type, req);
        am.cancel(p);
        if (at <= 0) return;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        } catch (Exception e) {
            am.set(AlarmManager.RTC_WAKEUP, at, p);
        }
    }

    private static long nextAt(int hour, int minute) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 30000) cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTimeInMillis();
    }

    static void scheduleAll(Context c) {
        JSONObject j = conf(c);
        long now = System.currentTimeMillis();

        long fastEnd = j.optLong("fastEnd", 0);
        set(c, "fast", 101, j.optBoolean("fast", true) && fastEnd > now ? fastEnd : 0);

        long water = 0;
        if (j.optBoolean("water", true)) {
            int[] hours = {9, 11, 13, 15, 17, 19};
            long best = Long.MAX_VALUE;
            for (int h : hours) best = Math.min(best, nextAt(h, 0));
            water = best;
        }
        set(c, "water", 102, water);

        set(c, "steps", 103, j.optBoolean("steps", true) ? nextAt(19, 30) : 0);

        long weigh = 0;
        if (j.optBoolean("weigh", true)) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, 6);
            cal.set(Calendar.MINUTE, 30);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            int guard = 0;
            while ((cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY || cal.getTimeInMillis() <= now) && guard++ < 14) {
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
            weigh = cal.getTimeInMillis();
        }
        set(c, "weigh", 104, weigh);

        set(c, "stepsave", 105, now + 60L * 60L * 1000L);
        set(c, "stepnight", 106, nextAt(23, 56));
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            scheduleAll(c);
            return;
        }
        String type = intent.getStringExtra("type");
        if (type == null) return;

        if (type.equals("stepsave") || type.equals("stepnight")) {
            captureSteps(c, goAsync());
            scheduleAll(c);
            return;
        }

        JSONObject s = summary(c);
        boolean fresh = StepStore.today().equals(s.optString("date"));
        String name = s.optString("name", "");
        String hi = name.isEmpty() ? "" : ", " + name;

        switch (type) {
            case "fast":
                if (s.optLong("fastStart", 0) > 0) {
                    show(c, 1, "Puasa selesai! 🎉", "Target " + s.optInt("fastGoal", 16) + " jam tercapai" + hi
                            + ". Buka dengan makanan bergizi ya, meong!");
                }
                break;
            case "water": {
                int water = fresh ? s.optInt("water", 0) : 0;
                int goal = s.optInt("waterGoal", 8);
                if (water < goal) {
                    show(c, 2, "Waktunya minum air 💧", "Tong air kapal baru " + water + " dari " + goal
                            + " gelas. Ayo minum segelas" + hi + "!");
                }
                break;
            }
            case "steps": {
                int steps = Math.max(StepStore.todaySteps(c), fresh ? s.optInt("steps", 0) : 0);
                int goal = s.optInt("stepGoal", 8000);
                if (steps < goal) {
                    show(c, 3, "Ayo jalan sebentar 👣", "Masih kurang " + fmt(goal - steps)
                            + " langkah menuju pulau target. Kapten tunggu di dek!");
                }
                break;
            }
            case "weigh":
                show(c, 4, "Waktunya timbang ⚖️", "Catat berat badanmu minggu ini supaya peta harta karun terus maju.");
                break;
            default:
                break;
        }
        scheduleAll(c);
    }

    private static String fmt(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', '.');
    }

    static void show(Context c, int id, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Pengingat", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent p = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(p)
                .build();
        nm.notify(id, n);
    }

    /** Baca sensor langkah sebentar di latar belakang, lalu simpan. */
    private static void captureSteps(final Context c, final PendingResult pr) {
        final SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        boolean allowed = Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        if (sensor == null || !allowed) {
            pr.finish();
            return;
        }
        final boolean[] done = {false};
        final SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (done[0]) return;
                done[0] = true;
                StepStore.record(c, (long) e.values[0]);
                sm.unregisterListener(this);
                MeongWidget.updateAll(c);
                pr.finish();
            }

            @Override
            public void onAccuracyChanged(Sensor s, int a) { }
        };
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!done[0]) {
                done[0] = true;
                sm.unregisterListener(listener);
                pr.finish();
            }
        }, 6000);
    }
}
