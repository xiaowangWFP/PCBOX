package com.phoneconsole.pc.net;

import com.phoneconsole.pc.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdbPortForwarderWirelessTest {

    @Test
    void tickParsesWiredAndWirelessAndKeepsCameraForwardOnWiredOnly() {
        List<String> commands = new ArrayList<>();
        Set<String> reverseMapped = ConcurrentHashMap.newKeySet();
        Set<String> forwardMapped = ConcurrentHashMap.newKeySet();

        AdbPortForwarder adb = new AdbPortForwarder(defaultNet(), 8500, 8554, cmd -> {
            String joined = String.join(" ", cmd);
            commands.add(joined);
            if (cmd.length == 3 && "devices".equals(cmd[1])) {
                return new AdbPortForwarder.Result(0, """
                        List of devices attached
                        GYQGVSNJSCJVAYNF       device product:matisse model:22011211C transport_id:1
                        10.26.3.135:33007      device product:matisse model:22011211C transport_id:2
                        """, false);
            }
            if (joined.contains(" reverse --list")) {
                String serial = cmd[2];
                return new AdbPortForwarder.Result(0,
                        reverseMapped.contains(serial) ? "UsbFfs tcp:8500 tcp:8500" : "", false);
            }
            if (joined.contains(" reverse tcp:8500 tcp:8500")) {
                reverseMapped.add(cmd[2]);
                return new AdbPortForwarder.Result(0, "", false);
            }
            if (joined.contains(" forward --list")) {
                StringBuilder out = new StringBuilder();
                for (String s : forwardMapped) {
                    out.append(s).append(" tcp:8554 tcp:8554\n");
                }
                return new AdbPortForwarder.Result(0, out.toString(), false);
            }
            if (joined.contains(" forward tcp:8554 tcp:8554")) {
                forwardMapped.add(cmd[2]);
                return new AdbPortForwarder.Result(0, "", false);
            }
            return new AdbPortForwarder.Result(0, "", false);
        });

        adb.tick();

        AdbPortForwarder.Status st = adb.probe();
        assertEquals(1, st.wiredCount);
        assertEquals(1, st.wirelessCount);
        assertTrue(st.wirelessConnected);
        assertEquals(2, st.devices.size());

        // 反向映射（wsPort 8500）有线与无线均有，但摄像头 RTSP 正向映射（8554）在有线在场时只绑定有线
        assertTrue(commands.stream().anyMatch(c -> c.contains("-s GYQGVSNJSCJVAYNF reverse tcp:8500 tcp:8500")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("-s 10.26.3.135:33007 reverse tcp:8500 tcp:8500")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("-s GYQGVSNJSCJVAYNF forward tcp:8554 tcp:8554")));
        assertFalse(commands.stream().anyMatch(c -> c.contains("10.26.3.135:33007 forward tcp:8554")),
                "有线和无线同时在线时不应抢占 PC 端 tcp:8554 forward 端口");
    }

    @Test
    void tickAutoConnectsViaMdnsFallbackAndAppliesWirelessMappingsWhenNoWiredPresent() {
        AtomicBoolean wirelessOnline = new AtomicBoolean(false);
        Set<String> reverseMapped = ConcurrentHashMap.newKeySet();
        Set<String> forwardMapped = ConcurrentHashMap.newKeySet();
        List<String> commands = new ArrayList<>();

        AdbPortForwarder adb = new AdbPortForwarder(defaultNet(), 8500, 8554, cmd -> {
            String joined = String.join(" ", cmd);
            commands.add(joined);
            if (cmd.length == 3 && "devices".equals(cmd[1])) {
                String out = wirelessOnline.get()
                        ? "List of devices attached\n10.26.3.135:33007      device product:matisse model:22011211C transport_id:3"
                        : "List of devices attached\n";
                return new AdbPortForwarder.Result(0, out, false);
            }
            if (cmd.length == 3 && "connect".equals(cmd[1]) && "10.26.3.135:39999".equals(cmd[2])) {
                return new AdbPortForwarder.Result(1, "cannot connect to 10.26.3.135:39999: Connection refused", false);
            }
            if (cmd.length == 3 && "mdns".equals(cmd[1]) && "services".equals(cmd[2])) {
                return new AdbPortForwarder.Result(0,
                        "adb-GYQGVSNJSCJVAYNF-gqiLDR\t_adb-tls-connect._tcp\t10.26.3.135:33007", false);
            }
            if (cmd.length == 3 && "connect".equals(cmd[1]) && "10.26.3.135:33007".equals(cmd[2])) {
                wirelessOnline.set(true);
                return new AdbPortForwarder.Result(0, "connected to 10.26.3.135:33007", false);
            }
            if (joined.contains(" reverse --list")) {
                return new AdbPortForwarder.Result(0,
                        reverseMapped.contains(cmd[2]) ? "UsbFfs tcp:8500 tcp:8500" : "", false);
            }
            if (joined.contains(" reverse tcp:8500 tcp:8500")) {
                reverseMapped.add(cmd[2]);
                return new AdbPortForwarder.Result(0, "", false);
            }
            if (joined.contains(" forward --list")) {
                return new AdbPortForwarder.Result(0,
                        forwardMapped.contains(cmd[2]) ? cmd[2] + " tcp:8554 tcp:8554" : "", false);
            }
            if (joined.contains(" forward tcp:8554 tcp:8554")) {
                forwardMapped.add(cmd[2]);
                return new AdbPortForwarder.Result(0, "", false);
            }
            return new AdbPortForwarder.Result(0, "", false);
        });

        AppConfig.AutoUnlock cfg = new AppConfig.AutoUnlock();
        cfg.wirelessEnabled = true;
        cfg.wirelessEndpoint = "10.26.3.135:39999";
        cfg.wirelessMdnsAutoDiscover = true;
        adb.setAutoUnlockSupplier(() -> cfg);

        adb.tick();

        Map<String, Object> status = adb.statusMap();
        assertEquals(0, status.get("wiredCount"));
        assertEquals(1, status.get("wirelessCount"));
        assertEquals(true, status.get("wirelessConnected"));
        assertTrue(commands.stream().anyMatch(c -> c.endsWith("connect 10.26.3.135:33007")));
        assertTrue(commands.stream().anyMatch(c -> c.contains("-s 10.26.3.135:33007 forward tcp:8554 tcp:8554")),
                "仅无线在线时应正常建立 tcp:8554 摄像头正向转发");
    }

    @Test
    void connectWirelessCleansUpOfflineEntryBeforeReconnecting() {
        List<String> commands = new ArrayList<>();
        AtomicBoolean connected = new AtomicBoolean(false);

        AdbPortForwarder adb = new AdbPortForwarder(defaultNet(), 8500, 0, cmd -> {
            String joined = String.join(" ", cmd);
            commands.add(joined);
            if (cmd.length == 3 && "devices".equals(cmd[1])) {
                if (!connected.get()) {
                    return new AdbPortForwarder.Result(0,
                            "List of devices attached\n10.26.3.135:33007      offline transport_id:2", false);
                }
                return new AdbPortForwarder.Result(0,
                        "List of devices attached\n10.26.3.135:33007      device transport_id:4", false);
            }
            if (cmd.length == 3 && "connect".equals(cmd[1]) && "10.26.3.135:33007".equals(cmd[2])) {
                connected.set(true);
                return new AdbPortForwarder.Result(0, "connected to 10.26.3.135:33007", false);
            }
            return new AdbPortForwarder.Result(0, "", false);
        });

        Map<String, Object> res = adb.connectWireless("10.26.3.135:33007", false);
        assertEquals(true, res.get("ok"));

        int disconnectIdx = indexOfSuffix(commands, "disconnect 10.26.3.135:33007");
        int connectIdx = indexOfSuffix(commands, "connect 10.26.3.135:33007");
        assertTrue(disconnectIdx >= 0 && connectIdx > disconnectIdx,
                "遇到旧 offline 残留时应先 disconnect 再 connect");
    }

    @Test
    void pairWirelessValidatesInputAndInvokesAdbPair() {
        List<String> commands = new ArrayList<>();
        AdbPortForwarder adb = new AdbPortForwarder(defaultNet(), 8500, 0, cmd -> {
            commands.add(String.join(" ", cmd));
            if (cmd.length >= 4 && "pair".equals(cmd[1])) {
                return new AdbPortForwarder.Result(0, "Successfully paired to 10.26.3.135:37123 [guid=adb-test]", false);
            }
            return new AdbPortForwarder.Result(0, "List of devices attached\n", false);
        });

        Map<String, Object> badEndpoint = adb.pairWireless("10.26.3.135", "123456");
        assertEquals(false, badEndpoint.get("ok"));
        assertTrue(commands.isEmpty());

        Map<String, Object> ok = adb.pairWireless("10.26.3.135:37123", "123456");
        assertEquals(true, ok.get("ok"));
        assertTrue(commands.stream().anyMatch(c -> c.endsWith("pair 10.26.3.135:37123 123456")));
    }

    @Test
    void failedConnectImmediatelyDisconnectsToAvoidOfflineZombie() {
        List<String> commands = new ArrayList<>();
        AdbPortForwarder adb = new AdbPortForwarder(defaultNet(), 8500, 0, cmd -> {
            commands.add(String.join(" ", cmd));
            if (cmd.length == 3 && "devices".equals(cmd[1])) {
                return new AdbPortForwarder.Result(0, "List of devices attached\n", false);
            }
            if (cmd.length == 3 && "connect".equals(cmd[1])) {
                return new AdbPortForwarder.Result(-1, "命令超时（3s）", false);
            }
            return new AdbPortForwarder.Result(0, "", false);
        });

        Map<String, Object> res = adb.connectWireless("10.26.3.135:33007", false);
        assertEquals(false, res.get("ok"));

        int connectIdx = indexOfSuffix(commands, "connect 10.26.3.135:33007");
        int disconnectIdx = indexOfSuffix(commands, "disconnect 10.26.3.135:33007");
        assertTrue(connectIdx >= 0 && disconnectIdx > connectIdx,
                "adb connect 失败/超时后应立即 disconnect 清理 offline 僵尸设备");
    }

    private static AppConfig.Network defaultNet() {
        AppConfig.Network net = new AppConfig.Network();
        net.adbExecutable = "adb";
        net.wsPort = 8500;
        net.httpPort = 8501;
        return net;
    }

    private static int indexOfSuffix(List<String> list, String suffix) {
        String spaced = " " + suffix;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).endsWith(spaced)) {
                return i;
            }
        }
        return -1;
    }
}
