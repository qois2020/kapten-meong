package id.kaptenmeong.sehat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Layanan latar depan yang merekam rute GPS selama sesi jalan,
 * supaya tetap mencatat walau layar HP dimatikan.
 */
public class GpsService extends Service implements LocationListener {

    private static final String CHANNEL = "sesi_jalan";
    private static final int NOTIF_ID = 11;

    static volatile boolean running = false;
    private static double distance = 0;
    private static float speed = 0;
    private static float accuracy = -1;
    private static boolean fix = false;
    private static Location last = null;
    private static final ArrayList<double[]> points = new ArrayList<>();

    private LocationManager lm;

    static synchronized void reset() {
        distance = 0;
        speed = 0;
        accuracy = -1;
        fix = false;
        last = null;
        points.clear();
    }

    /** Status sesi dalam bentuk JSON untuk halaman web. */
    static synchronized String json() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "{\"run\":%b,\"m\":%.1f,\"spd\":%.2f,\"acc\":%.1f,\"fix\":%b,\"pts\":[",
                running, distance, speed, accuracy, fix));
        int n = points.size();
        int step = Math.max(1, n / 300);
        boolean first = true;
        for (int i = 0; i < n; i += step) {
            double[] p = points.get(i);
            if (!first) sb.append(',');
            sb.append(String.format(Locale.US, "[%.6f,%.6f]", p[0], p[1]));
            first = false;
        }
        if (n > 1 && (n - 1) % step != 0) {
            double[] p = points.get(n - 1);
            sb.append(String.format(Locale.US, ",[%.6f,%.6f]", p[0], p[1]));
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Sesi jalan", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("Kapten Meong sedang mencatat rute")
                .setContentText("Sesi jalan aktif · ketuk untuk membuka")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        synchronized (GpsService.class) {
            last = null; // sambungan baru setelah jeda: jangan hitung lompatan
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 2f, this, Looper.getMainLooper());
            running = true;
        } catch (Exception e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onLocationChanged(Location loc) {
        synchronized (GpsService.class) {
            fix = true;
            accuracy = loc.getAccuracy();
            if (loc.hasSpeed()) speed = loc.getSpeed();
            if (accuracy > 30) return; // sinyal terlalu kabur
            if (last == null) {
                last = loc;
                points.add(new double[]{loc.getLatitude(), loc.getLongitude()});
                return;
            }
            float d = last.distanceTo(loc);
            double dt = Math.max(1, (loc.getTime() - last.getTime()) / 1000.0);
            if (d / dt > 12) return; // lompatan tidak wajar (> 43 km/jam)
            if (d < Math.max(3, accuracy * 0.5)) return; // abaikan goyangan saat diam
            distance += d;
            last = loc;
            points.add(new double[]{loc.getLatitude(), loc.getLongitude()});
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onProviderEnabled(String provider) { }

    @Override
    public void onProviderDisabled(String provider) { }

    @Override
    public void onDestroy() {
        running = false;
        if (lm != null) {
            try {
                lm.removeUpdates(this);
            } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
