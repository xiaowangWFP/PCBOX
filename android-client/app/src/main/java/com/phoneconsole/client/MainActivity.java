package com.phoneconsole.client;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.text.InputType;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.phoneconsole.client.model.ButtonItem;
import com.phoneconsole.client.model.ButtonStateMessage;
import com.phoneconsole.client.model.LayoutMessage;
import com.phoneconsole.client.model.ScreenDevicesMessage;
import com.phoneconsole.client.model.ScreenStateMessage;
import com.phoneconsole.client.model.SessionMessage;
import com.phoneconsole.client.model.TelemetryMessage;
import com.phoneconsole.client.net.ChannelStore;
import com.phoneconsole.client.ui.TouchpadPageController;
import com.phoneconsole.client.net.ConnectionManager;
import com.phoneconsole.client.screen.H264Decoder;
import com.phoneconsole.client.ui.ControlWorkspaceController;
import com.phoneconsole.client.ui.RingProgressView;
import com.phoneconsole.client.ui.TactileDeckAdapter;
import com.phoneconsole.client.ui.UiMotionHelper;
import com.phoneconsole.client.ui.WindowInsetsHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主界面（Tactile Obsidian 新版）：
 * 竖屏底部导航 / 横屏左侧图标轨，四个页面：性能监视、功能控制、屏幕监视、连接设置。
 * 页面全部由新版布局驱动，控制页交给 {@link ControlWorkspaceController} 统一调度。
 */
public class MainActivity extends AppCompatActivity implements ConnectionManager.Listener {

    private static final String PAGE_PERFORMANCE = "performance";
    private static final String PAGE_CONTROL = "control";
    private static final String PAGE_TOUCHPAD = "touchpad";
    private static final String PAGE_SCREEN = "screen";
    private static final String PAGE_SETTINGS = "settings";

    /** 推流格式兜底值：实际尺寸与帧率由服务端 screen_state / screen_stats 下发 */
    private static final int DEFAULT_STREAM_WIDTH = 1280;
    private static final int DEFAULT_STREAM_HEIGHT = 720;
    private static final int DEFAULT_STREAM_FPS = 30;

    /** 服务端默认页（媒体快捷指令已并入该页，不再单独维护媒体面板） */
    private static final String DEFAULT_PAGE_ID = "default_page";

    private static final String DEFAULT_PORT = "8500";

    /** 快捷指令 → 通用控制页按键 id 的映射 */
    private static final Map<String, String> MEDIA_BUTTON_ALIAS = Map.of(
            "media_vol_up", "btn_vol_up",
            "media_vol_down", "btn_vol_down",
            "media_mute", "btn_mute",
            "media_prev", "btn_prev",
            "media_play", "btn_play",
            "media_next", "btn_next");

    // 布局骨架：竖屏底栏与横屏图标轨互斥，另一侧为 null
    private View topBar;
    private View pageContainer;
    private View bottomNavBar;
    private View sideNavRail;
    private View navPerf;
    private View navControl;
    private View navTouchpad;
    private View navScreen;
    private View navSettings;
    private TextView tvPageTitle;
    private TextView tvConnStatus;
    private View statusDot;
    private View bottomNavIndicator;
    private View railNavIndicator;
    private TextView tvTopFps;
    private TextView tvTopCpu;
    private TextView tvTopGpu;
    private TextView tvTopMem;
    private TextView tvTopVram;
    private double lastCpu = -1;
    private double lastMem = -1;
    private double lastGpu = -1;
    private double lastVram = -1;

    // 页面
    private View pagePerformance;
    private View pageControl;
    private View pageTouchpad;
    private View pageScreen;
    private View pageSettings;

    // 触控板页
    private TouchpadPageController touchpadController;

    // 性能页
    private TextView tvCpuValue;
    private TextView tvMemValue;
    private TextView tvGpuValue;
    private TextView tvVramValue;
    private TextView tvFpsValue;
    private TextView tvVolValue;
    private TextView tvVolSub;
    private TextView tvNetUp;
    private TextView tvNetDown;
    private RingProgressView ringCpu;
    private RingProgressView ringMem;
    private RingProgressView ringGpu;
    private RingProgressView ringVram;
    private RingProgressView ringFps;
    private RingProgressView ringVol;
    private ProgressBar barCpu;
    private ProgressBar barMem;
    private ProgressBar barGpu;
    private ProgressBar barVram;
    private ProgressBar barFps;
    private ProgressBar barVol;

    // 控制页
    private ControlWorkspaceController controlController;
    private final List<TactileDeckAdapter.DeckKeyItem> deckItems = new ArrayList<>();
    /** 服务端下发的面板清单，旋转重建布局后需要原样恢复 */
    private List<ControlWorkspaceController.DeckPageMeta> deckPages = new ArrayList<>();
    private final Map<String, String> buttonStates = new HashMap<>();
    private String deckPageTitle = "";

    // 屏幕页
    private TextView btnToggleStream;
    private TextView tvScreenMeta;
    private TextView tvScreenHint;
    private TextView btnScreenFullscreen;
    private Spinner spinnerMonitors;
    private SurfaceView surfaceScreen;
    private View screenStage;
    private Surface screenSurface;
    private H264Decoder h264Decoder;
    private boolean firstFrame;
    private boolean suppressMonitorCallback;

    // 采集显示器选择
    private final List<ScreenDevicesMessage.Device> monitors = new ArrayList<>();
    private int selectedMonitor = -1;

    // 设置页
    private EditText etServerHost;
    private EditText etServerPort;
    private Spinner spinnerChannels;
    private TextView btnAddChannel;
    private TextView btnDeleteChannel;
    private TextView btnScanQr;
    private final List<ChannelStore.Channel> channels = new ArrayList<>();
    private int channelIndex;
    private boolean suppressChannelCallback;
    private ActivityResultLauncher<Intent> scanLauncher;
    private TextView tvSessionDetail;

    private ConnectionManager connection;
    private String currentPage = PAGE_PERFORMANCE;
    private String currentPageId = DEFAULT_PAGE_ID;

    private boolean connected;
    private boolean screenAllowed;
    private boolean streaming;
    private boolean streamRequested;
    private boolean fullscreen;
    private String transport = "lan";

    private double actualFps;
    private int streamKbps;
    private int streamWidth = DEFAULT_STREAM_WIDTH;
    private int streamHeight = DEFAULT_STREAM_HEIGHT;
    private int streamFps = DEFAULT_STREAM_FPS;

    private int lastVolumeSent = -1;
    private long lastVolumeInputAt;


    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        scanLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        return;
                    }
                    handleScannedEndpoint(result.getData().getStringExtra(ScanActivity.EXTRA_RESULT));
                });

        bindViews();
        WindowInsetsHelper.attach(this, findViewById(R.id.rootContainer),
                topBar, pageContainer, bottomNavBar, sideNavRail);

        setupNavigation();
        setupPerformancePage();
        setupControlPage();
        setupTouchpadPage();
        setupScreenPage();
        setupSettingsPage();

        connection = new ConnectionManager(getApplicationContext(), this);
        connection.start();

        String initialPage = getIntent() == null ? null : getIntent().getStringExtra("page");
        selectPage(pageOrDefault(initialPage));

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
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            selectPage(pageOrDefault(intent.getStringExtra("page")));
        }
    }

    /**
     * 清单里声明了 configChanges，系统不会重建 Activity，
     * 横竖屏切换需要手动重新加载对应资源目录下的布局（layout / layout-land）。
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (h264Decoder != null) {
            h264Decoder.stop();
        }

        setContentView(R.layout.activity_main);
        bindViews();
        WindowInsetsHelper.attach(this, findViewById(R.id.rootContainer),
                topBar, pageContainer, bottomNavBar, sideNavRail);

        setupNavigation();
        setupPerformancePage();
        setupControlPage();
        setupTouchpadPage();
        setupScreenPage();
        setupSettingsPage();

        fullscreen = false;
        selectPage(currentPage);

        if (controlController != null) {
            if (!deckItems.isEmpty()) {
                controlController.updateDeckButtons(deckPageTitle, new ArrayList<>(deckItems), 3, 1);
            }
            List<ControlWorkspaceController.DeckPageMeta> pages = deckPages;
            if (pages == null || pages.isEmpty()) {
                pages = new ArrayList<>();
                pages.add(new ControlWorkspaceController.DeckPageMeta(currentPageId, deckPageTitle));
            }
            controlController.updateDeckPages(pages, currentPageId);
        }

        applyStreamFormat(0, 0, 0);
        renderConnStatus();
        updateScreenUi();
        renderSessionDetail();
    }

    /** 顶栏连接状态（旋转后重建布局时需要重新套用，避免停留在默认的「未连接」） */
    private void renderConnStatus() {
        if (statusDot != null) {
            statusDot.setBackgroundResource(connected
                    ? R.drawable.bg_status_dot_on : R.drawable.bg_status_dot_off);
        }
        if (tvConnStatus == null) {
            return;
        }
        String text = connected
                ? ("usb".equals(transport) ? "USB 通道" : "Wi-Fi 通道")
                : "未连接";
        tvConnStatus.setText(text);
        tvConnStatus.setTextColor(ContextCompat.getColor(this,
                connected ? R.color.status_ok : R.color.text_muted));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopStream();
        if (h264Decoder != null) {
            h264Decoder.stop();
        }
        if (connection != null) {
            connection.stop();
        }
        super.onDestroy();
    }

    // ---------------- 初始化 ----------------

    private void bindViews() {
        topBar = findViewById(R.id.topBar);
        pageContainer = findViewById(R.id.pageContainer);
        bottomNavBar = findViewById(R.id.bottomNavBar);
        sideNavRail = findViewById(R.id.sideNavRail);
        navPerf = findViewById(R.id.navPerf);
        navControl = findViewById(R.id.navControl);
        navTouchpad = findViewById(R.id.navTouchpad);
        navScreen = findViewById(R.id.navScreen);
        navSettings = findViewById(R.id.navSettings);
        tvPageTitle = findViewById(R.id.tvPageTitle);
        tvConnStatus = findViewById(R.id.tvConnStatus);
        statusDot = findViewById(R.id.statusDot);
        bottomNavIndicator = findViewById(R.id.bottomNavIndicator);
        railNavIndicator = findViewById(R.id.railNavIndicator);
        tvTopFps = findViewById(R.id.tvTopFps);
        tvTopCpu = findViewById(R.id.tvTopCpu);
        tvTopGpu = findViewById(R.id.tvTopGpu);
        tvTopMem = findViewById(R.id.tvTopMem);
        tvTopVram = findViewById(R.id.tvTopVram);
        renderTopMetrics();

        pagePerformance = findViewById(R.id.pagePerformance);
        pageControl = findViewById(R.id.pageControl);
        pageTouchpad = findViewById(R.id.pageTouchpad);
        pageScreen = findViewById(R.id.pageScreen);
        pageSettings = findViewById(R.id.pageSettings);

        tvCpuValue = findViewById(R.id.tvCpuValue);
        tvMemValue = findViewById(R.id.tvMemValue);
        tvGpuValue = findViewById(R.id.tvGpuValue);
        tvVramValue = findViewById(R.id.tvVramValue);
        tvFpsValue = findViewById(R.id.tvFpsValue);
        tvVolValue = findViewById(R.id.tvVolValue);
        tvVolSub = findViewById(R.id.tvVolSub);
        tvNetUp = findViewById(R.id.tvNetUp);
        tvNetDown = findViewById(R.id.tvNetDown);

        ringCpu = findViewById(R.id.ringCpu);
        ringMem = findViewById(R.id.ringMem);
        ringGpu = findViewById(R.id.ringGpu);
        ringVram = findViewById(R.id.ringVram);
        ringFps = findViewById(R.id.ringFps);
        ringVol = findViewById(R.id.ringVol);
        barCpu = findViewById(R.id.barCpu);
        barMem = findViewById(R.id.barMem);
        barGpu = findViewById(R.id.barGpu);
        barVram = findViewById(R.id.barVram);
        barFps = findViewById(R.id.barFps);
        barVol = findViewById(R.id.barVol);

        btnToggleStream = findViewById(R.id.btnToggleStream);
        tvScreenMeta = findViewById(R.id.tvScreenMeta);
        tvScreenHint = findViewById(R.id.tvScreenHint);
        btnScreenFullscreen = findViewById(R.id.btnScreenFullscreen);
        spinnerMonitors = findViewById(R.id.spinnerMonitors);
        surfaceScreen = findViewById(R.id.surfaceScreen);
        screenStage = findViewById(R.id.screenStage);

        etServerHost = findViewById(R.id.etServerHost);
        etServerPort = findViewById(R.id.etServerPort);
        spinnerChannels = findViewById(R.id.spinnerChannels);
        btnAddChannel = findViewById(R.id.btnAddChannel);
        btnDeleteChannel = findViewById(R.id.btnDeleteChannel);
        btnScanQr = findViewById(R.id.btnScanQr);
        tvSessionDetail = findViewById(R.id.tvSessionDetail);
    }

    private void setupNavigation() {
        navPerf.setOnClickListener(v -> selectPage(PAGE_PERFORMANCE));
        navControl.setOnClickListener(v -> selectPage(PAGE_CONTROL));
        navTouchpad.setOnClickListener(v -> selectPage(PAGE_TOUCHPAD));
        navScreen.setOnClickListener(v -> selectPage(PAGE_SCREEN));
        navSettings.setOnClickListener(v -> selectPage(PAGE_SETTINGS));
    }

    private void setupPerformancePage() {
        // 环形指示器的进度弧颜色，与底部细进度条保持一致
        setRingColor(ringCpu, R.color.accent_blue);
        setRingColor(ringMem, R.color.status_ok);
        setRingColor(ringGpu, R.color.status_warn);
        setRingColor(ringVram, R.color.accent_bright);
        setRingColor(ringFps, R.color.perf_cyan);
        setRingColor(ringVol, R.color.status_purple);

        findViewById(R.id.btnPerfVolDown).setOnClickListener(v -> sendMedia("media_vol_down"));
        findViewById(R.id.btnPerfVolUp).setOnClickListener(v -> sendMedia("media_vol_up"));
        findViewById(R.id.btnPerfMute).setOnClickListener(v -> sendMedia("media_mute"));
        updateFpsCard();
    }

    private void setupControlPage() {
        controlController = new ControlWorkspaceController(pageControl,
                new ControlWorkspaceController.ControlActionDispatcher() {
                    @Override
                    public void onSwitchDeckPage(String pageId) {
                        connection.requestLayout(pageId);
                    }

                    @Override
                    public void onDeckKeyClick(String buttonId) {
                        connection.sendAction(currentPageId, buttonId, "click");
                    }

                    @Override
                    public void onDeckKeyRepeat(String buttonId) {
                        // 服务端长按靠 long_press_start/end 维持，这里用连续 click 达成同样的连发效果
                        connection.sendAction(currentPageId, buttonId, "click");
                    }

                    @Override
                    public void onDeckKeyDelete(String buttonId, String label) {
                        connection.sendDeleteCustomKey(currentPageId, buttonId);
                    }

                    @Override
                    public void onAddCustomKey(ControlWorkspaceController.CustomKeyDraft draft) {
                        connection.sendAddCustomKey(
                                currentPageId,
                                draft.label,
                                draft.actionType,
                                draft.target,
                                draft.bgColorHex);
                    }

                    @Override
                    public void onSetVolume(int volumePercent) {
                        applyVolumeByDelta(volumePercent);
                    }

                    @Override
                    public void onToggleSpeakerMute() {
                        sendMedia("media_mute");
                    }

                    @Override
                    public void onToggleMicMute() {
                        connection.sendToggleMicMute();
                    }

                    @Override
                    public void onQuickMediaCommand(String command) {
                        if ("prev".equals(command)) {
                            sendMedia("media_prev");
                        } else if ("next".equals(command)) {
                            sendMedia("media_next");
                        } else {
                            sendMedia("media_play");
                        }
                    }

                    @Override
                    public void onQuickHotkey(String combo) {
                        if ("alt+tab".equals(combo)) {
                            connection.sendAction(DEFAULT_PAGE_ID, "btn_alt_tab", "click");
                        } else {
                            connection.sendQuickHotkey(combo);
                        }
                    }

                    @Override
                    public void onSendRemoteText(String text, boolean pressEnter) {
                        if (text == null || text.isEmpty()) {
                            return;
                        }
                        connection.sendRemoteText(text, pressEnter);
                    }

                    @Override
                    public void onTouchpadMove(int dx, int dy) {
                        connection.sendTouchpadMove(dx, dy);
                    }

                    @Override
                    public void onTouchpadClick(String mouseButton) {
                        connection.sendTouchpadClick(mouseButton);
                    }

                    @Override
                    public void onTouchpadScroll(int deltaY) {
                        connection.sendTouchpadScroll(deltaY);
                    }
                });
    }

    private void setupScreenPage() {
        h264Decoder = new H264Decoder(new H264Decoder.Listener() {
            @Override
            public void onFirstFrame() {
                firstFrame = true;
                streaming = true;
                streamRequested = false;
                updateScreenUi();
            }

            @Override
            public void onError(String message) {
                tvScreenHint.setVisibility(View.VISIBLE);
                tvScreenHint.setText(message);
                tvScreenMeta.setText("H.264 解码失败");
            }
        });

        surfaceScreen.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                screenSurface = holder.getSurface();
                applyScreenAspect();
                if ((streamRequested || streaming) && !h264Decoder.isRunning()
                        && screenSurface != null && screenSurface.isValid()) {
                    h264Decoder.start(screenSurface, streamWidth, streamHeight, streamFps);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                screenSurface = holder.getSurface();
                applyScreenAspect();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (h264Decoder != null) {
                    h264Decoder.stop();
                }
                screenSurface = null;
            }
        });

        screenStage.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> applyScreenAspect());

        spinnerMonitors.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(ContextCompat.getColor(
                            MainActivity.this, R.color.text_primary));
                }
                if (suppressMonitorCallback) {
                    return;
                }
                selectMonitor(position <= 0 ? -1 : monitors.get(position - 1).id);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        btnToggleStream.setOnClickListener(v -> {
            if (streaming || streamRequested) {
                stopStream();
            } else {
                startStream();
            }
        });

        btnScreenFullscreen.setOnClickListener(v -> {
            if (fullscreen) {
                exitFullscreen();
            } else {
                enterFullscreen();
            }
        });

        rebuildMonitorSpinner();
        applyStreamFormat(0, 0, 0);
    }

    private void setupSettingsPage() {
        // 横竖屏布局各自独立维护，这里统一做空值保护，避免某个方向缺控件时直接崩溃
        if (spinnerChannels == null || btnAddChannel == null || btnDeleteChannel == null
                || btnScanQr == null || etServerHost == null || etServerPort == null) {
            View reconnect = findViewById(R.id.btnReconnect);
            if (reconnect != null) {
                reconnect.setOnClickListener(v -> saveAndReconnect());
            }
            return;
        }

        spinnerChannels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressChannelCallback || position < 0 || position >= channels.size()) {
                    return;
                }
                channelIndex = position;
                ChannelStore.Channel channel = channels.get(position);
                etServerHost.setText(channel.host);
                etServerPort.setText(String.valueOf(channel.port));
                ChannelStore.save(MainActivity.this, channels, channelIndex);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        btnAddChannel.setOnClickListener(v -> showAddChannelDialog());
        btnDeleteChannel.setOnClickListener(v -> deleteCurrentChannel());
        btnScanQr.setOnClickListener(v -> startScan());
        findViewById(R.id.btnReconnect).setOnClickListener(v -> saveAndReconnect());

        reloadChannels();
    }

    /** 读取本地通道；为空时用当前地址生成一个默认通道 */
    private void reloadChannels() {
        channels.clear();
        channels.addAll(ChannelStore.load(this));
        if (channels.isEmpty()) {
            String host = "127.0.0.1";
            int port = Integer.parseInt(DEFAULT_PORT);
            String endpoint = connection == null ? null : connection.getLanEndpoint();
            if (endpoint != null && !endpoint.isEmpty()) {
                String body = endpoint.replaceFirst("^wss?://", "");
                int idx = body.lastIndexOf(':');
                if (idx > 0) {
                    host = body.substring(0, idx);
                    try {
                        port = Integer.parseInt(body.substring(idx + 1));
                    } catch (NumberFormatException ignored) {
                        port = Integer.parseInt(DEFAULT_PORT);
                    }
                } else if (!body.isEmpty()) {
                    host = body;
                }
            }
            channels.add(new ChannelStore.Channel("默认通道", host, port));
        }
        channelIndex = Math.max(0, Math.min(ChannelStore.selected(this), channels.size() - 1));
        ChannelStore.save(this, channels, channelIndex);
        renderChannelSpinner();
    }

    private void renderChannelSpinner() {
        if (channels.isEmpty()) {
            return;
        }
        channelIndex = Math.max(0, Math.min(channelIndex, channels.size() - 1));
        ChannelStore.Channel current = channels.get(channelIndex);
        if (etServerHost != null) {
            etServerHost.setText(current.host);
        }
        if (etServerPort != null) {
            etServerPort.setText(String.valueOf(current.port));
        }
        if (spinnerChannels == null) {
            return;
        }
        List<String> labels = new ArrayList<>();
        for (ChannelStore.Channel channel : channels) {
            labels.add(channel.label());
        }
        suppressChannelCallback = true;
        spinnerChannels.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));
        spinnerChannels.setSelection(channelIndex);
        suppressChannelCallback = false;
    }

    private void saveAndReconnect() {
        String host = etServerHost.getText().toString().trim();
        String portText = etServerPort.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "请填写服务端地址", Toast.LENGTH_SHORT).show();
            return;
        }
        int port;
        try {
            port = portText.isEmpty() ? Integer.parseInt(DEFAULT_PORT) : Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口需为数字", Toast.LENGTH_SHORT).show();
            return;
        }
        ChannelStore.Channel channel = channels.get(channelIndex);
        channel.host = host;
        channel.port = port;
        if (channel.name == null || channel.name.trim().isEmpty()) {
            channel.name = host;
        }
        ChannelStore.save(this, channels, channelIndex);
        renderChannelSpinner();
        connection.setLanEndpoint(normalizeEndpoint(host + ":" + port));
        connection.reconnect();
        tvSessionDetail.setText("已保存并重新连接…");
    }

    private void showAddChannelDialog() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText nameInput = new EditText(this);
        nameInput.setHint("名称（可留空）");
        EditText hostInput = new EditText(this);
        hostInput.setHint("地址，例如 192.168.1.10");
        hostInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        EditText portInput = new EditText(this);
        portInput.setHint("端口，默认 " + DEFAULT_PORT);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);

        box.addView(nameInput);
        box.addView(hostInput);
        box.addView(portInput);

        new AlertDialog.Builder(this)
                .setTitle("添加通道")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String host = hostInput.getText().toString().trim();
                    if (host.isEmpty()) {
                        Toast.makeText(this, "请填写地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port;
                    try {
                        String text = portInput.getText().toString().trim();
                        port = text.isEmpty() ? Integer.parseInt(DEFAULT_PORT) : Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        port = Integer.parseInt(DEFAULT_PORT);
                    }
                    String name = nameInput.getText().toString().trim();
                    channels.add(new ChannelStore.Channel(name.isEmpty() ? host : name, host, port));
                    channelIndex = channels.size() - 1;
                    ChannelStore.save(this, channels, channelIndex);
                    renderChannelSpinner();
                })
                .show();
    }

    private void deleteCurrentChannel() {
        if (channels.size() <= 1) {
            Toast.makeText(this, "至少保留一个通道", Toast.LENGTH_SHORT).show();
            return;
        }
        channels.remove(channelIndex);
        channelIndex = Math.max(0, channelIndex - 1);
        ChannelStore.save(this, channels, channelIndex);
        renderChannelSpinner();
    }

    private void startScan() {
        if (scanLauncher == null) {
            return;
        }
        scanLauncher.launch(new Intent(this, ScanActivity.class));
    }

    /** 解析扫码结果（支持 ws://host:port 或 host:port），保存为新通道并立即重连 */
    private void handleScannedEndpoint(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            Toast.makeText(this, "二维码内容为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String body = raw.trim().replaceFirst("^wss?://", "");
        int slash = body.indexOf('/');
        if (slash > 0) {
            body = body.substring(0, slash);
        }
        String host = body;
        String portText = DEFAULT_PORT;
        int idx = body.lastIndexOf(':');
        if (idx > 0) {
            host = body.substring(0, idx);
            portText = body.substring(idx + 1);
        }
        if (host.isEmpty() || !portText.matches("\\d+")) {
            Toast.makeText(this, "无法识别的二维码：" + raw, Toast.LENGTH_SHORT).show();
            return;
        }
        int port = Integer.parseInt(portText);

        int found = -1;
        for (int i = 0; i < channels.size(); i++) {
            ChannelStore.Channel channel = channels.get(i);
            if (host.equals(channel.host) && port == channel.port) {
                found = i;
                break;
            }
        }
        if (found >= 0) {
            channelIndex = found;
        } else {
            channels.add(new ChannelStore.Channel("扫码 " + host, host, port));
            channelIndex = channels.size() - 1;
        }
        ChannelStore.save(this, channels, channelIndex);
        renderChannelSpinner();
        connection.setLanEndpoint(normalizeEndpoint(host + ":" + port));
        connection.reconnect();
        tvSessionDetail.setText("已扫码并连接 " + host + ":" + port);
    }


    /** 触控板独立页：整页触摸区 + 可拖动悬浮键组 + 右上角设置面板 */
    private void setupTouchpadPage() {
        if (pageTouchpad == null) {
            return;
        }
        touchpadController = new TouchpadPageController(this, this, pageTouchpad,
                new TouchpadPageController.ActionSink() {
                    @Override
                    public void onMove(int dx, int dy) {
                        if (connection != null) {
                            connection.sendTouchpadMove(dx, dy);
                        }
                    }

                    @Override
                    public void onClick(@NonNull String mouseButton) {
                        if (connection != null) {
                            connection.sendTouchpadClick(mouseButton);
                        }
                    }

                    @Override
                    public void onScroll(int deltaY) {
                        if (connection != null) {
                            connection.sendTouchpadScroll(deltaY);
                        }
                    }

                    @Override
                    public void onSendText(@NonNull String text) {
                        if (connection != null) {
                            connection.sendRemoteText(text, false);
                        }
                    }
                });
    }

    /**
     * 触控板页开启「音量键滚动」后，把音量加/减键转成滚轮事件并拦截系统音量调节；
     * 其余页面或开关关闭时保持系统默认行为。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean isVolumeKey = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (PAGE_TOUCHPAD.equals(currentPage)
                && touchpadController != null
                && touchpadController.isVolumeScrollEnabled()
                && isVolumeKey
                && connection != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                connection.sendTouchpadScroll(code == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == TouchpadPageController.REQ_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (touchpadController != null) {
                touchpadController.onRecordPermissionResult(granted);
            }
        }
    }
    // ---------------- 页面切换 ----------------

    private String pageOrDefault(String page) {
        if (PAGE_CONTROL.equals(page) || PAGE_TOUCHPAD.equals(page)
                || PAGE_SCREEN.equals(page) || PAGE_SETTINGS.equals(page)) {
            return page;
        }
        return PAGE_PERFORMANCE;
    }

    private View pageViewOf(String page) {
        if (PAGE_CONTROL.equals(page)) {
            return pageControl;
        }
        if (PAGE_TOUCHPAD.equals(page)) {
            return pageTouchpad;
        }
        if (PAGE_SCREEN.equals(page)) {
            return pageScreen;
        }
        if (PAGE_SETTINGS.equals(page)) {
            return pageSettings;
        }
        return pagePerformance;
    }

    private int pageIndex(String page) {
        if (PAGE_CONTROL.equals(page)) {
            return 1;
        }
        if (PAGE_TOUCHPAD.equals(page)) {
            return 2;
        }
        if (PAGE_SCREEN.equals(page)) {
            return 3;
        }
        if (PAGE_SETTINGS.equals(page)) {
            return 4;
        }
        return 0;
    }

    private void selectPage(String page) {
        if (PAGE_SCREEN.equals(currentPage) && !PAGE_SCREEN.equals(page)) {
            if (fullscreen) {
                exitFullscreen();
            }
            stopStream();
        }

        View previous = pageViewOf(currentPage);
        View next = pageViewOf(page);
        boolean forward = pageIndex(page) > pageIndex(currentPage);
        currentPage = page;

        UiMotionHelper.switchPageWithMotion(previous, next, forward);
        for (String each : new String[]{PAGE_PERFORMANCE, PAGE_CONTROL, PAGE_TOUCHPAD, PAGE_SCREEN, PAGE_SETTINGS}) {
            View view = pageViewOf(each);
            if (view != null && view != next) {
                view.setVisibility(View.GONE);
            }
        }
        if (next != null) {
            next.setVisibility(View.VISIBLE);
        }

        tvPageTitle.setText(pageTitleOf(page));
        UiMotionHelper.animateTitleSwap(tvPageTitle);
        applyNavSelection(page);

        if (PAGE_SETTINGS.equals(page)) {
            fillEndpointFields();
            renderSessionDetail();
        }
        if (PAGE_SCREEN.equals(page)) {
            // 进入屏幕页只刷新状态，推流必须由用户点「开始监视」手动开启
            updateScreenUi();
        }
        if (PAGE_CONTROL.equals(page) && controlController != null) {
            controlController.resyncIndicators();
        }
    }

    private String pageTitleOf(String page) {
        if (PAGE_CONTROL.equals(page)) {
            return getString(R.string.nav_ctrl_full);
        }
        if (PAGE_TOUCHPAD.equals(page)) {
            return getString(R.string.nav_touchpad_full);
        }
        if (PAGE_SCREEN.equals(page)) {
            return getString(R.string.nav_screen_full);
        }
        if (PAGE_SETTINGS.equals(page)) {
            return getString(R.string.nav_settings_full);
        }
        return getString(R.string.nav_perf_full);
    }

    private void applyNavSelection(String page) {
        View[] items = new View[]{navPerf, navControl, navTouchpad, navScreen, navSettings};
        int[] icons = new int[]{R.id.ivNavPerf, R.id.ivNavControl, R.id.ivNavTouchpad, R.id.ivNavScreen, R.id.ivNavSettings};
        int[] labels = new int[]{R.id.tvNavPerf, R.id.tvNavControl, R.id.tvNavTouchpad, R.id.tvNavScreen, R.id.tvNavSettings};
        String[] pages = new String[]{PAGE_PERFORMANCE, PAGE_CONTROL, PAGE_TOUCHPAD, PAGE_SCREEN, PAGE_SETTINGS};

        int accent = ContextCompat.getColor(this, R.color.accent_bright);
        int muted = ContextCompat.getColor(this, R.color.text_muted);

        for (int i = 0; i < items.length; i++) {
            if (items[i] == null) {
                continue;
            }
            boolean selected = pages[i].equals(page);
            items[i].setSelected(selected);

            View icon = items[i].findViewById(icons[i]);
            if (icon instanceof ImageView) {
                ((ImageView) icon).setColorFilter(selected ? accent : muted);
            }
            View label = items[i].findViewById(labels[i]);
            if (label instanceof TextView) {
                ((TextView) label).setTextColor(selected ? accent : muted);
            }
        }

        View active = items[Math.max(0, Math.min(items.length - 1, pageIndex(page)))];
        if (bottomNavIndicator != null && active != null) {
            UiMotionHelper.animateHorizontalIndicator(bottomNavIndicator, active, 0, true);
        }
        if (railNavIndicator != null && active != null) {
            UiMotionHelper.animateVerticalIndicator(railNavIndicator, active, true);
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
        deckPageTitle = layout.pageTitle == null ? "" : layout.pageTitle;

        buttonStates.clear();
        deckItems.clear();
        for (ButtonItem item : layout.buttons) {
            if (item == null || item.id == null) {
                continue;
            }
            deckItems.add(toDeckItem(item));
        }

        int cols = layout.grid != null && layout.grid.cols > 0 ? layout.grid.cols : 3;
        controlController.updateDeckButtons(deckPageTitle, new ArrayList<>(deckItems), cols, 1);

        List<ControlWorkspaceController.DeckPageMeta> pages = new ArrayList<>();
        if (layout.pages != null) {
            for (LayoutMessage.PageRef ref : layout.pages) {
                if (ref != null && ref.id != null) {
                    pages.add(new ControlWorkspaceController.DeckPageMeta(
                            ref.id, ref.title == null ? ref.id : ref.title));
                }
            }
        }
        if (pages.isEmpty()) {
            pages.add(new ControlWorkspaceController.DeckPageMeta(currentPageId, deckPageTitle));
        }
        deckPages = pages;
        controlController.updateDeckPages(deckPages, currentPageId);
    }

    private TactileDeckAdapter.DeckKeyItem toDeckItem(ButtonItem item) {
        String hint = item.icon == null ? "" : item.icon;
        boolean active = "active".equalsIgnoreCase(item.state) || "on".equalsIgnoreCase(item.state);
        return new TactileDeckAdapter.DeckKeyItem(
                item.id,
                item.label == null ? item.id : item.label,
                hint,
                item.bgColor,
                active,
                false);
    }

    @Override
    public void onTelemetry(TelemetryMessage telemetry) {
        if (telemetry == null) {
            return;
        }
        setMetric(tvCpuValue, telemetry.cpu);
        setMetric(tvMemValue, telemetry.mem);
        setMetric(tvGpuValue, telemetry.gpu);

        lastCpu = telemetry.cpu;
        lastMem = telemetry.mem;
        lastGpu = telemetry.gpu;
        lastVram = telemetry.vram;
        renderTopMetrics();

        boolean muted = telemetry.isMuted;
        int volume = muted ? 0 : telemetry.volume;
        tvVolValue.setText(muted ? "静音" : volume + "%");
        tvVolSub.setText(muted ? "已静音" : "正常");
        tvNetUp.setText("↑ " + safe(telemetry.netUp));
        tvNetDown.setText("↓ " + safe(telemetry.netDown));

        applyGauge(barCpu, ringCpu, telemetry.cpu);
        applyGauge(barMem, ringMem, telemetry.mem);
        applyGauge(barGpu, ringGpu, telemetry.gpu);
        applyGauge(barVol, ringVol, muted ? 0 : volume);
        updateVramCard(telemetry.vram, telemetry.vramTotal);

        if (controlController != null) {
            controlController.updateAudioTelemetry(volume, muted, telemetry.micMuted);
        }
        if (System.currentTimeMillis() - lastVolumeInputAt > 1500) {
            lastVolumeSent = volume;
        }
    }

    @Override
    public void onButtonState(ButtonStateMessage state) {
        if (state == null || state.buttonId == null) {
            return;
        }
        buttonStates.put(state.buttonId, state.state == null ? "" : state.state);
        for (int i = 0; i < deckItems.size(); i++) {
            TactileDeckAdapter.DeckKeyItem item = deckItems.get(i);
            if (!item.id.equals(state.buttonId)) {
                continue;
            }
            String label = state.label == null ? item.label : state.label;
            String bgColor = state.bgColor == null ? item.bgColorHex : state.bgColor;
            String hint = state.icon == null ? item.hint : state.icon;
            boolean active = "active".equalsIgnoreCase(state.state) || "on".equalsIgnoreCase(state.state);
            deckItems.set(i, new TactileDeckAdapter.DeckKeyItem(
                    item.id, label, hint, bgColor, active, false));
            break;
        }
        if (controlController != null) {
            int cols = 3;
            controlController.updateDeckButtons(deckPageTitle, new ArrayList<>(deckItems), cols, 1);
        }
    }

    @Override
    public void onConnectionChanged(boolean connected, String mode, String endpoint) {
        this.connected = connected;
        renderConnStatus();

        if (!connected) {
            transport = "lan";
            screenAllowed = false;
            streaming = false;
            streamRequested = false;
            stopDecoder();
        }
        updateScreenUi();
        renderSessionDetail();
    }

    @Override
    public void onSession(SessionMessage session) {
        if (session == null) {
            return;
        }
        transport = session.transport == null ? "lan" : session.transport;
        screenAllowed = session.screenStream;
        applyStreamFormat(0, 0, session.screenMaxFps);
        renderConnStatus();
        updateScreenUi();
        renderSessionDetail();
    }

    @Override
    public void onScreenState(ScreenStateMessage state) {
        if (state == null) {
            return;
        }
        String reason = state.reason;
        if ("usb_required".equals(reason)) {
            screenAllowed = false;
            streaming = false;
            streamRequested = false;
            stopDecoder();
            tvScreenMeta.setText("需 USB 连接");
            tvScreenHint.setText("服务端拒绝：屏幕监视需要 USB 连接");
        } else if ("encoder_unavailable".equals(reason) || "encoder_error".equals(reason)) {
            streaming = false;
            streamRequested = false;
            stopDecoder();
            tvScreenHint.setVisibility(View.VISIBLE);
            tvScreenHint.setText("encoder_unavailable".equals(reason)
                    ? "服务端未找到 ffmpeg，请检查 PC 端 config.json 的 screen.ffmpegExecutable"
                    : "H.264 编码器启动失败，请查看 PC 服务端运行日志");
            tvScreenMeta.setText("推流不可用");
        } else if (state.streaming) {
            applyStreamFormat(state.width, state.height, state.fps);
            streaming = true;
            streamRequested = false;
            actualFps = 0;
            streamKbps = 0;
            firstFrame = false;
            tvScreenHint.setVisibility(View.VISIBLE);
            tvScreenHint.setText("正在等待 H.264 关键帧…");
            if (screenSurface != null && screenSurface.isValid()) {
                h264Decoder.start(screenSurface, streamWidth, streamHeight, streamFps);
            }
        } else {
            streaming = false;
            streamRequested = false;
            stopDecoder();
        }
        updateScreenUi();
    }

    @Override
    public void onScreenStats(double actualFps, int kbps, int width, int height, int fps) {
        if (width > 0 && height > 0 && (width != streamWidth || height != streamHeight)) {
            applyStreamFormat(width, height, fps);
            restartDecoder();
        } else {
            applyStreamFormat(0, 0, fps);
        }
        this.actualFps = actualFps;
        this.streamKbps = kbps;
        renderTopMetrics();
        updateFpsCard();
        if (streaming) {
            tvScreenMeta.setText(streamSummary());
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
        rebuildMonitorSpinner();
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

    private void rebuildMonitorSpinner() {
        if (spinnerMonitors == null) {
            return;
        }
        List<String> labels = new ArrayList<>();
        labels.add("整块桌面");
        int checked = 0;
        for (int i = 0; i < monitors.size(); i++) {
            ScreenDevicesMessage.Device device = monitors.get(i);
            labels.add((device.name == null ? "显示器 " + (i + 1) : device.name)
                    + " · " + device.width + "×" + device.height);
            if (device.id == selectedMonitor) {
                checked = i + 1;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        suppressMonitorCallback = true;
        spinnerMonitors.setAdapter(adapter);
        spinnerMonitors.setSelection(checked);
        suppressMonitorCallback = false;

        // 深色背景下的选中项文字颜色由代码统一指定，避免默认黑字看不清
        spinnerMonitors.post(() -> {
            View selected = spinnerMonitors.getSelectedView();
            if (selected instanceof TextView) {
                ((TextView) selected).setTextColor(
                        ContextCompat.getColor(this, R.color.text_primary));
            }
        });
    }

    private void selectMonitor(int id) {
        if (id == selectedMonitor) {
            return;
        }
        selectedMonitor = id;
        if (streaming || streamRequested) {
            stopStream();
            startStream();
        }
    }

    private void applyScreenAspect() {
        if (surfaceScreen == null || screenStage == null) {
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
        ViewGroup.LayoutParams params = surfaceScreen.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            surfaceScreen.setLayoutParams(params);
        }
    }

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
        if (resized) {
            applyScreenAspect();
            if (screenStage != null) {
                screenStage.post(this::applyScreenAspect);
            }
        }
    }

    private void restartDecoder() {
        if (!streaming || screenSurface == null || !screenSurface.isValid()) {
            return;
        }
        firstFrame = false;
        tvScreenHint.setVisibility(View.VISIBLE);
        tvScreenHint.setText("画面参数已更新，正在等待 H.264 关键帧…");
        h264Decoder.start(screenSurface, streamWidth, streamHeight, streamFps);
    }

    private String streamFormat() {
        return streamWidth + "×" + streamHeight + "@" + streamFps + " H.264";
    }

    private String streamSummary() {
        StringBuilder text = new StringBuilder(streamFormat());
        if (actualFps > 0) {
            text.append(" · ").append(String.format(Locale.ROOT, "%.1f", actualFps)).append(" fps");
        }
        if (streamKbps > 0) {
            text.append(streamKbps >= 1024
                    ? String.format(Locale.ROOT, " · %.1f MB/s", streamKbps / 1024.0)
                    : " · " + streamKbps + " KB/s");
        }
        return text.toString();
    }

    private void startStreamIfPossible() {
        if (!connected || !screenAllowed || streaming || streamRequested) {
            updateScreenUi();
            return;
        }
        streamRequested = true;
        actualFps = 0;
        streamKbps = 0;
        firstFrame = false;
        tvScreenHint.setVisibility(View.VISIBLE);
        tvScreenHint.setText("正在启动 H.264 编码…");
        connection.sendScreenControl(true, selectedMonitor);
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

    private void stopDecoder() {
        if (h264Decoder != null) {
            h264Decoder.stop();
        }
        firstFrame = false;
        if (surfaceScreen != null) {
            surfaceScreen.setVisibility(View.GONE);
        }
    }

    private void updateScreenUi() {
        if (btnToggleStream == null) {
            return;
        }
        boolean usable = connected && screenAllowed;
        btnToggleStream.setEnabled(usable);
        btnToggleStream.setAlpha(usable ? 1f : 0.5f);
        btnToggleStream.setText((streaming || streamRequested) ? "停止监视" : "开始监视");
        btnScreenFullscreen.setVisibility(streaming ? View.VISIBLE : View.GONE);
        renderTopMetrics();
        updateFpsCard();

        if (streaming) {
            surfaceScreen.setVisibility(View.VISIBLE);
            tvScreenHint.setVisibility(firstFrame ? View.GONE : View.VISIBLE);
            if (!firstFrame) {
                tvScreenHint.setText("正在等待 H.264 关键帧…");
            }
            tvScreenMeta.setText(streamSummary());
            return;
        }

        surfaceScreen.setVisibility(View.GONE);
        tvScreenHint.setVisibility(View.VISIBLE);
        if (!connected) {
            tvScreenHint.setText("尚未连接 PC 服务端。\n请在「连接设置」中确认地址与端口。");
            tvScreenMeta.setText("待机中");
        } else if (!screenAllowed) {
            tvScreenHint.setText("服务端未开放屏幕推流。\n请确认服务端版本与连接状态。");
            tvScreenMeta.setText("推流不可用");
        } else if (streamRequested) {
            tvScreenHint.setText("正在启动 H.264 编码…");
            tvScreenMeta.setText("启动中");
        } else {
            tvScreenHint.setText("通道就绪。\n点击「开始监视」查看 " + streamFormat() + " 实时画面。");
            tvScreenMeta.setText("待机中");
        }
    }

    // ---------------- 全屏 ----------------

    private void enterFullscreen() {
        if (fullscreen) {
            return;
        }
        fullscreen = true;
        if (topBar != null) {
            topBar.setVisibility(View.GONE);
        }
        if (bottomNavBar != null) {
            bottomNavBar.setVisibility(View.GONE);
        }
        if (sideNavRail != null) {
            sideNavRail.setVisibility(View.GONE);
        }
        View toolbar = screenToolbar();
        if (toolbar != null) {
            toolbar.setVisibility(View.GONE);
        }
        btnScreenFullscreen.setText("退出全屏");
        applyScreenAspect();
        if (screenStage != null) {
            screenStage.post(this::applyScreenAspect);
        }
    }

    private void exitFullscreen() {
        if (!fullscreen) {
            return;
        }
        fullscreen = false;
        if (topBar != null) {
            topBar.setVisibility(View.VISIBLE);
        }
        if (bottomNavBar != null) {
            bottomNavBar.setVisibility(View.VISIBLE);
        }
        if (sideNavRail != null) {
            sideNavRail.setVisibility(View.VISIBLE);
        }
        View toolbar = screenToolbar();
        if (toolbar != null) {
            toolbar.setVisibility(View.VISIBLE);
        }
        btnScreenFullscreen.setText("全屏");
        applyScreenAspect();
        if (screenStage != null) {
            screenStage.post(this::applyScreenAspect);
        }
    }

    /** 屏幕页顶部工具条（开始/屏幕选择/格式标签），全屏时整体隐藏 */
    private View screenToolbar() {
        if (pageScreen instanceof ViewGroup && ((ViewGroup) pageScreen).getChildCount() > 0) {
            return ((ViewGroup) pageScreen).getChildAt(0);
        }
        return null;
    }

    // ---------------- 设置页 ----------------

    private void fillEndpointFields() {
        if (connection == null) {
            return;
        }
        String endpoint = connection.getLanEndpoint();
        String host = "127.0.0.1";
        String port = DEFAULT_PORT;
        if (endpoint != null && !endpoint.isEmpty()) {
            String body = endpoint.replaceFirst("^wss?://", "");
            int index = body.lastIndexOf(':');
            if (index > 0) {
                host = body.substring(0, index);
                port = body.substring(index + 1);
            } else {
                host = body;
            }
        }
        etServerHost.setText(host);
        etServerPort.setText(port);

        if (!channels.isEmpty() && channelIndex >= 0 && channelIndex < channels.size()) {
            ChannelStore.Channel channel = channels.get(channelIndex);
            channel.host = host;
            try {
                channel.port = Integer.parseInt(port);
            } catch (NumberFormatException ignored) {
                // 端口非法时保持原值
            }
            ChannelStore.save(this, channels, channelIndex);
            renderChannelSpinner();
        }
    }

    private void renderSessionDetail() {
        if (tvSessionDetail == null) {
            return;
        }
        String lan = connection == null ? "" : connection.getLanEndpoint();
        tvSessionDetail.setText("USB 地址：ws://127.0.0.1:8500\n"
                + "局域网地址：" + (lan == null || lan.isEmpty() ? "未设置" : lan) + "\n"
                + "状态：" + (connected ? "已连接" : "未连接") + "\n"
                + "通道：" + ("usb".equals(transport) ? "USB（adb reverse）" : "Wi-Fi 局域网") + "\n"
                + "屏幕监视：" + (screenAllowed ? "可用" : "不可用"));
    }

    private String normalizeEndpoint(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String url = raw.trim();
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "ws://" + url;
        }
        String hostPart = url.substring(url.indexOf("://") + 3);
        if (hostPart.isEmpty()) {
            return "";
        }
        if (hostPart.indexOf(':') < 0) {
            url = url + ":" + DEFAULT_PORT;
        }
        return url;
    }

    // ---------------- 动作映射 ----------------

    /** 新版 UI 的媒体/音量快捷按钮映射到通用控制页的对应按键 */
    private void sendMedia(String buttonId) {
        String mapped = MEDIA_BUTTON_ALIAS.getOrDefault(buttonId, buttonId);
        connection.sendAction(DEFAULT_PAGE_ID, mapped, "click");
    }

    /** 音量滑块按差值换算成若干次 volume_up / volume_down */
    private void applyVolumeByDelta(int percent) {
        lastVolumeInputAt = System.currentTimeMillis();
        if (lastVolumeSent < 0) {
            lastVolumeSent = percent;
            return;
        }
        int diff = percent - lastVolumeSent;
        if (Math.abs(diff) < 2) {
            return;
        }
        int steps = Math.min(10, Math.max(1, Math.abs(diff) / 4));
        String buttonId = diff > 0 ? "btn_vol_up" : "btn_vol_down";
        for (int i = 0; i < steps; i++) {
            connection.sendAction(DEFAULT_PAGE_ID, buttonId, "click");
        }
        lastVolumeSent = percent;
    }

    // ---------------- 其它 ----------------

    private void setMetric(TextView text, double value) {
        if (text == null) {
            return;
        }
        text.setText(percentText(value));
    }

    private void setRingColor(RingProgressView ring, int colorRes) {
        if (ring != null) {
            ring.setRingColor(ContextCompat.getColor(this, colorRes));
        }
    }

    /** 环形指示与底部细进度条共用同一百分比；数值非法（-1）时归零 */
    private void applyGauge(ProgressBar bar, RingProgressView ring, double value) {
        boolean valid = value >= 0;
        int percent = valid ? (int) Math.round(Math.min(100, value)) : 0;
        if (bar != null) {
            bar.setProgress(percent);
        }
        if (ring != null) {
            ring.setProgress(percent);
        }
    }

    /** 显存卡片：值显示已用 GB，环形/进度条按已用占总量的比例 */
    private void updateVramCard(double usedMb, double totalMb) {
        if (tvVramValue != null) {
            tvVramValue.setText(usedMb >= 0
                    ? String.format(Locale.ROOT, "%.1fG", usedMb / 1024.0)
                    : "—");
        }
        double percent = (usedMb >= 0 && totalMb > 0) ? usedMb / totalMb * 100.0 : -1;
        applyGauge(barVram, ringVram, percent);
    }

    /** 帧率卡片：数值来自推流统计，环形按 60fps 满量程换算 */
    private void updateFpsCard() {
        boolean active = streaming;
        if (tvFpsValue != null) {
            tvFpsValue.setText(active
                    ? String.format(Locale.ROOT, "%.1f", actualFps)
                    : "--");
        }
        applyGauge(barFps, ringFps, active ? Math.min(100.0, actualFps / 60.0 * 100.0) : -1);
    }

    /**
     * 顶栏五项常驻指标：每项固定在等宽区域内，且数值补齐到固定宽度，
     * 位数变化（如 9% -> 100%）不会推动相邻项。
     */
    private void renderTopMetrics() {
        if (tvTopCpu == null) {
            return;
        }
        tvTopFps.setText("FPS " + (streaming
                ? String.format(Locale.ROOT, "%4.1f", actualFps)
                : "  -- "));
        tvTopCpu.setText("CPU " + metricPercent(lastCpu));
        tvTopGpu.setText("GPU " + metricPercent(lastGpu));
        tvTopMem.setText("内存 " + metricPercent(lastMem));
        tvTopVram.setText("显存 " + metricVram(lastVram));
    }

    private String metricPercent(double value) {
        return value >= 0 ? String.format(Locale.ROOT, "%3d%%", Math.round(value)) : "  --";
    }

    private String metricVram(double megabytes) {
        return megabytes >= 0
                ? String.format(Locale.ROOT, "%5.1fG", megabytes / 1024.0)
                : "   --";
    }

    private String percentText(double value) {
        return value >= 0 ? Math.round(value) + "%" : "—";
    }

    private String safe(String value) {
        return value == null ? "0 B/s" : value;
    }
}
