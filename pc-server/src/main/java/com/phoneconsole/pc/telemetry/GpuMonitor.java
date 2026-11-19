package com.phoneconsole.pc.telemetry;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GPU 占用采集：走 Windows PDH 性能计数器 {@code \GPU Engine(*)\Utilization Percentage}。
 * <p>
 * 取值口径与任务管理器一致：所有 GPU 引擎实例中最忙的一个（max），范围 0~100。
 * 采集失败或系统不支持时返回 -1，客户端显示「—」。
 */
public class GpuMonitor {

    private static final Logger log = LoggerFactory.getLogger(GpuMonitor.class);

    private static final int PDH_FMT_DOUBLE = 0x00000200;
    private static final int PDH_MORE_DATA = (int) 0x800007D2L;
    private static final String COUNTER_PATH = "\\GPU Engine(*)\\Utilization Percentage";
    /** 每个进程的专用显存占用（字节），逐进程求和即为已用显存 */
    private static final String MEMORY_COUNTER_PATH = "\\GPU Process Memory(*)\\Dedicated Usage";

    private Pointer query;
    private Pointer counter;
    private boolean primed;
    private boolean warned;

    private Pointer memQuery;
    private Pointer memCounter;
    private boolean memPrimed;
    private boolean memWarned;

    public GpuMonitor() {
        if (!Platform.isWindows()) {
            return;
        }
        try {
            PointerByReference queryRef = new PointerByReference();
            int status = Pdh.INSTANCE.PdhOpenQueryW(null, null, queryRef);
            if (status != 0) {
                log.warn("PDH 查询创建失败：0x{}", Integer.toHexString(status));
                return;
            }
            query = queryRef.getValue();

            PointerByReference counterRef = new PointerByReference();
            status = Pdh.INSTANCE.PdhAddEnglishCounterW(query, new WString(COUNTER_PATH), null, counterRef);
            if (status != 0) {
                log.warn("GPU 计数器不可用（0x{}），GPU 占用将显示为 —", Integer.toHexString(status));
                close();
                return;
            }
            counter = counterRef.getValue();
            Pdh.INSTANCE.PdhCollectQueryData(query);
            primed = true;
            log.info("GPU 占用采集已启用（PDH GPU Engine）");
        } catch (Throwable t) {
            log.warn("GPU 采集初始化失败：{}", t.getMessage());
            close();
        }
        initMemoryCounter();
    }

    /** 显存计数器独立初始化：GPU 占用不可用时显存仍可能可读 */
    private void initMemoryCounter() {
        try {
            PointerByReference queryRef = new PointerByReference();
            int status = Pdh.INSTANCE.PdhOpenQueryW(null, null, queryRef);
            if (status != 0) {
                return;
            }
            memQuery = queryRef.getValue();
            PointerByReference counterRef = new PointerByReference();
            status = Pdh.INSTANCE.PdhAddEnglishCounterW(memQuery, new WString(MEMORY_COUNTER_PATH), null, counterRef);
            if (status != 0) {
                log.info("显存计数器不可用（0x{}），显存将显示为 —", Integer.toHexString(status));
                closeMemory();
                return;
            }
            memCounter = counterRef.getValue();
            Pdh.INSTANCE.PdhCollectQueryData(memQuery);
            memPrimed = true;
            log.info("显存采集已启用（PDH GPU Adapter Memory）");
        } catch (Throwable t) {
            log.warn("显存采集初始化失败：{}", t.getMessage());
            closeMemory();
        }
    }

    /** @return GPU 占用百分比；不可用时返回 -1 */
    public synchronized double usage() {
        if (!primed) {
            return -1;
        }
        try {
            int status = Pdh.INSTANCE.PdhCollectQueryData(query);
            if (status != 0) {
                return -1;
            }
            IntByReference sizeRef = new IntByReference();
            IntByReference countRef = new IntByReference();
            Pdh.INSTANCE.PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, sizeRef, countRef, null);
            if (sizeRef.getValue() <= 0) {
                return -1;
            }
            Memory buffer = new Memory(sizeRef.getValue());
            status = Pdh.INSTANCE.PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE, sizeRef, countRef, buffer);
            if (status != 0 && status != PDH_MORE_DATA) {
                return -1;
            }
            int count = countRef.getValue();
            if (count <= 0) {
                return 0;
            }
            Item first = new Item(buffer);
            Item[] items = (Item[]) first.toArray(count);
            double max = 0;
            for (Item item : items) {
                if (item.FmtValue.CStatus != 0) {
                    continue;
                }
                double value = item.FmtValue.doubleValue;
                if (Double.isFinite(value) && value > max) {
                    max = value;
                }
            }
            double percent = Math.max(0, Math.min(100, max));
            return Math.round(percent * 10.0) / 10.0;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                log.warn("GPU 采样失败：{}", t.getMessage());
            }
            return -1;
        }
    }

    /** @return 已用显存 MB；不可用时返回 -1 */
    public synchronized double vramUsedMb() {
        if (!memPrimed) {
            return -1;
        }
        try {
            int status = Pdh.INSTANCE.PdhCollectQueryData(memQuery);
            if (status != 0) {
                return -1;
            }
            IntByReference sizeRef = new IntByReference();
            IntByReference countRef = new IntByReference();
            Pdh.INSTANCE.PdhGetFormattedCounterArrayW(memCounter, PDH_FMT_DOUBLE, sizeRef, countRef, null);
            if (sizeRef.getValue() <= 0) {
                return -1;
            }
            Memory buffer = new Memory(sizeRef.getValue());
            status = Pdh.INSTANCE.PdhGetFormattedCounterArrayW(memCounter, PDH_FMT_DOUBLE, sizeRef, countRef, buffer);
            if (status != 0 && status != PDH_MORE_DATA) {
                return -1;
            }
            int count = countRef.getValue();
            if (count <= 0) {
                return 0;
            }
            Item first = new Item(buffer);
            Item[] items = (Item[]) first.toArray(count);
            double total = 0;
            for (Item item : items) {
                if (item.FmtValue.CStatus != 0) {
                    continue;
                }
                double bytes = item.FmtValue.doubleValue;
                if (Double.isFinite(bytes) && bytes > 0) {
                    total += bytes;
                }
            }
            return Math.round(total / 1048576.0 * 10.0) / 10.0;
        } catch (Throwable t) {
            if (!memWarned) {
                memWarned = true;
                log.warn("显存采样失败：{}", t.getMessage());
            }
            return -1;
        }
    }

    public synchronized void close() {
        primed = false;
        closeMemory();
        if (query != null) {
            try {
                Pdh.INSTANCE.PdhCloseQuery(query);
            } catch (Throwable ignored) {
                // 关闭阶段失败可忽略
            }
            query = null;
            counter = null;
        }
    }

    private void closeMemory() {
        memPrimed = false;
        if (memQuery != null) {
            try {
                Pdh.INSTANCE.PdhCloseQuery(memQuery);
            } catch (Throwable ignored) {
                // 关闭阶段失败可忽略
            }
            memQuery = null;
            memCounter = null;
        }
    }

    /** PDH_FMT_COUNTERVALUE（只读取 double 分支） */
    @Structure.FieldOrder({"CStatus", "doubleValue"})
    public static class FmtValue extends Structure {
        public int CStatus;
        public double doubleValue;
    }

    /** PDH_FMT_COUNTERVALUE_ITEM_W */
    @Structure.FieldOrder({"szName", "FmtValue"})
    public static class Item extends Structure {
        public Pointer szName;
        public FmtValue FmtValue;

        public Item() {
        }

        public Item(Pointer pointer) {
            super(pointer);
            read();
        }
    }

    interface Pdh extends Library {
        Pdh INSTANCE = Native.load("pdh", Pdh.class, W32APIOptions.DEFAULT_OPTIONS);

        int PdhOpenQueryW(WString dataSource, Pointer userData, PointerByReference query);

        int PdhAddEnglishCounterW(Pointer query, WString path, Pointer userData, PointerByReference counter);

        int PdhCollectQueryData(Pointer query);

        int PdhGetFormattedCounterArrayW(Pointer counter, int format, IntByReference bufferSize,
                                         IntByReference itemCount, Pointer buffer);

        int PdhCloseQuery(Pointer query);
    }
}
