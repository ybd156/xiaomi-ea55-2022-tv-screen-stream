#!/system/bin/sh
# 停止画面服务（只杀本程序，按完整类名精确匹配）
PIDS=$(ps -A -o PID,ARGS | grep 'com\.droidlogic\.app\.ScreenControlManager' | grep -v grep | awk '{print $1}')
if [ -z "$PIDS" ]; then
  echo "not running"
  exit 0
fi
for p in $PIDS; do kill "$p"; done
pkill -f 'tinycap /data/local/tmp/audio.fifo' 2>/dev/null
echo "stopped: $PIDS"
