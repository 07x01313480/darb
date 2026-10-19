package iq.layth.darb;

import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.Locale;

/**
 * Car dashboard (instrument cluster) output for BYD DiLink head units.
 *
 * Turn arrows: BYD's built-in AMap adapter (com.byd.amapservice, or com.example.amapservice on DiLink 3)
 * turns AutoNavi "standard broadcast" intents into the cluster's guidance card, the same way BYD's own
 * navigation feeds it. Map: DiLink 4/5 publish the cluster's projection area as a presentation display
 * (name contains "XDJAScreenProjection"); we show a Presentation there with a second WebView that runs
 * index.html?cluster=1 and receives position/route updates from the main app.
 *
 * On phones and non-BYD devices nothing here has any effect: the adapter isn't installed and no such
 * display exists.
 */
@CapacitorPlugin(name = "Dashboard")
public class DashboardPlugin extends Plugin {

    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private static final String[] AMAP_PACKAGES = { "com.byd.amapservice", "com.example.amapservice" };
    private static final int KEY_GUIDANCE = 10001;
    private static final int KEY_STATE = 10019;
    private static final int STATE_NAV_STARTED = 8;
    private static final int STATE_NAV_ENDED = 9;
    private static final int FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000;
    private static final String CLUSTER_URL = "https://appassets.androidplatform.net/public/index.html?cluster=1";

    private final Handler main = new Handler(Looper.getMainLooper());
    private ClusterPresentation presentation;

    // ── Diagnostics ──

    @PluginMethod
    public void info(PluginCall call) {
        JSObject ret = new JSObject();
        JSArray displays = new JSArray();
        DisplayManager dm = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
        for (Display d : dm.getDisplays()) {
            Point p = new Point();
            d.getRealSize(p);
            JSObject o = new JSObject();
            o.put("id", d.getDisplayId());
            o.put("name", String.valueOf(d.getName()));
            o.put("w", p.x);
            o.put("h", p.y);
            o.put("presentation", (d.getFlags() & Display.FLAG_PRESENTATION) != 0);
            displays.put(o);
        }
        Display cluster = clusterDisplay();
        ret.put("displays", displays);
        ret.put("amap", amapPackage());
        ret.put("cluster", cluster == null ? null : cluster.getName());
        ret.put("mapShown", presentation != null);
        ret.put("sdk", Build.VERSION.SDK_INT);
        ret.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        call.resolve(ret);
    }

    private String amapPackage() {
        PackageManager pm = getContext().getPackageManager();
        for (String pkg : AMAP_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
                // try the next one
            }
        }
        return null;
    }

    /** The cluster projection display: full-map layer first, then the DiLink 4/5 base layer, then DiLink 3's. */
    private Display clusterDisplay() {
        DisplayManager dm = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
        Display best = null;
        int bestScore = 0;
        for (Display d : dm.getDisplays()) {
            String n = String.valueOf(d.getName());
            int score = 0;
            if (n.contains("XDJAScreenProjection_0")) score = 4;
            else if (n.contains("XDJAScreenProjection") && !n.contains("XDJAScreenProjection_1")) score = 3;
            else if (n.contains("XDJAScreenProjection_1")) score = 2;
            else if (n.toLowerCase(Locale.ROOT).contains("xdjavirtualsurface")) score = 1;
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    // ── Turn arrows (AutoNavi standard broadcast) ──

    private Intent amapIntent(String pkg, int keyType) {
        Intent i = new Intent(AMAP_ACTION);
        i.setPackage(pkg);
        i.addFlags(FLAG_RECEIVER_INCLUDE_BACKGROUND);
        i.putExtra("KEY_TYPE", keyType);
        i.putExtra("IS_BYD_MAP", true);
        i.putExtra("IS_BYD_BAIDU_MAP", false);
        return i;
    }

    @PluginMethod
    public void start(PluginCall call) {
        String pkg = amapPackage();
        if (pkg == null) {
            call.resolve(new JSObject().put("sent", false));
            return;
        }
        Intent i = amapIntent(pkg, KEY_STATE);
        i.putExtra("EXTRA_STATE", STATE_NAV_STARTED);
        getContext().sendBroadcast(i);
        call.resolve(new JSObject().put("sent", true).put("pkg", pkg));
    }

    @PluginMethod
    public void guidance(PluginCall call) {
        String pkg = amapPackage();
        if (pkg == null) {
            call.resolve(new JSObject().put("sent", false));
            return;
        }
        Intent i = amapIntent(pkg, KEY_GUIDANCE);
        i.putExtra("TYPE", 0);
        i.putExtra("EXTRA_STATE", 0);
        i.putExtra("EXTRA_IS_FOREGROUND", 0);
        i.putExtra("NEW_ICON", call.getInt("icon", 0));
        i.putExtra("ROUNG_ABOUT_NUM", call.getInt("roundabout", 0));
        i.putExtra("SEG_REMAIN_DIS", call.getInt("dist", -1));
        i.putExtra("NEXT_ROAD_NAME", call.getString("road", ""));
        i.putExtra("ROUTE_REMAIN_DIS", call.getInt("remainDist", -1));
        i.putExtra("ROUTE_REMAIN_TIME", call.getInt("remainTime", -1));
        // The DiLink 3 adapter shows these preformatted strings instead of the numbers above.
        putIfSet(i, "SEG_REMAIN_DIS_AUTO", call.getString("distText", ""));
        putIfSet(i, "ROUTE_REMAIN_DIS_AUTO", call.getString("remainDistText", ""));
        putIfSet(i, "ROUTE_REMAIN_TIME_AUTO", call.getString("remainTimeText", ""));
        putIfSet(i, "ETA_TEXT", call.getString("etaText", ""));
        getContext().sendBroadcast(i);
        call.resolve(new JSObject().put("sent", true).put("pkg", pkg));
    }

    private static void putIfSet(Intent i, String key, String value) {
        if (value != null && !value.isEmpty()) i.putExtra(key, value);
    }

    @PluginMethod
    public void end(PluginCall call) {
        String pkg = amapPackage();
        if (pkg != null) {
            Intent i = amapIntent(pkg, KEY_STATE);
            i.putExtra("EXTRA_STATE", STATE_NAV_ENDED);
            i.putExtra("EXTRA_IS_FOREGROUND", 1);
            i.putExtra("NEW_ICON", -1);
            i.putExtra("SEG_REMAIN_DIS", -1);
            i.putExtra("NEXT_ROAD_NAME", "");
            i.putExtra("ROUTE_REMAIN_DIS", -1);
            i.putExtra("ROUTE_REMAIN_TIME", -1);
            getContext().sendBroadcast(i);
        }
        call.resolve(new JSObject().put("sent", pkg != null));
    }

    // ── Map on the cluster (Presentation + WebView) ──

    @PluginMethod
    public void showMap(PluginCall call) {
        main.post(() -> {
            Display d = clusterDisplay();
            if (d == null) {
                call.reject("No dashboard display found", "NO_CLUSTER");
                return;
            }
            if (presentation != null && presentation.getDisplay().getDisplayId() == d.getDisplayId()) {
                call.resolve(new JSObject().put("display", d.getName()));
                return;
            }
            dismissMap();
            try {
                presentation = new ClusterPresentation(getActivity(), d);
                presentation.show();
                Point p = new Point();
                d.getRealSize(p);
                call.resolve(new JSObject().put("display", d.getName()).put("w", p.x).put("h", p.y));
            } catch (Exception e) {
                presentation = null;
                call.reject("Could not show the map on the dashboard: " + e.getMessage(), "SHOW_FAILED");
            }
        });
    }

    @PluginMethod
    public void hideMap(PluginCall call) {
        main.post(() -> {
            dismissMap();
            call.resolve();
        });
    }

    /** Pushes the app state (position, route, next turn, theme) to the dashboard map page. */
    @PluginMethod
    public void update(PluginCall call) {
        final String json = call.getData().toString();
        main.post(() -> {
            if (presentation != null) presentation.push(json);
        });
        call.resolve();
    }

    private void dismissMap() {
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Exception ignored) {
                // display may already be gone
            }
            presentation = null;
        }
    }

    @Override
    protected void handleOnDestroy() {
        main.post(this::dismissMap);
    }

    /** Serves the app's bundled web files (assets/public) to the dashboard WebView. */
    private static WebResourceResponse serveAsset(Context ctx, Uri uri) {
        if (!"appassets.androidplatform.net".equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith("/public/")) return null;
        String asset = path.substring(1);
        String mime = mimeOf(asset);
        String encoding = mime.startsWith("text/") || mime.endsWith("json") ? "utf-8" : null;
        try {
            InputStream in = ctx.getAssets().open(asset);
            return new WebResourceResponse(mime, encoding, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    private static class ClusterPresentation extends Presentation {
        private WebView web;
        private boolean ready;
        private String pending;

        ClusterPresentation(Context outer, Display display) {
            super(outer, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            final Context ctx = getContext();
            web = new WebView(ctx);
            web.setBackgroundColor(Color.BLACK);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    WebResourceResponse r = serveAsset(ctx, request.getUrl());
                    return r != null ? r : super.shouldInterceptRequest(view, request);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    ready = true;
                    if (pending != null) push(pending);
                }
            });
            setContentView(web);
            web.loadUrl(CLUSTER_URL);
        }

        void push(String json) {
            if (!ready || web == null) {
                pending = json;
                return;
            }
            pending = null;
            web.evaluateJavascript("window.darbCluster&&window.darbCluster(" + JSONObject.quote(json) + ")", null);
        }

        @Override
        protected void onStop() {
            if (web != null) {
                web.destroy();
                web = null;
            }
            super.onStop();
        }
    }
}
