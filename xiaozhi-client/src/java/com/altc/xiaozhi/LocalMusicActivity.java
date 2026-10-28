package com.altc.xiaozhi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地音乐管理页（设置 → 本地音乐）。
 *
 * 列出音箱上 /sdcard/Music（含子目录）里所有可播的本地音频，
 * 支持：试听（播放/停止预览）、单首删除、一键清空全部。
 *
 * 进入本页时会先广播让主界面停掉正在放的本地音乐，避免两边同时出声。
 */
public class LocalMusicActivity extends Activity {

    /** 让主界面停下本地音乐播放的广播（常量与 MainActivity 里保持一致）。 */
    static final String ACTION_STOP_LOCAL = "com.altc.xiaozhi.action.STOP_LOCAL_MUSIC";

    private LocalFiles localFiles;
    private FileMusicPlayer preview;
    private ListView list;
    private TextView tvCount;
    private TextView empty;
    private final List<LocalFiles.Track> tracks = new ArrayList<>();
    private LocalFiles.Track playing;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) { stopPreview(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_local_music);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        localFiles = new LocalFiles(this);
        preview = new FileMusicPlayer();

        list = findViewById(R.id.list);
        tvCount = findViewById(R.id.tv_count);
        empty = findViewById(R.id.empty);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> reload());
        findViewById(R.id.btn_clear).setOnClickListener(v -> confirmClearAll());

        // 点行内空白区域 = 试听切换
        list.setOnItemClickListener((p, v, pos, id) -> togglePlay(pos));

        // 让主界面的本地音乐先停，免得和这里试听打架
        sendBroadcast(new Intent(ACTION_STOP_LOCAL));
        registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP_LOCAL));

        reload();
    }

    private void reload() {
        localFiles.invalidate();
        tracks.clear();
        tracks.addAll(localFiles.tracks());
        tvCount.setText("共 " + tracks.size() + " 首");
        empty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        list.setAdapter(new TrackAdapter());
    }

    private void togglePlay(int pos) {
        if (pos < 0 || pos >= tracks.size()) return;
        LocalFiles.Track t = tracks.get(pos);
        if (playing != null && playing.path().equals(t.path()) && preview.isPlaying()) {
            stopPreview();
            return;
        }
        // 先确保主界面也停了（双保险），再播这一首
        sendBroadcast(new Intent(ACTION_STOP_LOCAL));
        stopPreview();
        final LocalFiles.Track ft = t;
        preview.play(t.path(), new FileMusicPlayer.Listener() {
            @Override public void onDone() {
                ui.post(() -> { if (playing == ft) { playing = null; refresh(); } });
            }
            @Override public void onError(String why) {
                ui.post(() -> {
                    if (playing == ft) playing = null;
                    refresh();
                    Toast.makeText(LocalMusicActivity.this, "放不了：" + ft.title, Toast.LENGTH_SHORT).show();
                });
            }
        });
        playing = t;
        refresh();
    }

    private void stopPreview() {
        preview.stop();
        playing = null;
        refresh();
    }

    private void refresh() {
        if (list.getAdapter() != null) ((TrackAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    private void confirmClearAll() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "没有可清空的", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空全部本地音乐？")
                .setMessage("将删除默认目录（Music / Movies / Video 等）里的 "
                        + countInDefaultRoots() + " 个媒体文件，且不可恢复。\n" +
                        "（不会删除服务器曲库里已缓存的歌，也不会碰你在设置里加的「额外扫描目录」。）")
                .setPositiveButton("清空", (d, w) -> deleteAll())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 这个文件是不是落在默认扫描根里（默认根之外的、用户自己加的目录一律不动）。 */
    private boolean inDefaultRoots(File f) {
        if (f == null) return false;
        String p = f.getAbsolutePath();
        for (File r : LocalFiles.defaultRoots(this)) {
            String rp = r.getAbsolutePath();
            if (p.equals(rp) || p.startsWith(rp.endsWith("/") ? rp : rp + "/")) return true;
        }
        return false;
    }

    private int countInDefaultRoots() {
        int n = 0;
        for (LocalFiles.Track t : tracks) if (inDefaultRoots(t.file)) n++;
        return n;
    }

    private void deleteAll() {
        int n = 0, skipped = 0;
        for (LocalFiles.Track t : new ArrayList<>(tracks)) {
            // ⚠️ 自定义目录可能就是 Download 这种"还装着别的东西"的位置，
            // 「清空全部」只清默认根，避免一键把用户自己的文件删掉
            if (!inDefaultRoots(t.file)) {
                skipped++;
                continue;
            }
            if (t.file.delete()) n++;
        }
        stopPreview();
        reload();
        Toast.makeText(this, "已删除 " + n + " 个文件"
                + (skipped > 0 ? "，跳过自定义目录里 " + skipped + " 个" : ""),
                Toast.LENGTH_LONG).show();
    }

    private void deleteOne(final LocalFiles.Track t) {
        new AlertDialog.Builder(this)
                .setTitle("删除这首歌？")
                .setMessage(t.title)
                .setPositiveButton("删除", (d, w) -> {
                    if (playing != null && playing.path().equals(t.path())) stopPreview();
                    if (t.file.delete()) {
                        Toast.makeText(this, "已删除：" + t.title, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "删除失败：" + t.title, Toast.LENGTH_SHORT).show();
                    }
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        try { unregisterReceiver(stopReceiver); } catch (Throwable ignore) {}
        super.onDestroy();
    }

    private class TrackAdapter extends ArrayAdapter<LocalFiles.Track> {
        TrackAdapter() { super(LocalMusicActivity.this, 0, tracks); }

        @Override
        public View getView(int pos, View cv, ViewGroup p) {
            if (cv == null) cv = getLayoutInflater().inflate(R.layout.item_local_music, p, false);
            final LocalFiles.Track t = tracks.get(pos);
            ((TextView) cv.findViewById(R.id.tv_title)).setText(t.title);
            ((TextView) cv.findViewById(R.id.tv_sub)).setText(fmt(t.file));

            TextView play = cv.findViewById(R.id.btn_play);
            boolean isThis = playing != null && playing.path().equals(t.path());
            play.setText(isThis && preview.isPlaying() ? "停止" : "播放");
            play.setOnClickListener(v -> togglePlay(pos));

            cv.findViewById(R.id.btn_del).setOnClickListener(v -> deleteOne(t));
            return cv;
        }
    }

    private static String fmt(File f) {
        long len = f.length();
        String size = len >= 1024 * 1024
                ? (len / 1024 / 1024) + " MB"
                : (len / 1024) + " KB";
        String ext = "";
        int dot = f.getName().lastIndexOf('.');
        if (dot > 0) ext = f.getName().substring(dot + 1).toUpperCase() + " · ";
        return ext + size;
    }
}
