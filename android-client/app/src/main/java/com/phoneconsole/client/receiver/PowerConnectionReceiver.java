package com.phoneconsole.client.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.phoneconsole.client.MainActivity;

/**
 * 插上 USB 线或收到 PC ADB 唤醒广播时，按设置唤醒屏幕并拉起应用。
 */
public class PowerConnectionReceiver extends BroadcastReceiver {

    private static final String TAG = "PowerConnectionReceiver";
    private static final String PREFS = "phone_console";
    private static final String KEY_LOCAL_AUTO_WAKE = "local_usb_auto_wake";
    public static final String ACTION_WAKE_UP = "com.phoneconsole.client.ACTION_WAKE_UP";

    public static boolean isLocalAutoWakeEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCAL_AUTO_WAKE, true);
    }

    public static void setLocalAutoWakeEnabled(Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LOCAL_AUTO_WAKE, enabled)
                .apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_WAKE_UP.equals(action)) {
            wakeScreen(context);
            return;
        }
        if (!Intent.ACTION_POWER_CONNECTED.equals(action) || !isLocalAutoWakeEnabled(context)) {
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
