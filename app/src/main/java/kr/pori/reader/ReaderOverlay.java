package kr.pori.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;
import androidx.camera.view.PreviewView;
import androidx.camera.view.transform.CoordinateTransform;
import androidx.camera.view.transform.OutputTransform;

final class ReaderOverlay extends View {
    interface Pick { void at(float x, float y); }
    private final Pick pick;
    private final PreviewView preview;
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private PointF tip;
    private OutputTransform handSource, lineSource;
    private FrameReader.Line line;
    private boolean showTip = true;

    ReaderOverlay(Context context, PreviewView preview, Pick pick) {
        super(context);
        this.preview = preview;
        this.pick = pick;
        density = getResources().getDisplayMetrics().density;
        setContentDescription("카메라 화면. 원하는 글줄을 눌러 선택할 수 있어요.");
    }

    void hand(PointF tip, OutputTransform source) {
        this.tip = tip;
        handSource = source;
        invalidate();
    }
    void line(FrameReader.Line line, OutputTransform source) {
        this.line = line;
        lineSource = source;
        invalidate();
    }
    void showTip(boolean value) { showTip = value; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        OutputTransform target = preview.getOutputTransform();
        if (target == null) return;
        if (line != null && lineSource != null) {
            float[] points = line.corners.clone();
            new CoordinateTransform(lineSource, target).mapPoints(points);
            path.rewind();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < 8; i += 2) path.lineTo(points[i], points[i + 1]);
            path.close();
            pen.setStyle(Paint.Style.FILL);
            pen.setColor(0x55D4F399);
            canvas.drawPath(path, pen);
            pen.setStyle(Paint.Style.STROKE);
            pen.setColor(0xff184F40);
            pen.setStrokeWidth(3.5f * density);
            canvas.drawPath(path, pen);
            pen.setColor(0xffD4F399);
            pen.setStrokeWidth(1.6f * density);
            canvas.drawPath(path, pen);
        }
        if (showTip && tip != null && handSource != null) {
            float[] point = {tip.x, tip.y};
            new CoordinateTransform(handSource, target).mapPoints(point);
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(4.5f * density);
            pen.setColor(0xff183E33);
            canvas.drawCircle(point[0], point[1], 9 * density, pen);
            pen.setStrokeWidth(2.5f * density);
            pen.setColor(0xffD4F399);
            canvas.drawCircle(point[0], point[1], 9 * density, pen);
            pen.setStyle(Paint.Style.FILL);
            canvas.drawCircle(point[0], point[1], 2.5f * density, pen);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            performClick();
            OutputTransform target = preview.getOutputTransform();
            if (target != null && lineSource != null) {
                float[] point = {event.getX(), event.getY()};
                new CoordinateTransform(target, lineSource).mapPoints(point);
                pick.at(point[0], point[1]);
            } else {
                pick.at(Float.NaN, Float.NaN);
            }
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
