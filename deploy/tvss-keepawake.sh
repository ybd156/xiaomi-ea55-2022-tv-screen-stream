#!/system/bin/sh
# TV Screen Stream —— 常驻可达（允许息屏，但保证网络与服务不被清理）
#
# 背景（2026-11-21 实测）：
#   电视待机时**网络并没有断**（接口全在、能 ping 通对端），
#   真正消失的是**我们的进程** —— 系统在待机时把后台进程（我们的服务、adbd、
#   zerotier）一起清掉了，所以从外面看是「整机失联」。
#
# 解法（已实测）：
#   拿一个内核 wakelock 阻止系统进入挂起/清理。实测息屏 55 秒期间
#   mWakefulness=Asleep 而服务进程存活、loopback 有响应、PC 侧 8899 全程可达。
#
# 因此本脚本**不再强行点亮屏幕**：让电视照常息屏（省电、不刺眼），
# 只负责常驻 wakelock 与重现可达性所需的最小状态。
# 想亮屏时用遥控器，或访问 /wake。
#
# 关闭方式：
#   touch /data/local/tmp/tvss-no-keepawake     # 停止维护（会释放 wakelock）
#   rm /data/adb/service.d/tvss-keepawake.sh    # 彻底移除
#
# 日志：/data/local/tmp/tvss-keepawake.log

export PATH=/system/bin:/system/xbin:/vendor/bin:/vendor/xbin:/sbin:$PATH

LOG=/data/local/tmp/tvss-keepawake.log
OFF=/data/local/tmp/tvss-no-keepawake
LOCK=/data/local/tmp/tvss-keepawake.lock
WL_NAME=tvss

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG"; }

# 单实例
if [ -f "$LOCK" ]; then
    old=$(cat "$LOCK" 2>/dev/null)
    if [ -n "$old" ] && [ -d "/proc/$old" ]; then
        echo "already running pid=$old" >> "$LOG"
        exit 0
    fi
fi
echo $$ > "$LOCK"

# ---- 等开机完成 ----
i=0
while [ $i -lt 150 ]; do
    [ "$(getprop sys.boot_completed)" = "1" ] && break
    sleep 2
    i=$((i + 1))
done
sleep 20

# 允许电视自己息屏（之前为了防待机把它设成常亮，现在不需要了）
settings put global stay_on_while_plugged_in 0 2>/dev/null

log "==== 可达性守护启动（wakelock=$WL_NAME，允许息屏） ===="

while true; do
    if [ -f "$OFF" ]; then
        # 用户要求停手：把 wakelock 还回去，让系统照常管理
        echo "$WL_NAME" > /sys/power/wake_unlock 2>/dev/null
        sleep 30
        continue
    fi

    held=$(cat /sys/power/wake_lock 2>/dev/null)
    case "$held" in
        *"$WL_NAME"*) : ;;
        *)
            echo "$WL_NAME" > /sys/power/wake_lock 2>/dev/null
            cur=$(cat /sys/power/wake_lock 2>/dev/null)
            case "$cur" in
                *"$WL_NAME"*) log "已获取 wakelock（当前: $cur）" ;;
                *) log "获取 wakelock 失败（当前: $cur）" ;;
            esac
            ;;
    esac

    sleep 30
done
