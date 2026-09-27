#!/system/bin/sh
# ============================================================
#  Y700 亮度锁定模块 - 一键诊断脚本
#  检测模块全部作用点 + 动态验证锁定是否生效
#
#  用法:  su -c "sh /data/local/tmp/y700_brightness_diag.sh [采样秒数]"
#  示例:  su -c "sh /data/local/tmp/y700_brightness_diag.sh 10"
#  报告:  自动保存到 /sdcard/Y700亮度模块诊断_<时间>.txt
# ============================================================

DUR="${1:-6}"
case "$DUR" in
  ''|*[!0-9]*) DUR=6 ;;
esac

TS=$(date +%Y%m%d_%H%M%S)
OUT="/sdcard/Y700亮度模块诊断_${TS}.txt"
: > "$OUT" 2>/dev/null

say() { echo "$1" | tee -a "$OUT"; }
line() { say "--------------------------------------------------------"; }

# ---------- 定位节点 ----------
BL=""
for d in /sys/class/backlight/*/; do
  [ -f "$d/brightness" ] && { BL="$d"; break; }
done

CDEV=""
for d in /sys/class/thermal/cooling_device*/; do
  [ "$(cat "$d/type" 2>/dev/null)" = "panel0-backlight" ] && { CDEV="$d"; break; }
done

MAX=$(cat "${BL}max_brightness" 2>/dev/null)
[ -z "$MAX" ] && MAX=4095

say "==================== Y700 亮度锁定模块诊断 ===================="
say "时间: $(date '+%Y-%m-%d %H:%M:%S')"
say ""

say "【1. 环境信息】"
say "机型: $(getprop ro.product.model) ($(getprop ro.product.device))"
say "品牌: $(getprop ro.product.manufacturer) $(getprop ro.product.brand)"
say "Android: $(getprop ro.build.version.release) (SDK $(getprop ro.build.version.sdk))"
say "内核: $(uname -r)"
say "SELinux: $(getenforce)"
if [ "$(id -u)" = "0" ]; then say "root: OK"; else say "root: 非 root，请改用 su -c 运行"; fi
say ""

say "【2. 模块安装】"
if [ -d /data/adb/modules/brightness_lock ]; then
  say "模块目录: 存在"
  say "--- module.prop ---"
  cat /data/adb/modules/brightness_lock/module.prop 2>/dev/null | tee -a "$OUT"
  if [ -f /data/adb/modules/brightness_lock/disable ]; then
    say "!! 发现 disable 标志：模块已被禁用"
  else
    say "disable 标志: 无（已启用）"
  fi
  for f in service.sh brightnessd.sh; do
    if [ -f /data/adb/modules/brightness_lock/$f ]; then
      say "$f: 存在"
    else
      say "!! $f: 缺失"
    fi
  done
else
  say "!! 模块目录不存在"
fi
say ""

say "【3. 配置 target】"
t=$(cat /data/adb/brightness_lock/target 2>/dev/null)
say "target = ${t:-<空>}"
say ""

say "【4. 守护进程】"
found=0
for p in /proc/[0-9]*/cmdline; do
  if grep -q brightnessd "$p" 2>/dev/null; then
    pid=${p#/proc/}; pid=${pid%/cmdline}
    say "运行中: PID=$pid  cmdline=$(tr '\0' ' ' < "$p")"
    found=1
  fi
done
[ "$found" = "0" ] && say "!! 未检测到守护进程（未运行）"
say ""

say "【5. 背光节点】"
if [ -n "$BL" ]; then
  say "节点目录: $BL"
  say "brightness        = $(cat "${BL}brightness" 2>/dev/null)"
  say "max_brightness    = $MAX"
  say "actual_brightness = $(cat "${BL}actual_brightness" 2>/dev/null)"
  say "bl_power          = $(cat "${BL}bl_power" 2>/dev/null)"
else
  say "!! 未找到背光节点（/sys/class/backlight/ 下无 brightness）"
fi
say ""

say "【6. 温控冷却设备】"
if [ -n "$CDEV" ]; then
  say "冷却设备: $CDEV  (type=panel0-backlight)"
  say "cur_state = $(cat "${CDEV}cur_state" 2>/dev/null) / max_state = $(cat "${CDEV}max_state" 2>/dev/null)"
else
  say "!! 未找到 panel0-backlight 冷却设备"
fi
say ""

say "【7. 动态验证锁定】"
case "$t" in
  ''|off|unlock|0|*[!0-9]*)
    say "当前未锁定（target=$t），跳过动态验证。"
    say "提示: 先设置 su -c 'echo 80 > /data/adb/brightness_lock/target' 再重跑。"
    ;;
  *)
    pre_raw=$(cat "${BL}brightness" 2>/dev/null)
    pre_cdev=$(cat "${CDEV}cur_state" 2>/dev/null)
    want=$(( t * MAX / 100 ))
    say "目标档位: $t% -> 期望背光 raw = $want"
    say "测试前: 背光=$pre_raw  冷却设备 cur_state=$pre_cdev"
    say "写入干扰值: 背光=1, 冷却设备=255 ..."
    echo 1 > "${BL}brightness" 2>/dev/null
    [ -n "$CDEV" ] && echo 255 > "${CDEV}cur_state" 2>/dev/null
    sleep "$DUR"
    after_raw=$(cat "${BL}brightness" 2>/dev/null)
    after_cdev=$(cat "${CDEV}cur_state" 2>/dev/null)
    say "${DUR}秒后: 背光=$after_raw  冷却设备 cur_state=$after_cdev"
    diff=$(( after_raw - want )); [ $diff -lt 0 ] && diff=$(( -diff ))
    tol=$(( MAX * 3 / 100 ))
    if [ "$diff" -le "$tol" ] && [ "$after_cdev" = "0" ]; then
      say "✅ 锁定生效：背光已被守护进程恢复为 $after_raw，冷却设备归 0"
    else
      say "❌ 锁定未生效：背光停在 $after_raw（期望 $want），冷却设备=$after_cdev"
      say "   可能原因：守护进程未运行 / 节点写失败 / target 未生效"
      echo "$pre_raw" > "${BL}brightness" 2>/dev/null
      [ -n "$CDEV" ] && echo "$pre_cdev" > "${CDEV}cur_state" 2>/dev/null
      say "   已恢复测试前状态：背光=$pre_raw"
    fi
    ;;
esac
say ""

say "==================== 诊断结束 ===================="
echo ""
echo "报告已保存到: $OUT"
