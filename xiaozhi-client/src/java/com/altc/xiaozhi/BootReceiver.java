package com.altc.xiaozhi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 开机自启悬浮入口：重启后若用户开着「待机悬浮入口」，自动把小智按钮浮出来。
 * 真机进程被杀后也能在开机时恢复（前提是系统发了 BOOT_COMPLETED）。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SharedPreferences sp = c.getSharedPreferences("xz", Context.MODE_PRIVATE);
        if (sp.getBoolean("floatEntry", true)) {
            c.startService(new Intent(c, FloatEntryService.class));
        }
    }
}
