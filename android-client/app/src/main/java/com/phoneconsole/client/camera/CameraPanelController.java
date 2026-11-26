package com.phoneconsole.client.camera;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.gson.JsonObject;
import com.pedro.encoder.utils.gl.AspectRatioMode;
import com.pedro.library.view.OpenGlView;
import com.phoneconsole.client.R;
import com.phoneconsole.client.net.ConnectionManager;
import com.phoneconsole.client.ui.ThemeManager;

/**
 * 手机虚拟摄像头主工作台控制器：
 * - 直接绑定到独立的「相机」Tab 页面（page_camera.xml / layout-land/page_camera.xml）
 * - 切到「相机」Tab 时自动挂载 OpenGlView 预览，切走时自动退入前台服务不断流
 * - 响应 PC 端 camera_control（含微信/会议打开时自动唤醒）并同步 4 套外观主题
 */
public final class CameraPanelController implements CameraStreamService.StateObserver {

    public static final int REQ_CAMERA = 4102;

    private final Activity activity;
    private final ConnectionManager connection;

    private CameraStreamService service;
    private boolean bound;
    private CameraConfig lastState = new CameraConfig();
    private CameraConfig pendingStartConfig;
    private boolean pageVisible;
    private boolean surfaceReady;
    private boolean promptedPermissionOnTab;

    private ThemeManager.AppTheme activeTheme = ThemeManager.AppTheme.OLED;

    private View previewFrame;
    private OpenGlView previewView;
    private View permissionCard;
    private TextView tvPermHint;
    private TextView btnGrantPerm;
    private View statusDot;
    private TextView tvStatus;
    private TextView tvMeta;
    private TextView btnToggle;
    private TextView btnSwitch;
    private TextView btnRotate;
    private TextView btnMirrorH;
    private TextView btnMirrorV;
    private TextView btnFlash;
    private TextView btnAutoDemand;
    private TextView btnRes;
    private View cardWake;
    private TextView tvWakeTitle;
    private TextView tvWakeSub;

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder instanceof CameraStreamService.LocalBinder) {
                CameraStreamService.LocalBinder local = (CameraStreamService.LocalBinder) binder;
                service = local.service();
                service.setObserver(CameraPanelController.this);
                lastState = service.snapshot();
                attachPreviewIfReady();
                renderUi();
                connection.sendCameraState(lastState);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    public CameraPanelController(Activity activity, ConnectionManager connection) {
        this.activity = activity;
        this.connection = connection;
        lastState.permissionGranted = CameraStreamService.hasCameraPermission(activity);
        Intent intent = new Intent(activity, CameraStreamService.class);
        bound = activity.bindService(intent, serviceConn, Context.BIND_AUTO_CREATE);
    }

    /** 绑定独立的「相机」Tab 视图（启动及横竖屏重建布局后调用） */
    public void bindCameraPage(View pageCamera) {
        if (pageCamera == null) {
            return;
        }
        surfaceReady = false;
        previewFrame = pageCamera.findViewById(R.id.cameraPreviewFrame);
        previewView = pageCamera.findViewById(R.id.cameraGlView);
        permissionCard = pageCamera.findViewById(R.id.cameraPermissionCard);
        tvPermHint = pageCamera.findViewById(R.id.tvCameraPermHint);
        btnGrantPerm = pageCamera.findViewById(R.id.btnCameraGrant);
        statusDot = pageCamera.findViewById(R.id.cameraStatusDot);
        tvStatus = pageCamera.findViewById(R.id.tvCameraStatus);
        tvMeta = pageCamera.findViewById(R.id.tvCameraMeta);
        btnToggle = pageCamera.findViewById(R.id.btnCameraToggle);
        btnSwitch = pageCamera.findViewById(R.id.btnCameraSwitch);
        btnRotate = pageCamera.findViewById(R.id.btnCameraRotate);
        btnMirrorH = pageCamera.findViewById(R.id.btnCameraMirrorH);
        btnMirrorV = pageCamera.findViewById(R.id.btnCameraMirrorV);
        btnFlash = pageCamera.findViewById(R.id.btnCameraFlash);
        btnAutoDemand = pageCamera.findViewById(R.id.btnCameraAutoDemand);
        btnRes = pageCamera.findViewById(R.id.btnCameraRes);
        cardWake = pageCamera.findViewById(R.id.cardCameraWake);
        tvWakeTitle = pageCamera.findViewById(R.id.tvCameraWakeTitle);
        tvWakeSub = pageCamera.findViewById(R.id.tvCameraWakeSub);

        if (previewView != null) {
            previewView.setAspectRatioMode(AspectRatioMode.Adjust);
            final OpenGlView gl = previewView;
            gl.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    surfaceReady = true;
                    attachPreviewIfReady();
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    surfaceReady = false;
                    if (service != null) {
                        service.detachPreview(gl);
                    }
                }
            });
        }

        if (btnGrantPerm != null) {
            btnGrantPerm.setOnClickListener(v -> requestCameraPermission());
        }

        if (btnToggle != null) {
            btnToggle.setOnClickListener(v -> {
                if (!CameraStreamService.hasCameraPermission(activity)) {
                    requestCameraPermission();
                    return;
                }
                boolean willStart = !lastState.streaming;
                if (service != null) {
                    service.toggleManualStream();
                } else {
                    CameraConfig next = lastState.copy();
                    next.manual = willStart;
                    CameraStreamService.startCommand(activity,
                            willStart ? CameraStreamService.ACTION_START_STREAM : CameraStreamService.ACTION_STOP_STREAM,
                            next, willStart);
                }
                connection.sendCameraRequest(willStart ? "start" : "stop", lastState);
            });
        }

        if (btnSwitch != null) {
            btnSwitch.setOnClickListener(v -> {
                if (service != null) {
                    service.switchCamera();
                }
            });
        }
        if (btnRotate != null) {
            btnRotate.setOnClickListener(v -> {
                if (service != null) {
                    service.rotateStep(90);
                }
            });
        }
        if (btnMirrorH != null) {
            btnMirrorH.setOnClickListener(v -> {
                if (service != null) {
                    service.toggleMirrorHorizontal();
                }
            });
        }
        if (btnMirrorV != null) {
            btnMirrorV.setOnClickListener(v -> {
                if (service != null) {
                    service.toggleMirrorVertical();
                }
            });
        }
        if (btnFlash != null) {
            btnFlash.setOnClickListener(v -> {
                if (service != null) {
                    service.toggleFlash();
                }
            });
        }
        if (btnAutoDemand != null) {
            btnAutoDemand.setOnClickListener(v -> {
                CameraConfig next = lastState.copy();
                next.autoDemand = !lastState.autoDemand;
                lastState.autoDemand = next.autoDemand;
                if (service != null) {
                    service.updatePrefsFromUi(next.autoDemand, 0, 0);
                }
                renderUi();
                connection.sendCameraRequest("auto_demand", next);
            });
        }
        if (btnRes != null) {
            btnRes.setOnClickListener(v -> {
                CameraConfig next = lastState.copy();
                if (next.width == 1280) {
                    next.width = 1920;
                    next.height = 1080;
                } else if (next.width == 1920) {
                    next.width = 640;
                    next.height = 480;
                } else {
                    next.width = 1280;
                    next.height = 720;
                }
                lastState.width = next.width;
                lastState.height = next.height;
                if (service != null) {
                    service.updatePrefsFromUi(next.autoDemand, next.width, next.height);
                }
                renderUi();
                connection.sendCameraRequest("configure", next);
            });
        }

        syncPermissionOverlay();
        renderUi();
    }

    public void setPageVisible(boolean visible) {
        this.pageVisible = visible;
        syncPermissionOverlay();
        if (visible) {
            if (!CameraStreamService.hasCameraPermission(activity) && !promptedPermissionOnTab) {
                promptedPermissionOnTab = true;
                requestCameraPermission();
            } else {
                attachPreviewIfReady();
            }
        }
        renderUi();
    }

    public void applyTheme(@NonNull ThemeManager.AppTheme theme) {
        this.activeTheme = theme;
        if (previewFrame != null) {
            ThemeManager.styleCard(previewFrame, theme);
        }
        if (cardWake != null) {
            ThemeManager.styleCard(cardWake, theme);
        }
        if (tvWakeTitle != null) {
            tvWakeTitle.setTextColor(theme.textPrimary);
            ThemeManager.applyHeaderTypeface(tvWakeTitle, theme, true);
        }
        if (tvWakeSub != null) {
            tvWakeSub.setTextColor(theme.textMuted);
        }
        if (tvPermHint != null) {
            tvPermHint.setTextColor(theme.textSecondary);
        }
        if (btnGrantPerm != null) {
            ThemeManager.stylePillButton(btnGrantPerm, theme, true, false);
        }
        renderUi();
    }

    public void onConnected() {
        lastState.permissionGranted = CameraStreamService.hasCameraPermission(activity);
        connection.sendCameraState(service != null ? service.snapshot() : lastState);
    }

    public void handleServerCommand(JsonObject cmd) {
        if (cmd == null) {
            return;
        }
        String action = strOf(cmd, "action", "");
        CameraConfig target = lastState.copy();
        if (cmd.has("width") && cmd.has("height")) {
            int w = intOf(cmd, "width", target.width);
            int h = intOf(cmd, "height", target.height);
            if (w > 0 && h > 0) {
                target.width = w;
                target.height = h;
            }
        }
        target.fps = intOf(cmd, "fps", target.fps);
        target.bitrateKbps = intOf(cmd, "bitrateKbps", target.bitrateKbps);
        target.rtspPort = intOf(cmd, "rtspPort", target.rtspPort);
        target.facing = strOf(cmd, "facing", target.facing);
        target.rotation = CameraConfig.normalizeRotation(intOf(cmd, "rotation", target.rotation));
        target.mirrorHorizontal = boolOf(cmd, "mirrorHorizontal", target.mirrorHorizontal);
        target.mirrorVertical = boolOf(cmd, "mirrorVertical", target.mirrorVertical);
        target.flash = boolOf(cmd, "flash", target.flash);
        target.autoDemand = boolOf(cmd, "autoDemand", target.autoDemand);
        target.consumerConnected = boolOf(cmd, "consumerConnected", target.consumerConnected);
        boolean force = boolOf(cmd, "force", false);

        if ("start".equals(action)) {
            if (!CameraStreamService.hasCameraPermission(activity)) {
                pendingStartConfig = target;
                lastState.permissionGranted = false;
                lastState.error = activity.getString(R.string.camera_permission_denied);
                connection.sendCameraState(lastState);
                requestCameraPermission();
                return;
            }
            target.manual = lastState.manual;
            CameraStreamService.startCommand(activity, CameraStreamService.ACTION_START_STREAM, target, force);
        } else if ("stop".equals(action)) {
            target.manual = false;
            CameraStreamService.startCommand(activity, CameraStreamService.ACTION_STOP_STREAM, target);
        } else {
            lastState = target;
            if (service != null) {
                CameraStreamService.startCommand(activity, CameraStreamService.ACTION_UPDATE_CONFIG, target);
            } else {
                renderUi();
            }
        }
    }

    public void onConfigurationChanged() {
        if (service != null) {
            service.onOrientationChanged();
        }
    }

    public void onCameraPermissionResult(boolean granted) {
        lastState.permissionGranted = granted;
        syncPermissionOverlay();
        if (!granted) {
            pendingStartConfig = null;
            lastState.error = activity.getString(R.string.camera_permission_denied);
            connection.sendCameraState(lastState);
            renderUi();
            Toast.makeText(activity, R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
            return;
        }
        lastState.error = "";
        connection.sendCameraState(lastState);
        if (pendingStartConfig != null) {
            CameraConfig target = pendingStartConfig;
            pendingStartConfig = null;
            CameraStreamService.startCommand(activity, CameraStreamService.ACTION_START_STREAM, target);
        }
        attachPreviewIfReady();
        renderUi();
    }

    @Override
    public void onCameraStateChanged(CameraConfig state) {
        if (state == null) {
            return;
        }
        lastState = state;
        activity.runOnUiThread(this::renderUi);
        connection.sendCameraState(state);
    }

    public void destroy() {
        if (service != null) {
            service.setObserver(null);
            if (lastState.streaming) {
                service.stopStream();
            }
        }
        if (bound) {
            try {
                activity.unbindService(serviceConn);
            } catch (Exception ignored) {
                // ignore
            }
            bound = false;
        }
        service = null;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    private void syncPermissionOverlay() {
        boolean granted = CameraStreamService.hasCameraPermission(activity);
        if (previewView != null) {
            previewView.setVisibility(granted ? View.VISIBLE : View.GONE);
        }
        if (permissionCard != null) {
            permissionCard.setVisibility(granted ? View.GONE : View.VISIBLE);
        }
    }

    private void attachPreviewIfReady() {
        if (pageVisible && surfaceReady && service != null && previewView != null
                && CameraStreamService.hasCameraPermission(activity)) {
            service.attachPreview(previewView);
        }
    }

    private void renderUi() {
        String facing = lastState.isFrontFacing() ? "前摄" : "后摄";
        if (statusDot != null) {
            statusDot.setBackground(ThemeManager.circle(activity,
                    lastState.streaming ? activeTheme.ok : activeTheme.textMuted,
                    android.graphics.Color.TRANSPARENT));
        }
        if (tvStatus != null) {
            String badge = lastState.streaming
                    ? (lastState.consumerConnected ? "推流中 · PC占用" : "推流中")
                    : (lastState.consumerConnected ? "待机 · PC占用" : "虚拟相机待机");
            tvStatus.setText(badge);
            tvStatus.setTextColor(lastState.streaming ? activeTheme.ok : activeTheme.textPrimary);
            ThemeManager.applyHeaderTypeface(tvStatus, activeTheme, true);
        }
        if (tvMeta != null) {
            String err = lastState.error != null && !lastState.error.isEmpty() ? " · " + lastState.error : "";
            tvMeta.setText(lastState.width + "×" + lastState.height + " · " + lastState.fps + "fps" + err);
            tvMeta.setTextColor(activeTheme.textMuted);
        }
        if (btnToggle != null) {
            btnToggle.setText(lastState.streaming ? R.string.camera_stop : R.string.camera_start);
            ThemeManager.stylePillButton(btnToggle, activeTheme, !lastState.streaming, lastState.streaming);
            ThemeManager.applyHeaderTypeface(btnToggle, activeTheme, true);
        }
        if (btnSwitch != null) {
            btnSwitch.setText("镜头: " + facing);
            ThemeManager.styleSoftAccentButton(btnSwitch, activeTheme, false);
        }
        if (btnRotate != null) {
            btnRotate.setText("旋转 " + lastState.rotation + "°");
            ThemeManager.styleSoftAccentButton(btnRotate, activeTheme, lastState.rotation != 0);
        }
        if (btnMirrorH != null) {
            btnMirrorH.setText(lastState.mirrorHorizontal ? "镜像:开" : "镜像:关");
            ThemeManager.styleSoftAccentButton(btnMirrorH, activeTheme, lastState.mirrorHorizontal);
        }
        if (btnMirrorV != null) {
            btnMirrorV.setText(lastState.mirrorVertical ? "翻转:开" : "翻转:关");
            ThemeManager.styleSoftAccentButton(btnMirrorV, activeTheme, lastState.mirrorVertical);
        }
        if (btnFlash != null) {
            btnFlash.setText(lastState.flash ? "闪光灯：开" : "闪光灯：关");
            ThemeManager.styleSoftAccentButton(btnFlash, activeTheme, lastState.flash);
        }
        if (btnRes != null) {
            btnRes.setText("画质 " + lastState.width + "×" + lastState.height);
            ThemeManager.styleSoftAccentButton(btnRes, activeTheme, false);
        }
        if (btnAutoDemand != null) {
            btnAutoDemand.setText(lastState.autoDemand ? "已开启" : "已关闭");
            ThemeManager.stylePillButton(btnAutoDemand, activeTheme, lastState.autoDemand, false);
        }
    }

    private static String strOf(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private static int intOf(JsonObject obj, String key, int fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private static boolean boolOf(JsonObject obj, String key, boolean fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : fallback;
    }
}
