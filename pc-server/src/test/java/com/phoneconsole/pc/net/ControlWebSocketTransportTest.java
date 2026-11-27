package com.phoneconsole.pc.net;

import com.phoneconsole.pc.config.AppConfig;
import com.phoneconsole.pc.core.ActionEngine;
import org.java_websocket.WebSocket;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlWebSocketTransportTest {

    @Test
    void resolveTransportDistinguishesWiredUsbWirelessAdbAndLan() {
        AppConfig cfg = new AppConfig();
        ControlWebSocketServer server = new ControlWebSocketServer(
                new InetSocketAddress("127.0.0.1", 0), cfg, new ActionEngine(cfg));

        AtomicBoolean wiredOnline = new AtomicBoolean(false);
        server.setWiredAdbSupplier(wiredOnline::get);

        WebSocket loopbackConn = stubConn("127.0.0.1", 7902);
        WebSocket lanConn = stubConn("10.53.253.6", 33734);

        assertEquals("wireless_adb", server.resolveTransport(loopbackConn),
                "无有线 USB 在线时，127.0.0.1 回环映射应识别为 wireless_adb");
        assertEquals("lan", server.resolveTransport(lanConn),
                "非回环直连地址应识别为 lan");

        wiredOnline.set(true);
        assertEquals("usb", server.resolveTransport(loopbackConn),
                "存在有线 USB 设备在线时，127.0.0.1 回环映射应识别为 usb");
        assertEquals("lan", server.resolveTransport(lanConn));
    }

    private static WebSocket stubConn(String host, int port) {
        InetSocketAddress addr = InetSocketAddress.createUnresolved(host, port);
        InetSocketAddress resolved;
        try {
            resolved = new InetSocketAddress(java.net.InetAddress.getByName(host), port);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getRemoteSocketAddress".equals(method.getName())) {
                return resolved;
            }
            if ("hashCode".equals(method.getName())) {
                return resolved.hashCode();
            }
            if ("equals".equals(method.getName())) {
                return proxy == args[0];
            }
            if ("toString".equals(method.getName())) {
                return addr.toString();
            }
            return null;
        };
        return (WebSocket) Proxy.newProxyInstance(
                ControlWebSocketTransportTest.class.getClassLoader(),
                new Class<?>[]{WebSocket.class},
                handler);
    }
}

