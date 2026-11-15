# G600 手柄映射（Android）

把手柄按键映射成屏幕上的触摸点。悬浮键位可以用手指拖到任意位置，锁定后触摸穿透，不挡游戏操作。

## 怎么拿到 APK

### 方式 A：直接下载（推荐，免登录）

```
https://github.com/xinxiushuo/g600-mapper/releases/latest/download/app-debug.apk
```

手机浏览器直接打开这个链接就能下，不用登录 GitHub。
下载后允许「安装未知来源应用」即可安装。

> 仓库：https://github.com/xinxiushuo/g600-mapper
> 每次 push 到 `main` 都会自动重新打包，并覆盖更新到上面这个 `latest` 直链。

### 方式 B：GitHub Actions 里手动下载

仓库顶部 **Actions** 标签 → 点最新一次 `Build APK` → 页面底部 **Artifacts** → 下载 `G600Mapper-debug-apk`（zip，解开是 `app-debug.apk`）。
注意：Artifacts 必须**登录 GitHub** 才能下载，所以推荐用方式 A。

### 方式 C：Android Studio（想改代码就走这个）

1. 装 [Android Studio](https://developer.android.com/studio)（约 3 GB，含 SDK）
2. `File → Open` 选择这个 `android` 目录
3. 等 Gradle 同步完，点绿色 ▶ 运行，或 `Build → Build Bundle(s)/APK(s) → Build APK(s)`
4. APK 在 `app/build/outputs/apk/debug/`

命令行打包（需已配好 JDK17 + `ANDROID_HOME`）：`bash build.sh`

## 用起来

1. 打开 APP → 点 **① 开启无障碍服务** → 在系统列表里找到「G600 手柄映射」并打开
2. 点 **手柄输入测试** → 按手柄上的键，确认能显示对应的 `keyCode`
   - 如果这里什么都读不到，说明 Android 没把手柄识别成 HID 手柄，后面的映射也没意义
3. 回主页 → 点 **② 拖动键位** → 把每个圆点拖到游戏里对应按钮的位置
4. 点 **③ 锁定并开始映射** → 切到游戏

编辑模式下圆点是可触摸的（会挡住操作），锁定后整层变成 `FLAG_NOT_TOUCHABLE`，触摸直接穿透给游戏。

## 能力边界（重要，先说清楚）

非 root 环境下，这个 APP 靠无障碍服务的 `dispatchGesture` 注入触摸，它有几个硬限制：

| 能力 | 支持情况 |
|---|---|
| 单个按键 → 点击/长按屏幕上某点 | ✅ 可用 |
| 多个按键同时按住（多指） | ✅ 可用（合并成一个多指手势） |
| 摇杆 → 屏幕上的虚拟摇杆拖动 | ⚠️ 效果有限 |

为什么摇杆难做：`dispatchGesture` 同一时刻只允许一个手势在执行，新的会取消旧的。所以这里用「每 40ms 重发一次手势」来模拟按住，游戏侧会感知为**高频点击**而不是严格的长按。对按钮类操作没影响，但对「需要按住摇杆持续移动」的 FPS/MOBA 就不够用。

想要真正的摇杆，只有三条路，都不是非 root 能解决的：
- **root** → 写 `/dev/uinput` 虚拟触摸设备（最接近真机）
- **LSPosed / Xposed** 钩子 → 直接改游戏的输入处理
- **游戏原生支持手柄** → 这是最省事的，很多游戏（原神、和平精英、大部分模拟器）本来就不用映射

另外三个已经实测确认的事实（Windows 侧验证，Android 同理）：

| 键 | 实际情况 | 能不能映射 |
|---|---|---|
| **BACK** | 走 Consumer 通道发 `AC Back`，到 Android 上变成 `KEYCODE_BACK` | ✅ 能，但要按设备来源区分，否则会吞掉手机返回键（代码已处理） |
| **HOME** | 发 `AC Home`，Android 会直接回桌面 | ❌ 系统级保留键，无障碍拦不住，建议别用 |
| **感叹号键** | 蓝牙下不发数据；**2.4G 下正常发 `Help 0x0095`** | ⚠️ 要看 Android 有没有把这个 usage 翻译成 keycode，装上用测试页按一下就知道 |

> **重要**：感叹号键请用 **2.4G 模式**。蓝牙固件把它砍了，2.4G 没砍。
> 如果 Android 端读不到它（系统映射表里没有 `0x0095`），那就只剩 root/uinput 或 LSPosed 一条路。

## 代码结构

```
app/src/main/java/com/g600/mapper/
├── MainActivity.kt        权限引导 + 开关
├── PadTestActivity.kt     手柄输入测试（确认 KeyCode）
├── MapperService.kt       无障碍服务：收按键 + 注入触摸
├── OverlayManager.kt      悬浮键位：显示 / 拖动 / 保存
├── MappingStore.kt        配置持久化（归一化坐标，换分辨率不丢）
└── PadKeys.kt             手柄键名表
```

配置文件存在 `SharedPreferences`，坐标用 0～1 归一化保存，所以横竖屏、换手机都能还原。

## 构建状态

已在 GitHub Actions 上通过（JDK 17 + Gradle 8.5 + AGP 8.2.2，`assembleDebug` 成功），产物 5.3 MB，`AndroidManifest.xml`、`classes.dex`、无障碍服务与权限声明均已校验存在。

## 注意

- 这是 **debug 签名**的包，可以正常安装，但不能上应用商店
- 各家 ROM（小米/华为/OPPO/vivo）对无障碍服务有额外限制，通常还需要在系统设置里单独允许「后台弹出界面」「自启动」「显示悬浮窗」，否则息屏或切后台后服务会被杀
- 第一版**不做摇杆映射**，只做按键 → 触摸点。原因见上文「能力边界」
