# -*- coding: utf-8 -*-
"""
g600.py —— 新游 G600 手柄诊断 / 采集 / 学习工具 (Windows, 零依赖)

    python g600.py info          设备与报告结构一览
    python g600.py watch         实时解析(轴/十字键/全部按键号), 事件流输出
    python g600.py learn         引导式学习: 逐个按提示操作, 自动生成映射表 mapping.json
    python g600.py raw           打印原始 16 进制报告

所有解析都调用 Windows 内核 HID 解析器 (HidP_*), 与 joy.cpl 的编号完全一致。
"""
import ctypes
import ctypes.wintypes as wt
import json
import sys
import threading
import time

import hid_probe as H

hid_dll = H.hid_dll
kernel32 = H.kernel32

HIDP_INPUT = 0
HIDP_STATUS_SUCCESS = 0x00110000   # HIDP_* 返回 NTSTATUS, 成功不是 0 !
HIDP_OUTPUT = 1
HIDP_FEATURE = 2


# ---------------- HIDP 结构 ----------------
class _Range(ctypes.Structure):
    _fields_ = [("UsageMin", wt.USHORT), ("UsageMax", wt.USHORT),
                ("StringMin", wt.USHORT), ("StringMax", wt.USHORT),
                ("DesignatorMin", wt.USHORT), ("DesignatorMax", wt.USHORT),
                ("DataIndexMin", wt.USHORT), ("DataIndexMax", wt.USHORT)]


class _NotRange(ctypes.Structure):
    _fields_ = [("Usage", wt.USHORT), ("Reserved1", wt.USHORT),
                ("StringIndex", wt.USHORT), ("Reserved2", wt.USHORT),
                ("DesignatorIndex", wt.USHORT), ("Reserved3", wt.USHORT),
                ("DataIndex", wt.USHORT), ("Reserved4", wt.USHORT)]


class _U(ctypes.Union):
    _fields_ = [("Range", _Range), ("NotRange", _NotRange)]


class HIDP_VALUE_CAPS(ctypes.Structure):
    _fields_ = [("UsagePage", wt.USHORT), ("ReportID", ctypes.c_ubyte),
                ("IsAlias", ctypes.c_ubyte), ("BitField", wt.USHORT),
                ("LinkCollection", wt.USHORT), ("LinkUsagePage", wt.USHORT),
                ("LinkUsage", wt.USHORT), ("IsRange", ctypes.c_ubyte),
                ("IsStringRange", ctypes.c_ubyte), ("IsDesignatorRange", ctypes.c_ubyte),
                ("IsAbsolute", ctypes.c_ubyte), ("HasNull", ctypes.c_ubyte),
                ("Reserved", ctypes.c_ubyte), ("BitSize", wt.USHORT),
                ("ReportCount", wt.USHORT), ("Reserved2", wt.USHORT * 5),
                ("UnitsExp", wt.ULONG), ("Units", wt.ULONG),
                ("LogicalMin", wt.LONG), ("LogicalMax", wt.LONG),
                ("PhysicalMin", wt.LONG), ("PhysicalMax", wt.LONG),
                ("u", _U)]


hid_dll.HidP_GetValueCaps.restype = wt.LONG
hid_dll.HidP_GetValueCaps.argtypes = [ctypes.c_int, ctypes.POINTER(HIDP_VALUE_CAPS),
                                      ctypes.POINTER(wt.USHORT), ctypes.c_void_p]
hid_dll.HidP_GetUsages.restype = wt.LONG
hid_dll.HidP_GetUsages.argtypes = [ctypes.c_int, wt.USHORT, wt.USHORT,
                                   ctypes.POINTER(wt.USHORT), ctypes.POINTER(wt.ULONG),
                                   ctypes.c_void_p, ctypes.c_char_p, wt.ULONG]
hid_dll.HidP_GetUsageValue.restype = wt.LONG
hid_dll.HidP_GetUsageValue.argtypes = [ctypes.c_int, wt.USHORT, wt.USHORT, wt.USHORT,
                                       ctypes.POINTER(wt.ULONG), ctypes.c_void_p,
                                       ctypes.c_char_p, wt.ULONG]

USAGE_X, USAGE_Y, USAGE_Z, USAGE_RX, USAGE_RY, USAGE_RZ = 0x30, 0x31, 0x32, 0x33, 0x34, 0x35
USAGE_HAT = 0x39
AXIS_NAME = {0x30: "X", 0x31: "Y", 0x32: "Z", 0x33: "Rx", 0x34: "Ry", 0x35: "Rz",
             0x36: "Slider", 0x37: "Dial", 0x38: "Wheel", 0x39: "Hat"}

# 用户实测的按键号 -> 名字 (joy.cpl 编号)
HAT_NAMES = {0: "↑", 1: "↗", 2: "→", 3: "↘", 4: "↓", 5: "↙",
             6: "←", 7: "↖", 0x0F: "居中"}

BTN_NAMES = {
    1: "A", 2: "B", 3: "?", 4: "X", 5: "Y", 6: "?", 7: "L1", 8: "R1",
    9: "L2", 10: "R2", 11: "?", 12: "START", 13: "?", 14: "L3(左摇杆按下)",
    15: "R3(右摇杆按下)",
}
HAT_DIRS = {0: "上", 1: "右上", 2: "右", 3: "右下", 4: "下", 5: "左下", 6: "左", 7: "左上"}


def dev_key(path):
    r"""把同一物理设备的多个接口(collection)归并成一个 key

    USB 复合设备:  \?\hid#vid_2015&pid_0102&mi_00&col01#...   -> vid_2015&pid_0102
    蓝牙 HID:      \?\hid#{00001812-..}_dev_vid&02000e_pid&3412_rev&6701_<mac>&col02#..
                   -> {00001812-..}_dev_vid&02000e_pid&3412_rev&6701_<mac>   (保留 MAC)
    """
    parts = path.split("#")
    if len(parts) < 2:
        return path
    keep = []
    for tok in parts[1].split("&"):
        if tok.lower().startswith(("mi_", "col")):
            break
        keep.append(tok)
    return "&".join(keep) if keep else parts[1]


def _is_bluetooth(path):
    p = path.lower()
    return "00001124" in p or "bthenum" in p or "bthled" in p or "bluetooth" in p


def list_device_groups():
    """把所有 HID collection 按物理设备分组, 方便区分蓝牙 / 2.4G 接收器"""
    groups, order = {}, []
    for d in H.enum_hid():
        k = dev_key(d["path"])
        if k not in groups:
            groups[k] = []
            order.append(k)
        groups[k].append(d)
    out = []
    for k in order:
        cols = groups[k]
        gp = [c for c in cols if c["usage_page"] == 1 and c["usage"] == 5]
        readable = False
        if gp:
            h = H.open_path(gp[0]["path"])
            if h:
                readable = True
                kernel32.CloseHandle(h)
        out.append({
            "key": k, "cols": cols, "pad": gp[0] if gp else None,
            "bt": _is_bluetooth(k), "readable": readable,
            "vid": cols[0]["vid"], "pid": cols[0]["pid"],
            "product": cols[0]["product"] or "(无名称)",
        })
    return out


def find_pad(kw=None):
    """kw 为空/auto 时自动选设备(2.4G/USB 优先于蓝牙); 否则按路径关键词过滤"""
    if not kw or str(kw).lower() in ("auto", "*", "any"):
        cands = [g for g in list_device_groups() if g["pad"]]
        if not cands:
            return [], []
        # 优先: 能打开的 > 2.4G/USB > 蓝牙
        cands.sort(key=lambda g: (not g["readable"], g["bt"], g["key"]))
        g = cands[0]
        tag = "蓝牙" if g["bt"] else "2.4G/USB"
        if not g["readable"]:
            print(f"[警告] {g['product']} 打不开(可能被别的程序独占)")
        print(f"[自动选择] {g['product']}  VID:PID {g['vid']:04X}:{g['pid']:04X}  ({tag})")
        for x in cands:
            if x is not g:
                print(f"           另有: {x['product']} {x['vid']:04X}:{x['pid']:04X}"
                      f" ({'蓝牙' if x['bt'] else '2.4G/USB'})"
                      + ("" if x["readable"] else " [打不开]"))
        return g["cols"], [g["pad"]]
    devs = [d for d in H.enum_hid() if str(kw).lower() in d["path"].lower()]
    if not devs:
        return [], []
    pads = [d for d in devs if d["usage_page"] == 1 and d["usage"] == 5]
    return devs, pads


def pick_active_pad(pads, probe_ms=400):
    """同一设备常有多个残留(ghost)接口, 只有一个是活的。
    逐个试读, 返回第一个真能收到数据的; 都不行则返回第一个可打开的。"""
    if not pads:
        return None
    fallback = None
    for c in pads:
        p = Pad(c)
        if not p.ok:
            continue
        if fallback is None:
            fallback = c
        n = 0
        t0 = time.time()
        while (time.time() - t0) * 1000 < probe_ms:
            if p.read(30):
                n += 1
                if n >= 3:
                    break
        p.close()
        if n >= 3:
            return c
    return fallback


# ---------------- XInput (2.4G 很可能走这条路) ----------------
class XINPUT_GAMEPAD(ctypes.Structure):
    _fields_ = [("wButtons", wt.WORD), ("bLeftTrigger", wt.BYTE),
                ("bRightTrigger", wt.BYTE), ("sThumbLX", wt.SHORT),
                ("sThumbLY", wt.SHORT), ("sThumbRX", wt.SHORT),
                ("sThumbRY", wt.SHORT)]


class XINPUT_STATE(ctypes.Structure):
    _fields_ = [("dwPacketNumber", wt.DWORD), ("Gamepad", XINPUT_GAMEPAD)]


XINPUT_BTN = {0x0001: "UP", 0x0002: "DOWN", 0x0004: "LEFT", 0x0008: "RIGHT",
              0x0010: "START", 0x0020: "BACK", 0x0040: "L3", 0x0080: "R3",
              0x0100: "LB", 0x0200: "RB", 0x1000: "A", 0x2000: "B",
              0x4000: "X", 0x8000: "Y"}


def xinput_probe():
    """检测 4 个 XInput 插槽, 返回 [(index, XINPUT_GAMEPAD)]"""
    for name in ("xinput1_4.dll", "xinput1_3.dll", "xinput9_1_0.dll"):
        try:
            xi = ctypes.WinDLL(name)
        except OSError:
            continue
        try:
            xi.XInputGetState.argtypes = [wt.DWORD, ctypes.POINTER(XINPUT_STATE)]
            xi.XInputGetState.restype = wt.DWORD
        except Exception:
            pass
        found = []
        for i in range(4):
            st = XINPUT_STATE()
            if xi.XInputGetState(i, ctypes.byref(st)) == 0:
                found.append((i, st.Gamepad))
        return found, name
    return [], None


class Pad:
    """一个 collection 的解析器"""

    def __init__(self, dev):
        self.dev = dev
        self.h = H.open_path(dev["path"])
        self.pp = ctypes.c_void_p()
        self.ok = False
        self.caps = H.HIDP_CAPS()
        self.values = []
        self.nbuttons = 0
        if not self.h:
            return
        if not hid_dll.HidD_GetPreparsedData(self.h, ctypes.byref(self.pp)):
            return
        hid_dll.HidP_GetCaps(self.pp, ctypes.byref(self.caps))
        # 取 input value caps
        ln = wt.USHORT(self.caps.NumberInputValueCaps)
        if ln.value:
            arr = (HIDP_VALUE_CAPS * ln.value)()
            if hid_dll.HidP_GetValueCaps(HIDP_INPUT, arr, ctypes.byref(ln), self.pp) == HIDP_STATUS_SUCCESS:
                self.values = [arr[i] for i in range(ln.value)]
        self.nbuttons = self.caps.NumberInputButtonCaps
        # 2.4G 接收器会积压报告, 让驱动少缓存几帧(失败也无所谓)
        try:
            hid_dll.HidD_SetNumInputBuffers(self.h, 4)
        except Exception:
            pass
        self.rep_len = self.caps.InputReportByteLength or dev["in_len"]
        self.reader = H.SyncReader(self.h, max(dev["in_len"], 64) * 8, is_handle=True)
        self.ok = True

    def close(self):
        try:
            if self.pp:
                hid_dll.HidD_FreePreparsedData(self.pp)
        except Exception:
            pass
        if getattr(self, "reader", None):
            self.reader.close()

    def describe(self):
        print(f"  UP {self.dev['usage_page']:04X}:{self.dev['usage']:04X} "
              f"in_len={self.dev['in_len']}  按钮数={self.caps.NumberInputButtonCaps} "
              f"轴数={self.caps.NumberInputValueCaps}")
        for v in self.values:
            u = v.u.NotRange.Usage if not v.IsRange else v.u.Range.UsageMin
            if v.UsagePage == 1:
                nm = AXIS_NAME.get(u, hex(u))
            elif v.UsagePage == 2:
                nm = {0xC4: "Accelerator(右扳机模拟)", 0xC5: "Brake(左扳机模拟)"}.get(u, f"Sim:{u:02X}")
            else:
                nm = f"{v.UsagePage:04X}:{u:04X}"
            print(f"    {nm:<22} bits={v.BitSize:<3} logical={v.LogicalMin}..{v.LogicalMax} "
                  f"reportID={v.ReportID}")

    def get_buttons(self, report, page=0x09):
        found = []
        for lc in range(0, 4):
            arr = (wt.USHORT * 64)()
            ln = wt.ULONG(64)
            st = hid_dll.HidP_GetUsages(HIDP_INPUT, page, lc, arr, ctypes.byref(ln),
                                        self.pp, report, len(report))
            if st == HIDP_STATUS_SUCCESS and ln.value:
                found.extend(arr[i] for i in range(ln.value))
        return sorted(set(found))

    def get_axis(self, report, usage, page=1):
        for lc in range(0, 4):
            val = wt.ULONG(0)
            st = hid_dll.HidP_GetUsageValue(HIDP_INPUT, page, lc, usage, ctypes.byref(val),
                                            self.pp, report, len(report))
            if st == HIDP_STATUS_SUCCESS:
                return val.value
        return None

    def _split(self, r):
        """2.4G 接收器会把积压的多帧粘成一个 buffer 返回, 只取最新那一帧"""
        n = self.rep_len or 0
        if not r or not n or len(r) <= n:
            return r
        if len(r) % n == 0:
            return r[-n:]          # 长度是整数倍 -> 取最后一帧(最新状态)
        return r[:n]               # 长度异常 -> 保守取第一帧

    def read(self, timeout_ms=50):
        """读一帧; timeout_ms 单位是毫秒(内部转秒, 否则会阻塞几百秒)"""
        if not self.ok:
            return None
        return self._split(self.reader.read(timeout_ms / 1000.0))

    def read_latest(self, timeout_ms=20):
        """丢弃队列中积压的旧帧, 直接返回最新状态(监控用, 无延迟)"""
        if not self.ok:
            return None
        r = self.reader.drain_last()
        if r is None:
            r = self.reader.read(timeout_ms / 1000.0)
        return self._split(r)


# ---------------- 命令 ----------------
def cmd_info(kw):
    devs, pads = find_pad(kw)
    print(f"匹配 {len(devs)} 个 collection, 其中 gamepad {len(pads)} 个\n")
    for d in devs:
        print(f"VID:PID {d['vid']:04X}:{d['pid']:04X}  in_len={d['in_len']}")
        print(f"  {d['path'][-70:]}")
    print()
    if not pads:
        print("!! 在 HID 里没找到 gamepad 接口。")
        print("   如果手柄已经开了, 那它很可能被系统识别成 XInput (Xbox 手柄)。")
        print("   请运行:  run.bat 7   (或 python g600.py scan)  做完整识别")
        return
    for p in pads:
        pad = Pad(p)
        if pad.ok:
            pad.describe()
        pad.close()


def cmd_scan(kw=None):
    """完整体检: 列出所有 HID 设备分组 + 探测 XInput 插槽"""
    show_all = str(kw).lower() in ("all", "full")
    groups = list_device_groups()
    pads = [g for g in groups if g["pad"]]
    print("=" * 62)
    print("  1) HID 设备一览 (按物理设备分组)")
    print("=" * 62)
    if not show_all:
        print(f"共 {len(groups)} 个 HID 设备, 其中 {len(pads)} 个带手柄接口"
              f" (要看全部请用: g600.py scan all)")
        groups = pads or groups
    for i, g in enumerate(groups, 1):
        tag = "蓝牙" if g["bt"] else "2.4G/USB"
        mark = ""
        if g["pad"]:
            mark = "   <== 可用手柄" if g["readable"] else "   <== 手柄接口, 打不开(被占用)"
        print(f"\n[{i}] {g['product']}   VID:PID {g['vid']:04X}:{g['pid']:04X}   [{tag}]{mark}")
        for c in g["cols"]:
            kind = {0x05: "GamePad", 0x02: "Mouse", 0x06: "Keyboard",
                    0x01: "Consumer(媒体键)", 0x80: "System"}.get(
                c["usage"] if c["usage_page"] == 1 else c["usage"], "Vendor")
            if c["usage_page"] == 0x01 and c["usage"] == 0x05:
                kind = "GamePad"
            print(f"      UP {c['usage_page']:04X}:{c['usage']:04X}  {kind:<16}"
                  f" in={c['in_len']:<3} out={c['out_len']:<3} feat={c['feat_len']}")

    print("\n" + "=" * 62)
    print("  2) XInput 探测 (被认成 Xbox 手柄时走这里, HID 里看不到)")
    print("=" * 62)
    found, dll = xinput_probe()
    if not dll:
        print("  系统里没有 xinput1_4/1_3/9_1_0.dll, 跳过")
    elif not found:
        print("  0~3 号插槽都没有设备")
    else:
        for idx, gp in found:
            btns = [n for b, n in XINPUT_BTN.items() if gp.wButtons & b]
            print(f"  [XInput #{idx}] 已连接  按键={btns or '无'}")
            print(f"     左摇杆 X={gp.sThumbLX:>6} Y={gp.sThumbLY:>6}   "
                  f"右摇杆 X={gp.sThumbRX:>6} Y={gp.sThumbRY:>6}")
            print(f"     左扳机={gp.bLeftTrigger:<3} 右扳机={gp.bRightTrigger}")

    print("\n" + "=" * 62)
    print("  结论")
    print("=" * 62)
    if found and pads:
        print("  两种接口都在 -> 手柄同时支持 HID 和 XInput, 玩游戏优先用 XInput")
    elif found:
        print("  只走 XInput -> 这是好事! 游戏原生支持, Windows 侧不用装任何软件")
        print("  但本工具的 HID 解析读不到它; 想改键位请用 Steam Input / x360ce")
    elif pads:
        print("  只走 HID (DInput) -> 用本工具, 或用 AntiMicroX / JoyToKey 映射")
    else:
        print("  两边都没有 -> 手柄没连上, 或接收器没被识别")
    print()


def cmd_watch(kw, seconds=None):
    devs, pads = find_pad(kw)
    if not pads:
        print("找不到 gamepad 接口, 手柄是否已连接?")
        return
    act = pick_active_pad(pads)
    if not act:
        print("找到手柄接口但都打不开")
        return
    pad = Pad(act)
    if not pad.ok:
        print("初始化失败")
        return
    pad.describe()
    print("\n实时解析中 (Ctrl+C 退出)\n" + "-" * 60)
    # 轴范围
    ranges = {}
    for v in pad.values:
        u = v.u.NotRange.Usage if not v.IsRange else v.u.Range.UsageMin
        if v.UsagePage == 1:
            ranges[u] = (v.LogicalMin, v.LogicalMax)
    prev_btn = set()
    prev_axes = {}
    t0 = time.time()
    while True:
        if seconds and time.time() - t0 > seconds:
            break
        rep = pad.read_latest(20)
        if rep is None:
            continue
        btns = set(pad.get_buttons(rep))
        for b in sorted(btns - prev_btn):
            print(f"  [按下] button {b:<2} -> {BTN_NAMES.get(b, '未定义')}")
        for b in sorted(prev_btn - btns):
            print(f"  [松开] button {b:<2} -> {BTN_NAMES.get(b, '未定义')}")
        prev_btn = btns
        parts = []
        for u, (lo, hi) in ranges.items():
            val = pad.get_axis(rep, u)
            if val is None:
                continue
            if u == USAGE_HAT:
                if prev_axes.get(u) != val:
                    parts.append(f"Hat={HAT_DIRS.get(val, f'空({val})')}")
                prev_axes[u] = val
            else:
                pct = round((val - lo) * 100.0 / (hi - lo)) if hi > lo else 0
                if abs(pct - prev_axes.get(u, pct)) >= 4:
                    parts.append(f"{AXIS_NAME.get(u,u)}={pct:>3}%")
                prev_axes[u] = pct
        if parts:
            print("  [轴] " + "  ".join(parts))


def cmd_learn(kw):
    devs, pads = find_pad(kw)
    if not pads:
        print("找不到 gamepad 接口")
        return
    pad = Pad(pads[0])
    if not pad.ok:
        print("初始化失败")
        return
    print("引导式按键学习。请按提示操作, 每次操作后脚本会自动记录。\n")
    mapping = {}

    def wait_change(baseline_btn, baseline_axes, label):
        """等待状态变化, 返回 (buttons, axes)"""
        print(f"  >>> 请 {label} ... ", end="", flush=True)
        t0 = time.time()
        while time.time() - t0 < 20:
            rep = pad.read(200)
            if rep is None:
                continue
            b = set(pad.get_buttons(rep))
            if b != baseline_btn:
                print(f"检测到 button {sorted(b)}")
                return b
        print("超时")
        return baseline_btn

    def baseline():
        """等 0.5s 取一帧稳定状态"""
        rep = None
        for _ in range(20):
            r = pad.read_latest(20)
            if r:
                rep = r
        if rep is None:
            return set(), {}
        axes = {}
        for v in pad.values:
            u = v.u.NotRange.Usage if not v.IsRange else v.u.Range.UsageMin
            if v.UsagePage == 1:
                axes[u] = (pad.get_axis(rep, u), v.LogicalMin, v.LogicalMax)
        return set(pad.get_buttons(rep)), axes

    b0, a0 = baseline()
    print(f"基线: buttons={sorted(b0)} axes={ {AXIS_NAME.get(k,k):v for k,v in a0.items()} }\n")

    tasks = [("单独按住 A 键", "A"), ("单独按住 B 键", "B"), ("单独按住 X 键", "X"),
             ("单独按住 Y 键", "Y"), ("按住左上 L1", "L1"), ("按住右上 R1", "R1"),
             ("按住左扳机 L2", "L2"), ("按住右扳机 R2", "R2"),
             ("按 START", "START"), ("按 BACK/SELECT 位置那个键", "BACK"),
             ("按 HOME(房子)键", "HOME"), ("按感叹号图标的键", "TURBO"),
             ("按下左摇杆(L3)", "L3"), ("按下右摇杆(R3)", "R3"),
             ("按住十字键 上", "DPAD_UP"), ("按住十字键 下", "DPAD_DOWN"),
             ("按住十字键 左", "DPAD_LEFT"), ("按住十字键 右", "DPAD_RIGHT")]
    for label, name in tasks:
        cur_b, cur_a = wait_change(b0, a0, label)
        new = sorted(cur_b - b0)
        if new:
            mapping[name] = {"buttons": new}
            print(f"      记录 {name} -> button {new}")
        else:
            mapping[name] = {"buttons": [], "note": "无按键事件(可能走了键盘/媒体通道)"}
            print(f"      记录 {name} -> 无按键事件")
        b0, a0 = baseline()
    with open("mapping.json", "w", encoding="utf-8") as f:
        json.dump(mapping, f, ensure_ascii=False, indent=2)
    print("\n已写入 mapping.json")
    print(json.dumps(mapping, ensure_ascii=False, indent=2))


def cmd_raw(kw, seconds=6):
    devs, pads = find_pad(kw)
    if not pads:
        return
    pad = Pad(pads[0])
    print("原始报告 (Ctrl+C 退出)")
    t0 = time.time()
    prev = None
    while time.time() - t0 < (seconds or 999):
        r = pad.read_latest(20)
        if r and r != prev:
            print(f"  {r.hex(' ')}   btn={sorted(pad.get_buttons(r))}")
            prev = r




# ---------------- 键盘 / 媒体键 名字表 ----------------
def kbd_name(u):
    if 0x04 <= u <= 0x1D:
        return "Key " + chr(ord('A') + u - 0x04)
    if 0x1E <= u <= 0x27:
        return "Key " + str((u - 0x1E + 1) % 10)
    tbl = {0x28: "Enter", 0x29: "Esc", 0x2A: "Backspace", 0x2B: "Tab", 0x2C: "Space",
           0x2D: "-", 0x2E: "=", 0x2F: "[", 0x30: "]", 0x31: "\\", 0x33: ";",
           0x34: "'", 0x35: "`", 0x36: ",", 0x37: ".", 0x38: "/", 0x39: "CapsLock",
           0x49: "Insert", 0x4A: "Home", 0x4B: "PageUp", 0x4C: "Delete", 0x4D: "End",
           0x4E: "PageDown", 0x4F: "Right", 0x50: "Left", 0x51: "Down", 0x52: "Up",
           0x65: "App", 0x66: "Power", 0xE0: "LCtrl", 0xE1: "LShift", 0xE2: "LAlt",
           0xE3: "LWin", 0xE4: "RCtrl", 0xE5: "RShift", 0xE6: "RAlt", 0xE7: "RWin"}
    if 0x3A <= u <= 0x45:
        return f"F{u - 0x3A + 1}"
    return f"0x{u:02X}"


CONSUMER_NAMES = {
    0x0094: "Quit", 0x0095: "Help(感叹号键, 2.4G 下才有)",
    0x0221: "AC Search", 0x0223: "AC Home(←打开浏览器)", 0x0224: "AC Back",
    0x0225: "AC Forward", 0x0226: "AC Stop", 0x0227: "AC Refresh",
    0x00CD: "Play/Pause", 0x00B5: "Next Track", 0x00B6: "Prev Track",
    0x00E2: "Mute", 0x00E9: "Volume Up", 0x00EA: "Volume Down",
    0x0183: "AL Config", 0x018A: "AL Email", 0x0192: "AL Calculator",
    0x0194: "AL My Computer", 0x022A: "AL Media Select", 0x018D: "AL Task Manager",
}

SYS_NAMES = {0x81: "System Power Down", 0x82: "System Sleep", 0x83: "System Wake",
             0x9F: "System Display Toggle"}


def cmd_all(kw, seconds=None):
    """同时监控全部 collection, 看清每个键到底走了哪条通道"""
    devs, pads = find_pad(kw)
    if not devs:
        print("找不到设备")
        return
    # 非手柄类型只挂一个; 手柄接口可能有多个镜像, 全部挂上(用路径区分状态)
    chans = []
    seen_kind = set()
    for d in devs:
        if d["in_len"] == 0:
            continue
        is_gp = d["usage_page"] == 1 and d["usage"] == 5
        if not is_gp:
            kind = (d["usage_page"], d["usage"])
            if kind in seen_kind:
                continue
            seen_kind.add(kind)
        pad = Pad(d)
        if not pad.ok:
            print(f"  [跳过] UP {d['usage_page']:02X}:{d['usage']:02X} "
                  f"in_len={d['in_len']} -> 系统独占, 应用层读不到")
            continue
        # 按键 usage page 由 collection 的 UsagePage 决定
        page = 0x09
        if pad.caps.UsagePage == 0x01 and pad.caps.Usage == 0x06:
            page = 0x07          # 键盘
        elif pad.caps.UsagePage == 0x0C:
            page = 0x0C          # 媒体键
        elif pad.caps.UsagePage == 0x01 and pad.caps.Usage == 0x02:
            page = 0x09          # 鼠标按键
        elif pad.caps.UsagePage == 0x01 and pad.caps.Usage == 0x80:
            page = 0x01          # 系统控制
        label = {0x05: "GAMEPAD", 0x02: "MOUSE", 0x06: "KEYBOARD", 0x01: "CONSUMER",
                 0x80: "SYSTEM"}.get(pad.caps.Usage, f"UP{pad.caps.UsagePage:02X}")
        chans.append((label, pad, page))
        print(f"  挂载 {label:<9} in_len={d['in_len']:<3} 按钮page=0x{page:02X}")
    if not chans:
        print("没有可读通道")
        return
    print("\n请依次按下每个键, 松开后再按下一个 (Ctrl+C 退出)\n")
    prev = {p.dev["path"]: None for _, p, _ in chans}
    t0 = time.time()
    while True:
        if seconds and time.time() - t0 > seconds:
            break
        idle = True
        for label, pad, page in chans:
            r = pad.read_latest(0)       # 非阻塞: 只取最新状态, 清掉积压旧帧
            if r is None:
                continue
            idle = False
            k = pad.dev["path"]
            btns = pad.get_buttons(r, page)
            hat = pad.get_axis(r, 0x39, 1) if label == "GAMEPAD" else None
            sig = (tuple(btns), hat)
            # 只在"按键组合/十字键方向"变化时输出, 否则摇杆漂移会一直刷屏
            if k in prev and prev[k] == sig:
                continue
            prev[k] = sig
            if label == "GAMEPAD":
                parts = [f"button{b}={BTN_NAMES.get(b,'未定义')}" for b in btns]
                if hat is not None:
                    parts.append("十字=" + HAT_NAMES.get(hat, f"0x{hat:02X}"))
                print(f"[{label}] " + (", ".join(parts) or "全部松开")
                      + f"   raw={r.hex(' ')}")
            elif label == "KEYBOARD":
                print(f"[{label}] " + (", ".join(kbd_name(b) for b in btns)
                                       or "(无按键)") + f"   raw={r.hex(' ')}")
            elif label == "CONSUMER":
                print(f"[{label}] " + (", ".join(
                    CONSUMER_NAMES.get(b, f"usage 0x{b:04X}") for b in btns)
                    or "(无按键)") + f"   raw={r.hex(' ')}")
            else:
                print(f"[{label}] " + (", ".join(
                    SYS_NAMES.get(b, f"0x{b:02X}") for b in btns)
                    or "(无按键)") + f"   raw={r.hex(' ')}")
        if idle:
            time.sleep(0.004)        # 全通道都没数据时才短睡, 保证响应够快


def cmd_calibrate(kw):
    """引导校准摇杆量程, 输出 calibration.json"""
    devs, pads = find_pad(kw)
    if not pads:
        print("找不到 gamepad")
        return
    pad = Pad(pick_active_pad(pads) or pads[0])
    cal = {}

    def sweep(label, usages, secs=8):
        print(f"\n>>> {label}")
        print(f"    准备好后按回车, 你有 {secs} 秒")
        input()
        pad.reader.flush()
        mn = {u: 9999 for u, _ in usages}
        mx = {u: -1 for u, _ in usages}
        t0 = time.time()
        seen = 0
        while time.time() - t0 < secs:
            r = pad.read(30)
            if not r:
                continue
            seen += 1
            for u, p in usages:
                v = pad.get_axis(r, u, p)
                if v is None:
                    continue
                mn[u] = min(mn[u], v)
                mx[u] = max(mx[u], v)
        print(f"    收到 {seen} 帧")
        for u, p in usages:
            if mx[u] < 0:
                print(f"    usage 0x{u:02X}: 没采到数据")
                continue
            print(f"    usage 0x{u:02X}: {mn[u]} .. {mx[u]}  (中点 {(mn[u]+mx[u])//2})")
            cal[f"{u:02X}"] = {"min": mn[u], "max": mx[u],
                               "center": (mn[u] + mx[u]) // 2}

    sweep("把左摇杆慢慢转 3 圈, 推到最大范围", [(0x30, 1), (0x31, 1)])
    sweep("把右摇杆慢慢转 3 圈, 推到最大范围", [(0x32, 1), (0x35, 1)])
    sweep("把 L2 和 R2 各按到底 3 次", [(0xC4, 2), (0xC5, 2)])
    with open("calibration.json", "w", encoding="utf-8") as f:
        json.dump(cal, f, ensure_ascii=False, indent=2)
    print("\n已写入 calibration.json")

class _Tee(object):
    """同时写到控制台和日志文件, 这样跑完可以直接把 logs 里的文件给我看"""

    def __init__(self, *streams):
        self.streams = streams

    def write(self, s):
        for st in self.streams:
            try:
                st.write(s)
                st.flush()
            except Exception:
                pass

    def flush(self):
        for st in self.streams:
            try:
                st.flush()
            except Exception:
                pass

    def isatty(self):
        return False


def _start_log(cmd):
    import os
    os.makedirs("logs", exist_ok=True)
    path = os.path.join("logs", time.strftime("%Y%m%d_%H%M%S") + "_" + cmd + ".log")
    fh = open(path, "w", encoding="utf-8")
    sys.stdout = _Tee(sys.__stdout__, fh)
    print("# 本次输出会自动保存到: " + os.path.abspath(path))
    return path


if __name__ == "__main__":
    a = sys.argv[1:]
    cmd = a[0] if a else "watch"
    kw = a[1] if len(a) > 1 and not a[1].isdigit() else None
    logpath = None
    if cmd in ("info", "watch", "raw", "learn", "all", "cal", "scan"):
        logpath = _start_log(cmd)
    try:
        if cmd == "info":
            cmd_info(kw)
        elif cmd == "scan":
            cmd_scan(kw)
        elif cmd == "watch":
            cmd_watch(kw, float(a[-1]) if a[-1].isdigit() else None)
        elif cmd == "raw":
            cmd_raw(kw)
        elif cmd == "learn":
            cmd_learn(kw)
        elif cmd == "all":
            cmd_all(kw, float(a[-1]) if a[-1].isdigit() else None)
        elif cmd == "cal":
            cmd_calibrate(kw)
        else:
            print(__doc__)
    except KeyboardInterrupt:
        print("\n已退出")
    finally:
        if logpath:
            print("\n#### 日志已保存: " + logpath)
