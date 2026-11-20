package kr.pori.reader;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.View;
import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.view.transform.ImageProxyTransformFactory;
import androidx.camera.view.transform.OutputTransform;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class CameraReader {
    interface Listener {
        void hand(FrameReader.Hand hand, OutputTransform source, long capturedAt);
        void frame(FrameReader.Frame frame, OutputTransform source);
        void error(String message);
    }

    private static final long HAND_INTERVAL_MS = 75, OCR_INTERVAL_MS = 220;
    private final ComponentActivity activity;
    private final PreviewView view;
    private final Listener listener;
    private final FrameReader reader;
    private final ExecutorService analysisWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService ocrWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean readingText = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageProxyTransformFactory transformFactory = new ImageProxyTransformFactory();
    private final View.OnLayoutChangeListener layout = (v, l, t, r, b, ol, ot, or, ob) -> bindIfReady();
    private LiveData<CameraState> cameraState;
    private Observer<CameraState> observer;
    private ProcessCameraProvider provider;
    private Preview preview;
    private ImageAnalysis analysis;
    private long lastHand = -1, lastOcr = -1;
    private volatile boolean closed;

    CameraReader(ComponentActivity activity, PreviewView view, Listener listener) {
        this.activity = activity;
        this.view = view;
        this.listener = listener;
        reader = new FrameReader(activity);
        transformFactory.setUsingCropRect(true);
        transformFactory.setUsingRotationDegrees(true);
    }

    void start() {
        view.addOnLayoutChangeListener(layout);
        var future = ProcessCameraProvider.getInstance(activity);
        future.addListener(() -> {
            if (closed) return;
            try {
                provider = future.get();
                bindIfReady();
            } catch (Exception e) {
                listener.error("후면 카메라를 열 수 없어요. 카메라 탭을 다시 열어 주세요.");
            }
        }, activity.getMainExecutor());
    }

    private void bindIfReady() {
        if (closed || provider == null || analysis != null || view.getDisplay() == null) return;
        ViewPort port = view.getViewPort();
        if (port == null) return; // Wait for the preview's first layout.
        int rotation = view.getDisplay().getRotation();
        preview = new Preview.Builder().setTargetRotation(rotation).build();
        preview.setSurfaceProvider(view.getSurfaceProvider());
        ResolutionSelector resolution = new ResolutionSelector.Builder().setResolutionStrategy(
                new ResolutionStrategy(new Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();
        analysis = new ImageAnalysis.Builder().setResolutionSelector(resolution)
                .setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        analysis.setAnalyzer(analysisWorker, this::analyze);
        UseCaseGroup group = new UseCaseGroup.Builder().setViewPort(port)
                .addUseCase(preview).addUseCase(analysis).build();
        try {
            var camera = provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, group);
            cameraState = camera.getCameraInfo().getCameraState();
            observer = state -> {
                if (!closed && state.getError() != null) {
                    listener.error("카메라를 사용할 수 없어요. 다른 카메라 앱을 닫고 다시 열어 주세요.");
                }
            };
            cameraState.observe(activity, observer);
        } catch (RuntimeException e) {
            listener.error("후면 카메라를 열 수 없어요. 카메라 탭을 다시 열어 주세요.");
        }
    }

    private static Bitmap uprightPixels(ImageProxy image) {
        Bitmap raw = image.toBitmap();
        Bitmap result = null;
        try {
            Rect crop = image.getCropRect();
            Matrix rotation = new Matrix();
            rotation.setRotate(image.getImageInfo().getRotationDegrees());
            result = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height(), rotation, false);
            return result;
        } finally {
            if (raw != result) raw.recycle();
        }
    }

    private void analyze(ImageProxy image) {
        Bitmap pixels = null;
        try {
            long stamp = TimeUnit.NANOSECONDS.toMillis(image.getImageInfo().getTimestamp());
            if (closed || (lastHand >= 0 && stamp - lastHand < HAND_INTERVAL_MS)) return;
            lastHand = stamp;
            long capturedAt = SystemClock.elapsedRealtime();
            OutputTransform source = transformFactory.getOutputTransform(image);
            pixels = uprightPixels(image);
            FrameReader.Hand hand = reader.hand(pixels, stamp);
            post(() -> listener.hand(hand, source, capturedAt));

            if (!closed && (lastOcr < 0 || stamp - lastOcr >= OCR_INTERVAL_MS)
                    && readingText.compareAndSet(false, true)) {
                lastOcr = stamp;
                Bitmap owned = pixels;
                ocrWorker.execute(() -> {
                    try {
                        if (closed) return;
                        FrameReader.Frame frame = reader.readLines(owned, hand, capturedAt);
                        post(() -> listener.frame(frame, source));
                    } finally {
                        owned.recycle();
                        readingText.set(false);
                    }
                });
                pixels = null; // The OCR worker owns these pixels until ML Kit completes.
            }
        } catch (RuntimeException | LinkageError e) {
            post(() -> listener.error("카메라 영상을 분석하지 못했어요. 다음 화면을 확인하고 있어요."));
        } finally {
            if (pixels != null) pixels.recycle();
            image.close();
        }
    }

    private void post(Runnable action) {
        if (!closed) main.post(() -> { if (!closed) action.run(); });
    }

    void close() {
        if (closed) return;
        closed = true;
        view.removeOnLayoutChangeListener(layout);
        main.removeCallbacksAndMessages(null);
        if (cameraState != null) cameraState.removeObserver(observer);
        if (analysis != null) {
            analysis.clearAnalyzer();
            provider.unbind(analysis, preview);
        }
        // Let an analyzer already running finish handing its bitmap to the OCR worker.
        analysisWorker.execute(() -> {
            try { reader.closeHands(); }
            finally {
                ocrWorker.execute(reader::closeText);
                ocrWorker.shutdown();
            }
        });
        analysisWorker.shutdown();
    }
}
