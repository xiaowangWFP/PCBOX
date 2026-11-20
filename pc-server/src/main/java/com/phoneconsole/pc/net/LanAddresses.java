package com.phoneconsole.pc.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 本机可被手机访问的 IPv4 地址。
 * <p>
 * 直接取第一个网卡很容易拿到虚拟网卡（VMware / Hyper-V / Clash TUN / Radmin VPN / Tailscale），
 * 手机根本连不上。这里按“像不像真实局域网”打分排序：
 * 优先 WLAN/以太网、DHCP 分配、私有网段的地址，排除已知的虚拟与隧道网卡。
 */
public final class LanAddresses {

    private static final Logger log = LoggerFactory.getLogger(LanAddresses.class);

    /** 明确的虚拟/隧道/蓝牙网卡，直接排除 */
    private static final Pattern VIRTUAL_ALIAS = Pattern.compile(
            "(?i)vmware|virtualbox|hyper-?v|vethernet|tailscale|zerotier|radmin|clash|meta\\b|tun\\b|tap\\b"
                    + "|bluetooth|蓝牙|loopback|docker|wsl|本地连接\\*|virtual adapter");

    /** 典型物理局域网网卡，优先 */
    private static final Pattern LAN_ALIAS = Pattern.compile(
            "(?i)wi-?fi|wlan|无线|ethernet|以太网|局域网");

    private LanAddresses() {
    }

    /** 排序后的候选地址，第一个即最推荐给手机使用的地址 */
    public static List<String> ordered() {
        List<Candidate> candidates = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                String alias = nic.getDisplayName() == null ? nic.getName() : nic.getDisplayName();
                if (VIRTUAL_ALIAS.matcher(alias).find()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }
                    String host = address.getHostAddress();
                    if (!isUsable(host)) {
                        continue;
                    }
                    int score = 0;
                    if (!LAN_ALIAS.matcher(alias).find()) {
                        score += 10;
                    }
                    if (!isPrivate(host)) {
                        score += 5;
                    }
                    candidates.add(new Candidate(host, score));
                }
            }
        } catch (Exception e) {
            log.warn("枚举本机地址失败：{}", e.getMessage());
        }

        candidates.sort(Comparator.comparingInt(c -> c.score));
        List<String> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!result.contains(candidate.host)) {
                result.add(candidate.host);
            }
        }
        return result;
    }

    private static boolean isUsable(String host) {
        if (host == null || host.startsWith("127.") || host.startsWith("169.254.")) {
            return false;
        }
        // 198.18.0.0/15 为基准测试/代理 TUN 网段，100.64.0.0/10 为运营商 NAT/Tailscale
        return !host.startsWith("198.18.") && !host.startsWith("198.19.")
                && !host.startsWith("100.64.") && !host.startsWith("100.65.");
    }

    private static boolean isPrivate(String host) {
        if (host.startsWith("10.") || host.startsWith("192.168.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            try {
                int second = Integer.parseInt(parts[1]);
                return second >= 16 && second <= 31;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    private record Candidate(String host, int score) {
    }
}
