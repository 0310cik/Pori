package kr.pori.reader;

import android.graphics.PointF;

final class PointingSelection {
    private static final long DWELL_MS = 400, RELEASE_MS = 1000;
    private FrameReader.Line held, candidate;
    private long since, lastSample, awaySince = -1;
    private int samples;

    void hand(PointF tip, long now) {
        if (candidate != null && (tip == null || !Float.isFinite(candidate.distance(tip.x, tip.y, true)))) {
            stable(null, now);
        }
        if (held == null) return;
        boolean away = tip == null || !Float.isFinite(held.distance(tip.x, tip.y, true));
        if (away) {
            if (awaySince < 0) awaySince = now;
            if (now - awaySince >= RELEASE_MS) held = null;
        } else {
            awaySince = -1;
        }
    }

    boolean stable(FrameReader.Line line, long now) {
        if (line == null) {
            candidate = null;
            samples = 0;
            return false;
        }
        // Ignore changes in OCR spelling/spacing when the physical row stays in place.
        if (!line.tracks(candidate) || now - lastSample > 1500) {
            samples = 0;
            since = now;
        }
        candidate = line;
        lastSample = now;
        samples++;
        if (line.tracks(held)) {
            held = line;
            return false;
        }
        return samples >= 2 && now - since >= DWELL_MS;
    }

    void select(FrameReader.Line line) {
        held = line;
        awaySince = -1;
    }
}
