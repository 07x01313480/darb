package iq.layth.darb;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.webkit.WebView;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Keeps navigation running while Darb is in the background: starts {@link NavService} and forwards its GPS
 * fixes to the page as "fix" events ({lat, lng, acc, speed, head, t}).
 */
@CapacitorPlugin(name = "NavBackground")
public class NavBackgroundPlugin extends Plugin {

    @PluginMethod
    public void start(PluginCall call) {
        Context ctx = getContext();
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            call.reject("location permission not granted", "NO_PERMISSION");
            return;
        }
        // Android 13+: without this the service still runs, but its notification is hidden.
        Activity act = getActivity();
        if (Build.VERSION.SDK_INT >= 33 && act != null
            && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try { act.requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7301); } catch (Exception ignored) {}
        }
        NavService.sink = this::sendFix;
        // Keep the page's renderer process at normal priority while it is not visible.
        if (Build.VERSION.SDK_INT >= 26 && getBridge() != null && getBridge().getWebView() != null) {
            try { getBridge().getWebView().setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Exception ignored) {}
        }
        Intent i = new Intent(ctx, NavService.class)
            .putExtra("title", call.getString("title", "Darb"))
            .putExtra("text", call.getString("text", ""));
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            NavService.sink = null;
            call.reject("could not start: " + e.getMessage(), "START_FAILED");
            return;
        }
        call.resolve();
    }

    @PluginMethod
    public void update(PluginCall call) {
        NavService s = NavService.running;
        if (s != null) s.setText(call.getString("text", ""));
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopService();
        call.resolve();
    }

    private void stopService() {
        NavService.sink = null;
        try { getContext().stopService(new Intent(getContext(), NavService.class)); } catch (Exception ignored) {}
    }

    private void sendFix(Location l) {
        JSObject o = new JSObject();
        o.put("lat", l.getLatitude());
        o.put("lng", l.getLongitude());
        if (l.hasAccuracy()) o.put("acc", (double) l.getAccuracy());
        if (l.hasSpeed()) o.put("speed", (double) l.getSpeed());
        if (l.hasBearing()) o.put("head", (double) l.getBearing());
        // Wall-clock time, like the WebView's own fixes (car GPS time and system time can disagree).
        o.put("t", System.currentTimeMillis());
        notifyListeners("fix", o);
    }

    @Override
    protected void handleOnDestroy() {
        stopService();
    }
}
