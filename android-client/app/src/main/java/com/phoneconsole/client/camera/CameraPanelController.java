package com.phoneconsole.client.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonObject;
import com.pedro.encoder.utils.gl.AspectRatioMode;
import com.pedro.library.view.OpenGlView;
import com.phoneconsole.client.R;
import com.phoneconsole.client.net.ConnectionManager;

/**
 * 手机端内置虚拟摄像头控制面板与服务绑定协调器：
 * - 管理控制页底部入口卡片状态展示
 * - 响应 PC 端 camera_control（含微信按需唤醒）
 * - 打开内置 OpenGlView 预览/旋转/镜像弹窗，收起弹窗后自动退入前台服务继续推流
 */
public final class CameraPanelController implements CameraStreamService.StateObserver {

    public static final int REQ_CAMERA = 4102;

    private final Activity activity;
    private final ConnectionManager connection;

    private CameraStreamService service;
    private boolean bound;
    private CameraConfig lastState = new CameraConfig();

    private CameraConfig pendingStartConfig;
    private boolean pendingOpenDialog;

    // 控制页卡片（横竖屏重建后会重新绑定）
    private TextView tvCardSubtitle;
    private TextView tvCardAction;

    // 当前打开的弹窗及其控件引用
    private AlertDialog dialog;
    private OpenGlView previewView;
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

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder instanceof CameraStreamService.LocalBinder) {
                CameraStreamService.LocalBinder local = (CameraStreamService.LocalBinder) binder;
                service = local.service();
                service.setObserver(CameraPanelController.this);
                lastState = service.snapshot();
                renderCard();
                renderDialog();
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

    /** 绑定控制页上的入口卡片（Activity 启动及横竖屏切换时调用） */
    public void bindControlCard(View pageControl) {
        if (pageControl == null) {
            return;
        }
        View card = pageControl.findViewById(R.id.cardCamera);
        tvCardSubtitle = pageControl.findViewById(R.id.tvCameraSubtitle);
        tvCardAction = pageControl.findViewById(R.id.tvCameraAction);
        if (card != null) {
            card.setOnClickListener(v -> openDialog());
        }
        renderCard();
    }

    public void onConnected() {
        lastState.permissionGranted = CameraStreamService.hasCameraPermission(activity);
        connection.sendCameraState(service != null ? service.snapshot() : lastState);
    }

    /** 处理 PC 端下发的 camera_control JSON */
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
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
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
                renderCard();
                renderDialog();
            }
        }
    }

    public void openDialog() {
        if (!CameraStreamService.hasCameraPermission(activity)) {
            pendingOpenDialog = true;
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (dialog != null && dialog.isShowing()) {
            return;
        }

        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_camera, null);
        OpenGlView glView = root.findViewById(R.id.cameraGlView);
        // XML 构造默认 AspectRatioMode.NONE 会强制铺满拉伸画面，改为 Adjust 按真实比例等比展示
        glView.setAspectRatioMode(AspectRatioMode.Adjust);
        previewView = glView;
        tvStatus = root.findViewById(R.id.tvCameraStatus);
        tvMeta = root.findViewById(R.id.tvCameraMeta);
        btnToggle = root.findViewById(R.id.btnCameraToggle);
        btnSwitch = root.findViewById(R.id.btnCameraSwitch);
        btnRotate = root.findViewById(R.id.btnCameraRotate);
        btnMirrorH = root.findViewById(R.id.btnCameraMirrorH);
        btnMirrorV = root.findViewById(R.id.btnCameraMirrorV);
        btnFlash = root.findViewById(R.id.btnCameraFlash);
        btnAutoDemand = root.findViewById(R.id.btnCameraAutoDemand);
        btnRes = root.findViewById(R.id.btnCameraRes);
        View btnClose = root.findViewById(R.id.btnCameraClose);

        AlertDialog d = new AlertDialog.Builder(activity).setView(root).create();
        dialog = d;

        btnToggle.setOnClickListener(v -> {
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
        btnSwitch.setOnClickListener(v -> {
            if (service != null) {
                service.switchCamera();
            }
        });
        btnRotate.setOnClickListener(v -> {
            if (service != null) {
                service.rotateStep(90);
            }
        });
        btnMirrorH.setOnClickListener(v -> {
            if (service != null) {
                service.toggleMirrorHorizontal();
            }
        });
        btnMirrorV.setOnClickListener(v -> {
            if (service != null) {
                service.toggleMirrorVertical();
            }
        });
        btnFlash.setOnClickListener(v -> {
            if (service != null) {
                service.toggleFlash();
            }
        });
        if (btnAutoDemand != null) {
            btnAutoDemand.setOnClickListener(v -> {
                CameraConfig next = lastState.copy();
                next.autoDemand = !lastState.autoDemand;
                lastState.autoDemand = next.autoDemand;
                if (service != null) {
                    service.updatePrefsFromUi(next.autoDemand, 0, 0);
                }
                renderDialog();
                renderCard();
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
                renderDialog();
                renderCard();
                connection.sendCameraRequest("configure", next);
            });
        }
        btnClose.setOnClickListener(v -> d.dismiss());

        glView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                if (service != null && glView == previewView) {
                    service.attachPreview(glView);
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (service != null) {
                    service.detachPreview(glView);
                }
            }
        });

        d.setOnDismissListener(ignored -> {
            if (service != null && previewView != null) {
                service.detachPreview(previewView);
            }
            previewView = null;
            tvStatus = null;
            tvMeta = null;
            btnToggle = null;
            btnSwitch = null;
            btnRotate = null;
            btnMirrorH = null;
            btnMirrorV = null;
            btnFlash = null;
            btnAutoDemand = null;
            btnRes = null;
            dialog = null;
        });

        d.show();
        resizeDialogWindow();
        renderDialog();
    }

    /** 横竖屏切换时调整弹窗宽度与推流方向，防止画面拉伸或卡片过窄 */
    public void onConfigurationChanged() {
        resizeDialogWindow();
        if (service != null) {
            service.onOrientationChanged();
        }
    }

    private void resizeDialogWindow() {
        if (dialog == null || !dialog.isShowing()) {
            return;
        }
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (window.getDecorView() != null) {
            window.getDecorView().setPadding(0, 0, 0, 0);
        }
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels > dm.heightPixels;
        int targetWidth = landscape
                ? Math.round(dm.widthPixels * 0.94f)
                : Math.max(Math.round(dm.widthPixels * 0.96f), dm.widthPixels - Math.round(12f * dm.density));
        window.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    public void onCameraPermissionResult(boolean granted) {
        lastState.permissionGranted = granted;
        if (!granted) {
            pendingStartConfig = null;
            pendingOpenDialog = false;
            lastState.error = activity.getString(R.string.camera_permission_denied);
            connection.sendCameraState(lastState);
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
        if (pendingOpenDialog) {
            pendingOpenDialog = false;
            openDialog();
        }
    }

    @Override
    public void onCameraStateChanged(CameraConfig state) {
        if (state == null) {
            return;
        }
        lastState = state;
        activity.runOnUiThread(() -> {
            renderCard();
            renderDialog();
        });
        connection.sendCameraState(state);
    }

    public void destroy() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
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
    }

    private void renderCard() {
        if (tvCardSubtitle != null) {
            if (!lastState.permissionGranted) {
                tvCardSubtitle.setText("需要相机权限 · 点按授权并开启");
            } else if (lastState.streaming) {
                String facing = lastState.isFrontFacing() ? "前摄" : "后摄";
                String wake = lastState.autoDemand ? "自动唤醒:开" : "自动唤醒:关";
                tvCardSubtitle.setText("推流中 · " + facing + " " + lastState.width + "×"
                        + lastState.height + " · " + lastState.rotation + "° · " + wake);
            } else {
                String wake = lastState.autoDemand ? "自动唤醒:开" : "自动唤醒:关";
                tvCardSubtitle.setText("待机中 · " + lastState.width + "×" + lastState.height + " · " + wake);
            }
        }
        if (tvCardAction != null) {
            tvCardAction.setText(lastState.streaming ? "管理" : activity.getString(R.string.camera_card_open));
            tvCardAction.setTextColor(ContextCompat.getColor(activity,
                    lastState.streaming ? R.color.status_ok : R.color.accent_bright));
        }
    }

    private void renderDialog() {
        if (dialog == null || !dialog.isShowing()) {
            return;
        }
        String facing = lastState.isFrontFacing() ? "前摄" : "后摄";
        if (tvStatus != null) {
            String badge = lastState.streaming
                    ? (lastState.consumerConnected ? "推流中 · PC占用" : "推流中")
                    : (lastState.consumerConnected ? "待机 · PC占用" : "预览待机");
            tvStatus.setText(badge);
            tvStatus.setTextColor(ContextCompat.getColor(activity,
                    lastState.streaming ? R.color.status_ok : R.color.text_secondary));
        }
        if (tvMeta != null) {
            String mirror = (lastState.mirrorHorizontal ? " · 水平镜像" : "")
                    + (lastState.mirrorVertical ? " · 垂直翻转" : "");
            String wake = lastState.autoDemand ? " · 自动唤醒:开" : " · 自动唤醒:关";
            String err = lastState.error != null && !lastState.error.isEmpty() ? " · " + lastState.error : "";
            tvMeta.setText(lastState.width + "×" + lastState.height + " · " + lastState.fps + "fps · "
                    + facing + " · " + lastState.rotation + "°" + mirror + wake + err);
        }
        if (btnToggle != null) {
            btnToggle.setText(lastState.streaming ? R.string.camera_stop : R.string.camera_start);
        }
        if (btnSwitch != null) {
            btnSwitch.setText("切换镜头（" + facing + "）");
        }
        if (btnRotate != null) {
            btnRotate.setText("旋转画面 " + lastState.rotation + "°");
        }
        if (btnMirrorH != null) {
            btnMirrorH.setText(lastState.mirrorHorizontal ? "水平镜像：开" : "水平镜像：关");
        }
        if (btnMirrorV != null) {
            btnMirrorV.setText(lastState.mirrorVertical ? "垂直翻转：开" : "垂直翻转：关");
        }
        if (btnFlash != null) {
            btnFlash.setText(lastState.flash ? "闪光灯：开" : "闪光灯：关");
        }
        if (btnAutoDemand != null) {
            btnAutoDemand.setText(lastState.autoDemand ? "自动唤醒：开" : "自动唤醒：关");
        }
        if (btnRes != null) {
            btnRes.setText("画质：" + lastState.width + "×" + lastState.height);
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

