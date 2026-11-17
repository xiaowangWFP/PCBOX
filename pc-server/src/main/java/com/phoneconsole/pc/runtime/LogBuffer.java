package com.phoneconsole.pc.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 运行日志环形缓冲：记录关键运行事件，供网页控制台增量拉取。
 * 只保存在内存中，进程退出即清空；容量固定，避免长时间运行占用内存。
 */
public final class LogBuffer {

    private static final LogBuffer INSTANCE = new LogBuffer();
    private static final int CAPACITY = 800;

    /** 单条日志 */
    public static final class Entry {
        public long seq;
        public long time;
        public String level;
        public String tag;
        public String message;

        Entry(long seq, String level, String tag, String message) {
            this.seq = seq;
            this.time = System.currentTimeMillis();
            this.level = level;
            this.tag = tag;
            this.message = message == null ? "" : message;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>(CAPACITY);
    private long seq;

    private LogBuffer() {
    }

    public static LogBuffer get() {
        return INSTANCE;
    }

    public void info(String tag, String message) {
        add("INFO", tag, message);
    }

    public void warn(String tag, String message) {
        add("WARN", tag, message);
    }

    public void error(String tag, String message) {
        add("ERROR", tag, message);
    }

    public synchronized void add(String level, String tag, String message) {
        if (entries.size() >= CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(new Entry(++seq, level, tag, message));
    }

    /** 取 seq 之后的日志；超过 limit 时只返回最新的 limit 条 */
    public synchronized List<Entry> since(long afterSeq, int limit) {
        int max = limit > 0 ? limit : 300;
        List<Entry> matched = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.seq > afterSeq) {
                matched.add(entry);
            }
        }
        if (matched.size() <= max) {
            return matched;
        }
        return new ArrayList<>(matched.subList(matched.size() - max, matched.size()));
    }

    public synchronized long lastSeq() {
        return seq;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** 清空内容，但保持 seq 单调递增，避免客户端拉取游标回退 */
    public synchronized void clear() {
        entries.clear();
    }
}
