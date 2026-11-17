package com.phoneconsole.client.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.phoneconsole.client.MainActivity;

/**
 * 插上 USB 线时唤醒屏幕并拉起应用。
 * 注意：Android 10+ 对后台启动 Activity 有限制，若被系统拦截可改用前台服务或通知引导。
 */
public class PowerConnectionReceiver extends BroadcastReceiver {

    private static final String TAG = "PowerConnectionReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
            return;
        }
        wakeScreen(context);

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(launch);
        } catch (Exception e) {
            Log.w(TAG, "自动拉起界面失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        try {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "phoneconsole:power-connected");
            wakeLock.acquire(3000);
        } catch (Exception e) {
            Log.w(TAG, "唤醒屏幕失败：" + e.getMessage());
        }
    }
}
