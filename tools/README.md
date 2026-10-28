# tools

运行期辅助脚本。都是「在电脑上跑、通过 adb 操作设备」，不参与 APK 构建。

先把设备连上（USB 或无线）：

```bash
adb connect <设备IP>:5555
adb devices
```

下面脚本的默认设备地址是占位的 `192.168.1.100:5555`，请换成自己的，
或者用第二个参数 / 环境变量指定：

```bash
bash tools/vol.sh show 192.168.1.50:5555
SERIAL=192.168.1.50:5555 bash tools/push-music.sh ./歌单
```

---

## vol.sh — 音量

```bash
bash tools/vol.sh show          # 只看当前档位，不动音量
bash tools/vol.sh mute          # 静音（调试时必备，免得半夜突然开口）
bash tools/vol.sh restore       # 恢复到 index 1（约 7%）
bash tools/vol.sh 8             # 直接设到第 8 档（0~15）
bash tools/vol.sh +2 / -2       # 相对调节
```

设备音量是 **0–15 档**（不是百分比）。有些机器 `cmd media_session volume` 不可用，
脚本走的是 `media volume --stream 3`。

> 调试建议：动手前 `mute`，收尾 `restore`。另外设置页的「启动时自动应用音量」
> 会把设定值写回系统，静音要在 App 启动**之后**做，否则会被覆盖。

---

## cache.sh — 缓存

```bash
bash tools/cache.sh show        # 看三类缓存各占多少
bash tools/cache.sh parts       # 清下载残片（跳过 3 分钟内写过的，正在播的不会掉）
bash tools/cache.sh smb         # 清整包缓存（共享盘 smb_ + 网盘 ol_）
bash tools/cache.sh lib         # 清云端 PCM 曲库
bash tools/cache.sh exports     # 清导出的聊天记录 TXT
bash tools/cache.sh all         # parts + smb + lib
```

只按前缀删文件，不做 `rm -rf`，不碰目录本身。
设备的 shell 是 toybox，**没有 awk**，所以统计一律拉回电脑算。

---

## push-music.sh — 推送音乐

```bash
bash tools/push-music.sh ./我的歌单              # 整目录推过去
bash tools/push-music.sh ./a.mp3 ./b.flac        # 或指定文件
```

- 音频进 `/sdcard/Music/xiaozhi`，视频进 `/sdcard/Movies`
- 同名不同内容会自动改名成「歌名 (2).mp3」，两份都留着，不会互相覆盖
- 中文路径在 Git Bash 下要 `MSYS_NO_PATHCONV=1`，脚本内部已处理

---

## adb_wifi_connect.sh — 无线连接

```bash
bash tools/adb_wifi_connect.sh <设备IP>
```

免去每次插线。前提是设备已开 USB 调试并授权过本机。

---

## enable-smb-guest.bat — Windows 共享放行

**在 Windows 上以管理员身份运行**，用来让局域网设备匿名读你的共享目录：

```bat
enable-smb-guest.bat "D:\Music\mp3"
```

它做三件事：允许 SMB 匿名/guest 访问、把 Guests 组从「拒绝从网络访问」里移除、
给共享目录网络读权限。只影响本机 SMB 共享，不改其他安全设置。

用完之后在设备设置页填：地址（电脑 IP）、共享名（如 `mp3`）、账号密码留空。

---

## 排障

设备上的运行日志（这台机器的固件屏蔽了 `Log.d`，只能落盘）：

```bash
adb shell cat /sdcard/Android/data/com.altc.xiaozhi/files/status.txt | tail -50
```

清空它：

```bash
adb shell "> /sdcard/Android/data/com.altc.xiaozhi/files/status.txt"
```
