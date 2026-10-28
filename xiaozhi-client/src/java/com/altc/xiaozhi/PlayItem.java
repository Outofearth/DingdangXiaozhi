package com.altc.xiaozhi;

/**
 * 可播放条目：本地文件 / 远程 SMB 共享 的统一抽象，播放队列不关心来源。
 * MediaPlayer 的 setDataSource(String) 对本地路径和已下载到本地的缓存路径都直接支持；
 * 远程条目从 P3 起还能直接给 MediaDataSource 边下边播。
 */
public interface PlayItem {
    /** 本地文件绝对路径，或远程条目的 smb:// 直链。 */
    String source();

    /** 显示 / 朗读用歌名。 */
    String displayTitle();

    /**
     * 是不是视频容器（mp4/mkv…）。
     * 注意：只代表「这个文件装得下画面」，真正出画面还是只出声要过 {@link MediaKind}。
     */
    default boolean isVideo() { return false; }
}
