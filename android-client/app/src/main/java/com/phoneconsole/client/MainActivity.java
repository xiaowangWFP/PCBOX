package com.phoneconsole.client;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonObject;
import com.phoneconsole.client.camera.CameraPanelController;
import com.phoneconsole.client.model.ButtonItem;
import com.phoneconsole.client.model.ButtonStateMessage;
import com.phoneconsole.client.model.LayoutMessage;
import com.phoneconsole.client.model.ScreenDevicesMessage;
import com.phoneconsole.client.model.ScreenStateMessage;
import com.phoneconsole.client.model.SessionMessage;
import com.phoneconsole.client.model.TelemetryMessage;
import com.phoneconsole.client.net.ChannelStore;
import com.phoneconsole.client.net.ConnectionManager;
import com.phoneconsole.client.screen.H264Decoder;
import com.phoneconsole.client.ui.FpsSparklineView;
import com.phoneconsole.client.ui.Haptics;
import com.phoneconsole.client.ui.ThemeManager;
import com.phoneconsole.client.ui.ThemeManager.AppTheme;
import com.phoneconsole.client.ui.TouchpadPageController;
import com.phoneconsole.client.ui.UiMotionHelper;
import com.phoneconsole.client.ui.WindowInsetsHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 主界面（3-Tab 极简工作台 + 4 套实时主题引擎）：
 * 1. 总览（PC 实时 FPS / 硬件监控 + 盲按音量/媒体/锁屏电源）
 * 2. 相机（手机虚拟摄像头独立主工作台，切走页面自动后台保活不断流）
 * 3. 触控（精密触控板 + 可切换同屏监视 + 可收纳快捷键横条 + 语音输入）
 * 顶部齿轮打开连接/扫码设置底部弹窗，顶部调色板按钮即时切换 4 套视觉外观。
 */
public class MainActivity extends AppCompatActivity implements ConnectionManager.Listener {

    private static final String PAGE_OVERVIEW = "overview";
    private static final String PAGE_CAMERA = "camera";
    private static final String PAGE_TOUCHPAD = "touchpad";

    private static final int DEFAULT_STREAM_WIDTH = 1280;
    private static final int DEFAULT_STREAM_HEIGHT = 720;
    private static final int DEFAULT_STREAM_FPS = 30;
    private static final String DEFAULT_PAGE_ID = "default_page";
    private static final String DEFAULT_PORT = "8500";
    private static final String UI_PREFS = "ui_theme_prefs";
    private static final String KEY_HOTKEY_BAR = "show_hotkey_bar";

    private static final Map<String, String> MEDIA_BUTTON_ALIAS = Map.of(
            "media_vol_up", "btn_vol_up",
            "media_vol_down", "btn_vol_down",
            "media_mute", "btn_mute",
            "media_prev", "btn_prev",
            "media_play", "btn_play",
            "media_next", "btn_next");

    private static final Set<String> HIDDEN_SERVER_BUTTON_IDS = new HashSet<>(Arrays.asList(
            "btn_mute", "btn_vol_down", "btn_vol_up", "btn_prev", "btn_play", "btn_next", "btn_alt_tab"));

    private AppTheme currentTheme = AppTheme.OLED;

    // 顶栏与导航骨架
    private View rootOuter;
    private View rootContainer;
    private View topBar;
    private View topBarDivider;
    private View pageContainer;
    private View bottomNavBar;
    private View bottomNavDivider;
    private View bottomNavPill;
    private View sideNavRail;
    private View railTopDivider;
    private View railRightDivider;
    private ImageView ivRailBrand;
    private View railNavIndicator;
    private View navOverview;
    private View navCamera;
    private View navTouchpad;
    private ImageView ivNavOverview;
    private ImageView ivNavCamera;
    private ImageView ivNavTouchpad;
    private TextView tvNavOverview;
    private TextView tvNavCamera;
    private TextView tvNavTouchpad;

    private TextView tvPageTitle;
    private View connStatusWrap;
    private View statusDot;
    private TextView tvConnStatus;
    private View btnThemePicker;
    private ImageView ivThemePicker;
    private View btnSettings;
    private ImageView ivSettings;

    // 3 个页面根视图
    private View pageOverview;
    private View pageCamera;
    private View pageTouchpad;

    // 总览页控件
    private View cardFps;
    private View cardCpu;
    private View cardGpu;
    private View cardMem;
    private View cardVram;
    private View cardNet;
    private View cardMediaPower;
    private TextView tvFpsLabel;
    private TextView tvFpsValue;
    private TextView tvFpsUnit;
    private TextView tvFpsSub;
    private TextView tvDisplayPicker;
    private FpsSparklineView fpsSparkline;
    private TextView tvCpuLabel;
    private TextView tvCpuValue;
    private TextView tvGpuLabel;
    private TextView tvGpuValue;
    private TextView tvMemLabel;
    private TextView tvMemValue;
    private TextView tvVramLabel;
    private TextView tvVramValue;
    private TextView tvNetLabel;
    private TextView tvNetUp;
    private TextView tvNetDown;
    private ProgressBar barCpu;
    private ProgressBar barGpu;
    private ProgressBar barMem;
    private ProgressBar barVram;

    private TextView tvVolTitle;
    private SeekBar seekVolume;
    private TextView tvVolumeValue;
    private TextView btnPrevTrack;
    private TextView btnPlayPause;
    private TextView btnNextTrack;
    private TextView btnSpeakerMute;
    private TextView btnMicMute;
    private TextView btnQuickLock;
    private TextView btnQuickSleep;
    private boolean userDraggingVolume;
    private int lastVolumeSent = -1;
    private long lastVolumeInputAt;
    private boolean speakerMuted;
    private boolean micMuted;

    // 双击确认
    private static final long CONFIRM_WINDOW_MS = 1600L;
    private TextView armedConfirmBtn;
    private long armedConfirmAt;
    private final Runnable clearConfirmRunnable = () -> {
        if (armedConfirmBtn != null) {
            if (armedConfirmBtn.getId() == R.id.btnQuickLock) {
                armedConfirmBtn.setText("锁屏");
            } else if (armedConfirmBtn.getId() == R.id.btnQuickSleep) {
                armedConfirmBtn.setText("睡眠");
            }
            ThemeManager.styleSoftAccentButton(armedConfirmBtn, currentTheme, false);
            armedConfirmBtn = null;
        }
    };

    // 显示器选择
    private List<TelemetryMessage.DisplayInfo> lastDisplays;
    private String selectedDisplayId = "";
    private boolean displaySelectionSent;
    private TelemetryMessage lastTelemetry;

    // 相机页
    private CameraPanelController cameraPanelController;

    // 触控与投屏合一页
    private TouchpadPageController touchpadController;
    private View tpTopBar;
    private View tpModeGroup;
    private TextView btnModeTouchOnly;
    private TextView btnModeScreenSplit;
    private View tpBarSpacer;
    private TextView btnToggleHotkeyBar;
    private View tpQuickHotkeyScroll;
    private LinearLayout tpQuickHotkeyRow;
    private View screenStage;
    private SurfaceView surfaceScreen;
    private TextView tvScreenHint;
    private TextView btnToggleStream;
    private TextView tvScreenMeta;
    private TextView btnScreenFullscreen;
    private Spinner spinnerMonitors;
    private View tpTouchArea;
    private Surface screenSurface;
    private H264Decoder h264Decoder;
    private boolean touchpadSplitScreen;
    private boolean showHotkeyBar = true;
    private boolean firstFrame;
    private boolean suppressMonitorCallback;
    private final List<ScreenDevicesMessage.Device> monitors = new ArrayList<>();
    private int selectedMonitor = -1;

    // 同步桌面端自定义按键列表（用于快捷键横条追加）
    private final List<ButtonItem> serverCustomButtons = new ArrayList<>();
    private String currentDeckPageId = DEFAULT_PAGE_ID;

    // 底部弹窗：4 皮肤选择 & 连接设置
    private View sheetThemeBackdrop;
    private View sheetThemeCard;
    private View sheetThemeHandle;
    private TextView tvThemeSheetTitle;
    private TextView btnCloseThemeSheet;
    private View themeOptOled;
    private View themeOptIndustrial;
    private View themeOptApple;
    private View themeOptDaylight;
    private View swatchOledBg, swatchOledAccent, swatchIndBg, swatchIndAccent;
    private View swatchAppleBg, swatchAppleAccent, swatchDayBg, swatchDayAccent;
    private TextView tagOledActive, tagIndActive, tagAppleActive, tagDayActive;
    private TextView tvOledName, tvOledDesc, tvIndName, tvIndDesc;
    private TextView tvAppleName, tvAppleDesc, tvDayName, tvDayDesc;

    private View sheetSettingsBackdrop;
    private View sheetSettingsCard;
    private View sheetSettingsHandle;
    private TextView tvSettingsSheetTitle;
    private TextView btnCloseSettingsSheet;
    private TextView tvSettingsChanLabel;
    private TextView tvSettingsHostLabel;
    private TextView tvSettingsPortLabel;
    private Spinner spinnerChannels;
    private TextView btnAddChannel;
    private TextView btnDeleteChannel;
    private EditText etServerHost;
    private EditText etServerPort;
    private TextView btnReconnect;
    private TextView btnScanQr;
    private TextView tvSessionDetail;
    private final List<ChannelStore.Channel> channels = new ArrayList<>();
    private int channelIndex;
    private boolean suppressChannelCallback;
    private ActivityResultLauncher<Intent> scanLauncher;

    private ConnectionManager connection;
    private String currentPage = PAGE_OVERVIEW;
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

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        currentTheme = ThemeManager.load(this);
        SharedPreferences uiPrefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        showHotkeyBar = uiPrefs.getBoolean(KEY_HOTKEY_BAR, true);

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
        WindowInsetsHelper.attach(this, rootContainer, topBar, pageContainer, bottomNavBar, sideNavRail);

        setupNavigation();
        setupOverviewPage();
        setupTouchpadAndScreenPage();
        setupThemeSheet();
        setupSettingsSheet();

        connection = new ConnectionManager(getApplicationContext(), this);
        cameraPanelController = new CameraPanelController(this, connection);
        cameraPanelController.bindCameraPage(pageCamera);
        connection.start();

        String initialPage = getIntent() == null ? null : getIntent().getStringExtra("page");
        String initialTheme = getIntent() == null ? null : getIntent().getStringExtra("theme");
        if (initialTheme != null && !initialTheme.isEmpty()) {
            currentTheme = AppTheme.fromId(initialTheme);
            ThemeManager.save(this, currentTheme);
        }
        applyCurrentTheme();
        selectPage(pageOrDefault(initialPage));
        applyExtraModalFromIntent(getIntent());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (sheetThemeBackdrop != null && sheetThemeBackdrop.getVisibility() == View.VISIBLE) {
                    closeThemeSheet();
                    return;
                }
                if (sheetSettingsBackdrop != null && sheetSettingsBackdrop.getVisibility() == View.VISIBLE) {
                    closeSettingsSheet();
                    return;
                }
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            String t = intent.getStringExtra("theme");
            if (t != null && !t.isEmpty()) {
                selectTheme(AppTheme.fromId(t));
            }
            String p = intent.getStringExtra("page");
            if (p != null && !p.isEmpty()) {
                selectPage(pageOrDefault(p));
            }
            applyExtraModalFromIntent(intent);
        }
    }

    private void applyExtraModalFromIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String sheet = intent.getStringExtra("sheet");
        if ("theme".equals(sheet)) {
            openThemeSheet();
        } else if ("settings".equals(sheet) || "settings".equals(intent.getStringExtra("page"))) {
            openSettingsSheet();
        } else {
            closeThemeSheet();
            closeSettingsSheet();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (h264Decoder != null) {
            h264Decoder.stop();
        }
        if (touchpadController != null) {
            touchpadController.destroy();
        }
        if (streaming) {
            firstFrame = false;
        }

        setContentView(R.layout.activity_main);
        bindViews();
        WindowInsetsHelper.attach(this, rootContainer, topBar, pageContainer, bottomNavBar, sideNavRail);

        setupNavigation();
        setupOverviewPage();
        setupTouchpadAndScreenPage();
        setupThemeSheet();
        setupSettingsSheet();
        if (cameraPanelController != null) {
            cameraPanelController.bindCameraPage(pageCamera);
            cameraPanelController.onConfigurationChanged();
        }

        fullscreen = false;
        applyCurrentTheme();
        selectPage(currentPage);

        if (lastTelemetry != null) {
            onTelemetry(lastTelemetry);
        }
        applyStreamFormat(0, 0, 0);
        renderConnStatus();
        updateScreenUi();
        renderSessionDetail();
        rebuildQuickHotkeyBar();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopStream();
        if (touchpadController != null) {
            touchpadController.destroy();
        }
        if (h264Decoder != null) {
            h264Decoder.stop();
        }
        if (cameraPanelController != null) {
            cameraPanelController.destroy();
        }
        if (connection != null) {
            connection.stop();
        }
        super.onDestroy();
    }

    // ---------------- 视图绑定 ----------------

    private void bindViews() {
        rootOuter = findViewById(R.id.rootOuter);
        rootContainer = findViewById(R.id.rootContainer);
        topBar = findViewById(R.id.topBar);
        topBarDivider = findViewById(R.id.topBarDivider);
        pageContainer = findViewById(R.id.pageContainer);
        bottomNavBar = findViewById(R.id.bottomNavBar);
        bottomNavDivider = findViewById(R.id.bottomNavDivider);
        bottomNavPill = findViewById(R.id.bottomNavPill);
        sideNavRail = findViewById(R.id.sideNavRail);
        railTopDivider = findViewById(R.id.railTopDivider);
        railRightDivider = findViewById(R.id.railRightDivider);
        ivRailBrand = findViewById(R.id.ivRailBrand);
        railNavIndicator = findViewById(R.id.railNavIndicator);

        navOverview = findViewById(R.id.navOverview);
        navCamera = findViewById(R.id.navCamera);
        navTouchpad = findViewById(R.id.navTouchpad);
        ivNavOverview = findViewById(R.id.ivNavOverview);
        ivNavCamera = findViewById(R.id.ivNavCamera);
        ivNavTouchpad = findViewById(R.id.ivNavTouchpad);
        tvNavOverview = findViewById(R.id.tvNavOverview);
        tvNavCamera = findViewById(R.id.tvNavCamera);
        tvNavTouchpad = findViewById(R.id.tvNavTouchpad);

        tvPageTitle = findViewById(R.id.tvPageTitle);
        connStatusWrap = findViewById(R.id.connStatusWrap);
        statusDot = findViewById(R.id.statusDot);
        tvConnStatus = findViewById(R.id.tvConnStatus);
        btnThemePicker = findViewById(R.id.btnThemePicker);
        ivThemePicker = findViewById(R.id.ivThemePicker);
        btnSettings = findViewById(R.id.btnSettings);
        ivSettings = findViewById(R.id.ivSettings);

        pageOverview = findViewById(R.id.pageOverview);
        pageCamera = findViewById(R.id.pageCamera);
        pageTouchpad = findViewById(R.id.pageTouchpad);

        // 总览页
        cardFps = findViewById(R.id.cardFps);
        cardCpu = findViewById(R.id.cardCpu);
        cardGpu = findViewById(R.id.cardGpu);
        cardMem = findViewById(R.id.cardMem);
        cardVram = findViewById(R.id.cardVram);
        cardNet = findViewById(R.id.cardNet);
        cardMediaPower = findViewById(R.id.cardMediaPower);
        tvFpsLabel = findViewById(R.id.tvFpsLabel);
        tvFpsValue = findViewById(R.id.tvFpsValue);
        tvFpsUnit = findViewById(R.id.tvFpsUnit);
        tvFpsSub = findViewById(R.id.tvFpsSub);
        tvDisplayPicker = findViewById(R.id.tvDisplayPicker);
        fpsSparkline = findViewById(R.id.fpsSparkline);
        tvCpuLabel = findViewById(R.id.tvCpuLabel);
        tvCpuValue = findViewById(R.id.tvCpuValue);
        tvGpuLabel = findViewById(R.id.tvGpuLabel);
        tvGpuValue = findViewById(R.id.tvGpuValue);
        tvMemLabel = findViewById(R.id.tvMemLabel);
        tvMemValue = findViewById(R.id.tvMemValue);
        tvVramLabel = findViewById(R.id.tvVramLabel);
        tvVramValue = findViewById(R.id.tvVramValue);
        tvNetLabel = findViewById(R.id.tvNetLabel);
        tvNetUp = findViewById(R.id.tvNetUp);
        tvNetDown = findViewById(R.id.tvNetDown);
        barCpu = findViewById(R.id.barCpu);
        barGpu = findViewById(R.id.barGpu);
        barMem = findViewById(R.id.barMem);
        barVram = findViewById(R.id.barVram);

        tvVolTitle = findViewById(R.id.tvVolTitle);
        seekVolume = findViewById(R.id.seekVolume);
        tvVolumeValue = findViewById(R.id.tvVolumeValue);
        btnPrevTrack = findViewById(R.id.btnPrevTrack);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnNextTrack = findViewById(R.id.btnNextTrack);
        btnSpeakerMute = findViewById(R.id.btnSpeakerMute);
        btnMicMute = findViewById(R.id.btnMicMute);
        btnQuickLock = findViewById(R.id.btnQuickLock);
        btnQuickSleep = findViewById(R.id.btnQuickSleep);

        selectedDisplayId = getSharedPreferences("display_prefs", MODE_PRIVATE)
                .getString("selected_display", "");

        // 触控与投屏页
        tpTopBar = findViewById(R.id.tpTopBar);
        tpModeGroup = findViewById(R.id.tpModeGroup);
        btnModeTouchOnly = findViewById(R.id.btnModeTouchOnly);
        btnModeScreenSplit = findViewById(R.id.btnModeScreenSplit);
        tpBarSpacer = findViewById(R.id.tpBarSpacer);
        btnToggleHotkeyBar = findViewById(R.id.btnToggleHotkeyBar);
        tpQuickHotkeyScroll = findViewById(R.id.tpQuickHotkeyScroll);
        tpQuickHotkeyRow = findViewById(R.id.tpQuickHotkeyRow);
        screenStage = findViewById(R.id.screenStage);
        surfaceScreen = findViewById(R.id.surfaceScreen);
        tvScreenHint = findViewById(R.id.tvScreenHint);
        btnToggleStream = findViewById(R.id.btnToggleStream);
        tvScreenMeta = findViewById(R.id.tvScreenMeta);
        btnScreenFullscreen = findViewById(R.id.btnScreenFullscreen);
        spinnerMonitors = findViewById(R.id.spinnerMonitors);
        tpTouchArea = findViewById(R.id.tpTouchArea);

        // 主题选择弹窗
        sheetThemeBackdrop = findViewById(R.id.sheetThemeBackdrop);
        sheetThemeCard = findViewById(R.id.sheetThemeCard);
        sheetThemeHandle = findViewById(R.id.sheetThemeHandle);
        tvThemeSheetTitle = findViewById(R.id.tvThemeSheetTitle);
        btnCloseThemeSheet = findViewById(R.id.btnCloseThemeSheet);
        themeOptOled = findViewById(R.id.themeOptOled);
        themeOptIndustrial = findViewById(R.id.themeOptIndustrial);
        themeOptApple = findViewById(R.id.themeOptApple);
        themeOptDaylight = findViewById(R.id.themeOptDaylight);
        swatchOledBg = findViewById(R.id.swatchOledBg);
        swatchOledAccent = findViewById(R.id.swatchOledAccent);
        swatchIndBg = findViewById(R.id.swatchIndBg);
        swatchIndAccent = findViewById(R.id.swatchIndAccent);
        swatchAppleBg = findViewById(R.id.swatchAppleBg);
        swatchAppleAccent = findViewById(R.id.swatchAppleAccent);
        swatchDayBg = findViewById(R.id.swatchDayBg);
        swatchDayAccent = findViewById(R.id.swatchDayAccent);
        tagOledActive = findViewById(R.id.tagOledActive);
        tagIndActive = findViewById(R.id.tagIndActive);
        tagAppleActive = findViewById(R.id.tagAppleActive);
        tagDayActive = findViewById(R.id.tagDayActive);
        tvOledName = findViewById(R.id.tvOledName);
        tvOledDesc = findViewById(R.id.tvOledDesc);
        tvIndName = findViewById(R.id.tvIndName);
        tvIndDesc = findViewById(R.id.tvIndDesc);
        tvAppleName = findViewById(R.id.tvAppleName);
        tvAppleDesc = findViewById(R.id.tvAppleDesc);
        tvDayName = findViewById(R.id.tvDayName);
        tvDayDesc = findViewById(R.id.tvDayDesc);

        // 设置弹窗
        sheetSettingsBackdrop = findViewById(R.id.sheetSettingsBackdrop);
        sheetSettingsCard = findViewById(R.id.sheetSettingsCard);
        sheetSettingsHandle = findViewById(R.id.sheetSettingsHandle);
        tvSettingsSheetTitle = findViewById(R.id.tvSettingsSheetTitle);
        btnCloseSettingsSheet = findViewById(R.id.btnCloseSettingsSheet);
        tvSettingsChanLabel = findViewById(R.id.tvSettingsChanLabel);
        tvSettingsHostLabel = findViewById(R.id.tvSettingsHostLabel);
        tvSettingsPortLabel = findViewById(R.id.tvSettingsPortLabel);
        spinnerChannels = findViewById(R.id.spinnerChannels);
        btnAddChannel = findViewById(R.id.btnAddChannel);
        btnDeleteChannel = findViewById(R.id.btnDeleteChannel);
        etServerHost = findViewById(R.id.etServerHost);
        etServerPort = findViewById(R.id.etServerPort);
        btnReconnect = findViewById(R.id.btnReconnect);
        btnScanQr = findViewById(R.id.btnScanQr);
        tvSessionDetail = findViewById(R.id.tvSessionDetail);
    }

    // ---------------- 导航与弹窗 ----------------

    private void setupNavigation() {
        navOverview.setOnClickListener(v -> selectPage(PAGE_OVERVIEW));
        navCamera.setOnClickListener(v -> selectPage(PAGE_CAMERA));
        navTouchpad.setOnClickListener(v -> selectPage(PAGE_TOUCHPAD));

        if (btnThemePicker != null) {
            btnThemePicker.setOnClickListener(v -> openThemeSheet());
        }
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> openSettingsSheet());
        }
    }

    private String pageOrDefault(String raw) {
        if (PAGE_CAMERA.equals(raw) || "control".equals(raw)) {
            return PAGE_CAMERA;
        }
        if (PAGE_TOUCHPAD.equals(raw) || "screen".equals(raw)) {
            return PAGE_TOUCHPAD;
        }
        return PAGE_OVERVIEW;
    }

    private View pageViewOf(String page) {
        if (PAGE_CAMERA.equals(page)) {
            return pageCamera;
        }
        if (PAGE_TOUCHPAD.equals(page)) {
            return pageTouchpad;
        }
        return pageOverview;
    }

    private int pageIndex(String page) {
        if (PAGE_CAMERA.equals(page)) {
            return 1;
        }
        if (PAGE_TOUCHPAD.equals(page)) {
            return 2;
        }
        return 0;
    }

    private void selectPage(String page) {
        if (PAGE_TOUCHPAD.equals(currentPage) && !PAGE_TOUCHPAD.equals(page)) {
            if (fullscreen) {
                exitFullscreen();
            }
            if (touchpadSplitScreen && (streaming || streamRequested)) {
                stopStream();
            }
        }

        View previous = pageViewOf(currentPage);
        View next = pageViewOf(page);
        boolean forward = pageIndex(page) > pageIndex(currentPage);
        currentPage = page;

        UiMotionHelper.switchPageWithMotion(previous, next, forward);
        for (String each : new String[]{PAGE_OVERVIEW, PAGE_CAMERA, PAGE_TOUCHPAD}) {
            View v = pageViewOf(each);
            if (v != null && v != next) {
                v.setVisibility(View.GONE);
            }
        }
        if (next != null) {
            next.setVisibility(View.VISIBLE);
        }

        tvPageTitle.setText(pageTitleOf(page));
        UiMotionHelper.animateTitleSwap(tvPageTitle);
        applyNavSelection(page);

        if (cameraPanelController != null) {
            cameraPanelController.setPageVisible(PAGE_CAMERA.equals(page));
        }
        if (PAGE_TOUCHPAD.equals(page)) {
            updateTouchpadScreenModeUi();
            updateScreenUi();
        }
    }

    private String pageTitleOf(String page) {
        if (PAGE_CAMERA.equals(page)) {
            return getString(R.string.nav_camera_full);
        }
        if (PAGE_TOUCHPAD.equals(page)) {
            return getString(R.string.nav_touchpad_full);
        }
        return getString(R.string.nav_overview_full);
    }

    private void applyNavSelection(String page) {
        View[] items = new View[]{navOverview, navCamera, navTouchpad};
        ImageView[] icons = new ImageView[]{ivNavOverview, ivNavCamera, ivNavTouchpad};
        TextView[] labels = new TextView[]{tvNavOverview, tvNavCamera, tvNavTouchpad};
        String[] pages = new String[]{PAGE_OVERVIEW, PAGE_CAMERA, PAGE_TOUCHPAD};

        for (int i = 0; i < items.length; i++) {
            if (items[i] == null) {
                continue;
            }
            boolean selected = pages[i].equals(page);
            items[i].setSelected(selected);

            int itemTextColor;
            if (bottomNavPill != null) {
                // 竖屏
                if (currentTheme.floatingNav) {
                    if (selected) {
                        items[i].setBackground(ThemeManager.roundRect(this, currentTheme.accent, Color.TRANSPARENT, 999f));
                        itemTextColor = currentTheme.onAccent;
                    } else {
                        items[i].setBackgroundColor(Color.TRANSPARENT);
                        itemTextColor = currentTheme.textMuted;
                    }
                } else {
                    if (selected) {
                        items[i].setBackground(ThemeManager.roundRect(this, currentTheme.cardAltBg, currentTheme.border, currentTheme.btnRadiusDp));
                        itemTextColor = currentTheme.accent;
                    } else {
                        items[i].setBackgroundColor(Color.TRANSPARENT);
                        itemTextColor = currentTheme.textMuted;
                    }
                }
            } else {
                // 横屏侧轨
                items[i].setBackgroundColor(Color.TRANSPARENT);
                itemTextColor = selected ? currentTheme.accent : currentTheme.textMuted;
            }

            if (icons[i] != null) {
                icons[i].setColorFilter(itemTextColor);
            }
            if (labels[i] != null) {
                labels[i].setTextColor(itemTextColor);
                ThemeManager.applyHeaderTypeface(labels[i], currentTheme, true);
            }
        }

        View active = items[Math.max(0, Math.min(items.length - 1, pageIndex(page)))];
        if (railNavIndicator != null && active != null) {
            railNavIndicator.setBackground(ThemeManager.roundRect(
                    this, currentTheme.cardAltBg, currentTheme.border, currentTheme.btnRadiusDp));
            UiMotionHelper.animateVerticalIndicator(railNavIndicator, active, true);
        }
    }

    // ---------------- 总览页（性能 + 音量/媒体/锁屏电源） ----------------

    private void setupOverviewPage() {
        if (tvDisplayPicker != null) {
            tvDisplayPicker.setOnClickListener(v -> showDisplayPicker());
        }
        if (seekVolume != null) {
            seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (tvVolumeValue != null) {
                        tvVolumeValue.setText(progress + "%");
                    }
                    if (fromUser) {
                        applyVolumeByDelta(progress);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                    userDraggingVolume = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    userDraggingVolume = false;
                    applyVolumeByDelta(bar.getProgress());
                }
            });
        }
        if (btnPrevTrack != null) {
            btnPrevTrack.setOnClickListener(v -> {
                Haptics.tap(this);
                sendMedia("media_prev");
            });
        }
        if (btnPlayPause != null) {
            btnPlayPause.setOnClickListener(v -> {
                Haptics.tap(this);
                sendMedia("media_play");
            });
        }
        if (btnNextTrack != null) {
            btnNextTrack.setOnClickListener(v -> {
                Haptics.tap(this);
                sendMedia("media_next");
            });
        }
        if (btnSpeakerMute != null) {
            btnSpeakerMute.setOnClickListener(v -> {
                Haptics.tap(this);
                sendMedia("media_mute");
            });
        }
        if (btnMicMute != null) {
            btnMicMute.setOnClickListener(v -> {
                Haptics.tap(this);
                if (connection != null) {
                    connection.sendToggleMicMute();
                }
            });
        }
        bindConfirmPowerButton(btnQuickLock, "win+l", "锁屏");
        bindConfirmPowerButton(btnQuickSleep, "sleep", "睡眠");

        updateDisplayCard(lastTelemetry);
    }

    private void bindConfirmPowerButton(@Nullable TextView btn, String action, String label) {
        if (btn == null) {
            return;
        }
        btn.setOnClickListener(v -> {
            long now = SystemClock.uptimeMillis();
            if (armedConfirmBtn == btn && now - armedConfirmAt <= CONFIRM_WINDOW_MS) {
                btn.removeCallbacks(clearConfirmRunnable);
                armedConfirmBtn = null;
                btn.setText(label);
                ThemeManager.styleSoftAccentButton(btn, currentTheme, false);
                Haptics.tap(this);
                if ("sleep".equals(action)) {
                    connection.sendAction(DEFAULT_PAGE_ID, "sleep", "click");
                } else {
                    connection.sendQuickHotkey(action);
                }
                return;
            }
            clearConfirmRunnable.run();
            armedConfirmBtn = btn;
            armedConfirmAt = now;
            btn.setText("再按确认");
            ThemeManager.stylePillButton(btn, currentTheme, false, true);
            btn.postDelayed(clearConfirmRunnable, CONFIRM_WINDOW_MS);
            Toast.makeText(this, "再点一次执行「" + label + "」", Toast.LENGTH_SHORT).show();
        });
    }

    // ---------------- 触控 + 投屏 + 快捷键合一页 ----------------

    private void setupTouchpadAndScreenPage() {
        if (pageTouchpad != null) {
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

                        @Override
                        public void onHotkey(@NonNull String combo) {
                            if (connection != null) {
                                connection.sendQuickHotkey(combo);
                            }
                        }
                    });
        }

        if (btnModeTouchOnly != null) {
            btnModeTouchOnly.setOnClickListener(v -> setTouchpadScreenMode(false));
        }
        if (btnModeScreenSplit != null) {
            btnModeScreenSplit.setOnClickListener(v -> setTouchpadScreenMode(true));
        }
        if (btnToggleHotkeyBar != null) {
            btnToggleHotkeyBar.setOnClickListener(v -> {
                showHotkeyBar = !showHotkeyBar;
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_HOTKEY_BAR, showHotkeyBar).apply();
                updateTouchpadScreenModeUi();
            });
        }

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
                if (tvScreenHint != null) {
                    tvScreenHint.setVisibility(View.VISIBLE);
                    tvScreenHint.setText(message);
                }
                if (tvScreenMeta != null) {
                    tvScreenMeta.setText("H.264 解码失败");
                }
            }
        });

        if (surfaceScreen != null) {
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
        }

        if (screenStage != null) {
            screenStage.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                   oldLeft, oldTop, oldRight, oldBottom) -> applyScreenAspect());
        }

        if (spinnerMonitors != null) {
            spinnerMonitors.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (view instanceof TextView) {
                        ((TextView) view).setTextColor(currentTheme.textPrimary);
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
        }

        if (btnToggleStream != null) {
            btnToggleStream.setOnClickListener(v -> {
                if (streaming || streamRequested) {
                    stopStream();
                } else {
                    startStream();
                }
            });
        }

        if (btnScreenFullscreen != null) {
            btnScreenFullscreen.setOnClickListener(v -> {
                if (fullscreen) {
                    exitFullscreen();
                } else {
                    enterFullscreen();
                }
            });
        }

        rebuildMonitorSpinner();
        applyStreamFormat(0, 0, 0);
        rebuildQuickHotkeyBar();
        updateTouchpadScreenModeUi();
    }

    private void setTouchpadScreenMode(boolean split) {
        if (touchpadSplitScreen == split) {
            return;
        }
        touchpadSplitScreen = split;
        if (split) {
            updateTouchpadScreenModeUi();
            if (connected && screenAllowed && !streaming && !streamRequested) {
                startStream();
            } else {
                updateScreenUi();
            }
            if (screenStage != null) {
                screenStage.post(this::applyScreenAspect);
            }
        } else {
            if (fullscreen) {
                exitFullscreen();
            }
            stopStream();
            updateTouchpadScreenModeUi();
        }
    }

    private void updateTouchpadScreenModeUi() {
        if (screenStage != null && !fullscreen) {
            screenStage.setVisibility(touchpadSplitScreen ? View.VISIBLE : View.GONE);
        }
        if (spinnerMonitors != null) {
            spinnerMonitors.setVisibility(touchpadSplitScreen && !fullscreen ? View.VISIBLE : View.GONE);
        }
        if (tpBarSpacer != null) {
            tpBarSpacer.setVisibility(touchpadSplitScreen ? View.GONE : View.VISIBLE);
        }
        if (tpQuickHotkeyScroll != null && !fullscreen) {
            tpQuickHotkeyScroll.setVisibility(showHotkeyBar ? View.VISIBLE : View.GONE);
        }
        if (btnModeTouchOnly != null && btnModeScreenSplit != null) {
            ThemeManager.stylePillButton(btnModeTouchOnly, currentTheme, !touchpadSplitScreen, false);
            ThemeManager.stylePillButton(btnModeScreenSplit, currentTheme, touchpadSplitScreen, false);
        }
        if (btnToggleHotkeyBar != null) {
            ThemeManager.styleSoftAccentButton(btnToggleHotkeyBar, currentTheme, showHotkeyBar);
        }
    }

    private void rebuildQuickHotkeyBar() {
        if (tpQuickHotkeyRow == null) {
            return;
        }
        tpQuickHotkeyRow.removeAllViews();

        // 默认高频配合鼠标使用的快捷键
        addHotkeyChip("截屏", () -> connection.sendQuickHotkey("win+shift+s"), false);
        addHotkeyChip("切窗", () -> connection.sendAction(DEFAULT_PAGE_ID, "btn_alt_tab", "click"), false);
        addHotkeyChip("桌面", () -> connection.sendQuickHotkey("win+d"), false);
        addHotkeyChip("复制", () -> connection.sendQuickHotkey("ctrl+c"), false);
        addHotkeyChip("粘贴", () -> connection.sendQuickHotkey("ctrl+v"), false);
        addHotkeyChip("撤销", () -> connection.sendQuickHotkey("ctrl+z"), false);
        addHotkeyChip("任务管理", () -> connection.sendQuickHotkey("ctrl+shift+esc"), false);

        // 保留服务端 config.json 里用户自定义的其它按键（剔除已合并到总览页的媒体键）
        for (ButtonItem item : serverCustomButtons) {
            if (item == null || item.id == null || HIDDEN_SERVER_BUTTON_IDS.contains(item.id)) {
                continue;
            }
            final String btnId = item.id;
            String label = item.label == null ? btnId : item.label;
            addHotkeyChip(label, () -> connection.sendAction(currentDeckPageId, btnId, "click"), false);
        }

        // 添加悬浮自定义按键入口
        addHotkeyChip("+ 添加悬浮键", () -> {
            if (touchpadController != null) {
                touchpadController.openKeyPicker();
            }
        }, true);
    }

    private void addHotkeyChip(String label, Runnable action, boolean accentText) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(12f);
        chip.setGravity(Gravity.CENTER);
        ThemeManager.applyHeaderTypeface(chip, currentTheme, true);
        int padH = ThemeManager.dp(this, 12);
        int height = ThemeManager.dp(this, 32);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height);
        lp.setMarginEnd(ThemeManager.dp(this, 6));
        chip.setLayoutParams(lp);
        chip.setPadding(padH, 0, padH, 0);
        chip.setBackground(ThemeManager.roundRect(this, currentTheme.cardBg, currentTheme.border, currentTheme.btnRadiusDp));
        chip.setTextColor(accentText ? currentTheme.accent : currentTheme.textPrimary);
        chip.setOnClickListener(v -> {
            Haptics.tap(this);
            action.run();
        });
        tpQuickHotkeyRow.addView(chip);
    }

    // ---------------- 4 套主题引擎实时应用 ----------------

    private void setupThemeSheet() {
        if (sheetThemeBackdrop != null) {
            sheetThemeBackdrop.setOnClickListener(v -> closeThemeSheet());
        }
        if (btnCloseThemeSheet != null) {
            btnCloseThemeSheet.setOnClickListener(v -> closeThemeSheet());
        }
        if (themeOptOled != null) {
            themeOptOled.setOnClickListener(v -> selectTheme(AppTheme.OLED));
        }
        if (themeOptIndustrial != null) {
            themeOptIndustrial.setOnClickListener(v -> selectTheme(AppTheme.INDUSTRIAL));
        }
        if (themeOptApple != null) {
            themeOptApple.setOnClickListener(v -> selectTheme(AppTheme.APPLE));
        }
        if (themeOptDaylight != null) {
            themeOptDaylight.setOnClickListener(v -> selectTheme(AppTheme.DAYLIGHT));
        }
    }

    private void openThemeSheet() {
        if (sheetSettingsBackdrop != null) {
            sheetSettingsBackdrop.setVisibility(View.GONE);
        }
        if (sheetThemeBackdrop != null) {
            sheetThemeBackdrop.setVisibility(View.VISIBLE);
            applyThemeSheetStyles();
        }
    }

    private void closeThemeSheet() {
        if (sheetThemeBackdrop != null) {
            sheetThemeBackdrop.setVisibility(View.GONE);
        }
    }

    private void selectTheme(AppTheme theme) {
        currentTheme = theme;
        ThemeManager.save(this, theme);
        Haptics.tap(this);
        applyCurrentTheme();
        applyThemeSheetStyles();
    }

    private void applyCurrentTheme() {
        ThemeManager.applySystemBars(this, rootContainer, currentTheme);

        if (rootOuter != null) {
            rootOuter.setBackgroundColor(currentTheme.bg);
        }
        if (rootContainer != null) {
            rootContainer.setBackgroundColor(currentTheme.bg);
        }
        if (topBar != null) {
            topBar.setBackgroundColor(currentTheme.topBarBg);
        }
        if (topBarDivider != null) {
            topBarDivider.setBackgroundColor(currentTheme.border);
        }
        if (tvPageTitle != null) {
            tvPageTitle.setTextColor(currentTheme.textPrimary);
            ThemeManager.applyHeaderTypeface(tvPageTitle, currentTheme, true);
        }
        if (connStatusWrap != null) {
            connStatusWrap.setBackground(ThemeManager.roundRect(this, currentTheme.cardBg, currentTheme.border, 999f));
        }
        if (btnThemePicker != null) {
            ThemeManager.styleAltSurface(btnThemePicker, currentTheme);
        }
        if (ivThemePicker != null) {
            ivThemePicker.setColorFilter(currentTheme.accent);
        }
        if (btnSettings != null) {
            ThemeManager.styleAltSurface(btnSettings, currentTheme);
        }
        if (ivSettings != null) {
            ivSettings.setColorFilter(currentTheme.textPrimary);
        }

        // 横屏侧边栏
        if (sideNavRail != null) {
            sideNavRail.setBackgroundColor(currentTheme.cardBg);
        }
        if (railTopDivider != null) {
            railTopDivider.setBackgroundColor(currentTheme.border);
        }
        if (railRightDivider != null) {
            railRightDivider.setBackgroundColor(currentTheme.border);
        }
        if (ivRailBrand != null) {
            ivRailBrand.setBackground(ThemeManager.roundRect(this, currentTheme.accent, Color.TRANSPARENT, currentTheme.btnRadiusDp));
            ivRailBrand.setColorFilter(currentTheme.onAccent);
        }

        // 竖屏底部导航（悬浮胶囊 vs 贴底条）
        if (bottomNavBar != null && bottomNavPill != null) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bottomNavPill.getLayoutParams();
            if (currentTheme.floatingNav) {
                bottomNavBar.setBackgroundColor(Color.TRANSPARENT);
                if (bottomNavDivider != null) {
                    bottomNavDivider.setVisibility(View.GONE);
                }
                lp.setMarginStart(ThemeManager.dp(this, 16));
                lp.setMarginEnd(ThemeManager.dp(this, 16));
                lp.topMargin = ThemeManager.dp(this, 4);
                bottomNavPill.setLayoutParams(lp);
                bottomNavPill.setBackground(ThemeManager.roundRect(this, currentTheme.cardBg, currentTheme.border, 999f));
            } else {
                bottomNavBar.setBackgroundColor(currentTheme.cardBg);
                if (bottomNavDivider != null) {
                    bottomNavDivider.setVisibility(View.VISIBLE);
                    bottomNavDivider.setBackgroundColor(currentTheme.border);
                }
                lp.setMarginStart(ThemeManager.dp(this, 6));
                lp.setMarginEnd(ThemeManager.dp(this, 6));
                lp.topMargin = ThemeManager.dp(this, 0);
                bottomNavPill.setLayoutParams(lp);
                bottomNavPill.setBackgroundColor(Color.TRANSPARENT);
            }
        }

        // 总览页卡片与文本
        applyOverviewTheme();

        // 相机页
        if (cameraPanelController != null) {
            cameraPanelController.applyTheme(currentTheme);
        }

        // 触控页
        applyTouchpadPageTheme();

        // 底部弹窗 & 状态栏
        applyThemeSheetStyles();
        applySettingsSheetStyles();
        applyNavSelection(currentPage);
        renderConnStatus();
        rebuildQuickHotkeyBar();
        rebuildMonitorSpinner();
        renderChannelSpinner();
    }

    private void applyOverviewTheme() {
        for (View card : new View[]{cardFps, cardCpu, cardGpu, cardMem, cardVram, cardNet, cardMediaPower}) {
            if (card != null) {
                ThemeManager.styleCard(card, currentTheme);
            }
        }
        for (TextView primary : new TextView[]{tvFpsValue, tvCpuValue, tvGpuValue, tvMemValue, tvVramValue,
                tvNetUp, tvNetDown, tvVolumeValue}) {
            if (primary != null) {
                primary.setTextColor(currentTheme.textPrimary);
                ThemeManager.applyHeaderTypeface(primary, currentTheme, true);
            }
        }
        for (TextView muted : new TextView[]{tvFpsLabel, tvFpsSub, tvCpuLabel, tvGpuLabel,
                tvMemLabel, tvVramLabel, tvNetLabel, tvVolTitle}) {
            if (muted != null) {
                muted.setTextColor(currentTheme.textSecondary);
                ThemeManager.applyHeaderTypeface(muted, currentTheme, false);
            }
        }
        if (tvFpsUnit != null) {
            tvFpsUnit.setTextColor(currentTheme.accent);
            ThemeManager.applyHeaderTypeface(tvFpsUnit, currentTheme, true);
        }
        if (tvDisplayPicker != null) {
            ThemeManager.styleSoftAccentButton(tvDisplayPicker, currentTheme, false);
        }
        if (fpsSparkline != null) {
            fpsSparkline.applyTheme(currentTheme.accent);
        }
        for (ProgressBar bar : new ProgressBar[]{barCpu, barGpu, barMem, barVram}) {
            if (bar != null) {
                ThemeManager.styleProgressBar(bar, currentTheme);
            }
        }
        if (seekVolume != null) {
            ThemeManager.styleSeekBar(seekVolume, currentTheme);
        }
        if (btnPrevTrack != null) {
            ThemeManager.styleSoftAccentButton(btnPrevTrack, currentTheme, false);
        }
        if (btnPlayPause != null) {
            ThemeManager.styleSoftAccentButton(btnPlayPause, currentTheme, true);
        }
        if (btnNextTrack != null) {
            ThemeManager.styleSoftAccentButton(btnNextTrack, currentTheme, false);
        }
        if (btnSpeakerMute != null) {
            ThemeManager.stylePillButton(btnSpeakerMute, currentTheme, false, speakerMuted);
        }
        if (btnMicMute != null) {
            ThemeManager.stylePillButton(btnMicMute, currentTheme, false, micMuted);
        }
        if (btnQuickLock != null && armedConfirmBtn != btnQuickLock) {
            ThemeManager.styleSoftAccentButton(btnQuickLock, currentTheme, false);
        }
        if (btnQuickSleep != null && armedConfirmBtn != btnQuickSleep) {
            ThemeManager.styleSoftAccentButton(btnQuickSleep, currentTheme, false);
        }
    }

    private void applyTouchpadPageTheme() {
        if (tpModeGroup != null) {
            tpModeGroup.setBackground(ThemeManager.roundRect(this, currentTheme.cardBg, currentTheme.border, currentTheme.btnRadiusDp));
        }
        updateTouchpadScreenModeUi();
        if (spinnerMonitors != null) {
            spinnerMonitors.setBackground(ThemeManager.roundRect(this, currentTheme.cardBg, currentTheme.border, currentTheme.btnRadiusDp));
        }
        if (screenStage != null) {
            ThemeManager.styleCard(screenStage, currentTheme);
        }
        if (tvScreenHint != null) {
            tvScreenHint.setTextColor(currentTheme.textSecondary);
        }
        if (btnToggleStream != null) {
            ThemeManager.stylePillButton(btnToggleStream, currentTheme, !(streaming || streamRequested), false);
        }
        if (btnScreenFullscreen != null) {
            ThemeManager.styleSoftAccentButton(btnScreenFullscreen, currentTheme, false);
        }
        if (tvScreenMeta != null) {
            tvScreenMeta.setBackground(ThemeManager.roundRect(this, currentTheme.cardAltBg, currentTheme.border, 8f));
            tvScreenMeta.setTextColor(currentTheme.textSecondary);
        }
        if (touchpadController != null) {
            touchpadController.applyTheme(currentTheme);
        }
    }

    private void applyThemeSheetStyles() {
        if (sheetThemeCard != null) {
            sheetThemeCard.setBackground(ThemeManager.roundRect(this, currentTheme.cardBg, currentTheme.border, 26f));
        }
        if (sheetThemeHandle != null) {
            sheetThemeHandle.setBackground(ThemeManager.roundRect(this, currentTheme.border, Color.TRANSPARENT, 999f));
        }
        if (tvThemeSheetTitle != null) {
            tvThemeSheetTitle.setTextColor(currentTheme.textPrimary);
            ThemeManager.applyHeaderTypeface(tvThemeSheetTitle, currentTheme, true);
        }
        if (btnCloseThemeSheet != null) {
            btnCloseThemeSheet.setTextColor(currentTheme.accent);
        }

        styleThemeOptionCard(themeOptOled, swatchOledBg, swatchOledAccent, tagOledActive, tvOledName, tvOledDesc, AppTheme.OLED);
        styleThemeOptionCard(themeOptIndustrial, swatchIndBg, swatchIndAccent, tagIndActive, tvIndName, tvIndDesc, AppTheme.INDUSTRIAL);
        styleThemeOptionCard(themeOptApple, swatchAppleBg, swatchAppleAccent, tagAppleActive, tvAppleName, tvAppleDesc, AppTheme.APPLE);
        styleThemeOptionCard(themeOptDaylight, swatchDayBg, swatchDayAccent, tagDayActive, tvDayName, tvDayDesc, AppTheme.DAYLIGHT);
    }

    private void styleThemeOptionCard(View card, View swBg, View swAcc, TextView tag,
                                      TextView name, TextView desc, AppTheme opt) {
        if (card == null) {
            return;
        }
        boolean selected = opt == currentTheme;
        card.setBackground(ThemeManager.roundRect(
                this,
                opt.cardAltBg,
                selected ? currentTheme.accent : currentTheme.border,
                opt.cardRadiusDp));
        if (swBg != null) {
            swBg.setBackground(ThemeManager.circle(this, opt.bg, opt.border));
        }
        if (swAcc != null) {
            swAcc.setBackground(ThemeManager.circle(this, opt.accent, Color.TRANSPARENT));
        }
        if (tag != null) {
            tag.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            tag.setTextColor(currentTheme.accent);
        }
        if (name != null) {
            name.setTextColor(opt.textPrimary);
            ThemeManager.applyHeaderTypeface(name, opt, true);
        }
        if (desc != null) {
            desc.setTextColor(opt.textSecondary);
        }
    }

    private void applySettingsSheetStyles() {
        if (sheetSettingsCard != null) {
            sheetSettingsCard.setBackground(ThemeManager.roundRect(this, currentTheme.cardBg, currentTheme.border, 26f));
        }
        if (sheetSettingsHandle != null) {
            sheetSettingsHandle.setBackground(ThemeManager.roundRect(this, currentTheme.border, Color.TRANSPARENT, 999f));
        }
        if (tvSettingsSheetTitle != null) {
            tvSettingsSheetTitle.setTextColor(currentTheme.textPrimary);
            ThemeManager.applyHeaderTypeface(tvSettingsSheetTitle, currentTheme, true);
        }
        if (btnCloseSettingsSheet != null) {
            btnCloseSettingsSheet.setTextColor(currentTheme.accent);
        }
        for (TextView lbl : new TextView[]{tvSettingsChanLabel, tvSettingsHostLabel, tvSettingsPortLabel}) {
            if (lbl != null) {
                lbl.setTextColor(currentTheme.textSecondary);
            }
        }
        if (spinnerChannels != null) {
            spinnerChannels.setBackground(ThemeManager.roundRect(this, currentTheme.cardAltBg, currentTheme.border, currentTheme.btnRadiusDp));
        }
        if (btnAddChannel != null) {
            ThemeManager.styleSoftAccentButton(btnAddChannel, currentTheme, false);
        }
        if (btnDeleteChannel != null) {
            ThemeManager.styleSoftAccentButton(btnDeleteChannel, currentTheme, false);
        }
        if (etServerHost != null) {
            ThemeManager.styleInput(etServerHost, currentTheme);
        }
        if (etServerPort != null) {
            ThemeManager.styleInput(etServerPort, currentTheme);
        }
        if (btnReconnect != null) {
            ThemeManager.stylePillButton(btnReconnect, currentTheme, true, false);
        }
        if (btnScanQr != null) {
            ThemeManager.styleSoftAccentButton(btnScanQr, currentTheme, false);
        }
        if (tvSessionDetail != null) {
            ThemeManager.styleAltSurface(tvSessionDetail, currentTheme);
            tvSessionDetail.setTextColor(currentTheme.textSecondary);
        }
    }

    // ---------------- 连接设置底部弹窗 ----------------

    private void setupSettingsSheet() {
        if (sheetSettingsBackdrop != null) {
            sheetSettingsBackdrop.setOnClickListener(v -> closeSettingsSheet());
        }
        if (btnCloseSettingsSheet != null) {
            btnCloseSettingsSheet.setOnClickListener(v -> closeSettingsSheet());
        }
        if (spinnerChannels == null || btnAddChannel == null || btnDeleteChannel == null
                || btnScanQr == null || etServerHost == null || etServerPort == null) {
            return;
        }
        spinnerChannels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(currentTheme.textPrimary);
                }
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
        if (btnReconnect != null) {
            btnReconnect.setOnClickListener(v -> saveAndReconnect());
        }
        reloadChannels();
    }

    private void openSettingsSheet() {
        if (sheetThemeBackdrop != null) {
            sheetThemeBackdrop.setVisibility(View.GONE);
        }
        renderChannelSpinner();
        renderSessionDetail();
        if (sheetSettingsBackdrop != null) {
            sheetSettingsBackdrop.setVisibility(View.VISIBLE);
            applySettingsSheetStyles();
        }
    }

    private void closeSettingsSheet() {
        if (sheetSettingsBackdrop != null) {
            sheetSettingsBackdrop.setVisibility(View.GONE);
        }
    }

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
        spinnerChannels.setAdapter(makeThemedSpinnerAdapter(labels));
        spinnerChannels.setSelection(channelIndex);
        suppressChannelCallback = false;
    }

    private ArrayAdapter<String> makeThemedSpinnerAdapter(List<String> labels) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(currentTheme.textPrimary);
                    ((TextView) v).setTextSize(12.5f);
                }
                return v;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                v.setBackgroundColor(currentTheme.cardBg);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(currentTheme.textPrimary);
                    ((TextView) v).setTextSize(13f);
                    int p = ThemeManager.dp(MainActivity.this, 10);
                    v.setPadding(p, p, p, p);
                }
                return v;
            }
        };
    }

    private void saveAndReconnect() {
        String host = etServerHost.getText().toString().trim().replaceFirst("^wss?://", "");
        String portText = etServerPort.getText().toString().trim();
        if (host.isEmpty() || host.matches(".*\\s+.*")) {
            Toast.makeText(this, "请填写有效服务端地址", Toast.LENGTH_SHORT).show();
            return;
        }
        int port;
        try {
            port = portText.isEmpty() ? Integer.parseInt(DEFAULT_PORT) : Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口需为数字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (port < 1 || port > 65535) {
            Toast.makeText(this, "端口需在 1~65535", Toast.LENGTH_SHORT).show();
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
        if (tvSessionDetail != null) {
            tvSessionDetail.setText("已保存并重新连接…");
        }
    }

    private void showAddChannelDialog() {
        int pad = ThemeManager.dp(this, 20);
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
                    String host = hostInput.getText().toString().trim().replaceFirst("^wss?://", "");
                    if (host.isEmpty() || host.matches(".*\\s+.*")) {
                        Toast.makeText(this, "请填写有效地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port;
                    try {
                        String text = portInput.getText().toString().trim();
                        port = text.isEmpty() ? Integer.parseInt(DEFAULT_PORT) : Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "端口需为数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (port < 1 || port > 65535) {
                        Toast.makeText(this, "端口需在 1~65535", Toast.LENGTH_SHORT).show();
                        return;
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
        if (scanLauncher != null) {
            scanLauncher.launch(new Intent(this, ScanActivity.class));
        }
    }

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
        if (host.isEmpty() || host.matches(".*\\s+.*") || !portText.matches("\\d+")) {
            Toast.makeText(this, "无法识别的二维码：" + raw, Toast.LENGTH_SHORT).show();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口无效：" + portText, Toast.LENGTH_SHORT).show();
            return;
        }
        if (port < 1 || port > 65535) {
            Toast.makeText(this, "端口需在 1~65535：" + port, Toast.LENGTH_SHORT).show();
            return;
        }

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
        if (tvSessionDetail != null) {
            tvSessionDetail.setText("已扫码并连接 " + host + ":" + port);
        }
    }

    // ---------------- 按键与权限回调 ----------------

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
        } else if (requestCode == CameraPanelController.REQ_CAMERA) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (cameraPanelController != null) {
                cameraPanelController.onCameraPermissionResult(granted);
            }
        }
    }

    // ---------------- ConnectionManager.Listener ----------------

    @Override
    public void onLayout(LayoutMessage layout) {
        if (layout == null || layout.buttons == null) {
            return;
        }
        if (layout.pageId != null) {
            currentDeckPageId = layout.pageId;
        }
        serverCustomButtons.clear();
        serverCustomButtons.addAll(layout.buttons);
        rebuildQuickHotkeyBar();
    }

    @Override
    public void onTelemetry(TelemetryMessage telemetry) {
        if (telemetry == null) {
            return;
        }
        this.lastTelemetry = telemetry;
        lastDisplays = telemetry.displays;
        updateDisplayCard(telemetry);
        if (!displaySelectionSent && !selectedDisplayId.isEmpty() && connection != null) {
            displaySelectionSent = true;
            connection.sendSelectDisplay(selectedDisplayId);
        }

        if (tvCpuValue != null) {
            tvCpuValue.setText(percentText(telemetry.cpu));
        }
        if (tvMemValue != null) {
            tvMemValue.setText(percentText(telemetry.mem));
        }
        if (tvGpuValue != null) {
            tvGpuValue.setText(percentText(telemetry.gpu));
        }
        applyBar(barCpu, telemetry.cpu);
        applyBar(barMem, telemetry.mem);
        applyBar(barGpu, telemetry.gpu);
        updateVramCard(telemetry.vram, telemetry.vramTotal);

        if (tvNetUp != null) {
            tvNetUp.setText("↑ " + safe(telemetry.netUp));
        }
        if (tvNetDown != null) {
            tvNetDown.setText("↓ " + safe(telemetry.netDown));
        }

        speakerMuted = telemetry.isMuted;
        micMuted = telemetry.micMuted;
        int volume = speakerMuted ? 0 : Math.max(0, Math.min(100, telemetry.volume));
        if (!userDraggingVolume && seekVolume != null) {
            seekVolume.setProgress(volume);
            if (tvVolumeValue != null) {
                tvVolumeValue.setText(speakerMuted ? "静音" : volume + "%");
            }
        }
        if (btnSpeakerMute != null) {
            btnSpeakerMute.setText(speakerMuted ? R.string.ctrl_speaker_muted : R.string.ctrl_speaker);
            ThemeManager.stylePillButton(btnSpeakerMute, currentTheme, false, speakerMuted);
        }
        if (btnMicMute != null) {
            btnMicMute.setText(micMuted ? R.string.ctrl_mic_muted : R.string.ctrl_mic);
            ThemeManager.stylePillButton(btnMicMute, currentTheme, false, micMuted);
        }
        if (System.currentTimeMillis() - lastVolumeInputAt > 1500) {
            lastVolumeSent = Math.max(0, Math.min(100, telemetry.volume));
        }
    }

    @Override
    public void onButtonState(ButtonStateMessage state) {
        // 媒体静音等核心状态已由 telemetry.isMuted 同步
    }

    @Override
    public void onConnectionChanged(boolean connected, String mode, String endpoint) {
        this.connected = connected;
        renderConnStatus();
        displaySelectionSent = false;
        if (!connected) {
            transport = "lan";
            screenAllowed = false;
            streaming = false;
            streamRequested = false;
            stopDecoder();
            if (fpsSparkline != null) {
                fpsSparkline.clearSamples();
            }
        } else {
            if (!selectedDisplayId.isEmpty() && connection != null) {
                displaySelectionSent = true;
                connection.sendSelectDisplay(selectedDisplayId);
            }
            if (cameraPanelController != null) {
                cameraPanelController.onConnected();
            }
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
            if (tvScreenMeta != null) tvScreenMeta.setText("需 USB 连接");
            if (tvScreenHint != null) tvScreenHint.setText("服务端拒绝：屏幕监视需要 USB 连接");
        } else if ("encoder_unavailable".equals(reason) || "encoder_error".equals(reason)) {
            streaming = false;
            streamRequested = false;
            stopDecoder();
            if (tvScreenHint != null) {
                tvScreenHint.setVisibility(View.VISIBLE);
                tvScreenHint.setText("encoder_unavailable".equals(reason)
                        ? "服务端未找到 ffmpeg，请检查 PC 端 config.json"
                        : "H.264 编码器启动失败，请查看 PC 服务端日志");
            }
            if (tvScreenMeta != null) tvScreenMeta.setText("推流不可用");
        } else if (state.streaming) {
            applyStreamFormat(state.width, state.height, state.fps);
            streaming = true;
            streamRequested = false;
            actualFps = 0;
            streamKbps = 0;
            firstFrame = false;
            if (tvScreenHint != null) {
                tvScreenHint.setVisibility(View.VISIBLE);
                tvScreenHint.setText("正在等待 H.264 关键帧…");
            }
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
        if (streaming && tvScreenMeta != null) {
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
        if (!PAGE_TOUCHPAD.equals(currentPage) || !touchpadSplitScreen || data == null || data.length == 0) {
            return;
        }
        if (h264Decoder != null) {
            h264Decoder.feed(data);
        }
    }

    @Override
    public void onCameraControl(JsonObject command) {
        if (cameraPanelController != null) {
            cameraPanelController.handleServerCommand(command);
        }
    }

    // ---------------- 状态栏 / 显示器 / 投屏实现 ----------------

    private void renderConnStatus() {
        if (statusDot != null) {
            statusDot.setBackground(ThemeManager.circle(this,
                    connected ? currentTheme.ok : currentTheme.textMuted, Color.TRANSPARENT));
        }
        if (tvConnStatus != null) {
            String text = connected
                    ? ("usb".equals(transport) ? "USB 通道" : "Wi-Fi 通道")
                    : "未连接";
            tvConnStatus.setText(text);
            tvConnStatus.setTextColor(connected ? currentTheme.ok : currentTheme.textMuted);
        }
    }

    private void updateDisplayCard(TelemetryMessage telemetry) {
        if (tvFpsValue != null) {
            if (telemetry != null && telemetry.fps >= 0) {
                tvFpsValue.setText(String.format(Locale.ROOT, "%.0f", telemetry.fps));
                if (tvFpsUnit != null) tvFpsUnit.setText("FPS");
                if (tvFpsSub != null) {
                    String hz = telemetry.displayHz > 0
                            ? String.format(Locale.ROOT, "%.0fHz · ", telemetry.displayHz) : "";
                    String src = "app".equals(telemetry.fpsSource) ? "前台应用" : "桌面合成";
                    tvFpsSub.setText(hz + src);
                }
                if (fpsSparkline != null) {
                    fpsSparkline.pushSample((float) telemetry.fps, (float) telemetry.displayHz);
                }
            } else if (telemetry != null && telemetry.displayHz > 0) {
                tvFpsValue.setText(String.format(Locale.ROOT, "%.0f", telemetry.displayHz));
                if (tvFpsUnit != null) tvFpsUnit.setText("Hz");
                if (tvFpsSub != null) tvFpsSub.setText("显示器刷新率");
                if (fpsSparkline != null) {
                    fpsSparkline.pushSample((float) telemetry.displayHz, (float) telemetry.displayHz);
                }
            } else {
                tvFpsValue.setText("--");
                if (tvFpsUnit != null) tvFpsUnit.setText("FPS");
                if (tvFpsSub != null) tvFpsSub.setText(connected ? "等待帧率样本" : "等待连接主机");
            }
        }
        if (tvDisplayPicker != null) {
            if (telemetry != null && telemetry.displayHz > 0) {
                tvDisplayPicker.setText(String.format(Locale.ROOT, "%.0fHz ▾", telemetry.displayHz));
            } else {
                tvDisplayPicker.setText("选择 ▾");
            }
        }
    }

    private void showDisplayPicker() {
        if (lastDisplays == null || lastDisplays.isEmpty()) {
            Toast.makeText(this, "还没有收到显示器列表", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[lastDisplays.size()];
        int checked = 0;
        for (int i = 0; i < lastDisplays.size(); i++) {
            TelemetryMessage.DisplayInfo display = lastDisplays.get(i);
            items[i] = (display.primary ? "主屏" : "显示器")
                    + " · " + display.width + "×" + display.height + " · " + display.hz + " Hz";
            if (display.id != null && display.id.equals(selectedDisplayId)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择显示器")
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    TelemetryMessage.DisplayInfo display = lastDisplays.get(which);
                    selectedDisplayId = display.id == null ? "" : display.id;
                    getSharedPreferences("display_prefs", MODE_PRIVATE)
                            .edit().putString("selected_display", selectedDisplayId).apply();
                    if (connection != null) {
                        connection.sendSelectDisplay(selectedDisplayId);
                        displaySelectionSent = true;
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void rebuildMonitorSpinner() {
        if (spinnerMonitors == null) {
            return;
        }
        List<String> labels = new ArrayList<>();
        labels.add("整块桌面");
        int checked = 0;
        for (int i = 0; i < monitors.size(); i++) {
            ScreenDevicesMessage.Device device = monitors.get(i);
            labels.add((device.name == null ? "屏幕 " + (i + 1) : device.name)
                    + " · " + device.width + "×" + device.height);
            if (device.id == selectedMonitor) {
                checked = i + 1;
            }
        }
        suppressMonitorCallback = true;
        spinnerMonitors.setAdapter(makeThemedSpinnerAdapter(labels));
        spinnerMonitors.setSelection(checked);
        suppressMonitorCallback = false;
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
        if (tvScreenHint != null) {
            tvScreenHint.setVisibility(View.VISIBLE);
            tvScreenHint.setText("画面参数已更新，正在等待关键帧…");
        }
        h264Decoder.start(screenSurface, streamWidth, streamHeight, streamFps);
    }

    private String streamFormat() {
        return streamWidth + "×" + streamHeight + "@" + streamFps;
    }

    private String streamSummary() {
        StringBuilder text = new StringBuilder(streamFormat());
        if (actualFps > 0) {
            text.append(" · ").append(String.format(Locale.ROOT, "%.0f fps", actualFps));
        }
        if (streamKbps > 0) {
            text.append(streamKbps >= 1024
                    ? String.format(Locale.ROOT, " · %.1f MB/s", streamKbps / 1024.0)
                    : " · " + streamKbps + " KB/s");
        }
        return text.toString();
    }

    private void startStream() {
        if (!connected || !screenAllowed || streaming || streamRequested) {
            updateScreenUi();
            return;
        }
        streamRequested = true;
        actualFps = 0;
        streamKbps = 0;
        firstFrame = false;
        if (tvScreenHint != null) {
            tvScreenHint.setVisibility(View.VISIBLE);
            tvScreenHint.setText("正在启动 H.264 编码…");
        }
        connection.sendScreenControl(true, selectedMonitor);
        updateScreenUi();
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
        ThemeManager.stylePillButton(btnToggleStream, currentTheme, !(streaming || streamRequested), streaming || streamRequested);

        if (btnScreenFullscreen != null) {
            btnScreenFullscreen.setVisibility(streaming ? View.VISIBLE : View.GONE);
        }

        if (streaming) {
            if (surfaceScreen != null) surfaceScreen.setVisibility(View.VISIBLE);
            if (tvScreenHint != null) {
                tvScreenHint.setVisibility(firstFrame ? View.GONE : View.VISIBLE);
                if (!firstFrame) tvScreenHint.setText("正在等待 H.264 关键帧…");
            }
            if (tvScreenMeta != null) tvScreenMeta.setText(streamSummary());
            return;
        }

        if (surfaceScreen != null) surfaceScreen.setVisibility(View.GONE);
        if (tvScreenHint != null) {
            tvScreenHint.setVisibility(View.VISIBLE);
            if (!connected) {
                tvScreenHint.setText("尚未连接主机\n请点右上角齿轮检查连接通道");
            } else if (!screenAllowed) {
                tvScreenHint.setText("当前通道未开放屏幕推流");
            } else if (streamRequested) {
                tvScreenHint.setText("正在启动 H.264 编码…");
            } else {
                tvScreenHint.setText("通道就绪 · " + streamFormat() + "\n点击左上角「开始监视」");
            }
        }
        if (tvScreenMeta != null) {
            tvScreenMeta.setText(streamRequested ? "启动中" : "待机中");
        }
    }

    private void enterFullscreen() {
        if (fullscreen || screenStage == null) {
            return;
        }
        fullscreen = true;
        if (topBar != null) topBar.setVisibility(View.GONE);
        if (bottomNavBar != null) bottomNavBar.setVisibility(View.GONE);
        if (sideNavRail != null) sideNavRail.setVisibility(View.GONE);
        if (tpTopBar != null) tpTopBar.setVisibility(View.GONE);
        if (tpQuickHotkeyScroll != null) tpQuickHotkeyScroll.setVisibility(View.GONE);
        if (tpTouchArea != null) tpTouchArea.setVisibility(View.GONE);
        if (pageTouchpad != null) pageTouchpad.setPadding(0, 0, 0, 0);

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) screenStage.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.weight = 1f;
        lp.bottomMargin = 0;
        lp.setMarginEnd(0);
        screenStage.setLayoutParams(lp);
        screenStage.setVisibility(View.VISIBLE);

        if (btnScreenFullscreen != null) btnScreenFullscreen.setText("退出全屏");
        applyScreenAspect();
        screenStage.post(this::applyScreenAspect);
    }

    private void exitFullscreen() {
        if (!fullscreen || screenStage == null) {
            return;
        }
        fullscreen = false;
        if (topBar != null) topBar.setVisibility(View.VISIBLE);
        if (bottomNavBar != null) bottomNavBar.setVisibility(View.VISIBLE);
        if (sideNavRail != null) sideNavRail.setVisibility(View.VISIBLE);
        if (tpTopBar != null) tpTopBar.setVisibility(View.VISIBLE);
        if (tpTouchArea != null) tpTouchArea.setVisibility(View.VISIBLE);

        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (pageTouchpad != null) {
            int ph = ThemeManager.dp(this, landscape ? 12 : 14);
            int pv = ThemeManager.dp(this, landscape ? 6 : 8);
            pageTouchpad.setPadding(ph, pv, ph, pv);
        }

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) screenStage.getLayoutParams();
        if (landscape) {
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.weight = 1.05f;
            lp.bottomMargin = 0;
            lp.setMarginEnd(ThemeManager.dp(this, 8));
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = 0;
            lp.weight = 0.44f;
            lp.bottomMargin = ThemeManager.dp(this, 8);
            lp.setMarginEnd(0);
        }
        screenStage.setLayoutParams(lp);

        if (btnScreenFullscreen != null) btnScreenFullscreen.setText("全屏");
        updateTouchpadScreenModeUi();
        applyScreenAspect();
        screenStage.post(this::applyScreenAspect);
    }

    private void renderSessionDetail() {
        if (tvSessionDetail == null) {
            return;
        }
        String lan = connection == null ? "" : connection.getLanEndpoint();
        tvSessionDetail.setText("USB 隧道：ws://127.0.0.1:8500\n"
                + "局域网地址：" + (lan == null || lan.isEmpty() ? "未设置" : lan) + "\n"
                + "状态：" + (connected ? "已连接" : "未连接") + " · "
                + ("usb".equals(transport) ? "USB（adb reverse）" : "Wi-Fi 局域网") + "\n"
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

    private void sendMedia(String buttonId) {
        if (connection == null) {
            return;
        }
        String mapped = MEDIA_BUTTON_ALIAS.getOrDefault(buttonId, buttonId);
        connection.sendAction(DEFAULT_PAGE_ID, mapped, "click");
    }

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
        int steps = Math.min(25, Math.max(1, Math.round(Math.abs(diff) / 2f)));
        String buttonId = diff > 0 ? "btn_vol_up" : "btn_vol_down";
        for (int i = 0; i < steps; i++) {
            connection.sendAction(DEFAULT_PAGE_ID, buttonId, "click");
        }
        lastVolumeSent = Math.max(0, Math.min(100, lastVolumeSent + (diff > 0 ? steps * 2 : -steps * 2)));
    }

    private void applyBar(@Nullable ProgressBar bar, double value) {
        if (bar != null) {
            int p = value >= 0 ? (int) Math.round(Math.min(100, value)) : 0;
            bar.setProgress(p);
        }
    }

    private void updateVramCard(double usedMb, double totalMb) {
        if (tvVramValue != null) {
            tvVramValue.setText(usedMb >= 0
                    ? String.format(Locale.ROOT, "%.1fG", usedMb / 1024.0)
                    : "--");
        }
        double percent = (usedMb >= 0 && totalMb > 0) ? usedMb / totalMb * 100.0 : -1;
        applyBar(barVram, percent);
    }

    private String percentText(double value) {
        return value >= 0 ? Math.round(value) + "%" : "--%";
    }

    private String safe(String value) {
        return value == null ? "0 B/s" : value;
    }
}
