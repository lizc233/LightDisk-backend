#!/usr/bin/env bash
# =====================================================================
# LightDisk 云端「视频转码链路」一键补齐
#   1) FFmpeg / FFprobe
#   2) RabbitMQ（含 admin 账号 + 15672 管理面板）
#   3) 数据库补丁（video_transcode_task / play_qoe_log / vip_config 三列 等）
#
# 适用：Cloud Studio 容器（root 身份，apt 可用）
# 用法：cd /workspace/LightDisk-backend && bash setup-rabbitmq.sh
#
# 可用环境变量覆盖：RMQ_USER RMQ_PASS RMQ_PORT MGMT_PORT
#                  DB_HOST DB_PORT DB_USER DB_PASS DB_NAME
# =====================================================================
set -u

RMQ_USER="${RMQ_USER:-admin}"
RMQ_PASS="${RMQ_PASS:-123456}"
RMQ_PORT="${RMQ_PORT:-5672}"
MGMT_PORT="${MGMT_PORT:-15672}"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-Lizc20041020}"
DB_NAME="${DB_NAME:-lightdisk}"

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
SQL_DIR="$REPO_DIR/LightDisk/src/main/resources/sql"

log() { echo "[*] $*"; }
die() { echo "[x] $*" >&2; exit 1; }

echo "=========================================================="
echo " LightDisk 云端视频链路依赖安装"
echo "=========================================================="
. /etc/os-release 2>/dev/null && echo "系统   : $PRETTY_NAME"
echo "主机名 : $(hostname)"
echo "仓库   : $REPO_DIR"

# ---------------------------------------------------------------
# 0. RabbitMQ 要求 hostname 能被解析，容器里经常缺失
# ---------------------------------------------------------------
if ! grep -qE "^127\.0\.0\.1[[:space:]]+$(hostname)([[:space:]]|$)" /etc/hosts 2>/dev/null; then
  echo "127.0.0.1 $(hostname)" >> /etc/hosts
  log "/etc/hosts 已补一行: 127.0.0.1 $(hostname)"
fi

export DEBIAN_FRONTEND=noninteractive
APT_UPDATE_DONE=0
apt_update_once() {
  [ "$APT_UPDATE_DONE" = "1" ] && return 0
  apt-get update -qq || true
  APT_UPDATE_DONE=1
}

# ---------------------------------------------------------------
# 1. FFmpeg
# ---------------------------------------------------------------
echo
if command -v ffmpeg >/dev/null 2>&1 && command -v ffprobe >/dev/null 2>&1; then
  log "FFmpeg 已存在: $(command -v ffmpeg)"
else
  log "安装 FFmpeg ..."
  apt_update_once
  apt-get install -y -qq ffmpeg || die "ffmpeg 安装失败"
  log "FFmpeg 就绪: $(command -v ffmpeg)"
fi

# ---------------------------------------------------------------
# 2. RabbitMQ
# ---------------------------------------------------------------
echo
if ss -ltn 2>/dev/null | grep -q ":${RMQ_PORT} "; then
  log "RabbitMQ 已监听 ${RMQ_PORT}，跳过安装"
else
  if command -v rabbitmq-server >/dev/null 2>&1; then
    log "rabbitmq-server 已安装，直接启动"
  else
    log "安装 rabbitmq-server（会一并安装 Erlang，约 1~3 分钟）..."
    apt_update_once
    apt-get install -y -qq rabbitmq-server || die "rabbitmq-server 安装失败"
  fi
  log "启动 RabbitMQ ..."
  (systemctl start rabbitmq-server 2>/dev/null || service rabbitmq-server start 2>/dev/null) || true
fi

log "等待 ${RMQ_PORT} 端口就绪 ..."
for _ in $(seq 1 40); do
  ss -ltn 2>/dev/null | grep -q ":${RMQ_PORT} " && break
  sleep 2
done
if ! ss -ltn 2>/dev/null | grep -q ":${RMQ_PORT} "; then
  echo "[!] RabbitMQ 尚未监听 ${RMQ_PORT}，最近日志："
  tail -20 /var/log/rabbitmq/*.log 2>/dev/null || true
  die "RabbitMQ 启动失败"
fi

# ---------------------------------------------------------------
# 3. 账号 + 管理面板
# ---------------------------------------------------------------
echo
if command -v rabbitmqctl >/dev/null 2>&1; then
  rabbitmqctl add_user "${RMQ_USER}" "${RMQ_PASS}" >/dev/null 2>&1 || true
  rabbitmqctl set_user_tags "${RMQ_USER}" administrator >/dev/null 2>&1 || true
  rabbitmqctl set_permissions -p / "${RMQ_USER}" ".*" ".*" ".*" >/dev/null 2>&1 || true
  rabbitmq-plugins enable rabbitmq_management >/dev/null 2>&1 || true
fi
log "账号 ${RMQ_USER} / ${RMQ_PASS} 已就绪；管理面板 http://<云端域名>:${MGMT_PORT}"

# ---------------------------------------------------------------
# 4. 数据库补丁
# ---------------------------------------------------------------
echo
if command -v mysql >/dev/null 2>&1; then
  MYSQL=(mysql --default-character-set=utf8mb4 -h"${DB_HOST}" -P"${DB_PORT}" -u"${DB_USER}" -p"${DB_PASS}")
  if "${MYSQL[@]}" -e "SELECT 1" >/dev/null 2>&1; then
    log "数据库连通，导入 SQL 补丁（重复执行报的 Duplicate 属正常，已用 --force 忽略）"
    for f in "$SQL_DIR"/*.sql; do
      [ -f "$f" ] || continue
      printf '    - %s\n' "$(basename "$f")"
      "${MYSQL[@]}" --force "${DB_NAME}" < "$f" 2>/dev/null || true
    done
    echo
    log "关键表检查："
    "${MYSQL[@]}" -N -e "SHOW TABLES FROM ${DB_NAME} LIKE '%transcode%'; SHOW TABLES FROM ${DB_NAME} LIKE '%qoe%';" 2>/dev/null | sed 's/^/    /'
    log "VIP 权限矩阵："
    "${MYSQL[@]}" -e "SELECT level, max_video_quality, max_playback_rate, max_concurrent_stream FROM ${DB_NAME}.vip_config ORDER BY level;" 2>/dev/null | sed 's/^/    /'
  else
    echo "[!] MySQL 连不上（${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME}），已跳过 SQL 导入。"
    echo "    请手动执行：mysql --default-character-set=utf8mb4 -uroot -p${DB_PASS} ${DB_NAME} < ${SQL_DIR}/2026-10-13_video_transcode_qoe_vip.sql"
  fi
else
  echo "[!] 未找到 mysql 客户端，已跳过 SQL 导入"
fi

# ---------------------------------------------------------------
# 5. 收尾提示
# ---------------------------------------------------------------
echo
echo "=========================================================="
echo " 依赖安装完成。下一步执行："
echo
echo "   cd /workspace/LightDisk-backend/LightDisk"
echo "   mvn -q -DskipTests clean package"
echo "   bash /workspace/stop-lightdisk.sh; sleep 2; bash /workspace/start-lightdisk.sh"
echo "   sleep 35; tail -30 /workspace/.lightdisk-run/backend.log"
echo "=========================================================="
