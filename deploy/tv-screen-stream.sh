#!/system/bin/sh
# TV Screen Stream —— 开机自启 + 守护
#
# 安装位置：/data/adb/service.d/tv-screen-stream.sh
# 该目录下的脚本由 Magisk 在 late_start service 阶段以 root 身份执行。
#
# 行为：
#   1. 等 sys.boot_completed=1
#   2. serve-vdin 模式下先停掉 screencontrol（/dev/video12 是独占设备）
#   3. 启动 app_process 服务；VDIN 起不来则自动回退到厂商模式
#   4. 每 20 秒检查一次（pidfile + /proc 判断），挂了就拉起来；
#      VDIN 模式下若 screencontrol 又被拉起来抢设备，重新停掉
#
# 环境变量可覆盖：TVSS_MODE(serve-vdin|serve) TVSS_W TVSS_H TVSS_Q TVSS_PORT
# 日志：/data/local/tmp/tvss-boot.log
#
# 回退：把 TVSS_MODE 改成 serve，或直接跑 /data/local/tmp/restore-service.sh

# Magisk 执行 service.d 时 PATH 很精简，必须自己补上
export PATH=/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH

LOG=/data/local/tmp/tvss-boot.log
PIDFILE=/data/local/tmp/tvss.pid
DEX=/data/local/tmp/sc.dex
MODE=${TVSS_MODE:-serve-vdin}
W=${TVSS_W:-480}
H=${TVSS_H:-270}
PORT=${TVSS_PORT:-8899}
Q=${TVSS_Q:-45}

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG"; }

# 判断服务是否还活着：pidfile 里的 pid 存在，且 cmdline 确实是我们的类
running() {
    [ -f "$PIDFILE" ] || return 1
    p=$(cat "$PIDFILE" 2>/dev/null)
    [ -n "$p" ] || return 1
    [ -d "/proc/$p" ] || return 1
    tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | grep -q 'com.droidlogic.app.ScreenControlManager' || return 1
    return 0
}

# 停掉 screencontrol 并确认它真的退出（它握着 /dev/video12）
free_vdin() {
    stop screencontrol 2>/dev/null
    n=0
    while [ $n -lt 15 ] && [ -n "$(pidof screencontrol)" ]; do
        killall screencontrol 2>/dev/null
        sleep 1
        n=$((n + 1))
    done
    [ -n "$(pidof screencontrol)" ] && log "警告：screencontrol 未能停掉（pid=$(pidof screencontrol)）"
    return 0
}

start_svc() {
    rm -f "$PIDFILE" /data/local/tmp/audio.fifo
    log "拉起服务（$MODE ${W}x${H} q$Q）"
    CLASSPATH=$DEX /system/bin/app_process -Xmx96m /system/bin \
        com.droidlogic.app.ScreenControlManager "$MODE" 1 "$W" "$H" "$PORT" "$W" "$H" "$Q" \
        >> "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
}

# ---- 等开机完成 ----
i=0
while [ $i -lt 150 ]; do
    [ "$(getprop sys.boot_completed)" = "1" ] && break
    sleep 2
    i=$((i + 1))
done
if [ "$(getprop sys.boot_completed)" != "1" ]; then
    log "boot_completed 超时，放弃"
    exit 1
fi
sleep 10

if [ ! -f "$DEX" ]; then
    log "缺少 $DEX，无法启动（需要先 adb push）"
    exit 1
fi
if [ "$MODE" = "serve-vdin" ] && [ ! -f /data/local/tmp/libvdin.so ]; then
    log "缺少 libvdin.so，降级为厂商模式"
    MODE=serve
fi

log "==== 守护启动 mode=$MODE ${W}x${H} :${PORT} q${Q} ===="

# ---- 守护循环 ----
while true; do
    if ! running; then
        if [ "$MODE" = "serve-vdin" ]; then
            free_vdin
        fi
        start_svc
        sleep 12
        if running && netstat -tnl 2>/dev/null | grep -q ":$PORT "; then
            log "服务已就绪（:${PORT} LISTEN, pid=$(cat $PIDFILE)）"
        elif [ "$MODE" = "serve-vdin" ]; then
            # VDIN 打不开就退回厂商模式，别让电视没画面
            log "VDIN 起不来，回退厂商模式"
            kill "$(cat $PIDFILE 2>/dev/null)" 2>/dev/null
            sleep 2
            start screencontrol 2>/dev/null
            sleep 3
            MODE=serve
            start_svc
            sleep 12
        else
            log "端口未监听，稍后重试"
        fi
    elif [ "$MODE" = "serve-vdin" ] && [ -n "$(pidof screencontrol)" ]; then
        # MiTV 会自己把 screencontrol 拉起来，它一占住 video12 我们就采不到帧
        log "screencontrol 又出现了，重新停掉"
        free_vdin
    fi
    sleep 20
done
