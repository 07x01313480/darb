package iq.layth.darb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

/**
 * Foreground service that runs while Darb is navigating. Android stops the WebView's geolocation when the app
 * goes to the background (and limits background location for apps without a foreground service), which froze
 * guidance, voice and the car dashboard. This service reads GPS natively and hands each fix to
 * {@link NavBackgroundPlugin}, which forwards it to the page.
 *
 * Uses LocationManager, not Google Play services: Chinese-market BYD head units have no Play services.
 */
public class NavService extends Service implements LocationListener {

    /** Receives fixes; set by the plugin while navigation is active. */
    interface Sink { void fix(Location l); }

    static volatile Sink sink;
    static volatile NavService running;

    private static final String CHANNEL = "nav";
    private static final int NOTE_ID = 7300;
    /** Network fixes are only used when GPS has been silent this long (tunnels, garages). */
    private static final long GPS_STALE_MS = 10000;

    private LocationManager lm;
    private long lastGps;
    private String title = "Darb";
    private String text = "";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.getStringExtra("title") != null) title = intent.getStringExtra("title");
            if (intent.getStringExtra("text") != null) text = intent.getStringExtra("text");
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTE_ID, note());
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (lm == null) startUpdates();
        return START_NOT_STICKY;
    }

    /** Updates the notification text (the next instruction). */
    void setText(String t) {
        text = t == null ? "" : t;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) try { nm.notify(NOTE_ID, note()); } catch (Exception ignored) {}
    }

    private Notification note() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pi);
        if (text != null && !text.isEmpty()) b.setContentText(text);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void startUpdates() {
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        for (String p : new String[] { LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER }) {
            try {
                if (lm.getAllProviders().contains(p)) lm.requestLocationUpdates(p, 1000, 0, this, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) {}
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        long now = System.currentTimeMillis();
        if (LocationManager.GPS_PROVIDER.equals(l.getProvider())) lastGps = now;
        else if (now - lastGps < GPS_STALE_MS) return;
        Sink s = sink;
        if (s != null) s.fix(l);
    }

    // Abstract before API 30; Android calls them on older devices.
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The app was swiped away: the page that used these fixes is gone.
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (lm != null) try { lm.removeUpdates(this); } catch (Exception ignored) {}
        lm = null;
        if (running == this) running = null;
        super.onDestroy();
    }
}
