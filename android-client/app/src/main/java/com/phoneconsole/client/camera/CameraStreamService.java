package com.phoneconsole.client.camera;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.pedro.common.ConnectChecker;
import com.pedro.encoder.input.video.CameraHelper;
import com.pedro.encoder.input.video.CameraOpenException;
import com.pedro.library.view.GlInterface;
import com.pedro.library.view.OpenGlView;
import com.pedro.rtspserver.RtspServerCamera2;
import com.phoneconsole.client.MainActivity;
import com.phoneconsole.client.R;

/**
 * 手机虚拟摄像头前台推流服务：
 * 基于 RootEncoder RtspServerCamera2，在有预览（OpenGlView）或后台控制台切页时
 * 通过 replaceView 无缝切换渲染目标，保证微信视频 / 开会期间不断流。
 */
public class CameraStreamService extends Service implements ConnectChecker {

    private static final String TAG = "CameraStreamService";
    private static final String CHANNEL_ID = "phoneconsole_camera";
    private static final int NOTIFICATION_ID = 2048;

    public static final String ACTION_START_STREAM = "com.phoneconsole.client.camera.START_STREAM";
    public static final String ACTION_STOP_STREAM = "com.phoneconsole.client.camera.STOP_STREAM";
    public static final String ACTION_UPDATE_CONFIG = "com.phoneconsole.client.camera.UPDATE_CONFIG";

    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_BITRATE_KBPS = "bitrateKbps";
    public static final String EXTRA_RTSP_PORT = "rtspPort";
    public static final String EXTRA_FACING = "facing";
    public static final String EXTRA_ROTATION = "rotation";
    public static final String EXTRA_MIRROR_H = "mirrorHorizontal";
    public static final String EXTRA_MIRROR_V = "mirrorVertical";
    public static final String EXTRA_FLASH = "flash";
    public static final String EXTRA_MANUAL = "manual";
    public static final String EXTRA_AUTO_DEMAND = "autoDemand";
    public static final String EXTRA_CONSUMER_CONNECTED = "consumerConnected";
    public static final String EXTRA_FORCE = "force";

    public interface StateObserver {
        void onCameraStateChanged(CameraConfig state);
    }

    public final class LocalBinder extends Binder {
        public CameraStreamService service() {
            return CameraStreamService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CameraConfig state = new CameraConfig();

    private volatile RtspServerCamera2 camera;
    private volatile OpenGlView attachedPreview;
    private volatile boolean foregroundActive;
    private volatile boolean videoPrepared;
    private volatile int preparedOrientation = -1;
    private volatile long manualStopGuardUntilMs;
    private volatile StateObserver observer;

    /** 判断是否已有相机运行时权限 */
    public static boolean hasCameraPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void startCommand(Context context, String action, @Nullable CameraConfig cfg) {
        startCommand(context, action, cfg, false);
    }

    public static void startCommand(Context context, String action, @Nullable CameraConfig cfg, boolean force) {
        Intent intent = new Intent(context, CameraStreamService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_FORCE, force);
        if (cfg != null) {
            intent.putExtra(EXTRA_WIDTH, cfg.width);
            intent.putExtra(EXTRA_HEIGHT, cfg.height);
            intent.putExtra(EXTRA_FPS, cfg.fps);
            intent.putExtra(EXTRA_BITRATE_KBPS, cfg.bitrateKbps);
            intent.putExtra(EXTRA_RTSP_PORT, cfg.rtspPort);
            intent.putExtra(EXTRA_FACING, cfg.facing);
            intent.putExtra(EXTRA_ROTATION, cfg.rotation);
            intent.putExtra(EXTRA_MIRROR_H, cfg.mirrorHorizontal);
            intent.putExtra(EXTRA_MIRROR_V, cfg.mirrorVertical);
            intent.putExtra(EXTRA_FLASH, cfg.flash);
            intent.putExtra(EXTRA_MANUAL, cfg.manual);
            intent.putExtra(EXTRA_AUTO_DEMAND, cfg.autoDemand);
            intent.putExtra(EXTRA_CONSUMER_CONNECTED, cfg.consumerConnected);
        }
        if (ACTION_START_STREAM.equals(action) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
        state.permissionGranted = hasCameraPermission(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        applyExtras(intent);
        if (ACTION_START_STREAM.equals(action)) {
            boolean force = intent.getBooleanExtra(EXTRA_FORCE, false);
            if (!force && SystemClock.elapsedRealtime() < manualStopGuardUntilMs) {
                Log.i(TAG, "忽略手动停止冷却期内的旧自动拉流指令");
                notifyObserver();
                if (!state.streaming && !foregroundActive && attachedPreview == null) {
                    stopSelf();
                }
                return START_NOT_STICKY;
            }
            if (force) {
                manualStopGuardUntilMs = 0L;
            }
            if (!hasCameraPermission(this)) {
                state.permissionGranted = false;
                state.streaming = false;
                state.error = "手机端未授予相机权限";
                notifyObserver();
                stopSelf();
                return START_NOT_STICKY;
            }
            enterForeground();
            mainHandler.post(this::startStreamingInternal);
        } else if (ACTION_STOP_STREAM.equals(action)) {
            state.manual = false;
            manualStopGuardUntilMs = SystemClock.elapsedRealtime() + 2500L;
            mainHandler.post(this::stopStreamingInternal);
        } else if (ACTION_UPDATE_CONFIG.equals(action)) {
            mainHandler.post(this::applyLiveOptionsAndRestartIfNeeded);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        releaseCamera();
        super.onDestroy();
    }

    public void setObserver(@Nullable StateObserver observer) {
        this.observer = observer;
        if (observer != null) {
            observer.onCameraStateChanged(state.copy());
        }
    }

    public CameraConfig snapshot() {
        state.permissionGranted = hasCameraPermission(this);
        return state.copy();
    }

    /** 将弹窗里的 OpenGlView 绑定到推流/预览会话；若未在推流则仅开启本地预览 */
    public void attachPreview(@NonNull OpenGlView view) {
        mainHandler.post(() -> {
            if (!hasCameraPermission(this)) {
                state.permissionGranted = false;
                state.error = "需要相机权限才能预览画面";
                notifyObserver();
                return;
            }
            state.permissionGranted = true;
            attachedPreview = view;
            ensureCameraCreated(view);
            if (camera == null) {
                return;
            }
            if (camera.isStreaming() || camera.isOnPreview()) {
                try {
                    camera.replaceView(view);
                } catch (Exception e) {
                    Log.w(TAG, "replaceView(OpenGlView) 失败: " + e.getMessage());
                }
            }
            if (!camera.isOnPreview()) {
                startPreviewInternal();
            }
            applyGlTransforms();
            state.previewing = true;
            notifyObserver();
        });
    }

    /** 关闭弹窗预览：正在推流时切为后台离屏上下文保活，未推流则关闭相机预览省电 */
    public void detachPreview(@Nullable OpenGlView view) {
        mainHandler.post(() -> {
            if (view != null && attachedPreview != null && attachedPreview != view) {
                return;
            }
            attachedPreview = null;
            state.previewing = false;
            RtspServerCamera2 cam = camera;
            if (cam == null) {
                notifyObserver();
                return;
            }
            if (cam.isStreaming()) {
                try {
                    cam.replaceView(getApplicationContext());
                } catch (Exception e) {
                    Log.w(TAG, "replaceView(Context) 失败: " + e.getMessage());
                }
            } else {
                stopPreviewAndReleaseIfIdle();
            }
            notifyObserver();
        });
    }

    public void toggleManualStream() {
        if (state.streaming) {
            state.manual = false;
            manualStopGuardUntilMs = SystemClock.elapsedRealtime() + 2500L;
            mainHandler.post(this::stopStreamingInternal);
        } else {
            state.manual = true;
            manualStopGuardUntilMs = 0L;
            startCommand(this, ACTION_START_STREAM, state, true);
        }
    }

    public void stopStream() {
        state.manual = false;
        manualStopGuardUntilMs = SystemClock.elapsedRealtime() + 2500L;
        mainHandler.post(this::stopStreamingInternal);
    }

    public void updatePrefsFromUi(boolean autoDemand, int width, int height) {
        mainHandler.post(() -> {
            state.autoDemand = autoDemand;
            boolean sizeChanged = width > 0 && height > 0 && (width != state.width || height != state.height);
            if (sizeChanged) {
                state.width = width;
                state.height = height;
                videoPrepared = false;
                preparedOrientation = -1;
                RtspServerCamera2 cam = camera;
                if (cam != null && cam.isStreaming()) {
                    restartStreamInternal();
                    return;
                }
            }
            notifyObserver();
        });
    }

    public void switchCamera() {
        mainHandler.post(() -> {
            state.facing = state.isFrontFacing() ? "back" : "front";
            RtspServerCamera2 cam = camera;
            if (cam != null && (cam.isStreaming() || cam.isOnPreview())) {
                try {
                    cam.switchCamera();
                    applyFlashSafe();
                } catch (CameraOpenException e) {
                    state.facing = state.isFrontFacing() ? "back" : "front";
                    state.error = "切换摄像头失败：" + e.getMessage();
                }
            }
            notifyObserver();
        });
    }

    public void rotateStep(int deltaDegrees) {
        mainHandler.post(() -> {
            state.rotation = CameraConfig.normalizeRotation(state.rotation + deltaDegrees);
            applyGlTransforms();
            notifyObserver();
        });
    }

    /** 设备横竖屏旋转时刷新预览/推流方向，避免竖横屏混用导致画面拉宽 */
    public void onOrientationChanged() {
        mainHandler.post(() -> {
            RtspServerCamera2 cam = camera;
            if (cam == null) {
                return;
            }
            int currentOrientation = CameraHelper.getCameraOrientation(this);
            if (preparedOrientation == currentOrientation) {
                return;
            }
            if (cam.isStreaming() || cam.isOnPreview()) {
                restartStreamInternal();
            }
        });
    }

    public void toggleMirrorHorizontal() {
        mainHandler.post(() -> {
            state.mirrorHorizontal = !state.mirrorHorizontal;
            applyGlTransforms();
            notifyObserver();
        });
    }

    public void toggleMirrorVertical() {
        mainHandler.post(() -> {
            state.mirrorVertical = !state.mirrorVertical;
            applyGlTransforms();
            notifyObserver();
        });
    }

    public void toggleFlash() {
        mainHandler.post(() -> {
            state.flash = !state.flash;
            applyFlashSafe();
            notifyObserver();
        });
    }

    private void applyExtras(Intent intent) {
        boolean sizeChanged = false;
        int newW = intent.getIntExtra(EXTRA_WIDTH, state.width);
        int newH = intent.getIntExtra(EXTRA_HEIGHT, state.height);
        int newFps = intent.getIntExtra(EXTRA_FPS, state.fps);
        int newBitrate = intent.getIntExtra(EXTRA_BITRATE_KBPS, state.bitrateKbps);
        int newPort = intent.getIntExtra(EXTRA_RTSP_PORT, state.rtspPort);

        if (newW > 0 && newH > 0 && (newW != state.width || newH != state.height)) {
            state.width = newW;
            state.height = newH;
            sizeChanged = true;
        }
        if (newFps > 0) {
            if (newFps != state.fps) {
                sizeChanged = true;
            }
            state.fps = newFps;
        }
        if (newBitrate > 0) {
            state.bitrateKbps = newBitrate;
        }
        if (newPort > 0 && newPort != state.rtspPort) {
            state.rtspPort = newPort;
            releaseCamera();
            sizeChanged = true;
        }
        if (intent.hasExtra(EXTRA_FACING)) {
            String f = intent.getStringExtra(EXTRA_FACING);
            if (f != null && !f.isEmpty()) {
                state.facing = "back".equalsIgnoreCase(f) ? "back" : "front";
            }
        }
        if (intent.hasExtra(EXTRA_ROTATION)) {
            state.rotation = CameraConfig.normalizeRotation(intent.getIntExtra(EXTRA_ROTATION, state.rotation));
        }
        if (intent.hasExtra(EXTRA_MIRROR_H)) {
            state.mirrorHorizontal = intent.getBooleanExtra(EXTRA_MIRROR_H, state.mirrorHorizontal);
        }
        if (intent.hasExtra(EXTRA_MIRROR_V)) {
            state.mirrorVertical = intent.getBooleanExtra(EXTRA_MIRROR_V, state.mirrorVertical);
        }
        if (intent.hasExtra(EXTRA_FLASH)) {
            state.flash = intent.getBooleanExtra(EXTRA_FLASH, state.flash);
        }
        if (intent.hasExtra(EXTRA_MANUAL)) {
            state.manual = intent.getBooleanExtra(EXTRA_MANUAL, state.manual);
        }
        if (intent.hasExtra(EXTRA_AUTO_DEMAND)) {
            state.autoDemand = intent.getBooleanExtra(EXTRA_AUTO_DEMAND, state.autoDemand);
        }
        if (intent.hasExtra(EXTRA_CONSUMER_CONNECTED)) {
            state.consumerConnected = intent.getBooleanExtra(EXTRA_CONSUMER_CONNECTED, state.consumerConnected);
        }
        if (sizeChanged && camera != null && camera.isStreaming()) {
            mainHandler.post(this::restartStreamInternal);
        }
    }

    private void ensureCameraCreated(@Nullable OpenGlView view) {
        if (camera != null) {
            return;
        }
        if (view != null) {
            camera = new RtspServerCamera2(view, this, state.rtspPort);
        } else {
            camera = new RtspServerCamera2(getApplicationContext(), this, state.rtspPort);
        }
        camera.disableAudio();
    }

    private boolean ensureVideoPrepared(RtspServerCamera2 cam) {
        int camOrientation = CameraHelper.getCameraOrientation(this);
        if (videoPrepared && preparedOrientation == camOrientation) {
            return true;
        }
        boolean prepared = cam.prepareVideo(
                state.width,
                state.height,
                state.fps,
                state.bitrateKbps * 1024,
                camOrientation);
        if (prepared) {
            videoPrepared = true;
            preparedOrientation = camOrientation;
        }
        return prepared;
    }

    private void startPreviewInternal() {
        RtspServerCamera2 cam = camera;
        if (cam == null) {
            return;
        }
        try {
            int camOrientation = CameraHelper.getCameraOrientation(this);
            // 先按目标分辨率与设备方向初始化 VideoEncoder，避免 Camera2Base 读取默认值 640x480 导致预览裁切/拉伸
            ensureVideoPrepared(cam);
            CameraHelper.Facing facing = state.isFrontFacing()
                    ? CameraHelper.Facing.FRONT : CameraHelper.Facing.BACK;
            cam.startPreview(facing, state.width, state.height, camOrientation);
            applyGlTransforms();
            applyFlashSafe();
        } catch (Exception e) {
            state.error = "打开相机预览失败：" + e.getMessage();
            Log.e(TAG, state.error, e);
        }
    }

    private void startStreamingInternal() {
        state.permissionGranted = true;
        state.error = "";
        ensureCameraCreated(attachedPreview);
        RtspServerCamera2 cam = camera;
        if (cam == null) {
            return;
        }
        if (cam.isStreaming()) {
            applyLiveOptionsAndRestartIfNeeded();
            return;
        }
        try {
            if (!cam.isOnPreview()) {
                startPreviewInternal();
            }
            boolean prepared = ensureVideoPrepared(cam);
            if (!prepared) {
                state.streaming = false;
                state.error = "当前设备不支持视频编码参数 " + state.width + "x" + state.height;
                notifyObserver();
                return;
            }
            applyGlTransforms();
            cam.startStream();
            state.streaming = true;
            applyFlashSafe();
            Log.i(TAG, "RTSP 摄像头推流已启动: port=" + state.rtspPort
                    + " " + state.width + "x" + state.height + "@" + state.fps
                    + " camOrientation=" + preparedOrientation);
        } catch (Exception e) {
            state.streaming = false;
            state.error = "摄像头启动失败：" + e.getMessage();
            Log.e(TAG, state.error, e);
        }
        notifyObserver();
    }

    private void restartStreamInternal() {
        RtspServerCamera2 cam = camera;
        if (cam == null) {
            return;
        }
        boolean wasStreaming = cam.isStreaming();
        if (wasStreaming) {
            cam.stopStream();
        }
        if (cam.isOnPreview()) {
            cam.stopPreview();
        }
        videoPrepared = false;
        preparedOrientation = -1;
        state.streaming = false;
        if (wasStreaming || attachedPreview != null) {
            startPreviewInternal();
        }
        if (wasStreaming) {
            startStreamingInternal();
        } else {
            notifyObserver();
        }
    }

    private void applyLiveOptionsAndRestartIfNeeded() {
        RtspServerCamera2 cam = camera;
        if (cam == null) {
            notifyObserver();
            return;
        }
        CameraHelper.Facing targetFacing = state.isFrontFacing()
                ? CameraHelper.Facing.FRONT : CameraHelper.Facing.BACK;
        if ((cam.isStreaming() || cam.isOnPreview()) && cam.getCameraFacing() != targetFacing) {
            try {
                cam.switchCamera();
            } catch (CameraOpenException e) {
                state.error = "切换镜头失败：" + e.getMessage();
            }
        }
        applyGlTransforms();
        applyFlashSafe();
        notifyObserver();
    }

    private void stopStreamingInternal() {
        RtspServerCamera2 cam = camera;
        if (cam != null) {
            if (cam.isStreaming()) {
                cam.stopStream();
            }
            videoPrepared = false;
            preparedOrientation = -1;
            if (attachedPreview == null) {
                stopPreviewAndReleaseIfIdle();
            }
        }
        state.streaming = false;
        exitForeground();
        notifyObserver();
        if (attachedPreview == null) {
            stopSelf();
        }
    }

    private void stopPreviewAndReleaseIfIdle() {
        RtspServerCamera2 cam = camera;
        if (cam != null) {
            try {
                if (cam.isStreaming() || videoPrepared) {
                    cam.stopStream();
                }
                if (cam.isOnPreview()) {
                    cam.stopPreview();
                }
            } catch (Exception ignored) {
                // ignore
            }
            videoPrepared = false;
            preparedOrientation = -1;
            camera = null;
        }
    }

    private void releaseCamera() {
        stopPreviewAndReleaseIfIdle();
        state.streaming = false;
        state.previewing = false;
    }

    private void applyGlTransforms() {
        RtspServerCamera2 cam = camera;
        if (cam == null) {
            return;
        }
        try {
            GlInterface gl = cam.getGlInterface();
            if (gl != null) {
                gl.setStreamRotation(state.rotation);
                gl.setIsStreamHorizontalFlip(state.mirrorHorizontal);
                gl.setIsStreamVerticalFlip(state.mirrorVertical);
                gl.setIsPreviewHorizontalFlip(state.mirrorHorizontal);
                gl.setIsPreviewVerticalFlip(state.mirrorVertical);
            }
        } catch (Exception e) {
            Log.w(TAG, "应用 OpenGL 画面变换失败: " + e.getMessage());
        }
    }

    private void applyFlashSafe() {
        RtspServerCamera2 cam = camera;
        if (cam == null || !cam.isOnPreview()) {
            return;
        }
        try {
            if (state.flash && !state.isFrontFacing()) {
                cam.enableLantern();
            } else {
                cam.disableLantern();
            }
        } catch (Exception ignored) {
            // 部分前摄或机型不支持闪光灯时忽略
        }
    }

    private void enterForeground() {
        if (foregroundActive) {
            return;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundActive = true;
    }

    private void exitForeground() {
        if (!foregroundActive) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        foregroundActive = false;
    }

    private Notification buildNotification() {
        Intent launch = new Intent(this, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_nav_screen)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.camera_fg_notice))
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(pi)
                .build();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        "虚拟摄像头推流",
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void notifyObserver() {
        StateObserver obs = observer;
        if (obs != null) {
            obs.onCameraStateChanged(state.copy());
        }
    }

    @Override
    public void onAuthError() {
    }

    @Override
    public void onAuthSuccess() {
    }

    @Override
    public void onConnectionFailed(@NonNull String reason) {
        state.error = reason;
        notifyObserver();
    }

    @Override
    public void onConnectionStarted(@NonNull String url) {
    }

    @Override
    public void onConnectionSuccess() {
    }

    @Override
    public void onDisconnect() {
    }

    @Override
    public void onNewBitrate(long bitrate) {
    }
}
