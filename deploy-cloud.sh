#!/usr/bin/env bash
# =============================================================================
# LightDisk 云端一键部署脚本（Cloud Studio / Ubuntu 通用）
#
# 用法（在云端工作区终端里执行）：
#   bash deploy-cloud.sh            # 全流程：环境 → 数据库 → 配置 → 后端 → 前端
#   bash deploy-cloud.sh env        # 只装/检查环境依赖（Java8/Maven/Node/ffmpeg/Redis/RabbitMQ/MySQL）
#   bash deploy-cloud.sh db         # 只建库 + 导入 src/main/resources/sql/*.sql
#   bash deploy-cloud.sh config     # 只生成 application-dev.yml 模板（存在则不动）
#   bash deploy-cloud.sh backend    # 只打包 + 启动后端
#   bash deploy-cloud.sh frontend   # 只装依赖 + 启动前端
#   bash deploy-cloud.sh check      # 只做健康检查
#
# 目录约定：
#   <工作区>/LightDisk/           ← 本脚本所在目录（后端 git 根）
#   <工作区>/LightDisk/LightDisk/ ← Maven 工程根（pom.xml）
#   <工作区>/front_lightdisk/     ← 前端仓库（可用 FRONT_DIR 覆盖）
# =============================================================================
set -uo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MVN_DIR="$REPO_DIR/LightDisk"
MCP_DIR="$REPO_DIR/lightdisk-mcp-server"
FRONT_DIR="${FRONT_DIR:-$(cd "$REPO_DIR/.." && pwd)/front_lightdisk}"
DATA_DIR="${LIGHTDISK_DATA:-$HOME/lightdisk_data}"
UPLOAD_DIR="$DATA_DIR/uploads"
BACK_LOG="$DATA_DIR/backend.log"
FRONT_LOG="$DATA_DIR/frontend.log"
DEV_YML="$MVN_DIR/src/main/resources/application-dev.yml"

if [ "$(id -u)" = "0" ]; then SUDO=""; else SUDO="sudo"; fi

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✓ %s\033[0m\n' "$1"; }
warn() { printf '  \033[33m! %s\033[0m\n' "$1"; }
die()  { printf '\n\033[31m✗ %s\033[0m\n' "$1"; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------- 环境依赖 ---
need_java8() {
  if have java; then
    local v; v="$(java -version 2>&1 | head -1)"
    case "$v" in
      *'"1.8'*|*'"8.'*) ok "Java 8 已就绪：$v"; return 0 ;;
      *) warn "当前 Java 不是 8（$v），后端必须用 Java 8，尝试安装…" ;;
    esac
  fi
  step "安装 OpenJDK 8"
  $SUDO apt-get update -qq
  if $SUDO apt-get install -y openjdk-8-jdk; then
    ok "openjdk-8-jdk 安装完成"
  else
    warn "apt 没有 openjdk-8（Ubuntu 22.04+ 常见），改用 Temurin 8 免安装包"
    local dir="/opt/jdk8"
    $SUDO mkdir -p "$dir"
    local url="https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u422-b05/OpenJDK8U-jdk_x64_linux_hotspot_8u422b05.tar.gz"
    curl -fsSL "$url" -o /tmp/jdk8.tgz || die "下载 JDK8 失败，请手动安装 Java 8 后重跑"
    $SUDO tar -xzf /tmp/jdk8.tgz -C "$dir" --strip-components=1
    export JAVA_HOME="$dir"
    echo "export JAVA_HOME=$dir" >> "$HOME/.bashrc"
    echo "export PATH=\$JAVA_HOME/bin:\$PATH" >> "$HOME/.bashrc"
    ok "Temurin 8 已装到 $dir（已写入 ~/.bashrc）"
  fi
}

need_maven() {
  if have mvn; then ok "Maven 已就绪：$(mvn -v 2>/dev/null | head -1)"; else
    step "安装 Maven"; $SUDO apt-get install -y maven && ok "Maven 安装完成"
  fi
  # 国内加速：配置阿里云镜像（已存在则不覆盖）
  if [ ! -f "$HOME/.m2/settings.xml" ]; then
    mkdir -p "$HOME/.m2"
    cat > "$HOME/.m2/settings.xml" <<'XML'
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id><name>aliyun</name>
      <url>https://maven.aliyun.com/repository/public</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
XML
    ok "已写入 ~/.m2/settings.xml（阿里云镜像加速）"
  fi
}

need_node() {
  if have node; then ok "Node 已就绪：$(node -v)"; else
    step "安装 Node.js"
    curl -fsSL https://deb.nodesource.com/setup_18.x | $SUDO -E bash - \
      && $SUDO apt-get install -y nodejs && ok "Node 安装完成" \
      || { $SUDO apt-get install -y nodejs npm && ok "nodejs/npm 安装完成（apt 源）"; }
  fi
  npm config set registry https://registry.npmmirror.com >/dev/null 2>&1 && ok "npm 已切到 npmmirror 源"
}

need_ffmpeg() {
  if have ffmpeg; then ok "ffmpeg 已就绪：$(ffmpeg -version 2>/dev/null | head -1)"; else
    step "安装 ffmpeg（视频转码 HLS 必需）"
    $SUDO apt-get install -y ffmpeg && ok "ffmpeg 安装完成"
  fi
}

need_redis() {
  if redis-cli -h 127.0.0.1 ping 2>/dev/null | grep -q PONG; then ok "Redis 已在运行"; else
    step "启动 Redis"
    if have redis-server; then
      $SUDO redis-server --daemonize yes && ok "redis-server 已后台启动"
    elif have docker; then
      docker run -d --name lightdisk-redis --restart unless-stopped -p 6379:6379 redis:7 >/dev/null \
        && ok "Redis 容器已启动"
    else
      $SUDO apt-get install -y redis-server && $SUDO redis-server --daemonize yes && ok "Redis 安装并启动"
    fi
    sleep 1; redis-cli ping 2>/dev/null | grep -q PONG && ok "Redis PING 正常" || warn "Redis 未响应，请检查"
  fi
}

need_rabbitmq() {
  if curl -fsS -u admin:123456 http://127.0.0.1:15672/api/overview >/dev/null 2>&1; then
    ok "RabbitMQ 已就绪（admin/123456）"; return 0
  fi
  step "部署 RabbitMQ（视频转码 / 分享通知 / 邮件队列依赖它）"
  if have docker; then
    docker rm -f rabbitmq >/dev/null 2>&1
    docker run -d --name rabbitmq --restart unless-stopped \
      -p 5672:5672 -p 15672:15672 \
      -e RABBITMQ_DEFAULT_USER=admin -e RABBITMQ_DEFAULT_PASS=123456 \
      rabbitmq:3.13-management >/dev/null && ok "RabbitMQ 容器已启动（admin/123456，面板 :15672）"
  else
    warn "没有 docker，改用 apt 安装"
    $SUDO apt-get install -y rabbitmq-server
    # systemd 容器里常不可用，兜底用服务脚本
    $SUDO systemctl start rabbitmq-server 2>/dev/null || $SUDO service rabbitmq-server start
    sleep 5
    $SUDO rabbitmq-plugins enable rabbitmq_management >/dev/null 2>&1
    $SUDO rabbitmqctl add_user admin 123456 2>/dev/null || $SUDO rabbitmqctl change_password admin 123456
    $SUDO rabbitmqctl set_user_tags admin administrator
    $SUDO rabbitmqctl set_permissions -p / admin ".*" ".*" ".*"
    ok "RabbitMQ 已安装并创建 admin/123456"
  fi
  warn "等 15 秒让 MQ 完成启动…"; sleep 15
  curl -fsS -u admin:123456 http://127.0.0.1:15672/api/overview >/dev/null 2>&1 \
    && ok "RabbitMQ 就绪" || warn "RabbitMQ 面板还没通，后端启动后看日志确认（不影响 Web 主体功能）"
}

need_mysql() {
  if have mysql; then ok "mysql 客户端已就绪"; else
    step "安装 mysql 客户端"
    $SUDO apt-get install -y default-mysql-client || $SUDO apt-get install -y mysql-client
  fi
  if mysqladmin -h 127.0.0.1 -uroot ping >/dev/null 2>&1 || $SUDO mysqladmin ping >/dev/null 2>&1; then
    ok "MySQL 服务在运行"
  else
    warn "MySQL 服务未运行，尝试启动"
    $SUDO systemctl start mysql 2>/dev/null || $SUDO service mysql start 2>/dev/null || warn "请手动启动 MySQL（或安装 mysql-server）"
  fi
}

# ---------------------------------------------------------------- 数据库 ---
# 从云端 application-dev.yml 读取连接信息（该文件不在 git 里，由你在云端维护）
db_import() {
  [ -f "$DEV_YML" ] || die "找不到 $DEV_YML，先执行：bash deploy-cloud.sh config 生成模板并填写"
  local host port user pass db
  host="$(awk '/^star:/{f=1} f&&/host:/{print $2; exit}' "$DEV_YML")"; host="${host:-127.0.0.1}"
  port="$(awk '/^star:/{f=1} f&&/port:/{print $2; exit}' "$DEV_YML")"; port="${port:-3306}"
  user="$(awk '/^star:/{f=1} f&&/username:/{print $2; exit}' "$DEV_YML")"; user="${user:-root}"
  pass="$(awk '/^star:/{f=1} f&&/password:/{print $2; exit}' "$DEV_YML")"
  db="$(awk '/^star:/{f=1} f&&/database:/{print $2; exit}' "$DEV_YML")"; db="${db:-lightdisk}"

  step "建库并导入 SQL 补丁（$user@$host:$port/$db）"
  local MYSQL=(mysql --default-character-set=utf8mb4 -h"$host" -P"$port" -u"$user")
  [ -n "${pass:-}" ] && MYSQL+=(-p"$pass")
  "${MYSQL[@]}" -e "CREATE DATABASE IF NOT EXISTS \`$db\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;" \
    || die "连不上 MySQL，检查 application-dev.yml 里的 host/port/username/password"

  local f
  for f in "$MVN_DIR"/src/main/resources/sql/*.sql; do
    printf '  → %s\n' "$(basename "$f")"
    # --force：脚本幂等，重复字段等报错直接跳过（如 ERROR 1060 Duplicate column）
    "${MYSQL[@]}" --force "$db" < "$f" 2>&1 | grep -vE "Duplicate column name|Duplicate key name|already exists" | head -5
  done
  ok "SQL 补丁执行完毕"
  "${MYSQL[@]}" "$db" -e "SELECT id,username,is_admin,status FROM user WHERE username='admin';" 2>/dev/null \
    | tee "$DATA_DIR/db-check.txt"
  ok "管理员账号核对结果已存 $DATA_DIR/db-check.txt（应有 is_admin=1）"
}

# ---------------------------------------------------------------- 配置 ---
gen_config() {
  mkdir -p "$MVN_DIR/src/main/resources" "$DATA_DIR"
  if [ -f "$DEV_YML" ]; then
    ok "application-dev.yml 已存在，跳过（如需改配置请自行编辑）"
    return 0
  fi
  step "生成云端 application-dev.yml 模板"
  cat > "$DEV_YML" <<YML
star:
  datasource:
    host: 127.0.0.1
    port: 3306
    database: lightdisk
    username: root
    password: 改成你的MySQL密码
  redis:
    host: 127.0.0.1
    port: 6379
  rabbitmq:
    host: 127.0.0.1
    port: 5672
    username: admin
    password: 123456

lightdisk:
  ai:
    enabled: true
    api-key: 改成你的DeepSeekKey
YML
  warn "已生成 $DEV_YML —— 请填好 MySQL 密码（和 DeepSeek Key）后重新执行本脚本！"
  exit 0
}

# ---------------------------------------------------------------- 后端 ---
build_backend() {
  step "打包后端（Java 8）"
  ( cd "$MVN_DIR" && mvn -q -DskipTests clean package ) || die "Maven 打包失败，看上面报错"
  local jar; jar="$(ls -t "$MVN_DIR"/target/*.jar 2>/dev/null | grep -v sources | head -1)"
  [ -n "$jar" ] || die "没找到 target/*.jar"
  ok "产物：$jar"
  echo "$jar" > "$DATA_DIR/jar.path"
}

start_backend() {
  local jar; jar="$(cat "$DATA_DIR/jar.path" 2>/dev/null)"
  [ -n "$jar" ] && [ -f "$jar" ] || { build_backend; jar="$(cat "$DATA_DIR/jar.path")"; }
  mkdir -p "$UPLOAD_DIR" "$UPLOAD_DIR/.hls" "$UPLOAD_DIR/.thumbnails"

  step "停止旧后端（如果有）"
  pkill -f "lightweight-cloud-disk" 2>/dev/null && ok "已停旧进程" || ok "没有旧进程"
  fuser -k 8080/tcp 2>/dev/null || true
  sleep 2

  step "启动后端（日志：$BACK_LOG）"
  # 关键：云端路径与本地不同，用命令行参数覆盖（优先级高于 application.yml），
  # 这样 application.yml 不用改，git pull 不会冲突。
  nohup java -jar "$jar" \
    --server.port=8080 \
    --file.upload-dir="$UPLOAD_DIR" \
    --lightdisk.transcode.output-dir="$UPLOAD_DIR/.hls" \
    --lightdisk.transcode.ffmpeg-path="$(command -v ffmpeg)" \
    --lightdisk.transcode.ffprobe-path="$(command -v ffprobe)" \
    --lightdisk.ai.mcp.command=node \
    "--lightdisk.ai.mcp.args[0]=$MCP_DIR/server.js" \
    > "$BACK_LOG" 2>&1 &
  ok "已后台启动，PID=$!"

  step "等后端起来（最多 60 秒）"
  for i in $(seq 1 30); do
    sleep 2
    if grep -q "Started LightDiskApplication" "$BACK_LOG" 2>/dev/null; then
      ok "后端启动成功"; return 0
    fi
    grep -qE "APPLICATION FAILED TO START|BeanCreationException" "$BACK_LOG" 2>/dev/null && \
      die "后端启动失败，看日志：tail -50 $BACK_LOG"
  done
  warn "60 秒内没看到启动完成日志，请查看：tail -50 $BACK_LOG"
}

# ---------------------------------------------------------------- 前端 ---
start_frontend() {
  [ -d "$FRONT_DIR" ] || die "找不到前端目录 $FRONT_DIR（可用 FRONT_DIR=/path 指定）"
  step "安装前端依赖"
  ( cd "$FRONT_DIR" && npm install --no-audit --no-fund ) || die "npm install 失败"
  step "启动前端 dev server（日志：$FRONT_LOG）"
  pkill -f "vite" 2>/dev/null && ok "已停旧 vite" || ok "没有旧 vite"
  sleep 2
  ( cd "$FRONT_DIR" && nohup npm run dev -- --host 0.0.0.0 --port 5173 > "$FRONT_LOG" 2>&1 & )
  sleep 8
  if curl -fsS http://127.0.0.1:5173 >/dev/null 2>&1; then
    ok "前端已起：http://127.0.0.1:5173（在 Cloud Studio 的「端口」面板把 5173 设为公开即可外网访问）"
  else
    warn "前端好像没起来，看日志：tail -30 $FRONT_LOG"
  fi
}

# ---------------------------------------------------------------- 检查 ---
check_all() {
  step "健康检查"
  curl -fsS http://127.0.0.1:8080/api/ai/status 2>/dev/null | head -c 300; echo
  echo -n "  admin 登录："
  curl -fsS -X POST http://127.0.0.1:8080/api/login -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"123456"}' 2>/dev/null | head -c 160; echo
  echo -n "  Redis："; redis-cli ping 2>/dev/null || echo "未响应"
  echo -n "  RabbitMQ 队列："
  curl -fsS -u admin:123456 http://127.0.0.1:15672/api/queues 2>/dev/null \
    | grep -o '"name":"[^"]*"' | head -6 | tr '\n' ' ' || echo "面板未通（不影响 Web 功能）"
  echo; ok "检查结束"
}

# ---------------------------------------------------------------- 主流程 ---
case "${1:-all}" in
  env)      need_java8; need_maven; need_node; need_ffmpeg; need_redis; need_rabbitmq; need_mysql ;;
  db)       need_mysql; db_import ;;
  config)   gen_config ;;
  backend)  need_java8; build_backend; start_backend ;;
  frontend) need_node; start_frontend ;;
  check)    check_all ;;
  all)
    mkdir -p "$DATA_DIR"
    need_java8; need_maven; need_node; need_ffmpeg; need_redis; need_rabbitmq; need_mysql
    gen_config
    db_import
    ( cd "$MCP_DIR" && npm install --no-audit --no-fund >/dev/null 2>&1 && ok "MCP 工具服务器依赖已装" || warn "MCP 依赖安装失败，AI 会自动降级为本地工具（不影响使用）" )
    build_backend; start_backend
    start_frontend
    check_all
    ;;
  *) die "未知参数：$1（可用：env|db|config|backend|frontend|check|all）" ;;
esac
