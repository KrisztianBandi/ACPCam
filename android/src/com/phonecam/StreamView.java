package com.phonecam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/** Full-screen live game view. Never stretches: either covers the screen (crop) or fits it (bars). */
public class StreamView extends View {
    private final Object lock = new Object();
    private Bitmap current, pending, spare;
    public volatile boolean fit = false;
    public volatile boolean doubleTapExit = true;
    public Runnable exitCallback;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix m = new Matrix();
    private final GestureDetector gd;

    public StreamView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (doubleTapExit && exitCallback != null) exitCallback.run();
                return true;
            }
        });
    }

    /** Called from the network thread with one JPEG. Decodes into a recycled bitmap. */
    public void pushJpeg(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        Bitmap target;
        synchronized (lock) { target = spare; spare = null; }
        Bitmap bmp = null;
        if (target != null) {
            o.inBitmap = target;
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o); } catch (Exception e) { bmp = null; }
        }
        if (bmp == null) {
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inMutable = true;
            bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        }
        if (bmp == null) return;
        synchronized (lock) {
            if (pending != null && pending != bmp) spare = pending;
            pending = bmp;
        }
        postInvalidate();
    }

    public void clear() {
        synchronized (lock) { current = null; pending = null; spare = null; }
        postInvalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) { gd.onTouchEvent(e); return true; }

    @Override protected void onDraw(Canvas c) {
        Bitmap b;
        synchronized (lock) {
            if (pending != null) {
                if (current != null && spare == null) spare = current;
                current = pending; pending = null;
            }
            b = current;
        }
        if (b == null) return;
        float vw = getWidth(), vh = getHeight(), bw = b.getWidth(), bh = b.getHeight();
        float s = fit ? Math.min(vw / bw, vh / bh) : Math.max(vw / bw, vh / bh);
        m.reset();
        m.postScale(s, s);
        m.postTranslate((vw - bw * s) / 2f, (vh - bh * s) / 2f);
        c.drawBitmap(b, m, paint);
    }
}
