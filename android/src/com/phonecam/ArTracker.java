package com.phonecam;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;
import com.google.ar.core.Camera;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Runs the ARCore session on the GL thread (ARCore needs a GL context for the camera texture),
 * draws the camera preview and publishes the camera pose converted to a East/North/Up frame.
 */
public class ArTracker implements GLSurfaceView.Renderer {
    public final Object lock = new Object();
    public volatile int state = 0;            // 0 = no session, 1 = tracking, 2 = lost / initializing
    public volatile String failure = "";
    public volatile boolean previewOn = true;   // when false the camera image is not drawn (tracking keeps running)
    // published under lock:
    public long seq = 0;
    public final float[] pos = new float[3];      // metres, ENU (AR world)
    public final float[] right = new float[3];    // camera right axis, ENU
    public final float[] vel = new float[3];      // m/s, filtered
    public long poseNanos = 0;

    private volatile Session session;
    private int texId = -1, program = 0, posLoc, uvLoc, texLoc;
    private int width = 1, height = 1;
    private volatile boolean geometryDirty = true;
    private boolean uvDirty = true;
    private final float[] quadCoords = { -1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f };
    private final float[] uvCoords = new float[8];
    private FloatBuffer quadBuf, uvBuf;
    private boolean havePrev = false;
    private final float[] prevPos = new float[3];
    private long prevNanos = 0;

    public void setSession(Session s) { session = s; geometryDirty = true; uvDirty = true; }
    public Session getSession() { return session; }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.07f, 0.07f, 0.09f, 1f);
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texId = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        String vs = "attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord;"
                + "void main(){ gl_Position = a_Position; v_TexCoord = a_TexCoord; }";
        String fs = "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float; varying vec2 v_TexCoord; uniform samplerExternalOES sTexture;"
                + "void main(){ gl_FragColor = texture2D(sTexture, v_TexCoord); }";
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(program);
        posLoc = GLES20.glGetAttribLocation(program, "a_Position");
        uvLoc = GLES20.glGetAttribLocation(program, "a_TexCoord");
        texLoc = GLES20.glGetUniformLocation(program, "sTexture");

        quadBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadBuf.put(quadCoords); quadBuf.position(0);
        uvBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        uvDirty = true;
    }

    private static int compile(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        return sh;
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        width = w; height = h;
        geometryDirty = true;
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        Session s = session;
        if (s == null || texId < 0) return;
        try {
            if (geometryDirty) { s.setDisplayGeometry(Surface.ROTATION_0, width, height); geometryDirty = false; uvDirty = true; }
            s.setCameraTextureName(texId);
            Frame frame = s.update();
            if (frame.hasDisplayGeometryChanged() || uvDirty) {
                frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
                        Coordinates2d.TEXTURE_NORMALIZED, uvCoords);
                uvBuf.position(0); uvBuf.put(uvCoords); uvBuf.position(0);
                uvDirty = false;
            }
            if (previewOn && frame.getTimestamp() != 0) drawBackground();

            Camera cam = frame.getCamera();
            if (cam.getTrackingState() == TrackingState.TRACKING) {
                Pose p = cam.getDisplayOrientedPose();
                long now = System.nanoTime();
                float ex = p.tx(), ey = -p.tz(), ez = p.ty();          // AR (x,y,up) -> ENU (E=x, N=-z, U=y)
                float[] xa = p.getXAxis();
                synchronized (lock) {
                    if (havePrev) {
                        float dt = (now - prevNanos) * 1e-9f;
                        if (dt > 0.004f && dt < 0.25f) {
                            float vx = (ex - prevPos[0]) / dt, vy = (ey - prevPos[1]) / dt, vz = (ez - prevPos[2]) / dt;
                            vel[0] += (vx - vel[0]) * 0.5f; vel[1] += (vy - vel[1]) * 0.5f; vel[2] += (vz - vel[2]) * 0.5f;
                        }
                    } else { vel[0] = vel[1] = vel[2] = 0; }
                    prevPos[0] = ex; prevPos[1] = ey; prevPos[2] = ez; prevNanos = now; havePrev = true;
                    pos[0] = ex; pos[1] = ey; pos[2] = ez;
                    right[0] = xa[0]; right[1] = -xa[2]; right[2] = xa[1];
                    poseNanos = now;
                    seq++;
                }
                failure = "";
                state = 1;
            } else {
                havePrev = false;
                failure = String.valueOf(cam.getTrackingFailureReason());
                state = 2;
            }
        } catch (Exception e) {
            failure = e.getClass().getSimpleName();
            state = 2;
        }
    }

    private void drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(texLoc, 0);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadBuf);
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, uvBuf);
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glEnableVertexAttribArray(uvLoc);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(posLoc);
        GLES20.glDisableVertexAttribArray(uvLoc);
        GLES20.glDepthMask(true);
    }
}
