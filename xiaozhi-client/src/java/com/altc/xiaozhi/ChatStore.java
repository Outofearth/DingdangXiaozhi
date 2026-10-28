package com.altc.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 本地聊天记录。
 *
 * 说明：小智 v1 协议**不提供历史消息查询接口**（服务端只在 xiaozhi.me 控制台展示
 * 历史对话），所以设备侧只能自己存一份，用于主界面上滑查看。
 *
 * 存储：SharedPreferences("xz") → key "chat_history"，值是一个 JSON 数组，每条
 * {"i":消息唯一ID, "r":角色, "t":文本, "ts":毫秒时间戳, "k":类型}。上限 {@link #MAX} 条，
 * 超了丢最旧的。想直接看原始数据：adb pull /data/data/com.altc.xiaozhi/shared_prefs/xz.xml
 *
 * 三类消息（屏幕语义靠它区分，见 {@link Msg#kind}）：
 *   me    —— 我说的（右侧绿气泡）
 *   ai    —— 小智的回答（左侧白气泡）
 *   event —— 播放/音量/失败等系统事件（居中灰色小字，**不是回答**）
 *
 * 多设备同步（同账号、同局域网）：每条消息带稳定唯一 id（UUID）。同步时按 id 去重、
 * 按 ts 排序合并，保证两端聊天记录一致（参考微信的多设备消息同步语义）。
 */
public class ChatStore {
    public static final String ROLE_ME = "me";
    public static final String ROLE_AI = "ai";

    /** 类型：对话文本（me / ai 用） */
    public static final String KIND_TEXT = "text";
    /** 类型：系统事件（播放、音量、失败提示…），渲染成居中灰条，不参与问答 */
    public static final String KIND_EVENT = "event";

    /** 记录条数上限（超出丢最旧的）。500 条 ≈ 60KB，SharedPreferences 扛得住。 */
    public static final int MAX = 500;

    private static final String KEY = "chat_history";

    /** 记录在别的页面被改过（管理页删除/清空）→ 主界面 onResume 时重绘一次。 */
    public static volatile boolean dirty = false;

    private final SharedPreferences sp;

    public ChatStore(Context c) {
        this.sp = c.getSharedPreferences("xz", Context.MODE_PRIVATE);
    }

    public static class Msg {
        /** 消息唯一 ID（UUID），同步去重与排序的锚点。老记录加载时若无 id 则当场补一个。 */
        public final String id;
        public final String role;
        public String text;
        /** 毫秒时间戳；老版本存下来的记录没有这个字段，迁移时置 0（显示为「较早」）。 */
        public final long ts;
        /** {@link #KIND_TEXT} 或 {@link #KIND_EVENT} */
        public final String kind;

        /** 新建一条（自动生成 id 与当前时间）。 */
        public Msg(String role, String text) {
            this(role, text, System.currentTimeMillis(), KIND_TEXT, null);
        }

        public Msg(String role, String text, long ts, String kind) {
            this(role, text, ts, kind, null);
        }

        /** 同步还原用：携带既有 id（来自对端，保证两端 id 一致）。 */
        public Msg(String role, String text, long ts, String kind, String id) {
            this.id = (id == null || id.isEmpty()) ? UUID.randomUUID().toString() : id;
            this.role = role;
            this.text = text;
            this.ts = ts > 0 ? ts : 0;
            this.kind = (kind == null || kind.isEmpty()) ? KIND_TEXT : kind;
        }

        public boolean isEvent() {
            return KIND_EVENT.equals(kind);
        }

        public boolean isMe() {
            return ROLE_ME.equals(role);
        }
    }

    /**
     * 老版本没有 kind 字段：当时所有播放提示都写成「♪ xxx（电脑直读）」这种，
     * 靠前缀 + 结尾括号把它们回填成事件，免得历史记录里新旧两种风格混着。
     */
    public static boolean looksLikeLegacyEvent(String t) {
        if (t == null) return false;
        String s = t.trim();
        return s.startsWith("♪") || s.startsWith("▶") || s.startsWith("音量 ")
                || s.startsWith("■");
    }

    /**
     * 老记录的文字顺手洗一遍：去掉「♪」记号，把括号里的行话换成「· 电脑共享 / · 本机」。
     * 只对事件生效——回答里出现的「（视频）」这类标注是清单内容，不能动。
     */
    public static String cleanLegacyEvent(String t) {
        if (t == null) return "";
        String s = t.trim();
        s = s.replace("（电脑直读）", " · 电脑共享")
                .replace("（本地播放）", " · 本机")
                .replace("（本地文件）", " · 本机")
                .replace("（远程播放）", " · 电脑共享");
        if (s.startsWith("♪")) s = s.substring(1).trim();
        return s;
    }

    public List<Msg> load() {
        List<Msg> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("t", "");
                if (t.isEmpty()) continue;
                String role = o.optString("r", ROLE_AI);
                String kind = o.optString("k", "");
                String id = o.optString("i", "");
                boolean legacy = kind.isEmpty();
                if (legacy) {
                    kind = (ROLE_AI.equals(role) && looksLikeLegacyEvent(t))
                            ? KIND_EVENT : KIND_TEXT;
                }
                if (KIND_EVENT.equals(kind) && legacy) t = cleanLegacyEvent(t);
                if (t.isEmpty()) continue;
                out.add(new Msg(role, t, o.optLong("ts", 0), kind, id));
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    public void save(List<Msg> list) {
        try {
            JSONArray arr = new JSONArray();
            int from = Math.max(0, list.size() - MAX);
            for (int i = from; i < list.size(); i++) {
                Msg m = list.get(i);
                JSONObject o = new JSONObject();
                o.put("i", m.id);
                o.put("r", m.role);
                o.put("t", m.text);
                o.put("ts", m.ts);
                o.put("k", m.kind);
                arr.put(o);
            }
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignore) {
        }
    }

    /**
     * 合并对端同步来的消息：按 id 去重（同 id 不重复加入），合并后按 ts 升序排序，
     * 再写回。返回新加入的条数（0 表示无变化）。供多设备同步调用，语义参考微信。
     */
    public int merge(List<Msg> incoming) {
        if (incoming == null || incoming.isEmpty()) return 0;
        List<Msg> cur = load();
        Set<String> ids = new HashSet<>();
        for (Msg m : cur) ids.add(m.id);
        int added = 0;
        for (Msg m : incoming) {
            if (m == null || m.text == null || m.text.isEmpty()) continue;
            if (ids.contains(m.id)) continue;
            cur.add(m);
            ids.add(m.id);
            added++;
        }
        if (added == 0) return 0;
        Collections.sort(cur, new Comparator<Msg>() {
            @Override public int compare(Msg a, Msg b) { return Long.compare(a.ts, b.ts); }
        });
        save(cur);
        return added;
    }

    /** 把当前记录序列化成同步传输用的 JSON 数组（含 id）。 */
    public static String toJson(List<Msg> list) {
        JSONArray arr = new JSONArray();
        for (Msg m : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("i", m.id);
                o.put("r", m.role);
                o.put("t", m.text);
                o.put("ts", m.ts);
                o.put("k", m.kind);
                arr.put(o);
            } catch (Throwable ignore) {
            }
        }
        return arr.toString();
    }

    /** 把同步收到的 JSON 数组解析成 Msg 列表（保留对端 id，保证去重一致）。 */
    public static List<Msg> fromJson(String json) {
        List<Msg> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("t", "");
                if (t.isEmpty()) continue;
                out.add(new Msg(o.optString("r", ROLE_AI), t, o.optLong("ts", 0),
                        o.optString("k", ""), o.optString("i", "")));
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    /** 删掉 list 里这些对象（按对象身份比对，不按文本，重复内容也不会误删）。 */
    public static boolean removeAll(List<Msg> list, java.util.Collection<Msg> victims) {
        boolean any = false;
        for (Msg m : list) {
            if (victims.contains(m)) any = true;
        }
        if (!any) return false;
        list.removeAll(victims);
        return true;
    }

    public void clear() {
        sp.edit().remove(KEY).apply();
    }

    /** 原始存储文件（给「记录存哪」这个问题的答案用，同时也是排查入口）。 */
    public static String rawPath(Context c) {
        return c.getFilesDir().getParent() + "/shared_prefs/xz.xml";
    }
}
