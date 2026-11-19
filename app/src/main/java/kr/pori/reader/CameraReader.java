package kr.pori.reader;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

final class CameraReader {
    interface Listener {
        void frame(FrameReader.Frame frame);
        void error(String text);
    }
    private final ComponentActivity activity;
    private final PreviewView view;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameReader reader;
    private Preview preview;
    private androidx.lifecycle.LiveData<androidx.camera.core.CameraState> cameraState;
    private androidx.lifecycle.Observer<androidx.camera.core.CameraState> observer;
    private ProcessCameraProvider provider;
    private boolean closed;
    CameraReader(ComponentActivity activity, PreviewView view, Listener listener) {
        this.activity=activity; this.view=view; this.listener=listener;
    }
    void start() {
        var future = ProcessCameraProvider.getInstance(activity);
        future.addListener(() -> {
            if (closed) return;
            try {
                provider = future.get();
                preview = new Preview.Builder().build();
                preview.setSurfaceProvider(view.getSurfaceProvider());
                var camera = provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview);
                cameraState=camera.getCameraInfo().getCameraState();
                observer=state -> {
                    if (!closed && state.getError()!=null) listener.error("카메라를 사용할 수 없어요. 다른 카메라 앱을 닫고 다시 열어 주세요.");
                };
                cameraState.observe(activity,observer);
                main.postDelayed(this::capture, 250);
            } catch (Exception failure) { listener.error("후면 카메라를 열 수 없어요. 사용법을 누른 뒤 다시 시도해 주세요."); }
        }, activity.getMainExecutor());
    }
    private void capture() {
        if (closed) return;
        Bitmap bitmap = view.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING ? view.getBitmap() : null;
        if (bitmap == null) { main.postDelayed(this::capture, 220); return; }
        worker.execute(() -> {
            Bitmap pixels=bitmap;
            FrameReader.Frame result=null;
            try {
                if (bitmap.getConfig()!=Bitmap.Config.ARGB_8888) pixels=bitmap.copy(Bitmap.Config.ARGB_8888,false);
                if (reader==null) reader=new FrameReader(activity.getApplicationContext());
                result=reader.analyze(pixels);
            } catch (Exception | LinkageError ignored) {
                // Deliver the failure on the UI thread and allow a fresh frame to recover.
            } finally {
                if (pixels != null && pixels!=bitmap) pixels.recycle();
                bitmap.recycle();
            }
            FrameReader.Frame frame=result;
            main.post(() -> {
                if (closed) return;
                if (frame==null) listener.error("글을 인식하기 어려워요. 조명을 밝히고 글에 초점을 맞춰 주세요.");
                else listener.frame(frame);
                main.postDelayed(this::capture, 120);
            });
        });
    }
    void close() {
        closed=true; main.removeCallbacksAndMessages(null);
        if (cameraState!=null) cameraState.removeObserver(observer);
        if (provider!=null && preview!=null) provider.unbind(preview);
        worker.execute(() -> { if (reader!=null) reader.close(); });
        worker.shutdown();
    }
}
