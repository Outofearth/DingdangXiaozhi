# xiaozhi-client（源码）

智能音箱版客户端的完整源码。项目介绍、功能与安装见[根目录 README](../README.md)。

## 构建

```bash
export ANDROID_SDK=/path/to/android-sdk
bash build.sh speaker     # 横屏音箱版
bash build.sh mobile      # 自适应手机/平板版
```

不用 Gradle，`build.sh` 手工串起 aapt2 → javac → d8 → zipalign → apksigner。
细节与依赖清单见 [docs/构建与适配.md](../docs/构建与适配.md)。

## 源码地图

```
src/java/com/altc/xiaozhi/
├── 会话
│   ├── XiaoZhiClient       WebSocket、握手、收发帧
│   ├── OtaClient           OTA 注册 / 验证码绑定
│   ├── AudioCapture        AudioRecord → Opus 上行
│   ├── AudioPlayer         Opus → AudioTrack 下行
│   ├── OpusCodec           JNI 桥
│   └── McpHandler          MCP 工具（清单 / 播放 / 音量 / 拍照）
├── 界面
│   ├── MainActivity        主界面 + 语音意图分发 + 清单编排
│   ├── VideoActivity       视频页（SurfaceView + 字幕层）
│   ├── SettingsActivity    设置
│   ├── ChatManagerActivity 聊天记录管理
│   ├── LocalMusicActivity  本机音乐列表
│   └── AppearanceActivity  外观
├── 媒体
│   ├── MediaSurvey         四源清单、去重、版式、兜底断行
│   ├── LocalFiles          本机扫描 + 打分式名字匹配 + 繁简表
│   ├── RemoteLibrary       SMB 扫描 / 缓存 / 打开数据源
│   ├── SmbStreamSource     SMB 边下边播
│   ├── NetDiskLibrary      网盘网关（OpenList）客户端 + 全量索引
│   ├── OlStreamSource      网盘 HTTP 边下边播
│   ├── FileMusicPlayer     播放器封装
│   ├── NowPlayingBar       「正在播放」卡片
│   ├── PlayItem / MediaKind / MediaFormats / PlayMode
│   └── MusicLibrary / MusicSession / NotifyPlayer
├── 基础设施
│   ├── VolumeCtl           音量的唯一出口
│   ├── StorageCtl          缓存统计与清理
│   ├── ChatStore / ChatExport
│   ├── SyncManager / SyncClient / SyncHttpServer   局域网 P2P 同步
│   ├── PresenceService     局域网发现
│   ├── FloatEntryService   待机悬浮入口
│   ├── PageScrollView      可锁定的外层滚动容器（嵌套滚动用）
│   ├── App / BootReceiver / AppConfig / PairCode
│   └── DeviceIdentity      【已停用】v2 序列号方案，仅留档
└── CameraCapture / MicState
```

`src/jni/opus_jni.c` 是 libopus 的最小 JNI 封装（编码 / 解码 / 申请释放）。

## 注意

- `AppConfig.java` 由 `build.sh` 生成，不要手改。
- `prebuilt/` 里的 `.so` 已编好，正常构建不用碰 NDK。
- 仓库不含 `opus-1.3.1/` 源码（4.9 MB）；需要重编 `.so` 时自行下载，见构建文档。
