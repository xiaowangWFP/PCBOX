package com.phoneconsole.client.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 统一 4 套移动端视觉主题引擎（无需重启 Activity 即时切换并持久化）：
 * 1. OLED 极夜悬浮胶囊（纯黑隐形边框 + 悬浮药丸底栏）
 * 2. 硬件点阵工业风（TE / Nothing 灵感，哑光炭黑 + 安全橙 + 等宽字体）
 * 3. 清透控制中心风（iOS / macOS 控制中心深石板灰 + 大圆角）
 * 4. 日光纸感浅色风（温润米白 + 纯白浮卡 + 浅色系统栏）
 */
public final class ThemeManager {

    private static final String PREFS = "ui_theme_prefs";
    private static final String KEY_THEME = "active_theme";

    public enum AppTheme {
        OLED(
                "oled",
                "OLED 极夜",
                "纯黑悬浮胶囊 · 暗桌面绝配",
                0xFF000000, // bg
                0xFF0B0C0F, // topBarBg
                0xFF121317, // cardBg
                0xFF1B1D24, // cardAltBg
                0x16FFFFFF, // border
                0xFFF5F6F8, // textPrimary
                0xFF8A8F9E, // textSecondary
                0xFF636877, // textMuted
                0xFF38BDF8, // accent
                0xFF03131C, // onAccent
                0xFF34D399, // ok
                0xFFF87171, // danger
                0x24FFFFFF, // touchDot
                0x4838BDF8, // touchGlow
                22f, 16f, true, false, false
        ),
        INDUSTRIAL(
                "industrial",
                "点阵工业",
                "TE 硬件外设感 · 等宽仪器数字",
                0xFF111113,
                0xFF151518,
                0xFF1A1A1D,
                0xFF242428,
                0x22FFFFFF,
                0xFFF4F4F0,
                0xFF9A9AA0,
                0xFF6E6E74,
                0xFFFF5A1F,
                0xFFFFFFFF,
                0xFF4ADE80,
                0xFFFF453A,
                0x36FF5A1F,
                0x4DFF5A1F,
                12f, 10f, false, true, false
        ),
        APPLE(
                "apple",
                "控制中心",
                "柔和石板灰 · 大圆角药丸手感",
                0xFF16181D,
                0xFF1B1E25,
                0xFF21242C,
                0xFF2B2F3A,
                0x14FFFFFF,
                0xFFF8FAFC,
                0xFF94A3B8,
                0xFF64748B,
                0xFF3B82F6,
                0xFFFFFFFF,
                0xFF22C55E,
                0xFFEF4444,
                0x2694A3B8,
                0x483B82F6,
                24f, 18f, false, false, false
        ),
        DAYLIGHT(
                "daylight",
                "日光纸感",
                "温润米白浮卡 · 白天清晰通透",
                0xFFF3F2EE,
                0xFFF3F2EE,
                0xFFFFFFFF,
                0xFFE9E7E0,
                0x1818181B,
                0xFF18181B,
                0xFF52525B,
                0xFF71717A,
                0xFF2563EB,
                0xFFFFFFFF,
                0xFF16A34A,
                0xFFDC2626,
                0x2218181B,
                0x382563EB,
                20f, 14f, true, false, true
        );

        public final String id;
        public final String title;
        public final String subtitle;
        public final int bg;
        public final int topBarBg;
        public final int cardBg;
        public final int cardAltBg;
        public final int border;
        public final int textPrimary;
        public final int textSecondary;
        public final int textMuted;
        public final int accent;
        public final int onAccent;
        public final int ok;
        public final int danger;
        public final int touchDot;
        public final int touchGlow;
        public final float cardRadiusDp;
        public final float btnRadiusDp;
        public final boolean floatingNav;
        public final boolean monospaceHeaders;
        public final boolean lightSystemBars;

        AppTheme(String id, String title, String subtitle,
                 int bg, int topBarBg, int cardBg, int cardAltBg, int border,
                 int textPrimary, int textSecondary, int textMuted,
                 int accent, int onAccent, int ok, int danger,
                 int touchDot, int touchGlow,
                 float cardRadiusDp, float btnRadiusDp,
                 boolean floatingNav, boolean monospaceHeaders, boolean lightSystemBars) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.bg = bg;
            this.topBarBg = topBarBg;
            this.cardBg = cardBg;
            this.cardAltBg = cardAltBg;
            this.border = border;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.textMuted = textMuted;
            this.accent = accent;
            this.onAccent = onAccent;
            this.ok = ok;
            this.danger = danger;
            this.touchDot = touchDot;
            this.touchGlow = touchGlow;
            this.cardRadiusDp = cardRadiusDp;
            this.btnRadiusDp = btnRadiusDp;
            this.floatingNav = floatingNav;
            this.monospaceHeaders = monospaceHeaders;
            this.lightSystemBars = lightSystemBars;
        }

        @NonNull
        public static AppTheme fromId(String id) {
            for (AppTheme theme : values()) {
                if (theme.id.equals(id)) {
                    return theme;
                }
            }
            return OLED;
        }
    }

    private ThemeManager() {
    }

    @NonNull
    public static AppTheme load(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return AppTheme.fromId(prefs.getString(KEY_THEME, AppTheme.OLED.id));
    }

    public static void save(@NonNull Context context, @NonNull AppTheme theme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, theme.id)
                .apply();
    }

    public static void applySystemBars(@NonNull Activity activity, @NonNull View root, @NonNull AppTheme theme) {
        activity.getWindow().getDecorView().setBackgroundColor(theme.bg);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(activity.getWindow(), root);
        if (controller != null) {
            controller.setAppearanceLightStatusBars(theme.lightSystemBars);
            controller.setAppearanceLightNavigationBars(theme.lightSystemBars);
        }
    }

    public static int dp(@NonNull Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    @NonNull
    public static GradientDrawable roundRect(@NonNull Context context, int fillColor, int strokeColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(dp(context, radiusDp));
        if (strokeColor != Color.TRANSPARENT) {
            d.setStroke(Math.max(1, dp(context, 1)), strokeColor);
        }
        return d;
    }

    @NonNull
    public static GradientDrawable circle(@NonNull Context context, int fillColor, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fillColor);
        if (strokeColor != Color.TRANSPARENT) {
            d.setStroke(Math.max(1, dp(context, 1)), strokeColor);
        }
        return d;
    }

    public static void styleCard(@NonNull View view, @NonNull AppTheme theme) {
        view.setBackground(roundRect(view.getContext(), theme.cardBg, theme.border, theme.cardRadiusDp));
    }

    public static void styleAltSurface(@NonNull View view, @NonNull AppTheme theme) {
        view.setBackground(roundRect(view.getContext(), theme.cardAltBg, theme.border, theme.btnRadiusDp));
    }

    public static void stylePillButton(@NonNull TextView tv, @NonNull AppTheme theme, boolean active, boolean danger) {
        Context ctx = tv.getContext();
        float r = theme.btnRadiusDp;
        if (danger) {
            int bg = Color.argb(38, Color.red(theme.danger), Color.green(theme.danger), Color.blue(theme.danger));
            int stroke = Color.argb(120, Color.red(theme.danger), Color.green(theme.danger), Color.blue(theme.danger));
            tv.setBackground(roundRect(ctx, bg, stroke, r));
            tv.setTextColor(theme.danger);
        } else if (active) {
            tv.setBackground(roundRect(ctx, theme.accent, Color.TRANSPARENT, r));
            tv.setTextColor(theme.onAccent);
        } else {
            tv.setBackground(roundRect(ctx, theme.cardAltBg, theme.border, r));
            tv.setTextColor(theme.textPrimary);
        }
    }

    public static void styleSoftAccentButton(@NonNull TextView tv, @NonNull AppTheme theme, boolean active) {
        Context ctx = tv.getContext();
        if (active) {
            int bg = Color.argb(34, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent));
            int stroke = Color.argb(110, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent));
            tv.setBackground(roundRect(ctx, bg, stroke, theme.btnRadiusDp));
            tv.setTextColor(theme.accent);
        } else {
            tv.setBackground(roundRect(ctx, theme.cardAltBg, theme.border, theme.btnRadiusDp));
            tv.setTextColor(theme.textPrimary);
        }
    }

    public static void styleInput(@NonNull EditText et, @NonNull AppTheme theme) {
        et.setBackground(roundRect(et.getContext(), theme.cardAltBg, theme.border, theme.btnRadiusDp));
        et.setTextColor(theme.textPrimary);
        et.setHintTextColor(theme.textMuted);
    }

    public static void styleProgressBar(@NonNull ProgressBar bar, @NonNull AppTheme theme) {
        Context ctx = bar.getContext();
        GradientDrawable bg = roundRect(ctx, theme.cardAltBg, Color.TRANSPARENT, 4f);
        GradientDrawable fg = roundRect(ctx, theme.accent, Color.TRANSPARENT, 4f);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.cardAltBg));
        bar.setProgressTintList(ColorStateList.valueOf(theme.accent));
        if (bar.getProgressDrawable() instanceof LayerDrawable) {
            ViewCompat.setBackground(bar, bg);
        }
    }

    public static void styleSeekBar(@NonNull SeekBar seekBar, @NonNull AppTheme theme) {
        seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(theme.cardAltBg));
        seekBar.setProgressTintList(ColorStateList.valueOf(theme.accent));
        seekBar.setThumbTintList(ColorStateList.valueOf(theme.accent));
    }

    public static void applyHeaderTypeface(@NonNull TextView tv, @NonNull AppTheme theme, boolean bold) {
        Typeface base = theme.monospaceHeaders ? Typeface.MONOSPACE : Typeface.SANS_SERIF;
        tv.setTypeface(base, bold ? Typeface.BOLD : Typeface.NORMAL);
    }
}

