package pf.brian.faito;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mise à jour intégrée : télécharge l'APK de la dernière Release GitHub
 * en envoyant la progression à la page, puis lance l'installateur Android.
 */
@CapacitorPlugin(name = "ApkUpdater")
public class ApkUpdaterPlugin extends Plugin {

    private File apkFile() {
        return new File(getContext().getCacheDir(), "update.apk");
    }

    @PluginMethod
    public void download(PluginCall call) {
        String url = call.getString("url");
        if (url == null) { call.reject("URL manquante"); return; }
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                // Suit les redirections GitHub -> serveur de fichiers
                String target = url;
                for (int i = 0; i < 6; i++) {
                    c = (HttpURLConnection) new URL(target).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", "Faito-Updater");
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        target = c.getHeaderField("Location");
                        c.disconnect();
                        continue;
                    }
                    if (code != 200) throw new Exception("HTTP " + code);
                    break;
                }
                long total = c.getContentLengthLong();
                File out = apkFile();
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    long done = 0, last = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 120) { last = now; emit(done, total); }
                    }
                    emit(done, total);
                }
                JSObject r = new JSObject();
                r.put("path", out.getAbsolutePath());
                call.resolve(r);
            } catch (Exception e) {
                call.reject("Téléchargement échoué : " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private void emit(long done, long total) {
        JSObject p = new JSObject();
        p.put("done", done);
        p.put("total", total);
        notifyListeners("progress", p);
    }

    @PluginMethod
    public void install(PluginCall call) {
        Context ctx = getContext();
        File f = apkFile();
        if (!f.exists()) { call.reject("Aucune mise à jour téléchargée"); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(s);
            JSObject r = new JSObject();
            r.put("needPermission", true);
            call.resolve(r);
            return;
        }
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        call.resolve(new JSObject());
    }

    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady = false;

    /** Lecture vocale (mode non-voyant). */
    @PluginMethod
    public void speak(PluginCall call) {
        final String text = call.getString("text", "");
        final float rate = call.getFloat("rate", 1f);
        if (tts == null) {
            tts = new android.speech.tts.TextToSpeech(getContext(), status -> {
                ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(java.util.Locale.FRENCH);
                    tts.setSpeechRate(rate);
                    tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "faito");
                }
            });
        } else if (ttsReady) {
            tts.setSpeechRate(rate);
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "faito");
        }
        call.resolve();
    }

    private android.hardware.SensorManager sm;
    private android.hardware.SensorEventListener sl;

    /** Capteur de lumière ambiante (lux). */
    @PluginMethod
    public void lightStart(PluginCall call) {
        sm = (android.hardware.SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        android.hardware.Sensor s = sm == null ? null : sm.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT);
        if (s == null) { call.reject("Pas de capteur de lumière"); return; }
        if (sl != null) sm.unregisterListener(sl);
        sl = new android.hardware.SensorEventListener() {
            @Override public void onSensorChanged(android.hardware.SensorEvent e) {
                JSObject o = new JSObject();
                o.put("lux", e.values[0]);
                notifyListeners("light", o);
            }
            @Override public void onAccuracyChanged(android.hardware.Sensor a, int b) {}
        };
        sm.registerListener(sl, s, android.hardware.SensorManager.SENSOR_DELAY_UI);
        call.resolve();
    }

    @PluginMethod
    public void lightStop(PluginCall call) {
        if (sm != null && sl != null) sm.unregisterListener(sl);
        sl = null;
        call.resolve();
    }

    /** Ouvre une adresse dans Chrome (utile pour le mode AR / WebXR). */
    @PluginMethod
    public void openExternal(PluginCall call) {
        String url = call.getString("url");
        if (url == null) { call.reject("URL manquante"); return; }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setPackage("com.android.chrome");
        try {
            getContext().startActivity(i);
        } catch (Exception e) {
            i.setPackage(null);
            getContext().startActivity(i);
        }
        call.resolve();
    }
}
