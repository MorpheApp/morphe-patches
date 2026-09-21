package app.morphe.extension.youtube.series;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Density-independent toolbar glyphs, with the host foreground color. */
final class UiIcon extends Drawable {
    static final int MORE = 0,
            ADD = 1,
            BACK = 2,
            PLAY = 3,
            EXPAND = 4,
            CHECK = 5,
            SEARCH = 6,
            CLOSE = 7;
    private final int kind, color, size;

    UiIcon(int kind, int color, int size) {
        this.kind = kind;
        this.color = color;
        this.size = size;
        setBounds(0, 0, size, size);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        if (kind == MORE) {
            for (int y : new int[] {5, 12, 19}) canvas.drawCircle(12, y, 1.7f, p);
        } else if (kind == ADD) {
            canvas.drawLine(5, 12, 19, 12, p);
            canvas.drawLine(12, 5, 12, 19, p);
        } else if (kind == EXPAND) {
            canvas.drawLine(7, 10, 12, 15, p);
            canvas.drawLine(12, 15, 17, 10, p);
        } else if (kind == CHECK) {
            canvas.drawLine(5, 12, 10, 17, p);
            canvas.drawLine(10, 17, 20, 7, p);
        } else if (kind == CLOSE) {
            canvas.drawLine(6, 6, 18, 18, p);
            canvas.drawLine(6, 18, 18, 6, p);
        } else if (kind == SEARCH) {
            p.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(10.5f, 10.5f, 7.5f, p);
            canvas.drawLine(16, 16, 21, 21, p);
        } else if (kind == PLAY) {
            Path triangle = new Path();
            triangle.moveTo(8, 5);
            triangle.lineTo(19, 12);
            triangle.lineTo(8, 19);
            triangle.close();
            canvas.drawPath(triangle, p);
        } else {
            canvas.drawLine(5, 12, 20, 12, p);
            canvas.drawLine(5, 12, 12, 5, p);
            canvas.drawLine(5, 12, 12, 19, p);
        }
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(ColorFilter filter) {}

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }
}
