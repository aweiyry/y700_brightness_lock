# Y700 亮度锁定（Brightness Lock）

联想拯救者 Y700 平板（五代 TB323FU）的 **Magisk / KernelSU 亮度锁定模块**，以及**配套的亮度调节 App**。

> **一句话**：游戏高负荷 / 温控降亮度时，把屏幕亮度**钉死在设定档位**，不让它自动降低；用 App 按 1% 精度随意调节并锁死。

| 组成 | 说明 |
|------|------|
| **模块** (`module/`) | 钉死背光温控冷却设备 + 持续回写背光节点，对抗负荷/温控降亮度 |
| **App** (`app/`) | 1% 精度（1~100%）亮度调节 + 预设档位（5/20/50/80/100%）+ 自定义输入 + 锁定开关 + 实时亮度显示 |
| **诊断脚本** (`tools/y700_brightness_diag.sh`) | 一键检测模块全部作用点 + 动态验证锁定是否生效，社区排障用 |

## 支持机型

| 型号 | 代际 | 状态 |
|------|------|------|
| TB323FU | 五代 | ✅ 已验证 |

> 三代 TB321FU / 四代 TB322FC 若背光节点与冷却设备命名一致（`panel0-backlight`），理论上兼容，但**未实测**。

---

## 背景：为什么 Y700 会自己降亮度

平板在高负荷（大型游戏、长时间烤机）时屏幕会变暗，**不是框架在省电**，而是内核温控的**背光冷却设备**在起作用：

```
/sys/class/thermal/cooling_device34
    type        = panel0-backlight   # 专门给背光用的温控冷却设备
    cur_state   = 0 ~ 255            # 温度越高，这个值被抬得越高
    max_state   = 255
```

温度升高时，温控守护进程会抬高 `cooling_device34` 的 `cur_state`，面板的有效亮度随之被压低；同时框架侧还会改背光节点 `/sys/class/backlight/panel0-backlight/brightness`。

**所以"降亮度"是两个动作叠加的结果**：温控抬冷却设备 + 框架改背光。本模块同时钉死这两处。

---

## 一、模块

### 功能

1. **钉死温控冷却设备**：锁定状态下持续把 `cooling_device34/cur_state` 写回 `0`，从源头阻止温控降亮度
2. **回写背光节点**：锁定状态下持续把背光 `brightness` 写回目标档位对应的 raw 值，对抗框架改写
3. **1% 精度**：亮度映射为线性 `raw = 档位% × max_brightness(4095) / 100`（档位可为 1~100 任意整数）
4. **满量程更亮**：`100% = 4095`，比系统手动档被 HBM 锁住的上限 `3071/75%` 还更亮（接近阳光屏 HBM 最高档）
5. **解锁即还原**：配置写 `off` 后守护进程不干预，亮度交还系统控制
6. **单实例守护**：守护进程启动时自动检测是否已有实例，重复拉起会直接退出（开机自启与手动启动不会并存）

### 原理

守护进程（`brightnessd.sh`）每秒循环一次，**仅在锁定状态**执行：

```
1. echo 0 > /sys/class/thermal/cooling_device34/cur_state    # 钉死温控
2. echo <raw> > /sys/class/backlight/panel0-backlight/brightness  # 回写背光
```

> **关于 HBM 上限**：该屏手动模式的天花板是 `191/255`（背光 3071，约 600 nits）。
> 0.749 → 1.0 那一段（600 → 800 nits）是留给阳光屏 HBM 的，只在「自动亮度开启 + 环境光 ≥ 10000 lux」时解锁。
> 本模块直接写内核背光节点，因此 `100% = 4095` 可突破这个手动档上限。

### 安装

1. 从 [Releases](https://github.com/aweiyry/y700_brightness_lock/releases)（或本仓库 `brightness_lock-vX.X.zip`）下载模块 zip
2. 通过 **KernelSU**（或 Magisk）刷入
3. 重启一次，让模块开机自启守护进程

### 配置（命令行）

目标档位写在 `/data/adb/brightness_lock/target`：

```sh
# 锁定 80%
su -c 'echo 80 > /data/adb/brightness_lock/target'
# 锁定 100%（满量程，比手动档更亮）
su -c 'echo 100 > /data/adb/brightness_lock/target'
# 解锁，恢复系统控制
su -c 'echo off > /data/adb/brightness_lock/target'
```

> 修改 `target` 后守护进程会在 1 秒内自动生效，**无需重启**。也可以直接用 App 调节（推荐）。

### 卸载

```sh
su -c 'rm -rf /data/adb/modules/brightness_lock /data/adb/brightness_lock'
# 若守护进程还在跑，一并结束
su -c 'for p in /proc/[0-9]*; do grep -q brightnessd $p/cmdline 2>/dev/null && kill -9 ${p#/proc/}; done'
adb uninstall com.y700.brightnesslock
```

---

## 二、配套 App（亮度锁定）

### 功能

- **1% 精度调节**：拖动滑杆即可 1~100% 任意档位（不再固定 5% 步进）
- **预设常用档位**：一键直达 5% / 20% / 50% / 80% / 100%
- **自定义档位输入**：直接输入 1~100 的整数档位并应用
- **锁定开关**：开 = 钉死档位（防高负荷降亮度）；关 = 解锁，恢复系统控制
- **实时亮度显示**：顶部大字显示当前档位，与系统亮度**严格一致**（解锁态读系统亮度、锁定态读背光，见下）
- **Root 状态提示**：状态栏显示 `su` 是否就绪

> **显示匹配说明**：锁定态下背光是 App/守护进程直写内核的，档位按 `raw = 档位% × 4095 / 100` 线性换算显示；
> 解锁态下亮度由系统接管（系统亮度到背光是感知曲线、非线性），因此 App 改读系统 `screen_brightness`，
> 保证你看到的百分比与系统滑块**逐档一致**。

### 数据与权限

- App 通过 `su -c` 写入 `/data/adb/brightness_lock/target` 与背光节点，**自身不存任何数据**
- 首次打开会弹 KernelSU 授权框，点「允许」即可
- 仅依赖 `su`（本机为 `/system/bin/su`），无其他权限需求

### 构建

> 本仓库 App 采用**无 Gradle 手动打包**（`app/build.sh`），不依赖 Android Studio。

```sh
cd app
bash build.sh
# 产物: app/out/BrightnessLock.apk
```

> 需先配好 `build-tools;35.0.0` 与 `platforms;android-35`，SDK 路径见 `build.sh` 内 `SDK=` 变量。

### 使用

1. 从 [Releases](https://github.com/aweiyry/y700_brightness_lock/releases) 下载 `BrightnessLock.apk` 安装（或 `adb install -r app/out/BrightnessLock.apk`）
2. 打开「亮度锁定」，首次弹 KernelSU 授权，点「允许」
3. 拖动滑杆、点预设档位（5/20/50/80/100%）或输入自定义档位，打开「锁定亮度」开关即可锁死

---

## 故障诊断（锁定没生效时用）

社区反馈「锁定不生效 / 亮度还是被降」时，跑诊断脚本 `tools/y700_brightness_diag.sh`——自动检测所有作用点并**动态验证锁定**：

```sh
# 方式一：从仓库取脚本推送到平板
adb push tools/y700_brightness_diag.sh /data/local/tmp/
su -c "sh /data/local/tmp/y700_brightness_diag.sh"

# 可选参数：动态采样时长(秒)，默认 6
su -c "sh /data/local/tmp/y700_brightness_diag.sh 10"
```

脚本运行后自动生成报告到 **`/sdcard/Y700亮度模块诊断_<时间>.txt`**，包含：

| 检查项 | 说明 |
|--------|------|
| 环境信息 | 机型 / Android / 内核 / root / SELinux 上下文 |
| 模块安装 | 模块目录 / 版本 / 文件完整性 / `disable` 标志 |
| 配置 | 当前 `target` 档位 |
| 守护进程 | 是否运行（直查 `/proc/*/cmdline`，**不依赖 `ps`**，因为进程名是 `sh`）|
| 背光节点 | `brightness` / `max_brightness` / `actual_brightness` / `bl_power` |
| 温控冷却设备 | `panel0-backlight` 的 `cur_state` / `max_state` 与目标值对比 |
| **动态验证锁定** | 锁定状态下写入干扰值（压低背光 + 抬高冷却设备），观察是否在数秒内被守护进程恢复 |
| 自动结论 | 直接列出发现的问题与建议 |

把生成的报告文件发出来即可定位问题（报告只含系统状态，不含隐私信息）。

---

## 版本历史

### 模块

- **V1.0** — 首个版本：钉死背光温控冷却设备 `cooling_device34` + 每秒回写背光节点；
  配置 `/data/adb/brightness_lock/target`（数值=锁定档位，`off`=解锁）；开机 `service.sh` 自启守护进程；
  **单实例守护**（重复拉起自动退出）

### App

- **V1.2** — 调节精度改为 **1%**（1~100 任意档位）；新增**预设档位按钮**（5/20/50/80/100%）与**自定义档位输入**；
  修复「解锁后 App 显示与系统亮度不匹配」：解锁态改读系统 `screen_brightness`（系统亮度到背光为感知曲线、非线性，
  直接读背光会算出错误百分比），锁定态仍读背光 raw，两态显示均与真实亮度逐档一致
- **V1.1** — 修复「调整后界面跳回 5%」：背光读取改走 root（非 root 读背光节点会
  `Permission denied`，原实现读失败回退 0 导致永远显示 5%），现在**界面显示与真实亮度一致**
- **V1.0** — 首个版本：SeekBar 19 档（5% 步进）+「±5%」按钮 + 锁定开关 + 实时背光读数

---

## 项目结构

```
.
├── module/                 # KernelSU / Magisk 模块源码
│   ├── module.prop         # 模块信息
│   ├── service.sh          # 开机自启，拉起守护进程
│   └── brightnessd.sh      # 守护进程（钉死冷却设备 + 回写背光）
├── tools/
│   └── y700_brightness_diag.sh  # 一键诊断脚本（检测作用点 + 动态验证锁定）
├── app/                    # 配套 App 源码 + 手动构建脚本
│   ├── src/com/y700/brightnesslock/MainActivity.java
│   ├── AndroidManifest.xml
│   ├── build.sh            # 无 Gradle 手动打包脚本
│   └── out/BrightnessLock.apk   # 编译产物
└── brightness_lock-v1.0.zip    # 可重装的模块包
```

---

## 免责声明

本模块会把屏幕背光钉死在上限附近、并绕过背光温控保护。**长时间满亮度 + 高负荷会显著增加发热与耗电**，高温下边充边玩有风险，请自行权衡，使用即视为自担风险。搬运请标明出处。

作者：**酷安@妲你小己吧**
