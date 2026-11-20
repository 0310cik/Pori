package kr.pori.reader;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class FingertipMotionTest {
    private static final int WIDTH = 320, HEIGHT = 245;
    private static final int WHITE = 0xffefefef, RED = 0xffc71818, TIP = 0xffa8a4a5;

    private static int[] scene(int background, int ink, int finger, int x, int y, int radius) {
        int[] pixels = new int[WIDTH * HEIGHT];
        Arrays.fill(pixels, background);
        for (int top = 16; top < HEIGHT; top += 26) {
            for (int left = 8; left < WIDTH - 9; left += 13) {
                for (int row = top; row < Math.min(top + 12, HEIGHT); row++) {
                    for (int col = left; col < left + 9; col++) {
                        if (col-left < 2 || row-top < 2 || row-top == 8) pixels[row*WIDTH+col] = ink;
                    }
                }
            }
        }
        if (x < 0) return pixels;
        for (int row = y; row < HEIGHT; row++) {
            for (int col = x-radius; col <= x+radius; col++) {
                if (row >= y+radius || Math.hypot(col-x, row-y-radius) <= radius) {
                    pixels[row*WIDTH+col] = finger;
                }
            }
        }
        return pixels;
    }

    @Test public void followsFastAndSlowMotionOnDarkPaperAndOnColoredPrint() {
        int[][] scenes = {
                {0xff121212, 0xff444444, 0xff777878}, {0xffededed, 0xffd51a17, 0xffaba5a3},
                {0xfffdfcf7, 0xff191919, 0xff754c3a}, {0xffb2b2b0, 0xff952222, 0xff716b69}
        };
        int[] xs = {80,82,85,90,96,102,120,146,172,190,195,197};
        for (int[] colors : scenes) {
            for (int radius : new int[]{9,17}) {
                PartialTipDetector detector = new PartialTipDetector();
                for (int i=0; i<xs.length; i++) {
                    int y = HEIGHT-radius*4+i%3;
                    int[] pixels = scene(colors[0],colors[1],colors[2],xs[i],y,radius);
                    var tip = detector.detect(pixels, WIDTH, HEIGHT, 1000+i*60);
                    if (i == 0) { assertNull(tip); continue; }
                    assertNotNull(tip);
                    assertTrue(Math.hypot(tip.x-xs[i], tip.y-y) <= radius*.55+1);
                }
                int[] empty = scene(colors[0],colors[1],colors[2],-1,0,radius);
                for (int i=0; i<6; i++) assertNull(detector.detect(empty, WIDTH, HEIGHT, 1720+i*60));
            }
        }
    }

    @Test public void matchesVisibleMovingRimDuringBriefLossOfShaftShapeThenExpires() {
        PartialTipDetector detector = new PartialTipDetector();
        for (int i=0; i<9; i++) {
            int x=100+i*4, y=157+i;
            int[] pixels=scene(WHITE,RED,TIP,x,y,18);
            if (i>=2 && i<=7) {
                // Geometry cannot identify the shaft where it meets this wide occluder.
                Arrays.fill(pixels, (y+25)*WIDTH, pixels.length, TIP);
            }
            var tip = detector.detect(pixels,WIDTH,HEIGHT,1000+i*50);
            if (i==0 || (i>=5 && i<=7)) {
                assertNull(tip);
            } else {
                assertNotNull(tip);
                assertTrue(Math.hypot(tip.x-x,tip.y-y)<5);
                if (i>=2 && i<=4) assertNull(tip.outline);
                else assertNotNull(tip.outline);
            }
        }
    }
}
