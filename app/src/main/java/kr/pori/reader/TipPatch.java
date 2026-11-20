package kr.pori.reader;

/** Matches the last measured rim against current pixels. The template is never updated by a match. */
final class TipPatch {
    private static final int COUNT = 37;
    private final int[] dx = new int[COUNT], dy = new int[COUNT], colors = new int[COUNT];
    private float contrast;
    private PartialTipDetector.Tip anchor;

    void capture(int[] pixels, int w, int h, PartialTipDetector.Tip tip) {
        anchor = tip;
        contrast = 0;
        float ux = tip.outwardX, uy = tip.outwardY;
        for (int i = 0; i < COUNT; i++) {
            float across = (i % 5 - 2) * .16f;
            // Five parallel profiles crossing a rounded rim. Sample the shaft as well.
            float along = .12f - (i / 5) * .15f - across * across;
            if (i >= 25) {
                // The two flanks distinguish a round rim from a long edge of the same color.
                int side = (i - 25) % 2 == 0 ? -1 : 1;
                across = side * (.37f + .13f * ((i-25)/2 % 3));
                along = -.24f - .17f * ((i-25)/6);
            }
            dx[i] = Math.round((ux * along - uy * across) * tip.width);
            dy[i] = Math.round((uy * along + ux * across) * tip.width);
            int x = Math.round(tip.x) + dx[i], y = Math.round(tip.y) + dy[i];
            if (x < 0 || x >= w || y < 0 || y >= h) { anchor = null; return; }
            colors[i] = pixels[y * w + x];
            if (i >= 10 && i < 15) contrast += difference(colors[i], colors[i % 5]) / 5f;
        }
    }

    PartialTipDetector.Tip locate(int[] pixels, int w, int h, PartialTipDetector.Tip previous) {
        if (anchor == null || contrast < 15) return null;
        int range = Math.min(96, Math.round(previous.width * 1.45f));
        int stride = Math.max(2, (range + 9) / 10);
        int cx = Math.round(previous.x), cy = Math.round(previous.y);
        float best = Float.MAX_VALUE;
        int bx = cx, by = cy;
        for (int y = cy-range; y <= cy+range; y += stride) {
            for (int x = cx-range; x <= cx+range; x += stride) {
                float s = cost(pixels,w,h,x,y);
                if (s < best) { best = s; bx = x; by = y; }
            }
        }
        int coarseX = bx, coarseY = by;
        for (int y = coarseY-stride; y <= coarseY+stride; y++) {
            for (int x = coarseX-stride; x <= coarseX+stride; x++) {
                float s = cost(pixels,w,h,x,y);
                if (s < best) { best = s; bx = x; by = y; }
            }
        }
        if (best > Math.min(25, contrast * .29f)) return null;
        // Another good match well outside the rim is ambiguous (e.g. two similar fingers).
        for (int y = cy-range; y <= cy+range; y += stride) {
            for (int x = cx-range; x <= cx+range; x += stride) {
                if (Math.hypot(x-bx,y-by) > anchor.width * .52f
                        && cost(pixels,w,h,x,y) <= best + Math.max(4, contrast * .09f)) return null;
            }
        }
        return new PartialTipDetector.Tip(bx, by, anchor.width, anchor.outwardX, anchor.outwardY, null);
    }

    private float cost(int[] pixels, int w, int h, int x, int y) {
        int sum = 0;
        for (int i = 0; i < COUNT; i++) {
            int u = x + dx[i], v = y + dy[i];
            if (u < 0 || v < 0 || u >= w || v >= h) return Float.MAX_VALUE;
            sum += Math.min(75, difference(colors[i], pixels[v*w+u]));
        }
        return sum / (float) COUNT;
    }

    private static int difference(int a, int b) {
        return (Math.abs((a >> 16 & 255) - (b >> 16 & 255))
                + 2 * Math.abs((a >> 8 & 255) - (b >> 8 & 255))
                + Math.abs((a & 255) - (b & 255))) / 4;
    }
}
