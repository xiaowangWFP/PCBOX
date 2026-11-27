package com.phoneconsole.pc.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LanAddressesTest {

    @Test
    void mobileHotspotScoresCloseToPhysicalLanAndBetterThanGenericInterface() {
        int physicalWlanScore = LanAddresses.scoreInterface("WLAN", "10.26.3.97", false);
        int hotspotScore = LanAddresses.scoreInterface("本地连接* 10", "192.168.137.1", true);
        int otherPrivateScore = LanAddresses.scoreInterface("Unknown Adapter", "172.16.1.10", false);

        assertTrue(physicalWlanScore < hotspotScore, "物理局域网网卡应保持最高优先级");
        assertTrue(hotspotScore < otherPrivateScore, "Windows 移动热点 (192.168.137.1) 应优先于未识别网卡");
    }
}

