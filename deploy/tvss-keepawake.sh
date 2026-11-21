#!/system/bin/sh
# TV Screen Stream —— 屏幕保活（防止电视自己待机导致远程全部失联）
#
# 背景（2026-11-21 事故）：电视进入待机后网络被断开，8899 / adb / ZeroTier / 米家
# 全部同时失联，而整机 uptime 连续（说明没关机）。外网连不上就无法远程唤醒，
# 所以唤醒动作必须发生在电视本机 —— 走 loopback 调我们自己的 /wake。
#
# 行为：
#   * 白天窗口（默认 08:00–23:59）内，若检测到 mWakefulness != Awake，调用本地 /wake
#   * 夜间窗口不干预，允许电视正常待机（否则等于永远关不掉）
#   * 顺带持续重申 stay_on_while_plugged_in=3（MiTV 会在 1–2 分钟内重置回 0）
#
# 关闭方式：
#   touch /data/local/tmp/tvss-no-keepawake     # 立即停手（脚本仍在跑但不动作）
#   rm /data/adb/service.d/tvss-keepawake.sh    # 彻底移除（重启后不再运行）
#
# 日志：/data/local/tmp/tvss-keepawake.log

export PATH=/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/sbin:$PATH

LOG=/data/local/tmp/tvss-keepawake.log
OFF=/data/local/tmp/tvss-no-keepawake
PORT=8899
DAY_START=${TVSS_AWAKE_FROM:-8}
DAY_END=${TVSS_AWAKE_TO:-24}

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG"; }

wakefulness() {
    dumpsys power 2>/dev/null | grep -o 'mWakefulness=[A-Za-z]*' | head -1 | cut -d= -f2
}

local_wake() {
    # 优先走服务端点（PowerManager.wakeUp，比 keyevent 可靠）
    wget -q -O /dev/null --timeout=6 "http://127.0.0.1:$PORT/wake" 2>/dev/null && return 0
    busybox wget -q -O - --timeout=6 "http://127.0.0.1:$PORT/wake" >/dev/null 2>&1 && return 0
    # 兜底：直接注入电源键（部分固件对 KEYCODE_WAKEUP 无反应，POWER 有效）
    input keyevent KEYCODE_WAKEUP 2>/dev/null
    return 0
}

# ---- 等开机完成 ----
i=0
while [ $i -lt 150 ]; do
    [ "$(getprop sys.boot_completed)" = "1" ] && break
    sleep 2
    i=$((i + 1))
done
sleep 30

log "==== 保活启动（窗口 ${DAY_START}:00–${DAY_END}:00） ===="

while true; do
    if [ -f "$OFF" ]; then
        sleep 60
        continue
    fi

    h=$(date +%H | sed 's/^0*//'); [ -z "$h" ] && h=0
    if [ "$h" -ge "$DAY_START" ] && [ "$h" -lt "$DAY_END" ]; then
        st=$(wakefulness)
        if [ "$st" != "Awake" ]; then
            log "检测到 ${st:-unknown}，本地唤醒"
            local_wake
            sleep 6
            log "唤醒后：$(wakefulness)"
        fi
        cur=$(settings get global stay_on_while_plugged_in)
        [ "$cur" != "3" ] && settings put global stay_on_while_plugged_in 3 2>/dev/null
    fi

    sleep 45
done
