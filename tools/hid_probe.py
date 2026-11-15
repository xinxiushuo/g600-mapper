# -*- coding: utf-8 -*-
"""
hid_probe.py - 零依赖 Windows HID 探测/采集工具 (纯 ctypes, 不需要 hidapi)

用法:
    python hid_probe.py list                 列出所有 HID 设备
    python hid_probe.py desc  <过滤词>        打印报告描述符(解析按键位)
    python hid_probe.py watch <过滤词>        实时监控报告, 自动标记变化的 bit

过滤词可以是 VID/PID 片段 (如 3412)、设备路径片段 (如 DDC2212)、或产品名片段
"""
import ctypes
import ctypes.wintypes as wt
import sys
import time

setupapi = ctypes.WinDLL("setupapi", use_last_error=True)
hid_dll = ctypes.WinDLL("hid", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

GENERIC_READ = 0x80000000
GENERIC_WRITE = 0x40000000
FILE_SHARE_READ = 0x00000001
FILE_SHARE_WRITE = 0x00000002
OPEN_EXISTING = 3
DIGCF_PRESENT = 0x02
DIGCF_DEVICEINTERFACE = 0x10
IOCTL_HID_GET_REPORT_DESCRIPTOR = 0x000B0000
IOCTL_HID_GET_INDEXED_STRING = 0x000B0032
ERROR_NO_MORE_ITEMS = 259


class GUID(ctypes.Structure):
    _fields_ = [("Data1", wt.DWORD), ("Data2", wt.WORD), ("Data3", wt.WORD),
                ("Data4", ctypes.c_ubyte * 8)]


class SP_DEVICE_INTERFACE_DATA(ctypes.Structure):
    _fields_ = [("cbSize", wt.DWORD), ("InterfaceClassGuid", GUID),
                ("Flags", wt.DWORD), ("Reserved", ctypes.POINTER(wt.ULONG))]


class SP_DEVICE_INTERFACE_DETAIL_DATA_W(ctypes.Structure):
    _fields_ = [("cbSize", wt.DWORD), ("DevicePath", ctypes.c_wchar * 512)]


class HIDD_ATTRIBUTES(ctypes.Structure):
    _fields_ = [("Size", wt.ULONG), ("VendorID", wt.USHORT),
                ("ProductID", wt.USHORT), ("VersionNumber", wt.USHORT)]


class HIDP_CAPS(ctypes.Structure):
    _fields_ = [("Usage", wt.USHORT), ("UsagePage", wt.USHORT),
                ("InputReportByteLength", wt.USHORT),
                ("OutputReportByteLength", wt.USHORT),
                ("FeatureReportByteLength", wt.USHORT),
                ("Reserved", wt.USHORT * 17),
                ("NumberLinkCollectionNodes", wt.USHORT),
                ("NumberInputButtonCaps", wt.USHORT),
                ("NumberInputValueCaps", wt.USHORT),
                ("NumberInputDataIndices", wt.USHORT),
                ("NumberOutputButtonCaps", wt.USHORT),
                ("NumberOutputValueCaps", wt.USHORT),
                ("NumberOutputDataIndices", wt.USHORT),
                ("NumberFeatureButtonCaps", wt.USHORT),
                ("NumberFeatureValueCaps", wt.USHORT),
                ("NumberFeatureDataIndices", wt.USHORT)]


# HID 设备接口 GUID: 4D1E55B2-F16F-11CF-88CB-001111000030
HID_GUID = GUID(0x4D1E55B2, 0xF16F, 0x11CF,
                (ctypes.c_ubyte * 8)(0x88, 0xCB, 0x00, 0x11, 0x11, 0x00, 0x00, 0x30))

# --- 64 位下必须声明指针返回类型, 否则句柄会被截断成 int32 ---
setupapi.SetupDiGetClassDevsW.restype = ctypes.c_void_p
setupapi.SetupDiGetClassDevsW.argtypes = [ctypes.POINTER(GUID), ctypes.c_wchar_p,
                                          ctypes.c_void_p, wt.DWORD]
setupapi.SetupDiEnumDeviceInterfaces.restype = wt.BOOL
setupapi.SetupDiEnumDeviceInterfaces.argtypes = [ctypes.c_void_p, ctypes.c_void_p,
                                                 ctypes.POINTER(GUID), wt.DWORD,
                                                 ctypes.POINTER(SP_DEVICE_INTERFACE_DATA)]
setupapi.SetupDiGetDeviceInterfaceDetailW.restype = wt.BOOL
setupapi.SetupDiGetDeviceInterfaceDetailW.argtypes = [
    ctypes.c_void_p, ctypes.POINTER(SP_DEVICE_INTERFACE_DATA),
    ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD), ctypes.c_void_p]
setupapi.SetupDiDestroyDeviceInfoList.argtypes = [ctypes.c_void_p]
kernel32.CreateFileW.restype = ctypes.c_void_p
kernel32.CreateFileW.argtypes = [ctypes.c_wchar_p, wt.DWORD, wt.DWORD,
                                 ctypes.c_void_p, wt.DWORD, wt.DWORD, ctypes.c_void_p]
kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
# CancelIoEx 用来打断阻塞中的同步 ReadFile, 否则关句柄时会死锁
try:
    kernel32.CancelIoEx.restype = wt.BOOL
    kernel32.CancelIoEx.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
except AttributeError:
    pass
kernel32.ReadFile.argtypes = [ctypes.c_void_p, ctypes.c_void_p, wt.DWORD,
                              ctypes.POINTER(wt.DWORD), ctypes.c_void_p]
hid_dll.HidD_GetAttributes.restype = wt.BOOL
hid_dll.HidD_GetAttributes.argtypes = [ctypes.c_void_p, ctypes.POINTER(HIDD_ATTRIBUTES)]
# 减小驱动端的输入缓冲, 避免 2.4G 接收器把多帧粘在一起返回
try:
    hid_dll.HidD_SetNumInputBuffers.restype = wt.BOOL
    hid_dll.HidD_SetNumInputBuffers.argtypes = [ctypes.c_void_p, wt.ULONG]
except AttributeError:
    pass
hid_dll.HidD_GetPreparsedData.restype = wt.BOOL
hid_dll.HidD_GetPreparsedData.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)]
hid_dll.HidP_GetCaps.restype = wt.LONG
hid_dll.HidP_GetCaps.argtypes = [ctypes.c_void_p, ctypes.POINTER(HIDP_CAPS)]


def _get_string(handle, fn):
    buf = ctypes.create_unicode_buffer(256)
    try:
        if fn(handle, buf, 256):
            return buf.value
    except Exception:
        pass
    return ""


def enum_hid():
    """返回所有已连接 HID 设备的 dict 列表"""
    out = []
    hdev = setupapi.SetupDiGetClassDevsW(ctypes.byref(HID_GUID), None, None,
                                         DIGCF_PRESENT | DIGCF_DEVICEINTERFACE)
    if not hdev or hdev in (-1, 0xFFFFFFFFFFFFFFFF):
        return out
    try:
        idx = 0
        while True:
            iface = SP_DEVICE_INTERFACE_DATA()
            iface.cbSize = ctypes.sizeof(SP_DEVICE_INTERFACE_DATA)
            if not setupapi.SetupDiEnumDeviceInterfaces(hdev, None, ctypes.byref(HID_GUID),
                                                        idx, ctypes.byref(iface)):
                break
            idx += 1
            detail = SP_DEVICE_INTERFACE_DETAIL_DATA_W()
            detail.cbSize = 8 if ctypes.sizeof(ctypes.c_void_p) == 8 else 6
            need = wt.DWORD(0)
            setupapi.SetupDiGetDeviceInterfaceDetailW(hdev, ctypes.byref(iface),
                                                      ctypes.byref(detail),
                                                      ctypes.sizeof(detail),
                                                      ctypes.byref(need), None)
            path = detail.DevicePath
            h = kernel32.CreateFileW(path, GENERIC_READ | GENERIC_WRITE,
                                     FILE_SHARE_READ | FILE_SHARE_WRITE, None,
                                     OPEN_EXISTING, 0, None)
            if not h or h in (-1, 0xFFFFFFFFFFFFFFFF):
                h = kernel32.CreateFileW(path, 0, FILE_SHARE_READ | FILE_SHARE_WRITE,
                                         None, OPEN_EXISTING, 0, None)
            if not h or h in (-1, 0xFFFFFFFFFFFFFFFF):
                continue
            try:
                attr = HIDD_ATTRIBUTES()
                attr.Size = ctypes.sizeof(HIDD_ATTRIBUTES)
                hid_dll.HidD_GetAttributes(h, ctypes.byref(attr))
                caps = HIDP_CAPS()
                pp = ctypes.c_void_p()
                usage = usage_page = 0
                in_len = out_len = feat_len = 0
                if hid_dll.HidD_GetPreparsedData(h, ctypes.byref(pp)):
                    hid_dll.HidP_GetCaps(pp, ctypes.byref(caps))
                    usage, usage_page = caps.Usage, caps.UsagePage
                    in_len = caps.InputReportByteLength
                    out_len = caps.OutputReportByteLength
                    feat_len = caps.FeatureReportByteLength
                    hid_dll.HidD_FreePreparsedData(pp)
                out.append({
                    "path": path,
                    "vid": attr.VendorID, "pid": attr.ProductID,
                    "ver": attr.VersionNumber,
                    "product": _get_string(h, hid_dll.HidD_GetProductString),
                    "manufacturer": _get_string(h, hid_dll.HidD_GetManufacturerString),
                    "serial": _get_string(h, hid_dll.HidD_GetSerialNumberString),
                    "usage_page": usage_page, "usage": usage,
                    "in_len": in_len, "out_len": out_len, "feat_len": feat_len,
                })
            finally:
                kernel32.CloseHandle(h)
    finally:
        setupapi.SetupDiDestroyDeviceInfoList(hdev)
    return out


class OVERLAPPED(ctypes.Structure):
    _fields_ = [("Internal", ctypes.c_void_p), ("InternalHigh", ctypes.c_void_p),
                ("Offset", wt.DWORD), ("OffsetHigh", wt.DWORD),
                ("hEvent", ctypes.c_void_p)]


FILE_FLAG_OVERLAPPED = 0x40000000
ERROR_IO_PENDING = 997
WAIT_OBJECT_0 = 0
WAIT_TIMEOUT = 258

kernel32.CreateEventW.restype = ctypes.c_void_p
kernel32.CreateEventW.argtypes = [ctypes.c_void_p, wt.BOOL, wt.BOOL, ctypes.c_wchar_p]
kernel32.WaitForSingleObject.restype = wt.DWORD
kernel32.WaitForSingleObject.argtypes = [ctypes.c_void_p, wt.DWORD]
kernel32.GetOverlappedResult.restype = wt.BOOL
kernel32.GetOverlappedResult.argtypes = [ctypes.c_void_p, ctypes.POINTER(OVERLAPPED),
                                         ctypes.POINTER(wt.DWORD), wt.BOOL]
kernel32.CancelIo.argtypes = [ctypes.c_void_p]


def open_path_async(path):
    """以 OVERLAPPED 方式打开, 之后 read_report 可带超时"""
    h = kernel32.CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                             None, OPEN_EXISTING, FILE_FLAG_OVERLAPPED, None)
    if not h or h in (-1, 0xFFFFFFFFFFFFFFFF):
        return None
    return h


def read_report(h, size, timeout_ms=300):
    """返回 bytes 或 None(超时/无数据). 句柄必须来自 open_path_async"""
    ov = OVERLAPPED()
    ov.hEvent = kernel32.CreateEventW(None, True, False, None)
    if not ov.hEvent:
        return None
    buf = (ctypes.c_ubyte * size)()
    n = wt.DWORD(0)
    try:
        if not kernel32.ReadFile(h, buf, size, ctypes.byref(n), ctypes.byref(ov)):
            err = kernel32.GetLastError()
            if err != ERROR_IO_PENDING:
                return None
            if kernel32.WaitForSingleObject(ov.hEvent, timeout_ms) != WAIT_OBJECT_0:
                kernel32.CancelIo(h)
                return None
        if not kernel32.GetOverlappedResult(h, ctypes.byref(ov), ctypes.byref(n), False):
            return None
        return bytes(buf[:n.value]) if n.value else None
    finally:
        kernel32.CloseHandle(ov.hEvent)


import queue
import threading


class SyncReader:
    """蓝牙 HID 不支持 OVERLAPPED 读 -> 用后台线程阻塞读 + 队列, 对外提供超时读"""

    def __init__(self, path_or_handle, size, is_handle=False):
        self.h = path_or_handle if is_handle else open_path(path_or_handle)
        self.size = size
        self.q = queue.Queue(maxsize=512)
        self.alive = self.h is not None
        self.err = None
        if self.alive:
            t = threading.Thread(target=self._run, daemon=True)
            t.start()

    def _run(self):
        buf = (ctypes.c_ubyte * self.size)()
        n = wt.DWORD(0)
        while True:
            try:
                if not kernel32.ReadFile(self.h, buf, self.size, ctypes.byref(n), None):
                    self.err = kernel32.GetLastError()
                    break
            except Exception as e:      # 句柄被关闭时会抛
                self.err = e
                break
            if n.value:
                try:
                    self.q.put(bytes(buf[:n.value]), timeout=1)
                except queue.Full:
                    pass

    def read(self, timeout=0.3):
        try:
            return self.q.get(timeout=timeout)
        except queue.Empty:
            return None

    def drain_last(self, max_items=256):
        """取走队列里所有待处理帧, 只返回最后一帧(非阻塞) —— 消除积压带来的延迟"""
        last = None
        for _ in range(max_items):
            try:
                last = self.q.get_nowait()
            except queue.Empty:
                break
        return last

    def flush(self):
        while not self.q.empty():
            try:
                self.q.get_nowait()
            except queue.Empty:
                break

    def close(self):
        self.alive = False
        if self.h:
            try:
                # 后台线程可能正阻塞在 ReadFile 上, 先取消 I/O 再关句柄
                kernel32.CancelIoEx(self.h, None)
            except Exception:
                pass
            time.sleep(0.03)
            try:
                kernel32.CloseHandle(self.h)
            except Exception:
                pass
            self.h = None


def open_path(path, read_only=True):
    access = GENERIC_READ if read_only else (GENERIC_READ | GENERIC_WRITE)
    h = kernel32.CreateFileW(path, access, FILE_SHARE_READ | FILE_SHARE_WRITE,
                             None, OPEN_EXISTING, 0, None)
    if not h or h in (-1, 0xFFFFFFFFFFFFFFFF):
        return None
    return h


def get_report_descriptor(path):
    """IOCTL 读取完整报告描述符"""
    h = open_path(path, read_only=True)
    if not h:
        return None
    try:
        buf = (ctypes.c_ubyte * 4096)()
        ret = wt.DWORD(0)
        ok = kernel32.DeviceIoControl(h, IOCTL_HID_GET_REPORT_DESCRIPTOR,
                                      None, 0, buf, 4096,
                                      ctypes.byref(ret), None)
        if not ok:
            return None
        return bytes(buf[:ret.value])
    finally:
        kernel32.CloseHandle(h)


# ---------------- 报告描述符简易解析 ----------------
USAGE_PAGE_NAMES = {0x01: "GenericDesktop", 0x09: "Button", 0x0C: "Consumer",
                    0x0B: "Telephony", 0xFF00: "VendorFF00", 0xFF01: "VendorFF01",
                    0xFF02: "VendorFF02", 0xFFA0: "VendorFFA0"}
GD_NAMES = {0x30: "X", 0x31: "Y", 0x32: "Z", 0x33: "Rx", 0x34: "Ry", 0x35: "Rz",
            0x36: "Slider", 0x37: "Dial", 0x38: "Wheel", 0x39: "Hat switch",
            0x80: "SystemControl", 0x81: "SystemPowerDown"}


def parse_descriptor(desc):
    """返回 (items, buttons) ; items 是字段列表, buttons 是按键总位数"""
    items = []          # (usage_page, usage, bit_offset, size, count)
    bit_in = 0
    total_buttons = 0
    usage_page = 0
    usages = []
    report_size = 0
    report_count = 0
    i = 0
    n = len(desc)
    logical_min = 0
    while i < n:
        prefix = desc[i]
        if prefix == 0xFE:      # long item
            size = desc[i + 1] if i + 1 < n else 0
            i += 3 + size
            continue
        bSize = prefix & 0x03
        bType = (prefix >> 2) & 0x03
        bTag = (prefix >> 4) & 0x0F
        size = 4 if bSize == 3 else bSize
        data = desc[i + 1:i + 1 + size]
        i += 1 + size
        val = 0
        for k, b in enumerate(data):
            val |= b << (8 * k)
        if bType == 1:      # Global
            if bTag == 0x0:      # Usage Page
                usage_page = val
            elif bTag == 0x7:    # Report Size
                report_size = val
            elif bTag == 0x8:    # Report ID
                bit_in = 0
            elif bTag == 0x9:    # Report Count
                report_count = val
            elif bTag == 0x1:
                logical_min = val
        elif bType == 2:    # Local
            if bTag == 0x0:
                usages.append(val)
        elif bType == 0:    # Main
            if bTag == 0x8:      # Input
                for c in range(report_count):
                    u = usages[c] if c < len(usages) else (usages[-1] if usages else 0)
                    if usage_page == 0x09:
                        total_buttons += 1
                        items.append(("button", u, bit_in, 1))
                    else:
                        items.append((USAGE_PAGE_NAMES.get(usage_page, hex(usage_page)),
                                      GD_NAMES.get(u, hex(u)), bit_in, report_size))
                    bit_in += report_size
                usages = []
            elif bTag == 0x9:    # Output
                bit_in += report_size * report_count
                usages = []
            elif bTag == 0xB:    # Feature
                usages = []
            elif bTag == 0xC:    # End Collection
                usages = []
            else:
                usages = []
    return items, total_buttons


def decode_field(desc, byte_off, bit_off, length):
    pass


def read_bit(data, bit_index):
    """bit_index: 从 report 的第 1 个字节(跳过 report ID 之后? 不跳) 起算"""
    bi = bit_index
    byte = data[bi // 8] if bi // 8 < len(data) else 0
    return (byte >> (bi % 8)) & 1


def match(dev, kw):
    s = (f"{dev['vid']:04X}:{dev['pid']:04X} {dev['product']} "
         f"{dev['path']} {dev['manufacturer']}").lower()
    return kw.lower() in s


def cmd_list():
    devs = enum_hid()
    print(f"共 {len(devs)} 个 HID 接口\n")
    for d in devs:
        print(f"VID:PID {d['vid']:04X}:{d['pid']:04X}  rev {d['ver']:04X}  "
              f"UP {d['usage_page']:04X}:{d['usage']:04X}  "
              f"len in/out/feat {d['in_len']}/{d['out_len']}/{d['feat_len']}")
        print(f"  name: {d['product']!r}  manu: {d['manufacturer']!r}  sn: {d['serial']!r}")
        print(f"  path: {d['path']}")
        print()


def cmd_desc(kw):
    for d in enum_hid():
        if not match(d, kw):
            continue
        print("=" * 78)
        print(f"VID:PID {d['vid']:04X}:{d['pid']:04X}  UP {d['usage_page']:04X}:{d['usage']:04X}"
              f"  {d['product']!r}")
        print(d["path"])
        desc = get_report_descriptor(d["path"])
        if not desc:
            print("  !! 读不到报告描述符\n")
            continue
        print(f"  descriptor {len(desc)} bytes")
        print("  raw:", desc.hex())
        items, nbtn = parse_descriptor(desc)
        print(f"  按键总数: {nbtn}")
        for it in items:
            if it[0] == "button":
                print(f"    button #{it[1]:<2}  bit {it[2]:>3}  (byte {it[2]//8}, "
                      f"bit {it[2]%8})")
            else:
                print(f"    {it[0]}.{it[1]}  bit {it[2]:>3}  size {it[3]}")
        print()


def cmd_watch(kw, seconds=None):
    targets = [d for d in enum_hid() if match(d, kw) and d["in_len"] > 0]
    if not targets:
        print("没找到匹配的设备")
        return
    # 优先 game controller (UsagePage 0x01, Usage 0x05)
    targets.sort(key=lambda d: 0 if (d["usage_page"] == 1 and d["usage"] == 5) else 1)
    t = targets[0]
    print(f"监控: {t['product']!r} UP {t['usage_page']:04X}:{t['usage']:04X} "
          f"in_len={t['in_len']}\n按下按键观察输出; Ctrl+C 退出\n")
    h = open_path_async(t["path"])
    if not h:
        print("打开失败")
        return
    size = max(t["in_len"], 64)
    prev = None
    t0 = time.time()
    try:
        while True:
            if seconds and time.time() - t0 > seconds:
                break
            data = read_report(h, size, 250)
            if data is None:
                continue
            if prev is not None and data != prev:
                diffs = []
                for bi in range(len(data) * 8):
                    if read_bit(data, bi) != read_bit(prev, bi):
                        diffs.append((bi, read_bit(data, bi)))
                if diffs:
                    s = " ".join(f"bit{b}={'DOWN' if v else 'up'}" for b, v in diffs)
                    print(f"{data.hex(' ')}\n   -> {s}")
            prev = data
    except KeyboardInterrupt:
        pass
    finally:
        kernel32.CloseHandle(h)


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        cmd_list()
    elif args[0] == "list":
        cmd_list()
    elif args[0] == "desc":
        cmd_desc(args[1] if len(args) > 1 else "")
    elif args[0] == "watch":
        cmd_watch(args[1] if len(args) > 1 else "",
                  float(args[2]) if len(args) > 2 else None)
    else:
        print(__doc__)
