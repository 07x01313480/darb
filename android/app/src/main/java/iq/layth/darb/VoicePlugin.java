package iq.layth.darb;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spoken guidance through Android text-to-speech.
 *
 * Compared with a generic TTS plugin it: waits for the engine to start (and restarts it if it failed at app
 * start, e.g. a car head unit whose TTS service comes up late); speaks as navigation guidance and asks other
 * audio (music) to duck; resolves as soon as speech starts and rejects with a specific code when it can't
 * (NO_ENGINE, STARTING_TIMEOUT, LANG, LANG_MISSING_DATA, SPEAK_FAILED, SPEAK_ERROR, NO_START); and reports
 * diagnostics (engine, Arabic support, media volume) for the Test voice button.
 *
 * All state is touched on the main thread; TTS callbacks are posted there.
 */
@CapacitorPlugin(name = "Voice")
public class VoicePlugin extends Plugin {

    private static final int STARTING = 1, OK = 0, FAILED = -1;
    private static final long INIT_TIMEOUT_MS = 10000, START_TIMEOUT_MS = 8000, REINIT_GAP_MS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private int status = FAILED;
    private long initAt;
    private int initGen;
    private String lastError = "";
    private final List<PluginCall> waiting = new ArrayList<>();
    private final Map<String, PluginCall> pending = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private int seq;
    /** Engine package asked for by the page ("" = system default). */
    private String engineReq = "";
    /** How the last successful speak() call was made, for diagnostics. */
    private String okMode = "";
    /** Calls already retried once after restarting the engine. */
    private final Map<PluginCall, Boolean> retried = new IdentityHashMap<>();
    private AudioFocusRequest focusReq;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {};
    private boolean focused;

    @Override
    public void load() {
        main.post(() -> initTts(engineReq));
    }

    private void initTts(String engine) {
        engineReq = engine == null ? "" : engine;
        if (tts != null) try { tts.shutdown(); } catch (Exception ignored) {}
        tts = null;
        status = STARTING;
        initAt = System.currentTimeMillis();
        final int gen = ++initGen;
        try {
            TextToSpeech.OnInitListener l = st -> main.post(() -> onInit(gen, st));
            tts = engineReq.isEmpty() ? new TextToSpeech(getContext(), l) : new TextToSpeech(getContext(), l, engineReq);
        } catch (Exception e) {
            lastError = "engine: " + e.getMessage();
            onInit(gen, TextToSpeech.ERROR);
            return;
        }
        main.postDelayed(() -> {
            if (gen == initGen && status == STARTING) {
                status = FAILED;
                lastError = "engine did not start";
                rejectWaiting("voice engine did not start", "STARTING_TIMEOUT");
            }
        }, INIT_TIMEOUT_MS);
    }

    private void onInit(int gen, int st) {
        if (gen != initGen) return;
        if (st != TextToSpeech.SUCCESS || tts == null) {
            status = FAILED;
            if (lastError.isEmpty()) lastError = "engine init failed (" + st + ")";
            rejectWaiting("no working text-to-speech engine", "NO_ENGINE");
            return;
        }
        status = OK;
        lastError = "";
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { main.post(() -> started(id)); }
            @Override public void onDone(String id) { main.post(() -> finished(id, null)); }
            @Override public void onError(String id) { main.post(() -> finished(id, "SPEAK_ERROR")); }
            @Override public void onError(String id, int code) { main.post(() -> finished(id, "SPEAK_ERROR " + code)); }
            @Override public void onStop(String id, boolean interrupted) { main.post(() -> finished(id, null)); }
        });
        List<PluginCall> calls = new ArrayList<>(waiting);
        waiting.clear();
        for (PluginCall c : calls) doSpeak(c, retried.remove(c) != null);
    }

    private void rejectWaiting(String msg, String code) {
        List<PluginCall> calls = new ArrayList<>(waiting);
        waiting.clear();
        for (PluginCall c : calls) { retried.remove(c); c.reject(msg, code); }
    }

    @PluginMethod
    public void speak(PluginCall call) {
        main.post(() -> {
            String text = call.getString("text", "");
            if (text == null || text.trim().isEmpty()) { call.resolve(); return; }
            String engine = call.getString("engine", "");
            if (engine == null) engine = "";
            if (!engine.equals(engineReq)) { waiting.add(call); flush(); initTts(engine); return; }
            if (status == OK) { doSpeak(call, false); return; }
            waiting.add(call);
            // Failed earlier (engine not ready when the app started, or just installed): try again.
            if (status == FAILED && System.currentTimeMillis() - initAt > REINIT_GAP_MS) initTts(engineReq);
            else if (status == FAILED) rejectWaiting("no working text-to-speech engine", "NO_ENGINE");
        });
    }

    private void doSpeak(PluginCall call, boolean afterRestart) {
        String text = call.getString("text", "");
        String lang = call.getString("lang", "en-US");
        boolean queue = Boolean.TRUE.equals(call.getBoolean("queue", false));
        boolean media = "media".equals(call.getString("channel", "nav"));
        Locale loc = Locale.forLanguageTag(lang);
        int av;
        try { av = tts.isLanguageAvailable(loc); } catch (Exception e) { av = TextToSpeech.LANG_NOT_SUPPORTED; }
        if (av == TextToSpeech.LANG_MISSING_DATA) { call.reject("voice data for " + lang + " is not installed", "LANG_MISSING_DATA"); return; }
        if (av < TextToSpeech.LANG_AVAILABLE) { call.reject("language " + lang + " is not supported", "LANG"); return; }
        try { tts.setLanguage(loc); } catch (Exception ignored) {}
        AudioAttributes attrs = attributes(media);
        if (!queue) flush();
        String id = "u" + (++seq);
        pending.put(id, call);
        active.add(id);
        requestFocus(attrs);
        int mode = queue ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH;
        // Some head-unit engines refuse a request that a phone engine accepts, so try progressively plainer ones.
        int r = TextToSpeech.ERROR;
        try { tts.setAudioAttributes(attrs); } catch (Exception ignored) {}
        try {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
            r = tts.speak(text, mode, params, id);
            if (r == TextToSpeech.SUCCESS) okMode = media ? "media" : "nav";
        } catch (Exception ignored) {}
        if (r != TextToSpeech.SUCCESS && !media) {
            try {
                tts.setAudioAttributes(attributes(true));
                Bundle params = new Bundle();
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
                r = tts.speak(text, mode, params, id);
                if (r == TextToSpeech.SUCCESS) okMode = "media (fallback)";
            } catch (Exception ignored) {}
        }
        if (r != TextToSpeech.SUCCESS) {
            try {
                HashMap<String, String> legacy = new HashMap<>();
                legacy.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
                legacy.put(TextToSpeech.Engine.KEY_PARAM_STREAM, String.valueOf(AudioManager.STREAM_MUSIC));
                r = tts.speak(text, mode, legacy);
                if (r == TextToSpeech.SUCCESS) okMode = "legacy";
            } catch (Exception ignored) {}
        }
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id);
            active.remove(id);
            releaseFocusIfIdle();
            if (!afterRestart) {
                // The connection to the engine may have dropped since it started: restart it and try once more.
                retried.put(call, Boolean.TRUE);
                waiting.add(call);
                initTts(engineReq);
                return;
            }
            lastError = "speak() failed" + (engineReq.isEmpty() ? "" : " (" + engineReq + ")");
            call.reject("the voice engine refused to speak", "SPEAK_FAILED");
            return;
        }
        main.postDelayed(() -> {
            PluginCall c = pending.remove(id);
            if (c == null) return;
            active.remove(id);
            releaseFocusIfIdle();
            lastError = "engine did not start speaking";
            c.reject("the voice engine did not respond", "NO_START");
        }, START_TIMEOUT_MS + (queue ? 20000 : 0));
    }

    private AudioAttributes attributes(boolean media) {
        return new AudioAttributes.Builder()
            .setUsage(media ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    }

    private void started(String id) {
        PluginCall c = pending.remove(id);
        if (c != null) c.resolve();
    }

    private void finished(String id, String error) {
        PluginCall c = pending.remove(id);
        if (c != null) {
            if (error == null) c.resolve();
            else { lastError = error; c.reject("the voice engine reported an error", error); }
        }
        active.remove(id);
        releaseFocusIfIdle();
    }

    /** Interrupts current speech; calls that hadn't started yet resolve (they were superseded, not failed). */
    private void flush() {
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        for (PluginCall c : pending.values()) c.resolve();
        pending.clear();
        active.clear();
    }

    private AudioManager audio() { return (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE); }

    private void requestFocus(AudioAttributes attrs) {
        AudioManager am = audio();
        if (am == null || focused) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener).build();
                am.requestAudioFocus(focusReq);
            } else {
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
            focused = true;
        } catch (Exception ignored) {}
    }

    private void releaseFocusIfIdle() {
        if (!active.isEmpty() || !focused) return;
        AudioManager am = audio();
        focused = false;
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
            else am.abandonAudioFocus(focusListener);
        } catch (Exception ignored) {}
    }

    @PluginMethod
    public void stop(PluginCall call) {
        main.post(() -> {
            flush();
            releaseFocusIfIdle();
            call.resolve();
        });
    }

    /** Diagnostics for Settings → Test voice. */
    @PluginMethod
    public void info(PluginCall call) {
        main.post(() -> {
            JSObject o = new JSObject();
            o.put("status", status == OK ? "ok" : status == STARTING ? "starting" : "failed");
            o.put("engineReq", engineReq);
            if (!okMode.isEmpty()) o.put("mode", okMode);
            if (!lastError.isEmpty()) o.put("error", lastError);
            JSArray engines = new JSArray();
            PackageManager pm = getContext().getPackageManager();
            try {
                for (ResolveInfo ri : pm.queryIntentServices(new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)) {
                    JSObject e = new JSObject();
                    e.put("name", ri.serviceInfo.packageName);
                    e.put("label", String.valueOf(ri.loadLabel(pm)));
                    engines.put(e);
                }
            } catch (Exception ignored) {}
            o.put("engines", engines);
            if (tts != null && status == OK) {
                try { o.put("engine", tts.getDefaultEngine()); } catch (Exception ignored) {}
                o.put("ar", langState("ar-SA"));
                o.put("en", langState("en-US"));
                try {
                    int n = 0;
                    Set<Voice> vs = tts.getVoices();
                    if (vs != null) for (Voice v : vs) if ("ar".equals(v.getLocale().getLanguage())) n++;
                    o.put("arVoices", n);
                } catch (Exception ignored) {}
            }
            AudioManager am = audio();
            if (am != null) {
                o.put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC));
                o.put("volumeMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            }
            call.resolve(o);
        });
    }

    private String langState(String tag) {
        try {
            int av = tts.isLanguageAvailable(Locale.forLanguageTag(tag));
            return av >= TextToSpeech.LANG_AVAILABLE ? "yes" : av == TextToSpeech.LANG_MISSING_DATA ? "missing-data" : "no";
        } catch (Exception e) { return "unknown"; }
    }

    /**
     * Opens the most useful system screen: what = "install" (voice data for the current engine),
     * "engine" (get a TTS engine: Google Speech Services) or "settings" (text-to-speech settings).
     */
    @PluginMethod
    public void openSettings(PluginCall call) {
        String what = call.getString("what", "settings");
        List<Intent> tries = new ArrayList<>();
        String engine = null;
        try { if (tts != null && status == OK) engine = tts.getDefaultEngine(); } catch (Exception ignored) {}
        Intent install = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
        if (engine != null) install.setPackage(engine);
        Intent ttsSettings = new Intent("com.android.settings.TTS_SETTINGS");
        Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts"));
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts"));
        if ("install".equals(what)) { tries.add(install); tries.add(ttsSettings); }
        else if ("engine".equals(what)) { tries.add(market); tries.add(ttsSettings); tries.add(web); }
        else { tries.add(ttsSettings); tries.add(install); }
        tries.add(new Intent(Settings.ACTION_SETTINGS));
        String[] names = { "install", "tts", "market", "web", "settings" };
        for (Intent i : tries) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
                JSObject o = new JSObject();
                o.put("opened", i == install ? names[0] : i == ttsSettings ? names[1] : i == market ? names[2] : i == web ? names[3] : names[4]);
                call.resolve(o);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {}
        }
        call.reject("no settings screen available", "NO_ACTIVITY");
    }

    @Override
    protected void handleOnDestroy() {
        main.removeCallbacksAndMessages(null);
        if (tts != null) try { tts.shutdown(); } catch (Exception ignored) {}
        tts = null;
    }
}
