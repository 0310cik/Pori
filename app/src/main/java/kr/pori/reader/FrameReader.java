package kr.pori.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import com.google.android.gms.tasks.Tasks;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;

// Called only by the analysis executor. All geometry is normalized to the visible preview.
final class FrameReader implements AutoCloseable {
    static final class Line {
        final String text;
        final RectF box;
        Line(String text, RectF box) { this.text = text; this.box = box; }

        boolean tracks(Line other) {
            if (other == null) return false;
            float h = Math.max(box.height(), other.box.height());
            boolean near = Math.abs(box.centerY() - other.box.centerY()) < h * .74f
                && box.left < other.box.right && other.box.left < box.right;
            return near;
        }
    }

    static final class Frame {
        final List<Line> lines = new ArrayList<>();
        PointF tip;
        boolean handAvailable;
        Line nearest(float x, float y, boolean pointing) {
            Line best = null;
            float shortest = Float.MAX_VALUE;
            for (Line line : lines) {
                RectF r = line.box;
                float h = r.height();
                float dx = Math.max(0, Math.max(r.left - x, x - r.right));
                // Users point just below the baseline to keep the print visible.
                float aimY = pointing ? y - .58f * h : y;
                float dy = Math.abs(aimY - r.centerY());
                if (dx > .035f || dy > h * (pointing ? 1.35f : .85f)) continue;
                float score = dy / h + 12 * dx;
                if (score < shortest) { shortest = score; best = line; }
            }
            return best;
        }
    }

    private final TextRecognizer text = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
    private HandLandmarker hands;
    FrameReader(Context context) {
        try {
            hands = HandLandmarker.createFromOptions(context, HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setNumHands(1).build());
        } catch (RuntimeException | LinkageError unavailable) { hands = null; }
    }

    Frame analyze(Bitmap bitmap) throws Exception {
        Frame frame = new Frame();
        float w = bitmap.getWidth(), h = bitmap.getHeight();
        Text result = Tasks.await(text.process(InputImage.fromBitmap(bitmap, 0)));
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox();
                if (r != null && !r.isEmpty() && !line.getText().trim().isEmpty())
                    frame.lines.add(new Line(line.getText(), new RectF(r.left/w, r.top/h, r.right/w, r.bottom/h)));
            }
        }

        if (hands != null) {
            MPImage input = new BitmapImageBuilder(bitmap).build();
            try {
                var detected = hands.detect(input).landmarks();
                frame.handAvailable = true;
                if (!detected.isEmpty()) {
                    var index = detected.get(0).get(8);
                    frame.tip = new PointF(index.x(), index.y());
                }
            } catch (RuntimeException failure) { frame.handAvailable = false; }
            finally { input.close(); }
        }
        return frame;
    }

    public void close() { if (hands != null) hands.close(); text.close(); }
}
