package id.kaptenmeong.sehat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Kapten Meong Sehat: aplikasi web lokal (assets/index.html) di dalam WebView,
 * ditambah jembatan "AndroidSteps" ke sensor langkah (TYPE_STEP_COUNTER) milik HP.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_ACTIVITY = 7;
    private static final int REQ_GPS = 8;
    private WebView web;
    private SensorManager sensorManager;
    private Sensor stepCounter;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#FFF3DC"));
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        prefs = getSharedPreferences("langkah", Context.MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#FFF3DC"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new StepBridge(), "AndroidSteps");
        web.addJavascriptInterface(new GpsBridge(), "AndroidGps");
        web.loadUrl("file:///android_asset/index.html");

        if (stepCounter != null && !hasActivityPermission()) {
            askActivityPermission();
        }
    }

    private boolean hasActivityPermission() {
        if (Build.VERSION.SDK_INT < 29) return true;
        return checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askActivityPermission() {
        if (Build.VERSION.SDK_INT >= 29) {
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ_ACTIVITY);
        }
    }

    private void startListening() {
        if (sensorManager != null && stepCounter != null && hasActivityPermission()) {
            sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startListening();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_ACTIVITY) startListening();
        notifyPage();
    }

    private void notifyPage() {
        if (web != null) {
            web.post(() -> web.evaluateJavascript("window.__sensorChanged && window.__sensorChanged()", null));
        }
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /**
     * Sensor memberi total langkah sejak HP dinyalakan. Kita simpan "base" per hari:
     * langkah hari ini = nilai sensor - base.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_STEP_COUNTER) return;
        long value = (long) event.values[0];
        String today = today();
        String lastDate = prefs.getString("lastDate", "");
        long last = prefs.getLong("last", -1);
        long base = prefs.getLong("base", 0);

        if (!today.equals(lastDate)) {
            // Hari baru: langkah sejak pembacaan terakhir dihitung untuk hari ini.
            base = (last >= 0 && value >= last) ? last : 0;
        } else if (last >= 0 && value < last) {
            // HP habis restart: sensor mulai dari nol lagi, lanjutkan hitungan hari ini.
            long soFar = last - base;
            base = -soFar;
        }
        prefs.edit().putString("lastDate", today).putLong("last", value).putLong("base", base).apply();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.__back && window.__back()) ? 'y' : 'n'", value -> {
            if (value == null || !value.contains("y")) finish();
        });
    }

    private LocationManager locationManager() {
        return (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Dipanggil dari JavaScript sebagai window.AndroidGps */
    public class GpsBridge {
        @JavascriptInterface
        public boolean available() {
            LocationManager lm = locationManager();
            return lm != null && lm.getAllProviders().contains(LocationManager.GPS_PROVIDER);
        }

        @JavascriptInterface
        public boolean enabled() {
            LocationManager lm = locationManager();
            return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        }

        @JavascriptInterface
        public boolean permitted() {
            return hasLocationPermission();
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, REQ_GPS);
                } else {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GPS);
                }
            });
        }

        @JavascriptInterface
        public void openLocationSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void start(boolean reset) {
            if (!hasLocationPermission()) return;
            if (reset) GpsService.reset();
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, GpsService.class);
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                    else startService(i);
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void pause() {
            runOnUiThread(() -> stopService(new Intent(MainActivity.this, GpsService.class)));
        }

        @JavascriptInterface
        public String state() {
            return GpsService.json();
        }
    }

    /** Dipanggil dari JavaScript sebagai window.AndroidSteps */
    public class StepBridge {
        @JavascriptInterface
        public boolean available() {
            return stepCounter != null;
        }

        @JavascriptInterface
        public boolean permitted() {
            return hasActivityPermission();
        }

        @JavascriptInterface
        public int todaySteps() {
            if (!today().equals(prefs.getString("lastDate", ""))) return 0;
            long steps = prefs.getLong("last", 0) - prefs.getLong("base", 0);
            return (int) Math.max(0, steps);
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(MainActivity.this::askActivityPermission);
        }
    }
}
