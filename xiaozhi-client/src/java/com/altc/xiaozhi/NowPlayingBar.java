package com.altc.xiaozhi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * 「正在播放」进度条：一条圆角轨道 + 已播进度，时间「当前 / 总长」骑在条上。
 *
 * 为什么自绘而不是 ProgressBar 叠一个 TextView：
 * 时间要求显示在条身上，两层叠加时文字能不能看清全靠进度走到哪儿 ——
 * 白字压到浅灰轨道上就糊了。自绘才能给时间垫一层白底胶囊，
 * 保证进度在任何位置都可读。顺带支持「点/拖条跳转」这条语音之外的路。
 */
public class NowPlayingBar extends View {

    /** 用户点/拖进度条：fraction 是 0~1 的目标位置比例。 */
    public interface SeekListener {
        void onSeekFraction(float fraction);
    }

    // 轨道不能太浅：它要压在事件条的浅灰底（#E4E4E4）上，同色系就直接糊没了
    private static final int COLOR_TRACK = 0xFFC6C6C6;
    private static final int COLOR_FROM = 0xFF07C160;
    private static final int COLOR_TO = 0xFF05974A;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 文字外面那圈白边：进度条从字底下穿过去，不垫东西就分不清字和条。 */
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    /** 已播比例 0~1。 */
    private float frac = 0f;
    private String label = "--:-- / --:--";
    private SeekListener seekListener;

    public NowPlayingBar(Context c) { this(c, null); }

    public NowPlayingBar(Context c, AttributeSet a) {
        super(c, a);
        trackPaint.setColor(COLOR_TRACK);
        progPaint.setColor(COLOR_FROM);

        textPaint.setColor(0xFF1A1A1A);
        textPaint.setTextSize(sp(13));
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.LEFT);

        // 白描边而不是白底块：底块会把进度条切成两段，看着像两条不同的进度
        haloPaint.setColor(0xFFFFFFFF);
        haloPaint.setTextSize(sp(13));
        haloPaint.setFakeBoldText(true);
        haloPaint.setTextAlign(Paint.Align.LEFT);
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(dp(2.6f));
        haloPaint.setStrokeJoin(Paint.Join.ROUND);
        haloPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setSeekListener(SeekListener l) { seekListener = l; }

    /**
     * 刷新进度。dur <= 0（还没 prepare 完 / 不知道总长）时只显示占位时间，
     * 不让进度条乱跳。
     */
    public void setProgress(int posMs, int durMs) {
        float f = 0f;
        String text;
        if (durMs > 0) {
            int p = Math.max(0, Math.min(posMs, durMs));
            f = p / (float) durMs;
            text = MediaFormats.fmt(p) + " / " + MediaFormats.fmt(durMs);
        } else {
            f = 0f;
            text = "--:-- / --:--";
        }
        if (Math.abs(f - frac) < 0.0005f && text.equals(label)) return;
        frac = f;
        label = text;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0) {
            progPaint.setShader(new LinearGradient(0, 0, w, 0, COLOR_FROM, COLOR_TO,
                    Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float barH = dp(11);
        final float cy = h / 2f;
        final float r = barH / 2f;

        // 轨道
        rf.set(0, cy - r, w, cy + r);
        canvas.drawRoundRect(rf, r, r, trackPaint);

        // 已播
        if (frac > 0.004f) {
            rf.right = Math.max(barH, w * frac);
            canvas.drawRoundRect(rf, r, r, progPaint);
        }

        // 时间：直接骑在条上，白描边黑字 —— 走到灰轨道还是绿进度上都读得清，
        // 又不垫底块，进度条始终是连续的一条。
        final float tw = textPaint.measureText(label);
        final Paint.FontMetrics fm = textPaint.getFontMetrics();
        final float baseline = cy - (fm.ascent + fm.descent) / 2f;
        final float tx = (w - tw) / 2f;
        canvas.drawText(label, tx, baseline, haloPaint);
        canvas.drawText(label, tx, baseline, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (seekListener == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 别让外面（ScrollView 之类）抢走这一次手势
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_UP:
                if (getWidth() > 0) seekListener.onSeekFraction(e.getX() / getWidth());
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }
}
