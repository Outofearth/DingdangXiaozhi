package com.altc.xiaozhi;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 外观设置：选择主界面排列方式。
 *
 * <ul>
 *   <li>{@link #MODE_STACK}：上下排列（聊天在上、按钮在下，默认）</li>
 *   <li>{@link #MODE_SPLIT}：左右排列（聊天在左、操作面板在右）</li>
 * </ul>
 * 只写 SharedPreferences，主界面在 onResume 里发现变化后自行重建。
 */
public class AppearanceActivity extends Activity {

    public static final String KEY_LAYOUT = "layoutMode";
    public static final String MODE_STACK = "stack";
    public static final String MODE_SPLIT = "split";

    private SharedPreferences sp;
    private LinearLayout cardStack;
    private LinearLayout cardSplit;
    private TextView tagStack;
    private TextView tagSplit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appearance);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        sp = getSharedPreferences("xz", MODE_PRIVATE);

        cardStack = findViewById(R.id.card_stack);
        cardSplit = findViewById(R.id.card_split);
        tagStack = findViewById(R.id.tag_stack);
        tagSplit = findViewById(R.id.tag_split);

        findViewById(R.id.back).setOnClickListener(v -> finish());

        cardStack.setOnClickListener(v -> choose(MODE_STACK));
        cardSplit.setOnClickListener(v -> choose(MODE_SPLIT));

        applyUi();
    }

    private void choose(String mode) {
        if (mode.equals(current())) {
            Toast.makeText(this, "已经是这个布局了", Toast.LENGTH_SHORT).show();
            return;
        }
        sp.edit().putString(KEY_LAYOUT, mode).apply();
        applyUi();
        Toast.makeText(this,
                MODE_SPLIT.equals(mode) ? "已切换为左右排列" : "已切换为上下排列",
                Toast.LENGTH_SHORT).show();
    }

    private String current() {
        return sp.getString(KEY_LAYOUT, MODE_STACK);
    }

    private void applyUi() {
        boolean split = MODE_SPLIT.equals(current());
        cardStack.setBackgroundResource(split ? R.drawable.bg_card : R.drawable.bg_card_selected);
        cardSplit.setBackgroundResource(split ? R.drawable.bg_card_selected : R.drawable.bg_card);
        tagStack.setVisibility(split ? View.GONE : View.VISIBLE);
        tagSplit.setVisibility(split ? View.VISIBLE : View.GONE);
    }
}
