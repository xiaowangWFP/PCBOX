package com.phoneconsole.client.ui;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 统一处理安卓状态栏、手势导航条及横竖屏挖孔屏 / 刘海屏（DisplayCutout）安全区避让。
 * 兼容 minSdk 21 ~ compileSdk 34，不依赖任何第三方库。
 */
public final class WindowInsetsHelper {

    private WindowInsetsHelper() {
    }

    public static void attach(
            @NonNull Activity activity,
            @NonNull View rootContainer,
            @Nullable View topBar,
            @Nullable View pageContainer,
            @Nullable View bottomNavBar,
            @Nullable View sideNavRail
    ) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), rootContainer);
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
        }

        final int topBarPadLeft = topBar != null ? topBar.getPaddingLeft() : 0;
        final int topBarPadTop = topBar != null ? topBar.getPaddingTop() : 0;
        final int topBarPadRight = topBar != null ? topBar.getPaddingRight() : 0;
        final int topBarPadBottom = topBar != null ? topBar.getPaddingBottom() : 0;

        final int bottomNavPadLeft = bottomNavBar != null ? bottomNavBar.getPaddingLeft() : 0;
        final int bottomNavPadTop = bottomNavBar != null ? bottomNavBar.getPaddingTop() : 0;
        final int bottomNavPadRight = bottomNavBar != null ? bottomNavBar.getPaddingRight() : 0;
        final int bottomNavPadBottom = bottomNavBar != null ? bottomNavBar.getPaddingBottom() : 0;

        final int railPadLeft = sideNavRail != null ? sideNavRail.getPaddingLeft() : 0;
        final int railPadTop = sideNavRail != null ? sideNavRail.getPaddingTop() : 0;
        final int railPadRight = sideNavRail != null ? sideNavRail.getPaddingRight() : 0;
        final int railPadBottom = sideNavRail != null ? sideNavRail.getPaddingBottom() : 0;

        final int pagePadLeft = pageContainer != null ? pageContainer.getPaddingLeft() : 0;
        final int pagePadTop = pageContainer != null ? pageContainer.getPaddingTop() : 0;
        final int pagePadRight = pageContainer != null ? pageContainer.getPaddingRight() : 0;
        final int pagePadBottom = pageContainer != null ? pageContainer.getPaddingBottom() : 0;

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer, (v, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );

            boolean isLandscapeRail = sideNavRail != null && sideNavRail.getVisibility() == View.VISIBLE;

            if (isLandscapeRail) {
                // 横屏：左侧图标轨避让左侧挖孔，顶栏与内容区避让顶部与右侧挖孔、底部手势条
                sideNavRail.setPadding(
                        railPadLeft + safe.left,
                        railPadTop + safe.top,
                        railPadRight,
                        railPadBottom + safe.bottom
                );
                if (topBar != null) {
                    topBar.setPadding(
                            topBarPadLeft,
                            topBarPadTop + safe.top,
                            topBarPadRight + safe.right,
                            topBarPadBottom
                    );
                }
                if (pageContainer != null) {
                    pageContainer.setPadding(
                            pagePadLeft,
                            pagePadTop,
                            pagePadRight + safe.right,
                            pagePadBottom + safe.bottom
                    );
                }
            } else {
                // 竖屏：顶栏避让状态栏/刘海，底栏避让底部手势条，内容区避让左右圆角
                if (topBar != null) {
                    topBar.setPadding(
                            topBarPadLeft + safe.left,
                            topBarPadTop + safe.top,
                            topBarPadRight + safe.right,
                            topBarPadBottom
                    );
                }
                if (bottomNavBar != null) {
                    bottomNavBar.setPadding(
                            bottomNavPadLeft + safe.left,
                            bottomNavPadTop,
                            bottomNavPadRight + safe.right,
                            bottomNavPadBottom + safe.bottom
                    );
                }
                if (pageContainer != null) {
                    pageContainer.setPadding(
                            pagePadLeft + safe.left,
                            pagePadTop,
                            pagePadRight + safe.right,
                            pagePadBottom
                    );
                }
            }

            return windowInsets;
        });

        ViewCompat.requestApplyInsets(rootContainer);
    }
}
