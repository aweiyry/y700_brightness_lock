#!/system/bin/sh
# 亮度锁定 - 开机服务 (KernelSU service.sh)
MODDIR=${0%/*}
[ -z "$MODDIR" ] && MODDIR=/data/adb/modules/brightness_lock

# 确保配置目录存在
mkdir -p /data/adb/brightness_lock
chmod 755 /data/adb/brightness_lock

# 首次运行：默认锁定 100%
[ -f /data/adb/brightness_lock/target ] || echo 100 > /data/adb/brightness_lock/target

# 后台启动守护进程
nohup "$MODDIR/brightnessd.sh" >/dev/null 2>&1 &
