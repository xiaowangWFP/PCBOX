package com.phoneconsole.client;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量扫码页：Camera（API1）预览 + ZXing core 直接解 Y 平面。
 * 不依赖 CameraX/ZXing-Android，minSdk 21 可用。
 */
public class ScanActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    public static final String EXTRA_RESULT = "scan_result";

    private static final int REQ_CAMERA = 2001;

    private SurfaceView surfacePreview;
    private TextView tvScanHint;

    private Camera camera;
    private boolean decoding;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final MultiFormatReader reader = new MultiFormatReader();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        surfacePreview = findViewById(R.id.surfacePreview);
        tvScanHint = findViewById(R.id.tvScanHint);
        findViewById(R.id.btnScanCancel).setOnClickListener(v -> finish());

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.List.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        surfacePreview.getHolder().addCallback(this);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && (grantResults.length == 0
                || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            tvScanHint.setText("未授予相机权限，无法扫码");
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        startPreview(holder);
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            camera.stopPreview();
            startPreview(holder);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        releaseCamera();
    }

    private void startPreview(SurfaceHolder holder) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            tvScanHint.setText("等待相机权限…");
            return;
        }
        try {
            if (camera == null) {
                camera = Camera.open();
            }
            Camera.Parameters params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);
            Camera.Size size = pickPreviewSize(params);
            if (size != null) {
                params.setPreviewSize(size.width, size.height);
            }
            if (params.getSupportedFocusModes() != null
                    && params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(params);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);

            int bufferSize = size == null ? 1280 * 720 * 3 / 2
                    : size.width * size.height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.addCallbackBuffer(new byte[bufferSize]);
            camera.setPreviewCallbackWithBuffer((data, cam) -> {
                if (!decoding && !finished) {
                    decoding = true;
                    decoder.execute(() -> decodeFrame(data, cam));
                } else {
                    cam.addCallbackBuffer(data);
                }
            });
            camera.startPreview();
            tvScanHint.setText("");
        } catch (Exception e) {
            tvScanHint.setText("相机启动失败：" + e.getMessage());
            releaseCamera();
        }
    }

    private Camera.Size pickPreviewSize(Camera.Parameters params) {
        Camera.Size best = null;
        for (Camera.Size size : params.getSupportedPreviewSizes()) {
            int pixels = size.width * size.height;
            if (pixels <= 1920 * 1080 && (best == null || pixels > best.width * best.height)) {
                best = size;
            }
        }
        return best;
    }

    private void decodeFrame(byte[] data, Camera cam) {
        Camera.Size size = cam.getParameters().getPreviewSize();
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    data, size.width, size.height, 0, 0, size.width, size.height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = reader.decodeWithState(bitmap);
            if (result != null && result.getText() != null) {
                deliver(result.getText());
                return;
            }
        } catch (Exception ignored) {
            // 没扫到属正常情况，继续下一帧
        } finally {
            reader.reset();
            decoding = false;
            cam.addCallbackBuffer(data);
        }
    }

    private void deliver(String text) {
        if (finished) {
            return;
        }
        finished = true;
        main.post(() -> {
            Intent intent = new Intent();
            intent.putExtra(EXTRA_RESULT, text);
            setResult(Activity.RESULT_OK, intent);
            finish();
        });
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {
                // 释放阶段失败可忽略
            }
            camera = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
        decoder.shutdownNow();
    }
}
