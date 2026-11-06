#!/system/bin/sh
# 电视画面网页直播
# 用法: run_srv.sh [宽] [高] [端口] [JPEG质量]
#   默认 960x540 / 8899 / 70
W=${1:-960}
H=${2:-540}
P=${3:-8899}
Q=${4:-70}
DEX=/data/local/tmp/sc.dex

if [ ! -f "$DEX" ]; then
  echo "missing $DEX -- push build/classes.dex first" >&2
  exit 1
fi

rm -f /data/local/tmp/audio.fifo
export CLASSPATH=$DEX
exec app_process -Xmx96m /system/bin \
  com.droidlogic.app.ScreenControlManager serve 1 $W $H $P $W $H $Q
