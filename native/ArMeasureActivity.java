package pf.brian.faito;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Mesure AR native (ARCore) : un réticule au centre, « + Point » pose un point
 * sur la surface visée ; deux points = une mesure en cm. « Terminer » renvoie
 * les mesures à la page de l'app.
 */
public class ArMeasureActivity extends Activity implements GLSurfaceView.Renderer {

    private GLSurfaceView glView;
    private Overlay overlay;
    private TextView status;
    private Session session;
    private boolean installRequested = false;
    private final Background bg = new Background();
    private int viewW = 1, viewH = 1;
    private boolean geometryChanged = true;

    private final List<Anchor> anchors = new ArrayList<>();
    private volatile boolean addRequested = false;
    private final float[] view = new float[16], proj = new float[16], vp = new float[16];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);

        glView = new GLSurfaceView(this);
        glView.setPreserveEGLContextOnPause(true);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glView.setRenderer(this);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(glView);

        overlay = new Overlay(this);
        root.addView(overlay);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(17);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setShadowLayer(6, 0, 0, Color.BLACK);
        status.setGravity(Gravity.CENTER);
        status.setPadding(24, 48, 24, 0);
        status.setText("Démarrage de la réalité augmentée…");
        root.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(16, 16, 16, 56);
        bar.addView(btn("↶ Annuler", 0xCC10232A, v -> undo()));
        bar.addView(btn("＋ Point", 0xFF0A7480, v -> { addRequested = true; }));
        bar.addView(btn("Effacer", 0xCC10232A, v -> clearAll()));
        bar.addView(btn("Terminer", 0xFFE69F00, v -> finishWithResult()));
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        overlay.setOnClickListener(v -> addRequested = true);
        setContentView(root);
    }

    private Button btn(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setBackgroundColor(color);
        b.setMinHeight(140);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(8, 0, 8, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void undo() {
        synchronized (anchors) {
            if (!anchors.isEmpty()) anchors.remove(anchors.size() - 1).detach();
        }
    }

    private void clearAll() {
        synchronized (anchors) {
            for (Anchor a : anchors) a.detach();
            anchors.clear();
        }
    }

    private float[] measuresCm() {
        synchronized (anchors) {
            int n = anchors.size() / 2;
            float[] out = new float[n];
            for (int i = 0; i < n; i++) out[i] = dist(anchors.get(2 * i).getPose(), anchors.get(2 * i + 1).getPose()) * 100f;
            return out;
        }
    }

    private void finishWithResult() {
        Intent data = new Intent();
        data.putExtra("mesures", measuresCm());
        setResult(RESULT_OK, data);
        finish();
    }

    private void failAndFinish(String msg) {
        Intent data = new Intent();
        data.putExtra("erreur", msg);
        setResult(RESULT_CANCELED, data);
        finish();
    }

    static float dist(Pose a, Pose b) {
        float dx = a.tx() - b.tx(), dy = a.ty() - b.ty(), dz = a.tz() - b.tz();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true;
                    return;
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
                    return;
                }
                session = new Session(this);
                Config config = new Config(session);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) config.setDepthMode(Config.DepthMode.AUTOMATIC);
                session.configure(config);
            } catch (UnavailableUserDeclinedInstallationException e) {
                failAndFinish("Installation de « Services Google Play pour la RA » refusée");
                return;
            } catch (UnavailableDeviceNotCompatibleException e) {
                failAndFinish("Ce téléphone n'est pas compatible ARCore");
                return;
            } catch (Exception e) {
                failAndFinish("AR indisponible : " + e.getMessage());
                return;
            }
        }
        try {
            session.resume();
        } catch (Exception e) {
            failAndFinish("Caméra indisponible : " + e.getMessage());
            return;
        }
        glView.onResume();
        geometryChanged = true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED) failAndFinish("Autorisation caméra refusée");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (session != null) {
            glView.onPause();
            session.pause();
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) session.close();
        session = null;
        super.onDestroy();
    }

    /* ---------- Rendu OpenGL ---------- */

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        bg.create();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        viewW = w;
        viewH = h;
        geometryChanged = true;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (session == null) return;
        if (geometryChanged) {
            int rot = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            session.setDisplayGeometry(rot, viewW, viewH);
            geometryChanged = false;
        }
        session.setCameraTextureName(bg.tex);
        Frame frame;
        try {
            frame = session.update();
        } catch (Exception e) {
            return;
        }
        bg.draw(frame);
        Camera cam = frame.getCamera();
        if (cam.getTrackingState() != TrackingState.TRACKING) {
            TrackingFailureReason r = cam.getTrackingFailureReason();
            String msg = r == TrackingFailureReason.INSUFFICIENT_LIGHT ? "Trop sombre : allume la lumière"
                    : r == TrackingFailureReason.EXCESSIVE_MOTION ? "Bouge plus lentement"
                    : r == TrackingFailureReason.INSUFFICIENT_FEATURES ? "Vise une surface avec des détails (pas un mur uni)"
                    : "Bouge doucement le téléphone pour analyser la scène…";
            post(msg, null, null, false);
            return;
        }
        cam.getViewMatrix(view, 0);
        cam.getProjectionMatrix(proj, 0, 0.05f, 100f);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        HitResult hit = null;
        for (HitResult h : frame.hitTest(viewW / 2f, viewH / 2f)) {
            Trackable t = h.getTrackable();
            if ((t instanceof Plane && ((Plane) t).isPoseInPolygon(h.getHitPose()))
                    || t instanceof DepthPoint
                    || (t instanceof Point && ((Point) t).getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
                hit = h;
                break;
            }
        }
        if (addRequested) {
            addRequested = false;
            if (hit != null) {
                synchronized (anchors) { anchors.add(hit.createAnchor()); }
                Vibrator vb = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vb != null) vb.vibrate(30);
            }
        }
        List<float[]> pts = new ArrayList<>();
        List<Pose> poses = new ArrayList<>();
        synchronized (anchors) {
            for (Anchor a : anchors) {
                Pose p = a.getPose();
                poses.add(p);
                pts.add(project(p.tx(), p.ty(), p.tz()));
            }
        }
        float[] reticle = null;
        Pose hitPose = hit != null ? hit.getHitPose() : null;
        if (hitPose != null) reticle = project(hitPose.tx(), hitPose.ty(), hitPose.tz());
        List<String> labels = new ArrayList<>();
        for (int i = 0; i + 1 < poses.size(); i += 2) labels.add(fmt(dist(poses.get(i), poses.get(i + 1))));
        String live = null;
        if (poses.size() % 2 == 1 && hitPose != null) live = fmt(dist(poses.get(poses.size() - 1), hitPose));
        String msg = hit == null ? "Vise une surface : le cercle devient vert quand on peut poser un point"
                : poses.size() % 2 == 1 ? "Vise le 2e point puis touche « ＋ Point »" : "Touche « ＋ Point » pour poser le 1er point";
        overlay.set(pts, labels, reticle, live, hit != null);
        post(msg, null, null, true);
    }

    private float[] project(float x, float y, float z) {
        float[] in = {x, y, z, 1f}, out = new float[4];
        Matrix.multiplyMV(out, 0, vp, 0, in, 0);
        if (out[3] <= 0) return null;
        return new float[]{(out[0] / out[3] + 1f) / 2f * viewW, (1f - out[1] / out[3]) / 2f * viewH};
    }

    static String fmt(float m) {
        float cm = m * 100f;
        return cm < 100 ? String.format(Locale.FRANCE, "%.1f cm", cm) : String.format(Locale.FRANCE, "%.2f m", m);
    }

    private void post(String msg, Object a, Object b, boolean tracking) {
        runOnUiThread(() -> {
            status.setText(msg);
            if (!tracking) overlay.set(null, null, null, null, false);
            overlay.invalidate();
        });
    }

    /* ---------- Fond caméra (texture externe ARCore) ---------- */
    static class Background {
        int tex, prog, aPos, aTex;
        FloatBuffer quad, quadTex;
        private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};

        void create() {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            tex = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(QUAD).position(0);
            quadTex = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            String vs = "attribute vec4 a_Pos; attribute vec2 a_Tex; varying vec2 v_Tex; void main(){ gl_Position = a_Pos; v_Tex = a_Tex; }";
            String fs = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v_Tex; uniform samplerExternalOES u_Tex; void main(){ gl_FragColor = texture2D(u_Tex, v_Tex); }";
            prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs));
            GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glLinkProgram(prog);
            aPos = GLES20.glGetAttribLocation(prog, "a_Pos");
            aTex = GLES20.glGetAttribLocation(prog, "a_Tex");
        }

        static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }

        void draw(Frame frame) {
            if (frame.hasDisplayGeometryChanged()) {
                quad.position(0);
                quadTex.position(0);
                frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, quadTex);
            }
            if (frame.getTimestamp() == 0) return;
            quad.position(0);
            quadTex.position(0);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthMask(false);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
            GLES20.glUseProgram(prog);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPos);
            GLES20.glDisableVertexAttribArray(aTex);
            GLES20.glDepthMask(true);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }
    }

    /* ---------- Calque 2D : réticule, points, traits, distances ---------- */
    static class Overlay extends View {
        private List<float[]> pts;
        private List<String> labels;
        private float[] reticle;
        private String live;
        private boolean ok;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
                ring = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG), box = new Paint(Paint.ANTI_ALIAS_FLAG);

        Overlay(Context c) {
            super(c);
            line.setColor(0xFF13A9B8);
            line.setStrokeWidth(10);
            line.setStrokeCap(Paint.Cap.ROUND);
            dot.setColor(0xFFFFC928);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(7);
            txt.setColor(Color.WHITE);
            txt.setTextSize(46);
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            txt.setTextAlign(Paint.Align.CENTER);
            box.setColor(0xE60A7480);
        }

        synchronized void set(List<float[]> p, List<String> l, float[] r, String lv, boolean o) {
            pts = p; labels = l; reticle = r; live = lv; ok = o;
            postInvalidate();
        }

        private void label(Canvas c, String s, float x, float y) {
            float w = txt.measureText(s) + 36;
            c.drawRoundRect(new RectF(x - w / 2, y - 92, x + w / 2, y - 26), 20, 20, box);
            c.drawText(s, x, y - 43, txt);
        }

        @Override
        protected synchronized void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (pts != null) {
                for (int i = 0; i + 1 < pts.size(); i += 2) {
                    float[] a = pts.get(i), b = pts.get(i + 1);
                    if (a == null || b == null) continue;
                    c.drawLine(a[0], a[1], b[0], b[1], line);
                    if (labels != null && i / 2 < labels.size()) label(c, labels.get(i / 2), (a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
                }
                if (pts.size() % 2 == 1 && reticle != null && pts.get(pts.size() - 1) != null) {
                    float[] a = pts.get(pts.size() - 1);
                    c.drawLine(a[0], a[1], reticle[0], reticle[1], line);
                    if (live != null) label(c, live, (a[0] + reticle[0]) / 2, (a[1] + reticle[1]) / 2);
                }
                for (float[] p : pts) if (p != null) c.drawCircle(p[0], p[1], 18, dot);
            }
            ring.setColor(ok ? 0xFF22DD66 : Color.WHITE);
            c.drawCircle(cx, cy, 42, ring);
            c.drawCircle(cx, cy, 6, ring);
        }
    }
}
