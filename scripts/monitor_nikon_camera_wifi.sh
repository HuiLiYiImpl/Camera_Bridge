#!/bin/zsh
set -euo pipefail

HOST="192.168.1.1"
PORT="15740"
DURATION_SECONDS="45"
ENABLE_PING="0"
PROBE_PORT_ONCE="0"

usage() {
  cat <<'EOF'
用法:
  ./scripts/monitor_nikon_camera_wifi.sh [--host 192.168.1.1] [--port 15740] [--duration 45] [--ping] [--probe-port-once]

默认行为:
  - 只做被动监控
  - 不主动探测 15740 端口
  - 不主动 ping 相机

可选参数:
  --ping               每秒附带一次 ping 检查
  --probe-port-once    只在开始时做一次 15740 端口探测
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)
      HOST="$2"
      shift 2
      ;;
    --port)
      PORT="$2"
      shift 2
      ;;
    --duration)
      DURATION_SECONDS="$2"
      shift 2
      ;;
    --ping)
      ENABLE_PING="1"
      shift
      ;;
    --probe-port-once)
      PROBE_PORT_ONCE="1"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $1"
      echo
      usage
      exit 1
      ;;
  esac
done

find_wifi_interface() {
  networksetup -listallhardwareports |
    awk '
      $0 ~ /^Hardware Port: Wi-Fi$/ { found=1; next }
      found && $1 == "Device:" { print $2; exit }
    '
}

read_ssid() {
  local iface="$1"
  local raw
  raw="$(networksetup -getairportnetwork "${iface}" 2>/dev/null || true)"
  raw="${raw#Current Wi-Fi Network: }"
  echo "${raw}"
}

probe_port_once() {
  local host="$1"
  local port="$2"
  if nc -G 1 -z "${host}" "${port}" >/dev/null 2>&1; then
    echo "OPEN"
  else
    echo "CLOSED"
  fi
}

WIFI_IFACE="$(find_wifi_interface)"
if [[ -z "${WIFI_IFACE}" ]]; then
  echo "未找到 Wi‑Fi 网卡接口。"
  exit 1
fi

echo "Wi‑Fi 接口: ${WIFI_IFACE}"
echo "目标地址: ${HOST}:${PORT}"
echo "监控时长: ${DURATION_SECONDS}s"
echo "附带 ping: $([[ "${ENABLE_PING}" == "1" ]] && echo 是 || echo 否)"
echo "启动时探测端口: $([[ "${PROBE_PORT_ONCE}" == "1" ]] && echo 是 || echo 否)"
echo

if [[ "${PROBE_PORT_ONCE}" == "1" ]]; then
  echo "启动时端口探测结果: $(probe_port_once "${HOST}" "${PORT}")"
  echo
fi

for ((i = 1; i <= DURATION_SECONDS; i++)); do
  timestamp="$(date '+%H:%M:%S')"
  ssid="$(read_ssid "${WIFI_IFACE}")"
  ip_addr="$(ipconfig getifaddr "${WIFI_IFACE}" 2>/dev/null || true)"
  route_iface="$(route -n get "${HOST}" 2>/dev/null | awk '/interface:/{print $2; exit}')"
  route_gateway="$(route -n get "${HOST}" 2>/dev/null | awk '/gateway:/{print $2; exit}')"

  if [[ "${ENABLE_PING}" == "1" ]]; then
    if ping -c 1 -W 1000 "${HOST}" >/dev/null 2>&1; then
      ping_status="OK"
    else
      ping_status="FAIL"
    fi
  else
    ping_status="SKIP"
  fi

  echo "[${timestamp}] SSID=${ssid:-<unknown>} IP=${ip_addr:-<none>} route_if=${route_iface:-<none>} gateway=${route_gateway:-<none>} ping=${ping_status}"
  sleep 1
done

echo
echo "判断方式："
echo "1. 如果 IP 从 192.168.1.x 变成 <none>，说明 Mac 自己从相机热点掉线了。"
echo "2. 如果最后又回到 192.168.2.x 或别的家庭网段，说明 macOS 自动回连了原来的 Wi‑Fi。"
echo "3. 如果被动监控都稳定，再单独加 --probe-port-once 看 15740 是不是打开。"
echo "4. 如果一加端口探测就掉线，说明 Nikon 这边对短连接探测很敏感，后续不要用轮询探测。"
