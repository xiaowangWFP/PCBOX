package com.phoneconsole.client;

import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.phoneconsole.client.model.ButtonItem;
import com.phoneconsole.client.model.ButtonStateMessage;
import com.phoneconsole.client.model.LayoutMessage;
import com.phoneconsole.client.model.ScreenDevicesMessage;
import com.phoneconsole.client.model.ScreenStateMessage;
import com.phoneconsole.client.model.SessionMessage;
import com.phoneconsole.client.model.TelemetryMessage;
import com.phoneconsole.client.net.ConnectionManager;
import com.phoneconsole.client.screen.H264Decoder;
import com.phoneconsole.client.ui.ButtonAdapter;
import com.phoneconsole.client.ui.HistoryGraphView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：左侧分组导航（可收起）+ 固定顶栏（音量/内存/GPU/CPU）+ 四个页面。
 * 页面：性能监视、屏幕监视、功能控制、连接设置。
 */
public class MainActivity extends AppCompatActivity implements ConnectionManager.Listener {

    private static final String PAGE_PERFORMANCE = "performance";
    private static final String PAGE_CONTROL = "control";
    private static final String PAGE_SCREEN = "screen";
    private static final String PAGE_SETTINGS = "settings";

    private static final String DEFAULT_PAGE = "default_page";

    /** 推流格式兜底值：实际尺寸与帧率由服务端 screen_state / screen_stats 下发 */
    private static final int DEFAULT_STREAM_WIDTH = 1280;
    private static final int DEFAULT_STREAM_HEIGHT = 720;
    private static final int DEFAULT_STREAM_FPS = 30;

    // 侧边栏 / 顶栏
    private View sidebar;
    private TextView navPerformance;
    private View navScreen;
    private TextView navControl;
    private TextView navSettings;
    private TextView screenBadge;
    private TextView pageTitleView;
    private TextView clockView;
    private TextView dotView;
    private TextView modeView;
    private TextView sidebarFoot;

    // 顶栏指标
    private TextView volText;
    private TextView memText;
    private TextView gpuText;
    private TextView cpuText;
    private ProgressBar volBar;
    private ProgressBar memBar;
    private ProgressBar gpuBar;
    private ProgressBar cpuBar;

    // 页面容器
    private View pagePerformance;
    private View pageControl;
    private View pageScreen;
    private View pageSettings;

    // 性能页
    private TextView cpuBig;
    private TextView memBig;
    private TextView gpuBig;
    private TextView volBig;
    private TextView muteState;
    private TextView netInfo;
    private ProgressBar volBigBar;
    private HistoryGraphView cpuGraph;
    private HistoryGraphView memGraph;
    private HistoryGraphView gpuGraph;

    // 控制页
    private TextView layoutTitle;
    private RecyclerView grid;
    private ButtonAdapter adapter;
    private GridLayoutManager gridLayoutManager;
    private int layoutRows = 3;

    // 屏幕页（H.264，格式随服务端 config.json 下发）
    private Button streamBtn;
    private TextView streamState;
    private TextureView screenTexture;
    private View screenStage;
    private TextView screenHint;
    private TextView streamFormatView;
    private Surface screenSurface;
    private H264Decoder h264Decoder;
    private boolean firstFrame;

    // 采集显示器选择
    private TextView monitorBtn;
    private final List<ScreenDevicesMessage.Device> monitors = new ArrayList<>();
    private int selectedMonitor = -1;
    private boolean monitorDialogPending;

    // 全屏放大
    private TextView fullscreenBtn;
    private View topbar;
    private View screenToolbar;
    private boolean fullscreen;
    private int sidebarVisibilityBeforeFullscreen = View.VISIBLE;

    // 设置页
    private EditText lanInput;
    private TextView connInfo;

    private ConnectionManager connection;
    private String currentPage = PAGE_PERFORMANCE;
    private String currentPageId = DEFAULT_PAGE;

    private boolean connected;
    private boolean screenAllowed;
    private boolean streaming;
    private boolean streamRequested;
    private String transport = "lan";

    private double actualFps;
    private int streamKbps;
    // 当前推流编码格式，随服务端报文更新
    private int streamWidth = DEFAULT_STREAM_WIDTH;
    private int streamHeight = DEFAULT_STREAM_HEIGHT;
    private int streamFps = DEFAULT_STREAM_FPS;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final Runnable clockTask = new Runnable() {
        @Override
        public void run() {
            clockView.setText(timeFormat.format(new Date()));
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
        setContentView(R.layout.activity_main);

        bindViews();
        applyCutoutInset();
        setupNavigation();
        setupControlPage();
        setupScreenPage();
        setupSettingsPage();

        connection = new ConnectionManager(getApplicationContext(), this);
        connection.start();

        // 支持 adb 联调直接打开指定页面：am start -n ... --es page screen
        String initialPage = getIntent() == null ? null : getIntent().getStringExtra("page");
        if (PAGE_PERFORMANCE.equals(initialPage) || PAGE_SCREEN.equals(initialPage)
                || PAGE_CONTROL.equals(initialPage) || PAGE_SETTINGS.equals(initialPage)) {
            selectPage(initialPage);
        } else {
            selectPage(PAGE_PERFORMANCE);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (fullscreen) {
                    exitFullscreen();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });

        handler.post(clockTask);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        String page = intent.getStringExtra("page");
        if (PAGE_PERFORMANCE.equals(page) || PAGE_CONTROL.equals(page)
                || PAGE_SCREEN.equals(page) || PAGE_SETTINGS.equals(page)) {
            selectPage(page);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopStream();
        if (h264Decoder != null) {
            h264Decoder.stop();
        }
        if (screenSurface != null) {
            screenSurface.release();
            screenSurface = null;
        }
        if (connection != null) {
            connection.stop();
        }
        super.onDestroy();
    }

    // ---------------- 初始化 ----------------

    private void bindViews() {
        sidebar = findViewById(R.id.nav_sidebar);
        navPerformance = findViewById(R.id.nav_performance);
        navScreen = findViewById(R.id.nav_screen);
        navControl = findViewById(R.id.nav_control);
        navSettings = findViewById(R.id.nav_settings);
        screenBadge = findViewById(R.id.nav_screen_badge);
        pageTitleView = findViewById(R.id.page_title);
        clockView = findViewById(R.id.status_clock);
        dotView = findViewById(R.id.status_dot);
        modeView = findViewById(R.id.status_mode);
        sidebarFoot = findViewById(R.id.sidebar_foot);

        volText = findViewById(R.id.vol_text);
        memText = findViewById(R.id.mem_text);
        gpuText = findViewById(R.id.gpu_text);
        cpuText = findViewById(R.id.cpu_text);
        volBar = findViewById(R.id.vol_bar);
        memBar = findViewById(R.id.mem_bar);
        gpuBar = findViewById(R.id.gpu_bar);
        cpuBar = findViewById(R.id.cpu_bar);

        pagePerformance = findViewById(R.id.page_performance);
        pageControl = findViewById(R.id.page_control);
        pageScreen = findViewById(R.id.page_screen);
        pageSettings = findViewById(R.id.page_settings);

        cpuBig = findViewById(R.id.cpu_big);
        memBig = findViewById(R.id.mem_big);
        gpuBig = findViewById(R.id.gpu_big);
        volBig = findViewById(R.id.vol_big);
        muteState = findViewById(R.id.mute_state);
        netInfo = findViewById(R.id.net_info);
        volBigBar = findViewById(R.id.vol_big_bar);
        cpuGraph = findViewById(R.id.cpu_graph);
        memGraph = findViewById(R.id.mem_graph);
        gpuGraph = findViewById(R.id.gpu_graph);
        cpuGraph.setLineColor(0xFF22D3EE);
        memGraph.setLineColor(0xFF34D399);
        gpuGraph.setLineColor(0xFFFBBF24);

        layoutTitle = findViewById(R.id.layout_title);
        grid = findViewById(R.id.grid);

        streamBtn = findViewById(R.id.stream_btn);
        streamState = findViewById(R.id.stream_state);
        screenTexture = findViewById(R.id.screen_texture);
        screenStage = findViewById(R.id.screen_stage);
        screenHint = findViewById(R.id.screen_hint);
        streamFormatView = findViewById(R.id.stream_format);
        monitorBtn = findViewById(R.id.btn_monitor);
        fullscreenBtn = findViewById(R.id.btn_fullscreen);
        topbar = findViewById(R.id.topbar);
        screenToolbar = findViewById(R.id.screen_toolbar);

        lanInput = findViewById(R.id.lan_input);
        connInfo = findViewById(R.id.conn_info);
    }

    /**
     * 内容已允许画进挖孔区域（shortEdges），横屏时挖孔在左边缘中部，
     * 给侧边栏补一个内边距，避免导航文字被摄像头开孔压住。
     */
    private void applyCutoutInset() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sidebar, (view, insets) -> {
            androidx.core.graphics.Insets cutout =
                    insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            view.setPadding(cutout.left, 0, 0, 0);
            return insets;
        });
        androidx.core.view.ViewCompat.requestApplyInsets(sidebar);
    }

    private void setupNavigation() {
        navPerformance.setOnClickListener(v -> selectPage(PAGE_PERFORMANCE));
        navScreen.setOnClickListener(v -> selectPage(PAGE_SCREEN));
        navControl.setOnClickListener(v -> selectPage(PAGE_CONTROL));
        navSettings.setOnClickListener(v -> selectPage(PAGE_SETTINGS));
        modeView.setOnClickListener(v -> selectPage(PAGE_SETTINGS));

        findViewById(R.id.btn_menu).setOnClickListener(v -> {
            sidebar.setVisibility(sidebar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        bindGroup(R.id.group_monitor_header, R.id.group_monitor_items, R.id.group_monitor_chevron);
        bindGroup(R.id.group_control_header, R.id.group_control_items, R.id.group_control_chevron);
        bindGroup(R.id.group_system_header, R.id.group_system_items, R.id.group_system_chevron);
    }

    /** 分组标题点击展开 / 收起 */
    private void bindGroup(int headerId, int itemsId, int chevronId) {
        View header = findViewById(headerId);
        View items = findViewById(itemsId);
        TextView chevron = findViewById(chevronId);
        header.setOnClickListener(v -> {
            boolean show = items.getVisibility() != View.VISIBLE;
            items.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.setText(show ? "▼" : "▶");
        });
    }

    private void setupControlPage() {
        gridLayoutManager = new GridLayoutManager(this, 3);
        grid.setLayoutManager(gridLayoutManager);
        grid.setHasFixedSize(true);
        adapter = new ButtonAdapter(this::onButtonAction);
        grid.setAdapter(adapter);
        grid.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom)
                -> applyGridMetrics());
    }

    /** 按服务端下发的行数把网格分成等高行，少量按钮不会被拉满整屏 */
    private void applyGridMetrics() {
        if (adapter == null || grid == null) {
            return;
        }
        int available = grid.getHeight() - grid.getPaddingTop() - grid.getPaddingBottom();
        if (available <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int minHeight = Math.round(56 * density);
        // item_button 上下各 5dp 外边距，行高要预留出来，否则最后一行会被裁掉
        int margin = Math.round(10 * density);
        adapter.setRowHeight(Math.max(minHeight, available / Math.max(1, layoutRows) - margin));
    }

    private void setupScreenPage() {
        h264Decoder = new H264Decoder(new H264Decoder.Listener() {
            @Override
            public void onFirstFrame() {
                firstFrame = true;
                screenHint.setVisibility(View.GONE);
                streaming = true;
                streamRequested = false;
                updateScreenUi();
                streamState.setText(streamSummary());
            }

            @Override
            public void onError(String message) {
                screenHint.setVisibility(View.VISIBLE);
                screenHint.setText(message);
                streamState.setText("H.264 解码失败");
            }
        });

        screenTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                screenSurface = new Surface(surfaceTexture);
                applyScreenAspect();
                if ((streamRequested || streaming) && !h264Decoder.isRunning() && screenSurface.isValid()) {
                    h264Decoder.start(screenSurface, streamWidth, streamHeight, streamFps);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                applyScreenAspect();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                if (h264Decoder != null) {
                    h264Decoder.stop();
                }
                if (screenSurface != null) {
                    screenSurface.release();
                    screenSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            }
        });
        screenStage.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> applyScreenAspect());

        streamBtn.setOnClickListener(v -> {
            if (streaming || streamRequested) {
                stopStream();
            } else {
                startStream();
            }
        });

        fullscreenBtn.setOnClickListener(v -> {
            if (fullscreen) {
                exitFullscreen();
            } else {
                enterFullscreen();
            }
        });

        monitorBtn.setOnClickListener(v -> {
            if (monitors.isEmpty()) {
                monitorDialogPending = true;
                connection.requestScreenDevices();
                monitorBtn.setText("获取屏幕中…");
            } else {
                showMonitorPicker();
            }
        });

        applyStreamFormat(0, 0, 0);
    }

    /** 弹出采集屏幕选择：整块桌面 + 每块显示器 */
    private void showMonitorPicker() {
        List<String> labels = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        labels.add("整块桌面（所有显示器）");
        ids.add(-1);
        for (ScreenDevicesMessage.Device device : monitors) {
            labels.add(device.name + " · " + device.width + "×" + device.height);
            ids.add(device.id);
        }
        int checked = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i) == selectedMonitor) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择监视屏幕")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked,
                        (dialog, which) -> {
                            dialog.dismiss();
                            selectMonitor(ids.get(which));
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void selectMonitor(int id) {
        selectedMonitor = id;
        updateMonitorLabel();
        if (streaming || streamRequested) {
            stopStream();
            startStream();
        }
    }

    private void updateMonitorLabel() {
        if (monitorBtn == null) {
            return;
        }
        if (selectedMonitor < 0) {
            monitorBtn.setText("选择屏幕");
            return;
        }
        for (ScreenDevicesMessage.Device device : monitors) {
            if (device.id == selectedMonitor) {
                monitorBtn.setText("屏幕：" + device.name);
                return;
            }
        }
        monitorBtn.setText("屏幕 " + (selectedMonitor + 1));
    }

    /** 进入全屏：隐藏侧边栏、顶栏与工具条，画面独占屏幕 */
    private void enterFullscreen() {
        if (fullscreen) {
            return;
        }
        fullscreen = true;
        sidebarVisibilityBeforeFullscreen = sidebar.getVisibility();
        sidebar.setVisibility(View.GONE);
        topbar.setVisibility(View.GONE);
        screenToolbar.setVisibility(View.GONE);
        pageScreen.setPadding(0, 0, 0, 0);
        fullscreenBtn.setText("⤢ 退出全屏");
        hideSystemBars();
        applyScreenAspect();
        screenStage.post(this::applyScreenAspect);
    }

    /** 退出全屏：恢复导航与工具条 */
    private void exitFullscreen() {
        if (!fullscreen) {
            return;
        }
        fullscreen = false;
        sidebar.setVisibility(sidebarVisibilityBeforeFullscreen);
        topbar.setVisibility(View.VISIBLE);
        screenToolbar.setVisibility(View.VISIBLE);
        int padding = Math.round(10 * getResources().getDisplayMetrics().density);
        pageScreen.setPadding(padding, padding, padding, padding);
        fullscreenBtn.setText("⛶ 全屏");
        hideSystemBars();
        applyScreenAspect();
        screenStage.post(this::applyScreenAspect);
    }

    /**
     * 舞台内按推流实际宽高比等比摆放 TextureView（不依赖 setTransform）：
     * 比例跟随服务端编码尺寸（默认 1280x720，按采集源比例可能变化），
     * 超宽屏等非 16:9 采集源也不会拉伸，四周由舞台的黑色背景填充。
     */
    private void applyScreenAspect() {
        if (screenTexture == null || screenStage == null) {
            return;
        }
        int stageWidth = screenStage.getWidth();
        int stageHeight = screenStage.getHeight();
        if (stageWidth <= 0 || stageHeight <= 0 || streamWidth <= 0 || streamHeight <= 0) {
            return;
        }
        float scale = Math.min(stageWidth / (float) streamWidth, stageHeight / (float) streamHeight);
        int width = Math.max(1, Math.round(streamWidth * scale));
        int height = Math.max(1, Math.round(streamHeight * scale));
        ViewGroup.LayoutParams params = screenTexture.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            screenTexture.setLayoutParams(params);
        }
        // 缓冲区按编码尺寸输出，缩放交给视图尺寸完成
        screenTexture.setTransform(null);
    }

    /** 采用服务端下发的推流格式（宽高传 0 表示只更新帧率与标签） */
    private void applyStreamFormat(int width, int height, int fps) {
        if (fps > 0) {
            streamFps = fps;
        }
        boolean resized = width > 0 && height > 0
                && (width != streamWidth || height != streamHeight);
        if (resized) {
            streamWidth = width;
            streamHeight = height;
        }
        if (streamFormatView != null) {
            streamFormatView.setText(streamFormatChip());
        }
        if (resized) {
            applyScreenAspect();
            screenStage.post(this::applyScreenAspect);
        }
    }

    /** 顶部格式标签文本，如 1280×720 · 30fps · H.264 */
    private String streamFormatChip() {
        return streamWidth + "×" + streamHeight + " · " + streamFps + "fps · H.264";
    }

    /** 服务端热更新参数导致分辨率变化时，按新尺寸重建解码器等待下一个 IDR */
    private void restartDecoder() {
        if (!streaming || screenSurface == null || !screenSurface.isValid()) {
            return;
        }
        firstFrame = false;
        screenHint.setVisibility(View.VISIBLE);
        screenHint.setText("画面参数已更新，正在等待 H.264 关键帧…");
        h264Decoder.start(screenSurface, streamWidth, streamHeight, streamFps);
    }

    /** 当前推流格式文本，如 1280×720@30 H.264 */
    private String streamFormat() {
        return streamWidth + "×" + streamHeight + "@" + streamFps + " H.264";
    }

    private String streamSummary() {
        StringBuilder text = new StringBuilder()
                .append(streamFormat())
                .append(" · 实际 ").append(String.format(Locale.ROOT, "%.1f", actualFps)).append(" fps");
        if (streamKbps > 0) {
            text.append(streamKbps >= 1024
                    ? String.format(Locale.ROOT, " · %.1f MB/s", streamKbps / 1024.0)
                    : " · " + streamKbps + " KB/s");
        }
        return text.toString();
    }

    private void setupSettingsPage() {
        findViewById(R.id.save_lan_btn).setOnClickListener(v -> {
            String value = normalizeEndpoint(lanInput.getText().toString().trim());
            connection.setLanEndpoint(value);
            connection.reconnect();
            connInfo.setText("已保存并重新连接…");
        });
    }

    // ---------------- 页面切换 ----------------

    private void selectPage(String page) {
        if (PAGE_SCREEN.equals(currentPage) && !PAGE_SCREEN.equals(page)) {
            if (fullscreen) {
                exitFullscreen();
            }
            stopStream();
        }
        currentPage = page;

        pagePerformance.setVisibility(PAGE_PERFORMANCE.equals(page) ? View.VISIBLE : View.GONE);
        pageControl.setVisibility(PAGE_CONTROL.equals(page) ? View.VISIBLE : View.GONE);
        pageScreen.setVisibility(PAGE_SCREEN.equals(page) ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(PAGE_SETTINGS.equals(page) ? View.VISIBLE : View.GONE);

        navPerformance.setSelected(PAGE_PERFORMANCE.equals(page));
        navScreen.setSelected(PAGE_SCREEN.equals(page));
        navControl.setSelected(PAGE_CONTROL.equals(page));
        navSettings.setSelected(PAGE_SETTINGS.equals(page));

        if (PAGE_CONTROL.equals(page)) {
            pageTitleView.setText("功能控制");
        } else if (PAGE_SCREEN.equals(page)) {
            pageTitleView.setText("屏幕监视");
        } else if (PAGE_SETTINGS.equals(page)) {
            pageTitleView.setText("连接设置");
        } else {
            pageTitleView.setText("性能监视");
        }

        if (PAGE_SETTINGS.equals(page)) {
            lanInput.setText(connection.getLanEndpoint());
            lanInput.setSelection(lanInput.getText().length());
            renderConnInfo();
        }
        if (PAGE_SCREEN.equals(page)) {
            updateScreenUi();
            startStreamIfPossible();
        }
    }

    // ---------------- ConnectionManager.Listener ----------------

    @Override
    public void onLayout(LayoutMessage layout) {
        if (layout == null || layout.buttons == null) {
            return;
        }
        if (layout.pageId != null) {
            currentPageId = layout.pageId;
        }
        layoutTitle.setText(layout.pageTitle == null ? "" : layout.pageTitle);

        int cols = layout.grid != null && layout.grid.cols > 0 ? layout.grid.cols : 3;
        if (gridLayoutManager.getSpanCount() != cols) {
            gridLayoutManager.setSpanCount(cols);
        }
        layoutRows = layout.grid != null && layout.grid.rows > 0 ? layout.grid.rows : 3;
        adapter.submit(layout.buttons);
        applyGridMetrics();
    }

    @Override
    public void onTelemetry(TelemetryMessage telemetry) {
        if (telemetry == null) {
            return;
        }
        setMetric(cpuBar, cpuText, telemetry.cpu);
        setMetric(memBar, memText, telemetry.mem);
        setMetric(gpuBar, gpuText, telemetry.gpu);

        boolean muted = telemetry.isMuted;
        volBar.setProgress(muted ? 0 : telemetry.volume);
        volText.setText(muted ? "静音" : telemetry.volume + "%");

        cpuBig.setText(formatPercent(telemetry.cpu));
        memBig.setText(formatPercent(telemetry.mem));
        gpuBig.setText(formatPercent(telemetry.gpu));
        volBig.setText(muted ? "0%" : telemetry.volume + "%");
        volBigBar.setProgress(muted ? 0 : telemetry.volume);
        muteState.setText(muted ? "已静音" : "正常");
        netInfo.setText("↑ " + safe(telemetry.netUp) + " · ↓ " + safe(telemetry.netDown));

        cpuGraph.addPoint((float) telemetry.cpu);
        memGraph.addPoint((float) telemetry.mem);
        gpuGraph.addPoint((float) telemetry.gpu);
    }

    @Override
    public void onButtonState(ButtonStateMessage state) {
        if (state == null || state.buttonId == null) {
            return;
        }
        adapter.updateButton(state.buttonId, state.state, state.label, state.icon, state.bgColor);
    }

    @Override
    public void onConnectionChanged(boolean connected, String mode, String endpoint) {
        this.connected = connected;
        dotView.setTextColor(connected ? 0xFF22C55E : 0xFFEF4444);
        modeView.setText(mode == null ? "-" : mode);
        modeView.setTextColor(connected ? 0xFF7DD3FC : 0xFF64748B);

        if (!connected) {
            transport = "lan";
            screenAllowed = false;
            streaming = false;
            streamRequested = false;
            stopDecoder();
            sidebarFoot.setText("连接断开，稍后自动重试");
        } else {
            sidebarFoot.setText(("usb".equals(transport) ? "USB 高速通道" : "Wi-Fi 局域网通道") + "\n" + endpoint);
        }
        updateScreenUi();
        renderConnInfo();
    }

    @Override
    public void onSession(SessionMessage session) {
        if (session == null) {
            return;
        }
        transport = session.transport == null ? "lan" : session.transport;
        screenAllowed = session.screenStream;
        // 通道帧率上限先按会话报文预估，推流启动后以 screen_state 下发的实际值为准
        applyStreamFormat(0, 0, session.screenMaxFps);
        modeView.setText("usb".equals(transport) ? "USB" : "Wi-Fi");
        sidebarFoot.setText(("usb".equals(transport) ? "USB 高速通道" : "Wi-Fi 局域网通道"));
        updateScreenUi();
        renderConnInfo();
        if (PAGE_SCREEN.equals(currentPage)) {
            startStreamIfPossible();
        }
    }

    @Override
    public void onScreenState(ScreenStateMessage state) {
        if (state == null) {
            return;
        }
        String reason = state.reason;
        if ("usb_required".equals(reason)) {
            screenAllowed = false;
            this.streaming = false;
            streamRequested = false;
            stopDecoder();
            streamState.setText("服务端拒绝：屏幕监视需要 USB 连接");
        } else if ("encoder_unavailable".equals(reason) || "encoder_error".equals(reason)) {
            this.streaming = false;
            streamRequested = false;
            stopDecoder();
            screenHint.setVisibility(View.VISIBLE);
            screenHint.setText("encoder_unavailable".equals(reason)
                    ? "服务端未找到 ffmpeg，请检查 PC 端 config.json 的 screen.ffmpegExecutable"
                    : "H.264 编码器启动失败，请查看 PC 服务端运行日志");
            streamState.setText("推流不可用");
        } else if (state.streaming) {
            applyStreamFormat(state.width, state.height, state.fps);
            this.streaming = true;
            streamRequested = false;
            actualFps = 0;
            streamKbps = 0;
            firstFrame = false;
            screenHint.setVisibility(View.VISIBLE);
            screenHint.setText("正在等待 H.264 关键帧…");
            if (screenSurface != null && screenSurface.isValid()) {
                h264Decoder.start(screenSurface, streamWidth, streamHeight, streamFps);
            }
            streamState.setText("推流中：" + streamFormat());
        } else {
            this.streaming = false;
            streamRequested = false;
            stopDecoder();
        }
        updateScreenUi();
    }

    @Override
    public void onScreenStats(double actualFps, int kbps, int width, int height, int fps) {
        // 服务端热更新参数后编码器会重启，分辨率变化时按新尺寸重建解码器
        if (width > 0 && height > 0 && (width != streamWidth || height != streamHeight)) {
            applyStreamFormat(width, height, fps);
            restartDecoder();
        } else {
            applyStreamFormat(0, 0, fps);
        }
        this.actualFps = actualFps;
        this.streamKbps = kbps;
        if (streaming) {
            streamState.setText(streamSummary());
        }
    }

    @Override
    public void onScreenDevices(ScreenDevicesMessage devices) {
        if (devices == null || devices.devices == null) {
            return;
        }
        monitors.clear();
        monitors.addAll(devices.devices);
        selectedMonitor = devices.current;
        updateMonitorLabel();
        if (monitorDialogPending) {
            monitorDialogPending = false;
            showMonitorPicker();
        }
    }

    @Override
    public void onScreenFrame(byte[] data) {
        if (!PAGE_SCREEN.equals(currentPage) || data == null || data.length == 0) {
            return;
        }
        if (h264Decoder != null) {
            h264Decoder.feed(data);
        }
    }

    // ---------------- 屏幕监视 ----------------

    private void startStreamIfPossible() {
        if (!connected || !screenAllowed || streaming || streamRequested) {
            updateScreenUi();
            return;
        }
        streamRequested = true;
        actualFps = 0;
        streamKbps = 0;
        firstFrame = false;
        screenHint.setVisibility(View.VISIBLE);
        screenHint.setText("正在启动 H.264 编码…");
        connection.sendScreenControl(true, selectedMonitor);
        streamState.setText("正在启动 H.264 编码…");
        updateScreenUi();
    }

    private void startStream() {
        if (!connected || !screenAllowed) {
            updateScreenUi();
            return;
        }
        startStreamIfPossible();
    }

    private void stopStream() {
        if (connection != null && (streamRequested || streaming)) {
            connection.sendScreenControl(false);
        }
        streamRequested = false;
        streaming = false;
        actualFps = 0;
        streamKbps = 0;
        stopDecoder();
        updateScreenUi();
    }

    /** 停止解码；TextureView 保持可见以维持 Surface 生命周期 */
    private void stopDecoder() {
        if (h264Decoder != null) {
            h264Decoder.stop();
        }
        firstFrame = false;
    }

    private void updateScreenUi() {
        if (screenBadge == null) {
            return;
        }
        if (!connected) {
            screenBadge.setText("未连接");
            screenBadge.setTextColor(0xFF94A3B8);
        } else if (screenAllowed) {
            screenBadge.setText("可用");
            screenBadge.setTextColor(0xFF86EFAC);
        } else {
            screenBadge.setText("不可用");
            screenBadge.setTextColor(0xFFFCA5A5);
        }

        boolean usable = connected && screenAllowed;
        streamBtn.setEnabled(usable);
        streamBtn.setText((streaming || streamRequested) ? "停止监视" : "开始监视");

        boolean canFullscreen = streaming;
        fullscreenBtn.setVisibility(canFullscreen ? View.VISIBLE : View.GONE);
        fullscreenBtn.setText(fullscreen ? "⤢ 退出全屏" : "⛶ 全屏");
        if (!canFullscreen && fullscreen) {
            exitFullscreen();
        }

        if (!PAGE_SCREEN.equals(currentPage)) {
            return;
        }
        if (streaming) {
            screenHint.setVisibility(firstFrame ? View.GONE : View.VISIBLE);
            if (!firstFrame) {
                screenHint.setText("正在等待 H.264 关键帧…");
            }
            streamState.setText(streamSummary());
        } else {
            screenHint.setVisibility(View.VISIBLE);
            if (!connected) {
                screenHint.setText("尚未连接 PC 服务端。\n请在「连接设置」中确认地址与端口。");
            } else if (!screenAllowed) {
                screenHint.setText("服务端未开放屏幕推流。\n请确认服务端版本与连接状态。");
            } else if (streamRequested) {
                screenHint.setText("正在启动 H.264 编码…");
            } else {
                screenHint.setText("通道就绪。\n点击「开始监视」查看 " + streamFormat() + " 实时画面。");
            }
            if (!screenAllowed) {
                streamState.setText("推流不可用");
            } else if (!streamRequested) {
                streamState.setText("推流已停止");
            }
        }
    }

    // ---------------- 其它 ----------------

    private void setMetric(ProgressBar bar, TextView text, double value) {
        boolean valid = value >= 0;
        text.setText(valid ? Math.round(value) + "%" : "—");
        bar.setProgress(valid ? (int) Math.round(Math.min(100, value)) : 0);
    }

    private String formatPercent(double value) {
        return value >= 0 ? Math.round(value) + "%" : "—";
    }

    private String safe(String value) {
        return value == null ? "0 B/s" : value;
    }

    private void renderConnInfo() {
        if (connInfo == null) {
            return;
        }
        String lan = connection == null ? "" : connection.getLanEndpoint();
        connInfo.setText("USB 地址：ws://127.0.0.1:8500\n"
                + "局域网地址：" + (lan.isEmpty() ? "未设置" : lan) + "\n"
                + "状态：" + (connected ? "已连接" : "未连接") + "\n"
                + "通道：" + ("usb".equals(transport) ? "USB（adb reverse）" : "Wi-Fi 局域网") + "\n"
                + "屏幕监视：" + (screenAllowed ? "可用" : "不可用"));
    }

    /** 补全 ws:// 前缀与默认端口，空串表示清除局域网地址 */
    private String normalizeEndpoint(String raw) {
        if (raw.isEmpty()) {
            return "";
        }
        String url = raw;
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "ws://" + url;
        }
        String hostPart = url.substring(url.indexOf("://") + 3);
        if (hostPart.indexOf(':') < 0) {
            url = url + ":8500";
        }
        return url;
    }

    private void onButtonAction(ButtonItem item, String event) {
        if (item == null || item.id == null) {
            return;
        }
        connection.sendAction(currentPageId, item.id, event);
    }

    private void hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
