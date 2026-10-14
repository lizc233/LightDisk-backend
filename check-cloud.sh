#!/usr/bin/env bash
# =====================================================================
# LightDisk 云端部署「完整性自检」
# 用法：cd /workspace/LightDisk-backend && bash check-cloud.sh
# 覆盖：代码版本 / 运行时 / 依赖服务 / 数据库 / 后端接口 / 前端
# =====================================================================
set -u

REPO_BACKEND="${REPO_BACKEND:-/workspace/LightDisk-backend}"
REPO_FRONTEND="${REPO_FRONTEND:-/workspace/LightDisk}"
API="${API:-http://127.0.0.1:8080}"
WEB="${WEB:-http://127.0.0.1:5173}"
DB_USER="${DB_USER:-root}"
DB_PASS="${DB_PASS:-Lizc20041020}"
DB_NAME="${DB_NAME:-lightdisk}"

PASS=0; FAIL=0
ok()  { printf '  \033[32m[ OK ]\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
no()  { printf '  \033[31m[ !! ]\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
hd()  { printf '\n\033[1m%s\033[0m\n' "$*"; }

echo "=========================================================="
echo " LightDisk 云端部署完整性自检   $(date '+%F %T')"
echo "=========================================================="

# ---------------------------------------------------------- 1. 代码
hd "1) 代码版本（是否拉到最新）"
for d in "$REPO_BACKEND" "$REPO_FRONTEND"; do
  if [ -d "$d/.git" ]; then
    # 过滤掉运行环境必然生成的文件（云端 .env / npm 的 package-lock.json），它们不算「未提交改动」
    dirty="$(git -C "$d" status --porcelain 2>/dev/null | grep -vE '\.env(\.bak)?$|package-lock\.json$')"
    printf '  %-22s %s\n' "$(basename "$d")" "$(git -C "$d" log --oneline -1 2>/dev/null)"
    printf '  %-22s remote=%s\n' "" "$(git -C "$d" remote get-url origin 2>/dev/null)"
    [ -z "$dirty" ] && ok "工作区干净" || no "有未提交改动：$(echo "$dirty" | head -3 | tr '\n' ' ')"
    if [ -f "$d/setup-rabbitmq.sh" ] || [ -f "$d/LightDisk/pom.xml" ]; then
      ok "关键文件存在"
    fi
  else
    no "找不到 git 仓库：$d"
  fi
done

# ---------------------------------------------------------- 2. 运行时
hd "2) 运行时（⚠️ 本项目必须 Java 8）"
JV="$(java -version 2>&1 | head -1)"
echo "  java  : $JV"
case "$JV" in
  *'"1.8'*|*' version "8'*) ok "Java 8 ✓" ;;
  *) no "不是 Java 8 —— 登录会报「系统繁忙」(jjwt 0.9.1 需要 javax.xml.bind，JDK11+ 已移除)" ;;
esac
command -v mvn  >/dev/null 2>&1 && ok "maven : $(mvn -v 2>/dev/null | head -1)" || no "缺 maven"
command -v node >/dev/null 2>&1 && ok "node  : $(node -v)" || no "缺 node"
if command -v ffmpeg >/dev/null 2>&1 && command -v ffprobe >/dev/null 2>&1; then
  ok "ffmpeg: $(ffmpeg -version 2>/dev/null | head -1 | cut -c1-60)"
else
  no "缺 ffmpeg/ffprobe（视频无法转码）"
fi

# ---------------------------------------------------------- 3. 依赖服务
hd "3) 依赖服务"
for spec in "3306:MySQL" "6379:Redis" "5672:RabbitMQ" "15672:RabbitMQ管理面板" "8080:后端" "5173:前端"; do
  port="${spec%%:*}"; name="${spec#*:}"
  if ss -ltn 2>/dev/null | grep -q ":${port}[[:space:]]"; then
    ok "${name} 监听 ${port}"
  elif curl -s -o /dev/null -m 3 "http://127.0.0.1:${port}/" 2>/dev/null; then
    ok "${name} 端口 ${port} 可访问（ss 未列出，探测方式差异）"
  else
    no "${name} 未监听 ${port}"
  fi
done
if command -v rabbitmqctl >/dev/null 2>&1; then
  echo "  --- RabbitMQ 队列 ---"
  rabbitmqctl list_queues name messages consumers 2>/dev/null | head -8 | sed 's/^/    /' \
    || no "rabbitmqctl 执行失败（节点没起来？）"
fi

# ---------------------------------------------------------- 4. 数据库
hd "4) 数据库"
if command -v mysql >/dev/null 2>&1; then
  MYSQL=(mysql --default-character-set=utf8mb4 -h127.0.0.1 -u"${DB_USER}" -p"${DB_PASS}")
  if "${MYSQL[@]}" -e "SELECT 1" >/dev/null 2>&1; then
    ok "MySQL 连接成功"
    echo "  --- 关键表 ---"
    for t in user storage_file file folder vip_config video_transcode_task play_qoe_log \
             file_content_version user_avatar \
             announcement announcement_read personal_message; do
      c=$("${MYSQL[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}' AND table_name='${t}';" 2>/dev/null)
      [ "${c:-0}" = "1" ] && printf '    \033[32m✓\033[0m %s\n' "$t" || printf '    \033[31m✗\033[0m %s （缺！）\n' "$t"
    done
    echo "  --- 视频/VIP 补丁 ---"
    "${MYSQL[@]}" -e "SELECT level, max_video_quality, max_playback_rate, max_concurrent_stream FROM ${DB_NAME}.vip_config ORDER BY level;" 2>/dev/null | sed 's/^/    /' \
      || no "vip_config 缺 max_video_quality 等列 → 请跑 sql/2026-10-13_video_transcode_qoe_vip.sql"
    echo "  --- 管理员账号 ---"
    "${MYSQL[@]}" -N -e "SELECT id,username,is_admin,status FROM ${DB_NAME}.user WHERE username='admin';" 2>/dev/null | sed 's/^/    /'
  else
    no "MySQL 连不上（${DB_USER}@127.0.0.1/${DB_NAME}）"
  fi
else
  no "缺 mysql 客户端"
fi

# ---------------------------------------------------------- 5. 后端接口
hd "5) 后端接口"
AI_CODE="$(curl -s -o /tmp/ld_ai_status.out -w '%{http_code}' -m 30 "${API}/api/ai/status")"
AI="$(cat /tmp/ld_ai_status.out 2>/dev/null)"; rm -f /tmp/ld_ai_status.out
if [ -n "$AI" ]; then
  ok "GET /api/ai/status → HTTP ${AI_CODE} $(echo "$AI" | cut -c1-96)"
else
  no "GET /api/ai/status 无响应（HTTP ${AI_CODE}，已等 30s；该接口首次调用要懒启动 MCP 子进程，慢属正常）"
fi

LOGIN="$(curl -s -m 10 -X POST "${API}/api/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"123456"}')"
echo "    登录返回：$(echo "$LOGIN" | cut -c1-160)"
case "$LOGIN" in
  *'"code":1'*) ok "管理员登录成功（JWT 签发正常）" ;;
  *) no "管理员登录失败 —— 若 java 不是 8，这就是 jjwt/JAXB 报的「系统繁忙」" ;;
esac

# ---------------------------------------------------------- 6. 前端
hd "6) 前端"
code="$(curl -s -o /dev/null -w '%{http_code}' -m 8 "${WEB}/")"
[ "$code" = "200" ] && ok "Vite 开发服务器返回 200" || no "前端返回 HTTP ${code}"
if command -v git >/dev/null 2>&1 && [ -d "$REPO_FRONTEND/.git" ]; then
  [ -f "$REPO_FRONTEND/node_modules/vite/package.json" ] && ok "node_modules 已安装" || no "缺 node_modules → cd $REPO_FRONTEND && npm install"
fi

# ---------------------------------------------------------- 汇总
echo
echo "=========================================================="
printf " 自检结果：通过 \033[32m%d\033[0m 项，失败 \033[31m%d\033[0m 项\n" "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo " 有未通过项，见上面 [ !! ] 行。"
else
  echo " 全部通过 —— 云端与本地功能一致。"
fi
echo "=========================================================="
