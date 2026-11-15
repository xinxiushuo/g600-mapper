# 新游 G600 手柄 — 诊断与采集工具

零依赖（只用 Windows 自带 API + Python 标准库），不需要装 hidapi / 驱动 / 厂家 APP。

## 一键使用

双击 `tools\run.bat` 打开菜单；也可以直接带参数运行（适合做快捷方式）：

```bash
tools\run.bat 7     # 设备识别（换蓝牙/2.4G 后先跑这个）
tools\run.bat 1     # 等价于 python tools/g600.py info
tools\run.bat 2     # 全通道监控
```

或者在项目根目录手动执行：

```bash
python tools/g600.py scan      # ★ 设备识别：被认成什么、走不走 XInput
python tools/g600.py info      # 手柄是什么、报告长什么样
python tools/g600.py all       # ★ 同时监控4个通道，看每个键走哪条路
python tools/g600.py watch     # 实时解析：轴百分比 + 按键号
python tools/g600.py raw       # 原始16进制
python tools/g600.py learn     # 引导式学习，自动生成 mapping.json
python tools/g600.py cal       # 引导校准摇杆量程，生成 calibration.json
python tools/hid_probe.py list # 列出所有 HID 设备
```

> 设备是**自动识别**的：不给关键词就按「能否打开 > 2.4G/USB 优先 > 蓝牙」挑一个。要强制指定某个设备，把路径里的关键词当第二个参数传进去即可。

> `run.bat` 必须是 **UTF-8 无 BOM + CRLF 换行**，且首行 `chcp 65001`。如果用编辑器把它另存成 LF 换行，cmd 会把多行拼成一行，报一堆「'xxx' 不是内部或外部命令」。改这个文件时注意别破坏这两点。

## 实测到的真实结构（权威，来自内核 HID 解析器）

设备：蓝牙 HID，MAC `DDC2212CF0EB`，VID/PID 未上报（`0000:0000`，杂牌常见）

| 通道 | Usage | 长度 | 能否读取 |
|---|---|---|---|
| Game Pad | `0001:0005` | 10 B | ✅ 主通道 |
| Consumer（媒体键） | `000C:0001` | 9 B | ✅ 可读（HOME 键在这里） |
| Keyboard | `0001:0006` | 21 B | ❌ 被 Windows 独占 |
| Mouse | `0001:0002` | 4 B | ❌ 被 Windows 独占 |

Game Pad 输入报告（Report ID = 2）：

```
02 | 2b 2a 22 31 | 0f | 00 00 | 00 00
ID |  X  Y  Z Rz |Hat | btn  | Accel/Brake
```

| 字段 | 位宽 | 逻辑范围 | 说明 |
|---|---|---|---|
| X / Y | 8 bit | 0–255 | 左摇杆 |
| Z / Rz | 8 bit | 0–255 | 右摇杆 |
| Hat | 4 bit | 0–7，`0x0F`=居中 | 十字键 |
| Buttons | 16 bit | 15 位有效 | 见下表 |
| Accelerator | 8 bit | 0–255 | 右扳机**模拟量** |
| Brake | 8 bit | 0–255 | 左扳机**模拟量** |

按键号（joy.cpl 编号）：

| 编号 | 功能 | 编号 | 功能 |
|---|---|---|---|
| 1 | A | 9 | L2 |
| 2 | B | 10 | R2 |
| 3 | *空* | 11 | *空* |
| 4 | X | 12 | START |
| 5 | Y | 13 | *空* |
| 6 | *空* | 14 | L3 左摇杆按下 |
| 7 | L1 | 15 | R3 右摇杆按下 |
| 8 | R1 | | |

### 每个键实际走哪条通道（2026-11-15 实测）

这是关键的一张表 —— **不是所有键都在 GamePad 通道里**，joy.cpl 只看 GamePad，所以会误判成"按键失灵"。

| 物理键 | 通道 | 编码 | 状态 |
|---|---|---|---|
| A / B / X / Y | GamePad | button 1 / 2 / 4 / 5 | ✅ |
| L1 / R1 / L2 / R2 | GamePad | button 7 / 8 / 9 / 10 | ✅ |
| START | GamePad | button 12 | ✅ |
| L3 / R3（摇杆按下） | GamePad | button 14 / 15 | ✅ |
| 十字键 | GamePad | Hat 4bit（`0x0F`=居中） | ✅ |
| **BACK** | **Consumer** | **AC Back `0x0224`** | ✅ 有信号，但 joy.cpl 看不到 |
| **HOME** | **Consumer** | **AC Home `0x0223`** | ⚠️ 系统级快捷键，拦不住 |
| **感叹号键** | **Consumer** | **Help `0x0095`** | 蓝牙 ❌ 无信号 / **2.4G ✅ 正常** |

> **感叹号键在 2.4G 下是活的**
>
> 蓝牙固件把它屏蔽了（一个字节都不发），但 2.4G 下正常上报 `Help 0x0095`。
> 这也解释了图标为什么是感叹号 —— 它本来就是"帮助"键。
> 实测帧：`02 95 00 00 00 00 00 00 00`（Consumer 通道，Report ID 2）
>
> 结论：**想要这个键，就用 2.4G。**

三个结论：

1. **BACK 不是坏的**。它发的是浏览器的"后退"指令（Consumer `AC Back`），所以在浏览器里按它会后退，在游戏里按它毫无反应 —— 因为游戏根本不响应媒体键。这个键可以用 AHK 之类的工具救活。
2. **HOME 是硬件级快捷键**，发 `AC Home`，Windows 直接把它交给默认浏览器，Android 会直接回桌面。任何软件层都拦不住。
3. **感叹号键取决于模式**：蓝牙固件彻底屏蔽（三条通道全无数据），**2.4G 下正常上报 `Help 0x0095`**。之前判断"固件死键"只对蓝牙成立。

GamePad 报告里 3 / 6 / 11 / 13 四个空位，就是 BACK、感叹号、HOME 以及一个未使用位在按键位图里预留的坑 —— 固件没往里填值，而是转到了 Consumer 通道（蓝牙分支只转了 BACK 和 HOME，漏了感叹号）。

## 关于固件

刷不了，也没必要：

- VID/PID 上报 `0000:0000`，说明它连 USB 描述符都没做正规申请，是方案商公版固件套壳。
- 没有官方固件包，也没有 DFU 入口（设备不暴露 Feature Report 升级通道，`feat_len = 0`）。
- 乱刷公版固件（Betop / 北通 / 各种 K 系列方案）大概率变砖，且无法回滚。

好消息是**它不需要固件**：这就是个标准 HID 手柄，Windows / Android / Linux 都能直接用，厂家 APP 只是个"按键映射到触摸"的中间层，不是驱动。

## 蓝牙 vs 2.4G（已实测）

| | 蓝牙模式 | 2.4G 模式 |
|---|---|---|
| 设备名 | 无（VID:PID = `0000:0000`） | **NewGamePad A102** |
| VID:PID | `0000:0000` | **`2015:0102`** |
| 通道类型 | HID DInput | HID DInput（**不是 XInput**） |
| Report ID | `2` | **`1`** |
| 报告长度 | 10 字节 | 10 字节 |
| 轴结构 | X/Y/Z/Rz + Hat + Brake/Accel | 完全相同 |
| 数据节奏 | 持续轮询（约 10 帧/秒） | **仅按键时发送**，静止时 0 帧 |

两点要注意：

1. **2.4G 插上后设备身份会变**，工具已改成自动识别，不用再指定关键词。
2. **Report ID 从 2 变成 1，按键号可能也不同**。切模式后请重跑一次 `run.bat 2` 重新确认，`mapping.json` / `calibration.json` 不通用。

```bash
python tools/g600.py scan      # 蓝牙/2.4G 到底被认成什么、走不走 XInput
```

### 2.4G 的两个坑（已修）

**1. 多帧粘连**

2.4G 接收器会积压报告，一次 `ReadFile` 可能返回 2 帧、4 帧拼在一起的 buffer：

```
01 2b 2a 23 31 0f 00 00 00 00 | 01 2a 2a 23 31 0f 00 00 00 00   ← 20 字节 = 2 帧
```

不切分的话，`HidP_GetUsages` 只解析**最前面那个旧帧**，于是状态永远慢半拍，而且 raw 每次拼接组合不同，会被判定为"数据变化"疯狂刷屏。

现在的处理：按 `InputReportByteLength` 整除切分，**只取最后一帧**（最新状态），同时 `HidD_SetNumInputBuffers(4)` 让驱动少缓存几帧。

**2. 队列积压导致延迟**

后台读线程持续往队列塞帧，主循环如果逐帧消费，就会一直追不上。监控类命令改用 `read_latest()`：**一次性清空队列只留最后一帧**，延迟从几百毫秒降到几毫秒。

## 手机映射 APP（Android）

在 `android/` 目录，Kotlin 写的无障碍服务方案：悬浮键位可拖动、触摸穿透、按键映射到屏幕任意坐标。

- **直接下载 APK（免登录）**：<https://github.com/xinxiushuo/g600-mapper/releases/latest/download/app-debug.apk>
- 仓库：<https://github.com/xinxiushuo/g600-mapper>（本地另存一份 `dist/app-debug.apk`）
- 构建方式：push 到 `main` → GitHub Actions 自动打包 → 覆盖更新到上面的 `latest` 直链
- **装的是哪一版**：APP 主界面和「手柄输入测试」页的**最底部**都有一行版本信息，形如 `v1.1 (2) · 构建 2026-11-15 11:26`；构建时间是 CI 真实的编译时间（北京时间），长按这一行可复制版本+机型，反馈问题时直接粘出来即可
- 说明与能力边界：见 [`android/README.md`](android/README.md)

> 非 root 的限制：按钮映射可用，摇杆的连续拖动效果有限 —— 原因和替代方案写在 `android/README.md` 里。

## 文件

```
README.md                          本文件
tools/g600.py                      手柄诊断 / 采集 / 校准主工具
tools/hid_probe.py                 HID 设备枚举与原始监控（ctypes 零依赖）
tools/run.bat                      菜单式启动器
android/                           手机映射 APP 源码
dist/app-debug.apk                 已构建好的 APK(本地备份, 不入库)
.github/workflows/build-apk.yml    自动打包 APK
```
