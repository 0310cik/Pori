package kr.pori.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import com.google.android.gms.tasks.Tasks;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmark;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;

// Both detectors report pixels in the same upright, viewport-cropped analysis image.
final class FrameReader {
    static final class Line {
        final String text;
        final float[] corners;
        final float x, y, alongX, alongY, width, height;

        Line(String text, Rect box, Point[] points) {
            this.text = text;
            if (points != null && points.length == 4 && !points[0].equals(points[1])) {
                corners = new float[8];
                for (int i = 0; i < 4; i++) {
                    corners[2 * i] = points[i].x;
                    corners[2 * i + 1] = points[i].y;
                }
            } else {
                corners = new float[]{box.left, box.top, box.right, box.top,
                        box.right, box.bottom, box.left, box.bottom};
            }
            x = (corners[0] + corners[2] + corners[4] + corners[6]) / 4;
            y = (corners[1] + corners[3] + corners[5] + corners[7]) / 4;
            float dx = (corners[2] + corners[4] - corners[0] - corners[6]) / 2;
            float dy = (corners[3] + corners[5] - corners[1] - corners[7]) / 2;
            width = Math.max(1f, (float) Math.hypot(dx, dy));
            alongX = dx / width;
            alongY = dy / width;
            height = Math.max(2f, Math.abs((corners[7] - corners[1]) * alongX
                    - (corners[6] - corners[0]) * alongY));
        }

        // Distances are in pixels along/across the printed line, including tilted pages.
        float distance(float px, float py, boolean finger) {
            float dx = px - x, dy = py - y;
            float across = dy * alongX - dx * alongY - (finger ? .32f * height : 0);
            float beyondEnd = Math.max(0, Math.abs(dx * alongX + dy * alongY) - width / 2);
            if (beyondEnd > height * (finger ? 1.5f : .55f)
                    || Math.abs(across) > height * (finger ? 1.32f : .82f)) {
                return Float.POSITIVE_INFINITY;
            }
            return (float) Math.hypot(beyondEnd, across);
        }

        boolean tracks(Line other) {
            if (other == null) return false;
            float dx = other.x - x, dy = other.y - y;
            return Math.abs(alongX * other.alongX + alongY * other.alongY) > .9f
                    && Math.abs(dy * alongX - dx * alongY) < .66f * Math.min(height, other.height) + 2
                    && Math.abs(dx * alongX + dy * alongY)
                        < (width + other.width) / 2 - .15f * Math.min(width, other.width);
        }
    }

    enum HandState { LANDMARKS, CONTOUR, SEARCHING, CONTOUR_ONLY }

    static final class Hand {
        final PointF tip, joint;
        final float[] outline;
        final HandState state;
        Hand(HandState state, PointF tip, PointF joint, float[] outline) {
            this.state = state;
            this.tip = tip;
            this.joint = joint;
            this.outline = outline;
        }
    }

    static final class Frame {
        final List<Line> lines;
        final Hand hand;
        final long capturedAt;
        final boolean ocrAvailable;
        Frame(List<Line> lines, Hand hand, long capturedAt, boolean ocrAvailable) {
            this.lines = lines;
            this.hand = hand;
            this.capturedAt = capturedAt;
            this.ocrAvailable = ocrAvailable;
        }

        Line nearest(float x, float y, boolean finger) {
            Line best = null, second = null;
            float shortest = Float.POSITIVE_INFINITY, next = Float.POSITIVE_INFINITY;
            for (Line line : lines) {
                float distance = line.distance(x, y, finger);
                if (distance < shortest) {
                    second = best; next = shortest;
                    best = line; shortest = distance;
                } else if (distance < next) {
                    second = line; next = distance;
                }
            }
            if (finger && second != null && next - shortest < .18f * Math.min(best.height, second.height)) return null;
            return best;
        }

        Line match(Line previous) {
            Line best = null;
            float shortest = Float.POSITIVE_INFINITY;
            for (Line line : lines) {
                if (!line.tracks(previous)) continue;
                float d = (float) Math.hypot(line.x - previous.x, line.y - previous.y);
                if (d < shortest) { shortest = d; best = line; }
            }
            return best;
        }
    }

    private final Context context;
    // Each model is created, used and closed on its own serial worker.
    private boolean triedHands;
    private HandLandmarker hands;
    private TextRecognizer text;
    private final PartialTipDetector contours = new PartialTipDetector();
    private int[] colorPixels;

    FrameReader(Context context) { this.context = context.getApplicationContext(); }

    Hand hand(Bitmap upright, long timestampMs) {
        Hand model = modelHand(upright, timestampMs);
        if (model != null) {
            contours.forget();
            return model;
        }
        int w = upright.getWidth(), h = upright.getHeight();
        if (colorPixels == null || colorPixels.length != w * h) colorPixels = new int[w * h];
        upright.getPixels(colorPixels, 0, w, 0, 0, w, h);
        PartialTipDetector.Tip contour = contours.detect(colorPixels, w, h, timestampMs);
        if (contour != null) {
            return new Hand(HandState.CONTOUR, new PointF(contour.x, contour.y), null, contour.outline);
        }
        return new Hand(hands == null ? HandState.CONTOUR_ONLY : HandState.SEARCHING, null, null, null);
    }

    private Hand modelHand(Bitmap upright, long timestampMs) {
        try {
            if (!triedHands) {
                triedHands = true;
                hands = HandLandmarker.createFromOptions(context,
                        HandLandmarker.HandLandmarkerOptions.builder()
                                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                                .setRunningMode(RunningMode.VIDEO).setNumHands(1).build());
            }
            if (hands == null) return null;
            // MPImage.close recycles its backing bitmap. OCR keeps its own full-resolution input.
            int w = upright.getWidth(), h = upright.getHeight();
            float scale = Math.min(1f, 640f / Math.max(w, h));
            Bitmap small = Bitmap.createScaledBitmap(upright, Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), true);
            if (small == upright) small = upright.copy(Bitmap.Config.ARGB_8888, false);
            try (MPImage image = new BitmapImageBuilder(small).build()) {
                var found = hands.detectForVideo(image, timestampMs).landmarks();
                if (!found.isEmpty() && found.get(0).size() > HandLandmark.INDEX_FINGER_TIP) {
                    var tip = found.get(0).get(HandLandmark.INDEX_FINGER_TIP);
                    var joint = found.get(0).get(HandLandmark.INDEX_FINGER_DIP);
                    PointF point = inFrame(tip.x(), tip.y(), w, h);
                    if (point != null) {
                        return new Hand(HandState.LANDMARKS, point, inFrame(joint.x(), joint.y(), w, h), null);
                    }
                }
            } finally {
                if (!small.isRecycled()) small.recycle();
            }
        } catch (RuntimeException | LinkageError e) {
            // A failed model must not interrupt the independent contour or OCR paths.
        }
        return null;
    }

    private static PointF inFrame(float x, float y, int width, int height) {
        return x >= 0 && x < 1 && y >= 0 && y < 1 ? new PointF(x * width, y * height) : null;
    }

    Frame readLines(Bitmap upright, Hand hand, long capturedAt) {
        List<Line> lines = new ArrayList<>();
        try {
            if (text == null) text = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            Text result = Tasks.await(text.process(InputImage.fromBitmap(upright, 0)));
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    Rect box = line.getBoundingBox();
                    String value = line.getText().trim();
                    if (box != null && !box.isEmpty() && !value.isEmpty()) {
                        lines.add(new Line(value, box, line.getCornerPoints()));
                    }
                }
            }
            return new Frame(lines, hand, capturedAt, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | RuntimeException | LinkageError e) {
            // Report this stage on screen. Neither camera data nor recognized text goes into logs.
        }
        return new Frame(java.util.Collections.emptyList(), hand, capturedAt, false);
    }

    void closeHands() { colorPixels = null; if (hands != null) hands.close(); }
    void closeText() { if (text != null) text.close(); }
}
