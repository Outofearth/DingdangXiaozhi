#!/usr/bin/env bash
# adb_wifi_connect.sh
# 一键将 USB 连接的 Android 设备切换到 WiFi ADB 并连接。
# 用法: ./adb_wifi_connect.sh [端口]   (默认端口 5555)
set -euo pipefail

PORT="${1:-5555}"

echo "==> 查找已连接的设备..."
DEVICE=""
while IFS= read -r line; do
  # 跳过标题行，取状态为 device 的第一个序列号
  if [[ "$line" == *"device"* && "$line" != *"List of"* && "$line" != *"offline"* ]]; then
    DEVICE="$(echo "$line" | awk '{print $1}')"
    break
  fi
done < <(adb devices)

if [[ -z "$DEVICE" ]]; then
  echo "错误: 未找到已连接的 ADB 设备，请先通过 USB 连接并开启调试。" >&2
  exit 1
fi
echo "设备序列号: $DEVICE"

echo "==> 查询 WiFi IP (wlan0)..."
# 在设备上执行 ip 命令，输出回到本地用 grep/awk 解析，避开设备端 \$ 转义问题
IP_RAW="$(adb -s "$DEVICE" shell "ip addr show wlan0 2>/dev/null" | grep -oE 'inet [0-9.]+' | head -1 | awk '{print $2}')"
IP="$(echo "$IP_RAW" | tr -d '\r')"
if [[ -z "$IP" ]]; then
  echo "错误: 无法获取 WiFi IP，请确认设备已连接 WiFi。" >&2
  exit 1
fi
echo "WiFi IP: $IP"

echo "==> 切换设备到 TCP 模式 (端口 $PORT)..."
adb -s "$DEVICE" tcpip "$PORT"
sleep 2

echo "==> 通过 WiFi 连接 ${IP}:${PORT}..."
adb connect "${IP}:${PORT}"

echo "==> 当前设备列表:"
adb devices -l
echo ""
echo "完成。后续命令可指定设备: adb -s ${IP}:${PORT} shell ..."
