package com.phoneconsole.pc.net;

import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.runtime.LogBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdbAutoUnlockServiceTest {

    @Test
    void deduplicatesWirelessDeviceWhenSamePhoneAlreadyHandledViaUsb() throws InterruptedException {
        List<String> amStartSerials = new ArrayList<>();
        AtomicBoolean roSerialChecked = new AtomicBoolean(false);
        CountDownLatch finishedLatch = new CountDownLatch(1);

        AdbPortForwarder adb = new AdbPortForwarder(defaultNet(), 8500, 0, cmd -> {
            if (cmd.length >= 5 && ("-s".equals(cmd[1]) || "-t".equals(cmd[1])) && "shell".equals(cmd[3])) {
                String serial = cmd[2];
                String shellCmd = cmd[4];
                if ("getprop ro.serialno".equals(shellCmd)) {
                    roSerialChecked.set(true);
                    return new AdbPortForwarder.Result(0, "GYQGVSNJSCJVAYNF", false);
                }
                if (shellCmd.startsWith("dumpsys power")) {
                    return new AdbPortForwarder.Result(0, "mWakefulness=Awake", false);
                }
                if (shellCmd.startsWith("am start")) {
                    amStartSerials.add(serial);
                    return new AdbPortForwarder.Result(0, "Starting: Intent ...", false);
                }
            }
            return new AdbPortForwarder.Result(0, "", false);
        });

        AppConfig.AutoUnlock cfg = new AppConfig.AutoUnlock();
        cfg.enabled = true;
        cfg.wirelessEnabled = true;
        cfg.wirelessEndpoint = "10.26.3.135:33007";
        cfg.triggerMode = "on_connect";
        cfg.launchApp = true;

        AdbAutoUnlockService service = new AdbAutoUnlockService(adb, () -> cfg);
        service.setStateListener(state -> finishedLatch.countDown());

        try {
            AdbPortForwarder.Device usb = device("GYQGVSNJSCJVAYNF", false);
            AdbPortForwarder.Device wifi = device("10.26.3.135:33007", true);
            service.onOnlineDevices(List.of(wifi, usb));

            assertTrue(finishedLatch.await(3, TimeUnit.SECONDS), "有线任务应在 3s 内完成");
            assertTrue(roSerialChecked.get(), "应对同时在线的无线设备核对 ro.serialno");
            assertEquals(List.of("GYQGVSNJSCJVAYNF"), amStartSerials,
                    "同一台手机同时通过 USB 与无线接入时应只解锁并拉起一次（优先 USB）");
        } finally {
            service.stop();
        }
    }

    @Test
    void wirelessOnlyDeviceRunsUnlockAndLaunchAndMasksPasswordInLogs() {
        long logCursorBefore = LogBuffer.get().lastSeq();
        AtomicBoolean awake = new AtomicBoolean(false);
        AtomicBoolean locked = new AtomicBoolean(true);
        List<String> executedShells = new ArrayList<>();

        AdbPortForwarder adb = new AdbPortForwarder(defaultNet(), 8500, 0, cmd -> {
            if (cmd.length == 3 && "devices".equals(cmd[1])) {
                return new AdbPortForwarder.Result(0,
                        "List of devices attached\n10.26.3.135:33007      device model:22011211C transport_id:5", false);
            }
            if (cmd.length >= 5 && ("-s".equals(cmd[1]) || "-t".equals(cmd[1])) && "shell".equals(cmd[3])) {
                String shellCmd = cmd[4];
                executedShells.add(shellCmd);
                if (shellCmd.startsWith("dumpsys power")) {
                    String out = (awake.get() ? "mWakefulness=Awake\n" : "mWakefulness=Asleep\n")
                            + (locked.get() ? "showing=true" : "showing=false");
                    return new AdbPortForwarder.Result(0, out, false);
                }
                if ("input keyevent 224".equals(shellCmd)) {
                    awake.set(true);
                    return new AdbPortForwarder.Result(0, "", false);
                }
                if ("wm size".equals(shellCmd)) {
                    return new AdbPortForwarder.Result(0, "Physical size: 1080x2400", false);
                }
                if (shellCmd.startsWith("input swipe ")) {
                    return new AdbPortForwarder.Result(0, "", false);
                }
                if (shellCmd.startsWith("input text ")) {
                    locked.set(false);
                    return new AdbPortForwarder.Result(0, "", false);
                }
                if (shellCmd.startsWith("am start ")) {
                    return new AdbPortForwarder.Result(0, "Status: ok", false);
                }
            }
            return new AdbPortForwarder.Result(0, "", false);
        });

        AppConfig.AutoUnlock cfg = new AppConfig.AutoUnlock();
        cfg.enabled = false;
        cfg.wirelessEnabled = true;
        cfg.wirelessEndpoint = "10.26.3.135:33007";
        cfg.swipeToUnlock = true;
        cfg.launchApp = true;
        cfg.password = "Secret 99";

        AdbAutoUnlockService service = new AdbAutoUnlockService(adb, () -> cfg);
        try {
            Map<String, Object> result = service.triggerNow("wireless");
            assertEquals(true, result.get("ok"));

            assertTrue(executedShells.stream().anyMatch(s -> s.equals("input keyevent 224")));
            assertTrue(executedShells.stream().anyMatch(s -> s.startsWith("input swipe ")));
            assertTrue(executedShells.stream().anyMatch(s -> s.equals("input text 'Secret%s99'")));
            assertFalse(executedShells.stream().anyMatch(s -> s.equals("input keyevent 66")),
                    "密码自动提交已解锁后不应额外补发回车键");
            assertTrue(executedShells.stream().anyMatch(s -> s.contains("am start -W -n com.phoneconsole.client/com.phoneconsole.client.MainActivity")));

            Map<String, Object> status = service.statusMap();
            assertEquals(true, status.get("wirelessConnected"));
            assertEquals(true, status.get("hasPassword"));
            assertEquals(9, status.get("passwordLength"));
            assertFalse(status.toString().contains("Secret"), "状态快照绝不能泄露密码明文");

            for (LogBuffer.Entry entry : LogBuffer.get().since(logCursorBefore, 200)) {
                assertFalse(entry.message.contains("Secret"), "运行日志绝不能记录密码明文");
            }
        } finally {
            service.stop();
        }
    }

    @Test
    void miuiInjectPermissionDeniedFallsBackToBroadcastWakeAndFlagsPermissionNeeded() {
        List<String> executedShells = new ArrayList<>();
        AdbPortForwarder adb = new AdbPortForwarder(defaultNet(), 8500, 0, cmd -> {
            if (cmd.length == 3 && "devices".equals(cmd[1])) {
                return new AdbPortForwarder.Result(0,
                        "List of devices attached\n10.26.3.135:33007      device model:22011211C transport_id:5", false);
            }
            if (cmd.length >= 5 && ("-s".equals(cmd[1]) || "-t".equals(cmd[1])) && "shell".equals(cmd[3])) {
                String shellCmd = cmd[4];
                executedShells.add(shellCmd);
                if (shellCmd.startsWith("dumpsys power")) {
                    return new AdbPortForwarder.Result(0, "mWakefulness=Asleep\nshowing=true", false);
                }
                if ("input keyevent 224".equals(shellCmd)) {
                    return new AdbPortForwarder.Result(1,
                            "java.lang.SecurityException: Injecting input events requires INJECT_EVENTS permission", false);
                }
                if (shellCmd.startsWith("am broadcast ") || shellCmd.startsWith("am start ")) {
                    return new AdbPortForwarder.Result(0, "Broadcast completed: result=0", false);
                }
            }
            return new AdbPortForwarder.Result(0, "", false);
        });

        AppConfig.AutoUnlock cfg = new AppConfig.AutoUnlock();
        cfg.wirelessEnabled = true;
        cfg.wirelessEndpoint = "10.26.3.135:33007";
        cfg.password = "1234";

        AdbAutoUnlockService service = new AdbAutoUnlockService(adb, () -> cfg);
        try {
            Map<String, Object> result = service.triggerNow("wireless");
            assertEquals(false, result.get("ok"));

            assertTrue(executedShells.stream().anyMatch(
                    s -> s.contains("am broadcast -n com.phoneconsole.client/.receiver.PowerConnectionReceiver")));
            assertTrue(executedShells.stream().anyMatch(s -> s.startsWith("am start ")));

            Map<String, Object> status = service.statusMap();
            assertEquals(true, status.get("needInputPermission"));
        } finally {
            service.stop();
        }
    }

    private static AdbPortForwarder.Device device(String serial, boolean wireless) {
        AdbPortForwarder.Device d = new AdbPortForwarder.Device();
        d.serial = serial;
        d.state = "device";
        d.wireless = wireless;
        d.transportId = wireless ? "2" : "1";
        return d;
    }

    private static AppConfig.Network defaultNet() {
        AppConfig.Network net = new AppConfig.Network();
        net.adbExecutable = "adb";
        net.wsPort = 8500;
        net.httpPort = 8501;
        return net;
    }
}
