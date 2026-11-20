package id.kaptenmeong.sehat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.ActivityRecognition;
import com.google.android.gms.location.ActivityTransition;
import com.google.android.gms.location.ActivityTransitionEvent;
import com.google.android.gms.location.ActivityTransitionRequest;
import com.google.android.gms.location.ActivityTransitionResult;
import com.google.android.gms.location.DetectedActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Mendeteksi saat pengguna naik kendaraan (motor/mobil) atau sepeda lewat
 * Activity Recognition (Google Play Services), supaya langkah palsu dari
 * getaran kendaraan tidak ikut dihitung.
 */
public class VehicleReceiver extends BroadcastReceiver {

    private static final String ACTION = "id.kaptenmeong.sehat.TRANSITION";

    static boolean enabled(Context c) {
        return StepStore.prefs(c).getBoolean("vehOn", true);
    }

    static boolean available(Context c) {
        try {
            return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) == ConnectionResult.SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, VehicleReceiver.class);
        i.setAction(ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(c, 200, i, flags);
    }

    @SuppressLint("MissingPermission")
    static void register(Context c) {
        if (!available(c)) return;
        boolean perm = Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        try {
            if (!enabled(c) || !perm) {
                if (perm) ActivityRecognition.getClient(c).removeActivityTransitionUpdates(pending(c));
                return;
            }
            List<ActivityTransition> list = new ArrayList<>();
            int[] types = {DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE};
            for (int type : types) {
                list.add(new ActivityTransition.Builder().setActivityType(type)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build());
                list.add(new ActivityTransition.Builder().setActivityType(type)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build());
            }
            ActivityRecognition.getClient(c).requestActivityTransitionUpdates(new ActivityTransitionRequest(list), pending(c));
        } catch (Throwable ignored) { }
    }

    @Override
    public void onReceive(final Context c, Intent intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return;
        ActivityTransitionResult result = ActivityTransitionResult.extractResult(intent);
        if (result == null) return;
        final List<ActivityTransitionEvent> events = result.getTransitionEvents();
        final PendingResult pr = goAsync();
        // Ambil angka sensor terbaru dulu, baru catat awal/akhir perjalanan.
        StepStore.readOnce(c, v -> {
            handle(c, events);
            pr.finish();
        }, () -> {
            handle(c, events);
            pr.finish();
        });
    }

    private static void handle(Context c, List<ActivityTransitionEvent> events) {
        for (ActivityTransitionEvent e : events) {
            String type = e.getActivityType() == DetectedActivity.ON_BICYCLE ? "sepeda" : "kendaraan";
            if (e.getTransitionType() == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                StepStore.vehicleEnter(c, type);
            } else {
                StepStore.vehicleExit(c);
            }
        }
        MeongWidget.updateAll(c);
    }
}
