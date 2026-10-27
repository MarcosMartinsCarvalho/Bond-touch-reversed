package com.marco.bondnotifyhaptics;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;

/** Native, event-driven glass fallback: no blur passes, timers, or offscreen buffers. */
final class GlassStyle {
    final boolean dark;
    final int ink, muted, accent, base;
    private final float density;

    GlassStyle(Context context) {
        dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        ink = dark ? 0xFFF0F5F4 : 0xFF182D29;
        muted = dark ? 0xFFB2C4BF : 0xFF536A63;
        accent = dark ? 0xFF7DE3C3 : 0xFF096B52;
        base = dark ? 0xFF101716 : 0xFFF0F5F2;
        density = context.getResources().getDisplayMetrics().density;
    }

    Drawable surface(boolean modal) { return new Surface(modal, false); }
    Drawable backdrop() { return new Surface(false, true); }
    Drawable control() {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x307DE3C3 : 0x22096B52),
                surface(false), null);
    }

    private final class Surface extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final boolean modal, backdrop;
        private Shader fill, rim;
        Surface(boolean modal, boolean backdrop) { this.modal = modal; this.backdrop = backdrop; }
        @Override protected void onBoundsChange(android.graphics.Rect bounds) {
            rect.set(bounds);
            int[] colors = backdrop
                    ? (dark ? new int[]{0xFF102922, base, 0xFF29212B}
                            : new int[]{0xFFDCEFE7, base, 0xFFF1E6EC})
                    : (dark ? new int[]{modal ? 0xFF25332F : 0xC52C3B36, modal ? 0xFF19211F : 0xC51A2421}
                            : new int[]{modal ? 0xFFFAFDFC : 0xD9FFFFFF, modal ? 0xFFEEF4F0 : 0xBFEAF0ED});
            fill = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    colors, null, Shader.TileMode.CLAMP);
            rim = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{dark ? 0x80FFFFFF : 0xFFFFFFFF, dark ? 0x153F6D5C : 0x22304F41,
                            dark ? 0x35FFFFFF : 0x99FFFFFF}, null, Shader.TileMode.CLAMP);
        }
        @Override public void draw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(fill);
            if (backdrop) { canvas.drawRect(rect, paint); return; }
            RectF inset = new RectF(rect);
            inset.inset(density, density);
            float radius = 8 * density;
            canvas.drawRoundRect(inset, radius, radius, paint);
            paint.setShader(rim);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(density);
            canvas.drawRoundRect(inset, radius, radius, paint);
            paint.setShader(null);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
