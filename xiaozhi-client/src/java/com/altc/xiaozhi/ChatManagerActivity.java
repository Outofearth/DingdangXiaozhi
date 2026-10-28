package com.altc.xiaozhi;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 聊天记录管理页。
 *
 * 解决三件事：**看清有哪些**（按时间段筛）→ **挑出来**（多选）→ **删掉或导出**。
 * 主界面长按消息也能复制/多选/删除，这里管的是「范围」：今天 / 近 7 天 / 近 30 天 / 全部。
 *
 * 没有 Toast：这台设备 Toast 合成后看不见（SurfaceFlinger 有图层，屏幕上没有），
 * 所以所有反馈都写到底部那行 {@code tv_path} 上。
 */
public class ChatManagerActivity extends Activity {

    private static final int R_TODAY = 0;
    private static final int R_7D = 1;
    private static final int R_30D = 2;
    private static final int R_ALL = 3;

    private static final long GAP_FOR_TIME_ROW = 5 * 60 * 1000L;

    private ChatStore store;
    private final List<ChatStore.Msg> all = new ArrayList<>();
    private int range = R_ALL;

    private final Set<ChatStore.Msg> selected =
            java.util.Collections.newSetFromMap(
                    new IdentityHashMap<ChatStore.Msg, Boolean>());
    private final Map<ChatStore.Msg, TextView> picks = new IdentityHashMap<>();

    private LinearLayout list;
    private android.widget.ScrollView scroll;
    private TextView empty, stat, selText, pathText;
    private final TextView[] rangePills = new TextView[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_manager);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        store = new ChatStore(this);
        list = findViewById(R.id.list);
        scroll = findViewById(R.id.scroll);
        empty = findViewById(R.id.empty);
        stat = findViewById(R.id.tv_stat);
        selText = findViewById(R.id.tv_sel);
        pathText = findViewById(R.id.tv_path);

        rangePills[0] = findViewById(R.id.r_range0);
        rangePills[1] = findViewById(R.id.r_range1);
        rangePills[2] = findViewById(R.id.r_range2);
        rangePills[3] = findViewById(R.id.r_range3);
        for (int i = 0; i < rangePills.length; i++) {
            final int r = i;
            rangePills[i].setOnClickListener(v -> {
                if (range == r) return;
                range = r;
                selected.clear();
                render();
            });
        }

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.clear_all).setOnClickListener(v -> confirmClearAll());
        findViewById(R.id.b_sel_all).setOnClickListener(v -> {
            List<ChatStore.Msg> f = filtered();
            if (selected.size() >= f.size()) {
                selected.clear();
            } else {
                selected.addAll(f);
            }
            refreshPicks();
            updateSel();
        });
        findViewById(R.id.b_copy).setOnClickListener(v -> copyTarget());
        findViewById(R.id.b_export).setOnClickListener(v -> exportTarget());
        findViewById(R.id.b_del).setOnClickListener(v -> confirmDeleteSelected());

        all.addAll(store.load());
        render();
        hint("共 " + all.size() + " 条 · 存储 " + ChatStore.rawPath(this)
                + " · 导出目录 " + ChatExport.dirOf(this));
    }

    /* ==================== 筛选 / 渲染 ==================== */

    private long rangeStart() {
        if (range == R_ALL) return 0;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (range == R_7D) c.add(Calendar.DAY_OF_YEAR, -6);
        if (range == R_30D) c.add(Calendar.DAY_OF_YEAR, -29);
        return c.getTimeInMillis();
    }

    private List<ChatStore.Msg> filtered() {
        long from = rangeStart();
        List<ChatStore.Msg> out = new ArrayList<>();
        for (ChatStore.Msg m : all) {
            // 老记录没有时间戳：只在「全部」里出现
            if (range == R_ALL || (m.ts > 0 && m.ts >= from)) out.add(m);
        }
        return out;
    }

    private String rangeLabel() {
        switch (range) {
            case R_TODAY:
                return "今天（" + fmt(System.currentTimeMillis(), "yyyy-MM-dd") + "）";
            case R_7D:
                return "近 7 天";
            case R_30D:
                return "近 30 天";
            default:
                return "全部";
        }
    }

    private static String fmt(long ts, String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date(ts));
    }

    private void render() {
        for (int i = 0; i < rangePills.length; i++) {
            boolean on = i == range;
            rangePills[i].setBackgroundResource(on ? R.drawable.bg_pill_on : R.drawable.bg_pill_off);
            rangePills[i].setTextColor(on ? 0xFFFFFFFF : 0xFF1A1A1A);
        }

        list.removeAllViews();
        picks.clear();
        List<ChatStore.Msg> f = filtered();
        selected.retainAll(f);

        long min = 0, max = 0;
        ChatStore.Msg prev = null;
        for (ChatStore.Msg m : f) {
            if (m.ts > 0) {
                if (min == 0 || m.ts < min) min = m.ts;
                if (m.ts > max) max = m.ts;
                if (prev == null || prev.ts == 0 || m.ts - prev.ts >= GAP_FOR_TIME_ROW) {
                    list.addView(timeRow(m.ts));
                }
            }
            prev = m;
            list.addView(bubbleRow(m));
        }

        empty.setVisibility(f.isEmpty() ? View.VISIBLE : View.GONE);

        int legacy = 0;
        for (ChatStore.Msg m : all) if (m.ts == 0) legacy++;
        StringBuilder sb = new StringBuilder();
        sb.append(rangeLabel()).append("：").append(f.size()).append(" 条");
        if (min > 0) {
            sb.append(" · ").append(fmt(min, "MM-dd HH:mm"));
            if (max > 0 && max != min) sb.append(" ~ ").append(fmt(max, "MM-dd HH:mm"));
        }
        if (range != R_ALL && legacy > 0) sb.append(" · 另有 ").append(legacy).append(" 条无时间");
        stat.setText(sb);
        updateSel();
        // 最新的记录在最后：进来就停在底部，别让人以为「怎么只有这些」
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private View timeRow(long ts) {
        TextView tv = new TextView(this);
        tv.setText(fmt(ts, "MM-dd HH:mm"));
        tv.setTextSize(12);
        tv.setTextColor(0xFF9E9E9E);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(10), 0, dp(2));
        row.addView(tv);
        return row;
    }

    private View bubbleRow(final ChatStore.Msg m) {
        boolean me = m.isMe();
        boolean event = m.isEvent();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(event ? Gravity.CENTER : (me ? Gravity.END : Gravity.START));
        row.setPadding(0, dp(event ? 3 : 4), 0, dp(event ? 3 : 4));

        TextView pick = new TextView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(24), dp(24));
        plp.gravity = Gravity.CENTER_VERTICAL;
        plp.setMarginEnd(dp(8));
        pick.setLayoutParams(plp);
        pick.setGravity(Gravity.CENTER);
        pick.setText("✓");
        pick.setTextSize(14);
        applyPick(pick, selected.contains(m));
        picks.put(m, pick);

        TextView tv = new TextView(this);
        tv.setText(m.text);
        tv.setLineSpacing(dp(3), 1f);
        int maxW;
        if (event) {
            tv.setTextSize(13);
            tv.setTextColor(0xFF5F5F5F);
            tv.setBackgroundResource(R.drawable.bg_event);
            maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
        } else {
            tv.setTextSize(16);
            tv.setTextColor(0xFF111111);
            tv.setBackgroundResource(me ? R.drawable.bg_bubble_user : R.drawable.bg_bubble_ai);
            maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.66f);
        }
        tv.setMaxWidth(maxW);

        row.addView(pick);
        row.addView(tv);

        View.OnClickListener toggle = v -> {
            if (!selected.remove(m)) selected.add(m);
            applyPick(pick, selected.contains(m));
            updateSel();
        };
        row.setOnClickListener(toggle);
        tv.setOnClickListener(toggle);
        return row;
    }

    private void applyPick(TextView pick, boolean on) {
        pick.setBackgroundResource(on ? R.drawable.bg_pick_on : R.drawable.bg_pick_off);
        pick.setTextColor(on ? 0xFFFFFFFF : 0x00000000);
    }

    private void refreshPicks() {
        for (Map.Entry<ChatStore.Msg, TextView> e : picks.entrySet()) {
            applyPick(e.getValue(), selected.contains(e.getKey()));
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /* ==================== 操作 ==================== */

    /** 选中了就操作选中的；一条没选就操作当前时间段全部（等于「导出这个时间段」）。 */
    private List<ChatStore.Msg> target() {
        List<ChatStore.Msg> f = filtered();
        if (selected.isEmpty()) return f;
        List<ChatStore.Msg> out = new ArrayList<>();
        for (ChatStore.Msg m : f) if (selected.contains(m)) out.add(m);
        return out;
    }

    private void updateSel() {
        selText.setText("已选 " + selected.size() + " 条");
    }

    private void hint(String s) {
        pathText.setText(s);
    }

    private void copyTarget() {
        List<ChatStore.Msg> t = target();
        if (t.isEmpty()) {
            hint("这个时间段没有记录可复制");
            return;
        }
        boolean ok = ChatExport.copy(this, ChatExport.toText(t, rangeLabel()));
        hint(ok ? "已复制 " + t.size() + " 条到剪贴板（" + rangeLabel() + "）"
                : "复制失败：系统剪贴板不可用");
    }

    private void exportTarget() {
        List<ChatStore.Msg> t = target();
        if (t.isEmpty()) {
            hint("这个时间段没有记录可导出");
            return;
        }
        java.io.File f = ChatExport.write(this, t, rangeLabel());
        hint(f == null ? "导出失败：写不进 " + ChatExport.dirOf(this)
                : "已导出 " + t.size() + " 条 → " + f.getAbsolutePath());
    }

    private void confirmDeleteSelected() {
        if (selected.isEmpty()) {
            hint("先点几条（或按「全选」），再删");
            return;
        }
        final List<ChatStore.Msg> victims = target();
        new AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("删除 " + victims.size() + " 条记录？删掉就找不回来了。")
                .setPositiveButton("删除", (d, w) -> doDelete(victims))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doDelete(List<ChatStore.Msg> victims) {
        List<ChatStore.Msg> left = new ArrayList<>();
        for (ChatStore.Msg m : all) if (!containsIdentity(victims, m)) left.add(m);
        int n = all.size() - left.size();
        all.clear();
        all.addAll(left);
        selected.clear();
        store.save(all);
        ChatStore.dirty = true;
        render();
        hint("已删除 " + n + " 条，剩 " + all.size() + " 条");
    }

    private static boolean containsIdentity(List<ChatStore.Msg> hay, ChatStore.Msg needle) {
        for (ChatStore.Msg m : hay) if (m == needle) return true;
        return false;
    }

    private void confirmClearAll() {
        if (all.isEmpty()) {
            hint("本来就没有记录");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空全部记录")
                .setMessage("清空全部 " + all.size() + " 条聊天记录？删掉就找不回来了。")
                .setPositiveButton("清空", (d, w) -> {
                    store.clear();
                    all.clear();
                    selected.clear();
                    ChatStore.dirty = true;
                    render();
                    hint("已清空全部记录");
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
