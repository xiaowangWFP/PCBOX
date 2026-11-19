package com.phoneconsole.client.ui;

import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

/**
 * 原生安卓过渡动效工具类：
 * 1. 页面方向性平滑滑入淡入；
 * 2. 竖屏底部导航横向滑块 / 横屏左侧图标轨纵向滑块 / 分段切换器滑块；
 * 3. 按键物理按压回弹与错峰入场，并自动尊重系统“移除动画”无障碍偏好。
 */
public final class UiMotionHelper {

    private static final Interpolator SPRING_EASE = new FastOutSlowInInterpolator();
    private static final Interpolator SNAP_EASE = new DecelerateInterpolator(1.6f);

    private UiMotionHelper() {
    }

    public static boolean isReducedMotion(@NonNull Context context) {
        try {
            float scale = Settings.Global.getFloat(
                    context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1.0f
            );
            return scale == 0f;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void switchPageWithMotion(
            @Nullable View previousPage,
            @NonNull View nextPage,
            boolean forward
    ) {
        if (previousPage == nextPage) return;

        if (previousPage != null) {
            previousPage.animate().cancel();
            previousPage.setVisibility(View.GONE);
            previousPage.setAlpha(1f);
            previousPage.setTranslationX(0f);
        }

        nextPage.animate().cancel();
        nextPage.setVisibility(View.VISIBLE);

        if (isReducedMotion(nextPage.getContext())) {
            nextPage.setAlpha(1f);
            nextPage.setTranslationX(0f);
            nextPage.setScaleX(1f);
            nextPage.setScaleY(1f);
            return;
        }

        float offsetDp = 22f * nextPage.getResources().getDisplayMetrics().density;
        nextPage.setAlpha(0f);
        nextPage.setTranslationX(forward ? offsetDp : -offsetDp);
        nextPage.setScaleX(0.99f);
        nextPage.setScaleY(0.99f);
        nextPage.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260)
                .setInterpolator(SPRING_EASE)
                .start();
    }

    public static void animateTitleSwap(@Nullable View titleView) {
        if (titleView == null || isReducedMotion(titleView.getContext())) return;
        float dy = 6f * titleView.getResources().getDisplayMetrics().density;
        titleView.animate().cancel();
        titleView.setAlpha(0f);
        titleView.setTranslationY(dy);
        titleView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(SPRING_EASE)
                .start();
    }

    public static void animateHorizontalIndicator(
            @Nullable View indicator,
            @Nullable View targetTab,
            int horizontalInsetPx,
            boolean animate
    ) {
        if (indicator == null || targetTab == null) return;
        targetTab.post(() -> {
            int targetWidth = Math.max(0, targetTab.getWidth() - horizontalInsetPx * 2);
            if (targetWidth <= 0) return;

            ViewGroup.LayoutParams lp = indicator.getLayoutParams();
            if (lp != null && lp.width != targetWidth) {
                lp.width = targetWidth;
                indicator.setLayoutParams(lp);
            }

            float targetX = targetTab.getX() + horizontalInsetPx;
            if (!animate || isReducedMotion(indicator.getContext())) {
                indicator.animate().cancel();
                indicator.setTranslationX(targetX);
                indicator.setAlpha(1f);
            } else {
                indicator.animate()
                        .translationX(targetX)
                        .alpha(1f)
                        .setDuration(260)
                        .setInterpolator(SPRING_EASE)
                        .start();
            }
        });
    }

    public static void animateVerticalIndicator(
            @Nullable View indicator,
            @Nullable View targetItem,
            boolean animate
    ) {
        if (indicator == null || targetItem == null) return;
        targetItem.post(() -> {
            int targetHeight = targetItem.getHeight();
            if (targetHeight <= 0) return;

            ViewGroup.LayoutParams lp = indicator.getLayoutParams();
            if (lp != null && lp.height != targetHeight) {
                lp.height = targetHeight;
                indicator.setLayoutParams(lp);
            }

            // 计算 targetItem 相对 indicator 父容器的纵向偏移
            float targetY = targetItem.getTop();
            View parent = (View) targetItem.getParent();
            View indicatorParent = (View) indicator.getParent();
            while (parent != null && parent != indicatorParent) {
                targetY += parent.getTop();
                if (!(parent.getParent() instanceof View)) break;
                parent = (View) parent.getParent();
            }

            if (!animate || isReducedMotion(indicator.getContext())) {
                indicator.animate().cancel();
                indicator.setTranslationY(targetY);
                indicator.setAlpha(1f);
            } else {
                indicator.animate()
                        .translationY(targetY)
                        .alpha(1f)
                        .setDuration(260)
                        .setInterpolator(SPRING_EASE)
                        .start();
            }
        });
    }

    public static void applyKeycapPress(@NonNull View view, boolean pressed) {
        if (isReducedMotion(view.getContext())) {
            view.setScaleX(1f);
            view.setScaleY(1f);
            view.setTranslationY(0f);
            return;
        }
        float dy = pressed ? 2f * view.getResources().getDisplayMetrics().density : 0f;
        float scale = pressed ? 0.96f : 1f;
        view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationY(dy)
                .setDuration(pressed ? 90 : 160)
                .setInterpolator(SNAP_EASE)
                .start();
    }
}
