package kr.pori.reader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Locates the exposed, rounded end of a single pointing finger on a page.
 * All output vertices are in the caller's upright image pixels. No palm or joint is inferred.
 */
final class PartialTipDetector {
    static final class Tip {
        final float x, y, width, outwardX, outwardY;
        final float[] outline;
        Tip(float x, float y, float width, float ux, float uy, float[] outline) {
            this.x = x; this.y = y; this.width = width;
            outwardX = ux; outwardY = uy; this.outline = outline;
        }
        boolean near(Tip other, float radii) {
            if (other == null || outwardX * other.outwardX + outwardY * other.outwardY < .55f) return false;
            float span = Math.max(width, other.width);
            return Math.min(width, other.width) > .5f * span
                    && Math.hypot(x - other.x, y - other.y) <= radii * span;
        }
    }

    private static final int SEARCH_SIZE = 448;
    private int width, height, step, cols, rows;
    private short[] red, blue;
    private byte[] strength, mask, luminance;
    private int[] integral;
    private final int[] brightnessBins = new int[32];
    private final TipPatch patch = new TipPatch();
    private long lastDirect;
    private boolean neutralScene, brightnessEdges;
    private int[] queue;
    private final int[] histogram = new int[64 * 64];
    private float[] low, high;
    private final List<Tip> proposals = new ArrayList<>();
    private Tip previous;
    private long lastSeen;
    private boolean confirmed;

    Tip detect(int[] pixels, int w, int h, long timeMs) {
        if (w < 16 || h < 16) return null;
        if (w != width || h != height) resize(w, h);
        if (timeMs - lastSeen > 380 || timeMs < lastSeen) forget();
        classify(pixels);
        proposals.clear();
        // Two contrast levels let a weak rim survive and separate a shadow joined to a finger.
        brightnessEdges = false;
        components(pixels, 11);
        components(pixels, 21);
        if (neutralScene && proposals.isEmpty()) {
            brightnessEdges = true;
            buildDensityMask();
            components(pixels, 1);
        }
        Tip observed = selectUnambiguous();
        if (observed == null && confirmed && timeMs > lastSeen && timeMs - lastDirect <= 180) {
            Tip match = patch.locate(pixels, w, h, previous);
            if (match != null) {
                brightnessEdges = true;
                float shift = refineRim(pixels, match.x, match.y, match.outwardX, match.outwardY, match.width);
                if (Float.isFinite(shift)) {
                    previous = new Tip(match.x + shift * match.outwardX, match.y + shift * match.outwardY,
                            match.width, match.outwardX, match.outwardY, null);
                    lastSeen = timeMs;
                    return previous;
                }
            }
        }
        if (observed == null) return null;
        boolean follows = observed.near(previous, confirmed ? 2.1f : 1.35f) && timeMs > lastSeen;
        if (follows && observed.near(previous, .20f)) {
            float x = .72f * observed.x + .28f * previous.x;
            float y = .72f * observed.y + .28f * previous.y;
            observed.outline[0] = x; observed.outline[1] = y;
            observed = new Tip(x, y, observed.width, observed.outwardX, observed.outwardY, observed.outline);
        }
        previous = observed;
        lastSeen = timeMs;
        lastDirect = timeMs;
        if (follows) patch.capture(pixels, w, h, observed);
        confirmed = follows;
        return confirmed ? observed : null;
    }

    void forget() { previous = null; confirmed = false; }

    private Tip selectUnambiguous() {
        if (proposals.size() == 1) return proposals.get(0);
        if (!confirmed) return null;
        Tip match = null;
        for (Tip tip : proposals) {
            if (tip.near(previous, 1.35f)) {
                if (match != null) return null;
                match = tip;
            }
        }
        return match;
    }

    private void resize(int w, int h) {
        width = w; height = h;
        step = Math.max(1, (Math.max(w, h) + SEARCH_SIZE - 1) / SEARCH_SIZE);
        cols = (w + step - 1) / step; rows = (h + step - 1) / step;
        int count = cols * rows;
        red = new short[count]; blue = new short[count];
        luminance = new byte[count];
        integral = new int[(cols + 1) * (rows + 1)];
        strength = new byte[count]; mask = new byte[count]; queue = new int[count];
        int diagonal = (int) Math.ceil(Math.hypot(cols, rows)) + 3;
        low = new float[diagonal]; high = new float[diagonal];
        forget();
    }

    private static int bin(int value) { return Math.max(0, Math.min(63, (value + 128) >> 2)); }

    private void classify(int[] pixels) {
        Arrays.fill(histogram, 0);
        Arrays.fill(brightnessBins, 0);
        for (int y = 0, i = 0; y < rows; y++) {
            int row = Math.min(height - 1, y * step + step / 2) * width;
            for (int x = 0; x < cols; x++, i++) {
                int pixel = pixels[row + Math.min(width - 1, x * step + step / 2)];
                int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
                int light = (r + 2 * g + b) / 4;
                luminance[i] = (byte) light;
                if (Math.max(r, Math.max(g,b)) - Math.min(r, Math.min(g,b)) < 40) brightnessBins[light >> 3]++;
                int sum = 100 + r + 2 * g + b;
                int rg = (r - g) * 512 / sum, bg = (b - g) * 512 / sum;
                red[i] = (short) rg; blue[i] = (short) bg;
                strength[i] = (byte) (r + g + b >= 64 && rg > -6 && rg < 103 && bg < 85 && bg > -91 ? 1 : 0);
                if (r + g + b > 150) histogram[bin(rg) * 64 + bin(bg)]++;
            }
        }
        int mode = 0;
        for (int i = 1; i < histogram.length; i++) if (histogram[i] > histogram[mode]) mode = i;
        int maxCoverage = 0;
        for (int i = 0; i < brightnessBins.length; i++) {
            int coverage = brightnessBins[i];
            if (i > 0) coverage += brightnessBins[i-1];
            if (i+1 < brightnessBins.length) coverage += brightnessBins[i+1];
            maxCoverage = Math.max(maxCoverage, coverage);
        }
        neutralScene = maxCoverage > .34f * strength.length;
        int paperR = (mode / 64) * 4 - 126, paperB = (mode % 64) * 4 - 126;
        for (int i = 0; i < strength.length; i++) {
            if (strength[i] == 0) continue;
            int dr = red[i] - paperR, db = blue[i] - paperB;
            // Opponent chroma relative to this scene, normalized for brightness. The mask
            // only proposes regions; their shape and actual foreground/background edges decide.
            int contrast = dr + db / 3;
            strength[i] = (byte) (dr > 1 ? Math.max(0, Math.min(120, contrast)) : 0);
        }
    }

    // A dominant light/dark surface gives a second, hue-independent silhouette.
    // Local coverage rejects narrow ink strokes before they join the finger boundary.
    private void buildDensityMask() {
        int mode = 0;
        for (int i=1; i<brightnessBins.length; i++) if (brightnessBins[i]>brightnessBins[mode]) mode=i;
        int paper = 8 * mode + 4;
        int pitch = cols + 1;
        for (int y=0; y<rows; y++) {
            int sum=0;
            for (int x=0; x<cols; x++) {
                int i=y*cols+x;
                if (Math.abs((luminance[i] & 255)-paper) >= 27) sum++;
                integral[(y+1)*pitch+x+1] = sum + integral[y*pitch+x+1];
            }
        }
        int radius = Math.max(2, Math.min(cols,rows)/95);
        for (int y=0; y<rows; y++) {
            int top=Math.max(0,y-radius), bottom=Math.min(rows,y+radius+1);
            for (int x=0; x<cols; x++) {
                int left=Math.max(0,x-radius), right=Math.min(cols,x+radius+1);
                int solid=integral[bottom*pitch+right]-integral[top*pitch+right]
                        -integral[bottom*pitch+left]+integral[top*pitch+left];
                strength[y*cols+x]=(byte)(solid*100 >= 68*(right-left)*(bottom-top) ? 1 : 0);
            }
        }
    }

    private void components(int[] pixels, int threshold) {
        for (int y = 0, i = 0; y < rows; y++) {
            int up = Math.max(0, y - 1) * cols, mid = y * cols, down = Math.min(rows - 1, y + 1) * cols;
            for (int x = 0; x < cols; x++, i++) {
                int a = Math.max(0, x - 1), b = Math.min(cols - 1, x + 1);
                int votes = votes(up, a, x, b, threshold) + votes(mid, a, x, b, threshold)
                        + votes(down, a, x, b, threshold);
                mask[i] = (byte) (votes >= 5 ? 1 : 0);
            }
        }
        for (int start = 0; start < mask.length; start++) {
            if (mask[start] == 0) continue;
            int count = 1;
            queue[0] = start; mask[start] = 0;
            for (int k = 0; k < count; k++) {
                int i = queue[k], x = i % cols;
                if (x != 0) count = enqueue(i - 1, count);
                if (x + 1 != cols) count = enqueue(i + 1, count);
                if (i >= cols) count = enqueue(i - cols, count);
                if (i + cols < mask.length) count = enqueue(i + cols, count);
            }
            if (count >= 32 && count < mask.length * .43f) {
                boolean isolatedSilhouette = threshold == 1 && !touchesBorder(count);
                inspectComponent(pixels, count, isolatedSilhouette);
            }
        }
    }

    private boolean touchesBorder(int count) {
        for (int k=0; k<count; k++) {
            int i=queue[k];
            if (i<cols || i>=mask.length-cols || i%cols==0 || i%cols==cols-1) return true;
        }
        return false;
    }

    private int votes(int row, int a, int x, int b, int threshold) {
        return (strength[row + a] >= threshold ? 1 : 0) + (strength[row + x] >= threshold ? 1 : 0)
                + (strength[row + b] >= threshold ? 1 : 0);
    }
    private int enqueue(int i, int count) {
        if (mask[i] != 0) { mask[i] = 0; queue[count++] = i; }
        return count;
    }

    private void inspectComponent(int[] pixels, int count, boolean isolatedSilhouette) {
        double sx = 0, sy = 0, xx = 0, xy = 0, yy = 0;
        for (int i = 0; i < count; i++) {
            int x = queue[i] % cols, y = queue[i] / cols;
            sx += x; sy += y; xx += x * x; xy += x * y; yy += y * y;
        }
        xx -= sx * sx / count; xy -= sx * sy / count; yy -= sy * sy / count;
        double angle = .5 * Math.atan2(2 * xy, xx - yy);
        float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle);
        float start = Float.POSITIVE_INFINITY, end = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            float s = ux * (queue[i] % cols) + uy * (queue[i] / cols);
            start = Math.min(start, s); end = Math.max(end, s);
        }
        float length = end - start + 1, meanWidth = count / length;
        if (meanWidth < Math.max(4f, Math.min(cols, rows) * .011f)
                || meanWidth > Math.min(cols, rows) * .48f
                || length < .96f * meanWidth || length > 11f * meanWidth) return;

        int last = Math.round(end - start);
        Arrays.fill(low, 0, last + 1, Float.POSITIVE_INFINITY);
        Arrays.fill(high, 0, last + 1, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < count; i++) {
            int x = queue[i] % cols, y = queue[i] / cols;
            int k = Math.round(ux * x + uy * y - start);
            float t = -uy * x + ux * y;
            low[k] = Math.min(low[k], t); high[k] = Math.max(high[k], t);
        }
        Tip a = cap(pixels, start, last, meanWidth, ux, uy, -1);
        Tip b = cap(pixels, start, last, meanWidth, ux, uy, 1);
        // An ellipse or a rounded printed bar has two plausible ends. Its polarity is unknown.
        if (a != null && b != null) return;
        Tip candidate = a != null ? a : b;
        if (candidate == null || (isolatedSilhouette && !hasInteriorDetail(pixels, candidate))) return;
        for (Tip known : proposals) if (candidate.near(known, .48f)) return;
        proposals.add(candidate);
    }

    // A page may hide the base, leaving a disconnected tip. Require interior shading
    // across several samples to avoid treating a solid printed stroke as that finger.
    private boolean hasInteriorDetail(int[] pixels, Tip tip) {
        int[] levels = new int[15];
        for (int i = 0; i < levels.length; i++) {
            float depth = (.23f + .17f * (i / 5)) * tip.width;
            float across = .10f * (i % 5 - 2) * tip.width;
            float x = tip.x - depth * tip.outwardX - across * tip.outwardY;
            float y = tip.y - depth * tip.outwardY + across * tip.outwardX;
            levels[i] = light(pixel(pixels, x, y));
        }
        Arrays.sort(levels);
        int bands = 1, last = levels[2];
        for (int i = 3; i <= 12; i++) {
            if (levels[i] >= last + 4) {
                bands++;
                last = levels[i];
            }
        }
        return bands >= 4;
    }

    private static final float[] DEPTHS = {.075f, .22f, .45f, .66f, .88f};
    private Tip cap(int[] pixels, float start, int last, float meanWidth, float ux, float uy, int sign) {
        int front = sign > 0 ? last : 0;
        float[] left = new float[DEPTHS.length], right = new float[DEPTHS.length];
        for (int j = 0; j < DEPTHS.length; j++) {
            int k = front - sign * Math.max(1, Math.round(DEPTHS[j] * meanWidth));
            if (k < 0 || k > last) return null;
            int band = Math.max(1, Math.round(meanWidth * .035f));
            int used = 0;
            for (int p = Math.max(0, k - band); p <= Math.min(last,k + band); p++) {
                if (low[p] > high[p]) continue;
                left[j] += low[p]; right[j] += high[p]; used++;
            }
            if (used == 0) return null;
            left[j] /= used; right[j] /= used;
        }
        float innerWidth = right[3] - left[3] + 1;
        float baseWidth = right[4] - left[4] + 1;
        // The two sides should keep a similar separation just behind the rounded cap.
        // Isolated patches of reflected light often widen and narrow again over this short span.
        if (Math.min(innerWidth, baseWidth) < .72f * Math.max(innerWidth, baseWidth)) return null;
        float shaft = (innerWidth + baseWidth) / 2;
        float near = right[0] - left[0] + 1, middle = right[1] - left[1] + 1;
        if (shaft < 5 || shaft > 1.65f * meanWidth || shaft < .63f * meanWidth
                || near > .84f * shaft || middle < .40f * shaft || middle > 1.11f * shaft
                || right[2] - left[2] < .66f * shaft) return null;
        float middleT = (left[0] + right[0]) / 2;
        // Reject one-sided corners and the slanted, cut-off base of a finger.
        if (middleT - left[3] < .25f * shaft || right[3] - middleT < .25f * shaft) return null;
        // A slanted cut can taper in the projected width profile. On a rounded rim,
        // the center protrudes past the chord joining its two flanks. A straight cut
        // has essentially zero protrusion regardless of its angle.
        int reach = Math.min(last, (int) Math.ceil(.72f * shaft));
        int center = depthAt(front, sign, middleT, reach);
        int flankA = depthAt(front, sign, middleT - .32f * shaft, reach);
        int flankB = depthAt(front, sign, middleT + .32f * shaft, reach);
        if (center < 0 || flankA < 0 || flankB < 0
                || (flankA + flankB) * .5f - center < Math.max(.5f, .045f * shaft)) return null;
        float end = start + front;
        float x = (ux * end - uy * middleT) * step + step / 2;
        float y = (uy * end + ux * middleT) * step + step / 2;
        float span = shaft * step, dx = sign * ux, dy = sign * uy;
        float margin = Math.max(2, span * .12f);
        if (x <= margin || y <= margin || x >= width - margin || y >= height - margin) return null;
        float offset = refineRim(pixels, x, y, dx, dy, span);
        if (!Float.isFinite(offset)) return null;
        x += offset * dx; y += offset * dy;

        float[] outline = new float[2 + 4 * DEPTHS.length];
        outline[0] = x; outline[1] = y;
        for (int j = 0; j < DEPTHS.length; j++) {
            float s = end - sign * DEPTHS[j] * meanWidth;
            setVertex(outline, 2 + 2*j, ux, uy, s, left[j]);
            setVertex(outline, outline.length - 2 - 2*j, ux, uy, s, right[j]);
        }
        return new Tip(x,y,span,dx,dy,outline);
    }

    private int depthAt(int front, int sign, float across, int reach) {
        for (int depth = 0; depth <= reach; depth++) {
            int section = front - sign * depth;
            if (low[section] <= across && across <= high[section]) return depth;
        }
        return -1;
    }

    private void setVertex(float[] outline, int j, float ux, float uy, float s, float t) {
        outline[j] = (ux * s - uy * t) * step + step / 2;
        outline[j+1] = (uy * s + ux * t) * step + step / 2;
    }

    private int pixel(int[] pixels, float x, float y) {
        return pixels[Math.max(0, Math.min(height - 1, Math.round(y))) * width
                + Math.max(0, Math.min(width - 1, Math.round(x)))];
    }

    // Find the outer colour edge on five short rays in the original camera pixels.
    // Comparing against the skin-to-background change rejects interior nail edges and ink.
    private float refineRim(int[] pixels, float x, float y, float dx, float dy, float span) {
        float[] shifts = new float[5];
        int valid = 0;
        int radius = Math.max(2, Math.round(.13f * span));
        float gap = Math.max(1.5f, Math.min(5, .045f * span));
        for (int ray = -2; ray <= 2; ray++) {
            float lateral = ray * span * .072f;
            float cx = x - dy*lateral, cy = y + dx*lateral;
            int inside = pixel(pixels, cx - dx*.23f*span, cy - dy*.23f*span);
            int outside = pixel(pixels, cx + dx*.18f*span, cy + dy*.18f*span);
            int dr = opponent(inside,16) - opponent(outside,16);
            int db = opponent(inside,0) - opponent(outside,0);
            int dl = brightnessEdges ? light(inside) - light(outside) : 0;
            float norm = (float) Math.sqrt(dr*dr + db*db + dl*dl);
            // Require a local color boundary as well as a contrast with the page as a whole.
            if (norm < 12) continue;
            float best = 0, location = 0;
            for (int d = -radius; d <= radius; d++) {
                int a = pixel(pixels, cx + dx*(d-gap), cy + dy*(d-gap));
                int b = pixel(pixels, cx + dx*(d+gap), cy + dy*(d+gap));
                float edge = (dr * (opponent(a,16)-opponent(b,16))
                        + db * (opponent(a,0)-opponent(b,0)) + dl * (light(a)-light(b))) / norm;
                edge /= 1f + .8f * Math.abs(d) / radius;
                if (edge > best) { best = edge; location = d; }
            }
            if (best > Math.max(3, .12f*norm)) shifts[valid++] = location;
        }
        if (valid < 3) return Float.NaN;
        Arrays.sort(shifts,0,valid);
        if (shifts[valid-2] - shifts[1] > .17f*span + 2) return Float.NaN;
        return shifts[valid/2];
    }

    private static int light(int rgb) {
        return ((rgb >> 16 & 255) + 2 * (rgb >> 8 & 255) + (rgb & 255)) / 4;
    }

    private static int opponent(int rgb, int shift) {
        int green = rgb >> 8 & 255;
        int sum = 100 + (rgb >> 16 & 255) + 2 * green + (rgb & 255);
        return ((rgb >> shift & 255) - green) * 512 / sum;
    }
}
