package com.phoneconsole.client.net;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 已保存的连接通道（地址 + 端口 + 自定义名称）。
 * 以 JSON 数组存在 SharedPreferences 里，支持任意条数，可下拉切换。
 */
public final class ChannelStore {

    public static final class Channel {
        public String name;
        public String host;
        public int port;

        public Channel() {
        }

        public Channel(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        public String endpoint() {
            return "ws://" + host + ":" + port;
        }

        /** 下拉框显示文本：名称 · host:port */
        public String label() {
            String title = (name == null || name.trim().isEmpty()) ? host : name.trim();
            return title + " · " + host + ":" + port;
        }
    }

    private static final String PREF = "channel_store";
    private static final String KEY_LIST = "channels";
    private static final String KEY_SELECTED = "selected";

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Channel>>() {
    }.getType();

    private ChannelStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static List<Channel> load(Context context) {
        String raw = prefs(context).getString(KEY_LIST, null);
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Channel> list = GSON.fromJson(raw, LIST_TYPE);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void save(Context context, List<Channel> channels, int selected) {
        int index = selected;
        if (index < 0 || index >= channels.size()) {
            index = 0;
        }
        prefs(context).edit()
                .putString(KEY_LIST, GSON.toJson(channels))
                .putInt(KEY_SELECTED, index)
                .apply();
    }

    public static int selected(Context context) {
        return prefs(context).getInt(KEY_SELECTED, 0);
    }
}
