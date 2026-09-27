#!/system/bin/sh
# 亮度锁定守护进程：持续钉死背光，对抗温控/负荷降亮度
CONF=/data/adb/brightness_lock/target

# 单实例守护：若已有其他 brightnessd 实例在跑，直接退出（避免重复拉起）
for p in /proc/[0-9]*/cmdline; do
  if grep -q brightnessd "$p" 2>/dev/null; then
    pid=${p#/proc/}; pid=${pid%/cmdline}
    [ "$pid" = "$$" ] && continue
    exit 0
  fi
done

# 定位背光节点
BL=""
for d in /sys/class/backlight/*/; do
  [ -f "$d/brightness" ] && { BL="$d/brightness"; break; }
done
[ -z "$BL" ] && exit 1
MAX=4095
[ -f "${BL%brightness}max_brightness" ] && MAX=$(cat "${BL%brightness}max_brightness")

# 定位背光温控冷却设备(panel0-backlight)
CDEV=""
for d in /sys/class/thermal/cooling_device*/; do
  [ "$(cat "$d/type" 2>/dev/null)" = "panel0-backlight" ] && { CDEV="${d}cur_state"; break; }
done

while true; do
  target=$(cat "$CONF" 2>/dev/null)
  case "$target" in
    ''|off|unlock|0)
      # 解锁：不干预，交给系统
      ;;
    *)
      # 锁定：先钉死背光温控冷却设备（关键，防止高负荷降亮度）
      if [ -n "$CDEV" ] && [ "$(cat "$CDEV" 2>/dev/null)" != "0" ]; then
        echo 0 > "$CDEV" 2>/dev/null
      fi
      # 档位限幅
      [ "$target" -gt 100 ] 2>/dev/null && target=100
      raw=$(( target * MAX / 100 ))
      if [ "$(cat "$BL" 2>/dev/null)" != "$raw" ]; then
        echo "$raw" > "$BL" 2>/dev/null
      fi
      ;;
  esac
  sleep 1
done
