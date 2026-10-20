package com.phonecam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Square "three lines" button icon (no text). */
public class MenuIcon extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    public MenuIcon(Context c) { super(c); p.setColor(0xFFE8EAF0); }
    public void setColor(int c) { p.setColor(c); invalidate(); }
    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), lw = w * 0.46f, th = Math.max(3f, h * 0.075f), gap = h * 0.17f;
        float x = (w - lw) / 2f, y = h / 2f - gap - th / 2f;
        for (int i = 0; i < 3; i++) {
            r.set(x, y + i * gap, x + lw, y + i * gap + th);
            c.drawRoundRect(r, th / 2f, th / 2f, p);
        }
    }
}
