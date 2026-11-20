package kr.pori.reader;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PartialTipDetectorTest {
    private static final int SIZE = 200, PAPER = 0xfff3f1e8, SKIN = 0xff995e63;

    private static int[] page() {
        int[] pixels = new int[SIZE * SIZE];
        Arrays.fill(pixels, PAPER);
        return pixels;
    }

    // A rounded end and a straight, slanted occlusion at the base, with no hand landmarks.
    private static void finger(int[] pixels, int x, int y) {
        int radius = 13;
        for (int row = y; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                double dx = col - x - .12 * (row - y);
                int dy = row - y - radius;
                if (Math.abs(dx) > radius || row - y > 43 + dx * .24) continue;
                if (dy >= 0 || dx * dx + dy * dy <= radius * radius) pixels[row * SIZE + col] = SKIN;
            }
        }
    }

    @Test public void detectsRoundEndAfterTwoObservationsAndFollowsMotion() {
        PartialTipDetector detector = new PartialTipDetector();
        for (int step = 0; step < 8; step++) {
            int[] pixels = page();
            int y = 55 + step * 4;
            finger(pixels, 90, y);
            PartialTipDetector.Tip tip = detector.detect(pixels, SIZE, SIZE, 1000 + 100 * step);
            if (step == 0) {
                assertNull(tip);
            } else {
                assertNotNull(tip);
                assertEquals(90, tip.x, 7);
                assertEquals(y, tip.y, 3);
                assertEquals(tip.x, tip.outline[0], 0);
                assertEquals(tip.y, tip.outline[1], 0);
            }
        }
    }

    @Test public void hidesLostTipAndRequiresNewEvidenceAfterALongGap() {
        int[] pixels = page();
        finger(pixels, 80, 55);
        PartialTipDetector detector = new PartialTipDetector();
        assertNull(detector.detect(pixels, SIZE, SIZE, 1000));
        assertNotNull(detector.detect(pixels, SIZE, SIZE, 1100));
        assertNull(detector.detect(page(), SIZE, SIZE, 1200));
        assertNotNull(detector.detect(pixels, SIZE, SIZE, 1300));
        assertNull(detector.detect(pixels, SIZE, SIZE, 2200));
        assertNotNull(detector.detect(pixels, SIZE, SIZE, 2300));
    }

    @Test public void findsShadedAchromaticTipWhenThePageOccludesItsBase() {
        for (int background : new int[]{0xffeeeeee, 0xff151515}) {
            int[] pixels = page();
            finger(pixels, 90, 55);
            for (int i = 0; i < pixels.length; i++) {
                int shade = 118 + (i % SIZE - 90) * 2;
                pixels[i] = pixels[i] == SKIN ? 0xff000000 | shade * 0x010101 : background;
            }
            var detector = new PartialTipDetector();
            assertNull(detector.detect(pixels, SIZE, SIZE, 1000));
            var tip = detector.detect(pixels, SIZE, SIZE, 1060);
            assertNotNull(tip);
            assertEquals(90, tip.x, 5);
            assertEquals(55, tip.y, 3);
        }
    }

    @Test public void ambiguousFingersAndStraightEdgesDoNotStartTracking() {
        int[] both = page();
        finger(both, 55, 55);
        finger(both, 140, 65);
        int[] rectangle = page();
        for (int y = 90; y < 140; y++) Arrays.fill(rectangle, y * SIZE + 85, y * SIZE + 107, SKIN);
        for (int[] image : new int[][] {both, rectangle}) {
            PartialTipDetector detector = new PartialTipDetector();
            assertNull(detector.detect(image, SIZE, SIZE, 0));
            assertNull(detector.detect(image, SIZE, SIZE, 100));
        }
    }
}
