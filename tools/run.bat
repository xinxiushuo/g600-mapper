@echo off
chcp 65001 >nul
cd /d "%~dp0"
title G600 Pad Tool

rem ---- 优先用 WorkBuddy 自带解释器(避免 Windows 商店的 python 存根弹窗), 再退回系统 python ----
set "PY="
for /d %%d in ("%USERPROFILE%\.workbuddy\binaries\python\versions\3.*") do set "PY=%%d\python.exe"
if not defined PY set "PY=python"
"%PY%" -c "import sys" >nul 2>nul
if errorlevel 1 set "PY=python"
"%PY%" -c "import sys" >nul 2>nul
if errorlevel 1 (
    echo.
    echo  [!] 没有找到可用的 Python
    echo      请先安装 Python 3.8 以上版本, 安装时勾选 Add to PATH
    echo.
    pause
    exit /b 1
)

if not "%~1"=="" goto byarg

:menu
cls
echo ==============================================
echo   新游 G600 手柄 诊断工具
echo ==============================================
echo.
echo   1. 设备信息
echo   2. 全通道监控 - 查 BACK / 感叹号 / HOME
echo   3. 实时解析 - 摇杆/按键实时数值
echo   4. 按键学习 - 生成 mapping.json
echo   5. 摇杆校准 - 生成 calibration.json
echo   6. 原始数据
echo   7. 设备识别 - 换蓝牙/2.4G 后先跑这个
echo   0. 退出
echo.
echo   当前解释器: %PY%
echo.
set "c="
set /p c=请输入序号: 

if "%c%"=="1" goto c1
if "%c%"=="2" goto c2
if "%c%"=="3" goto c3
if "%c%"=="4" goto c4
if "%c%"=="5" goto c5
if "%c%"=="6" goto c6
if "%c%"=="7" goto c7
if "%c%"=="0" exit /b 0
goto menu

:byarg
if "%~1"=="1" "%PY%" -u g600.py info
if "%~1"=="2" "%PY%" -u g600.py all
if "%~1"=="3" "%PY%" -u g600.py watch
if "%~1"=="4" "%PY%" -u g600.py learn
if "%~1"=="5" "%PY%" -u g600.py cal
if "%~1"=="6" "%PY%" -u g600.py raw
if "%~1"=="7" "%PY%" -u g600.py scan
exit /b 0

:c1
"%PY%" -u g600.py info
goto done

:c2
"%PY%" -u g600.py all
goto done

:c3
"%PY%" -u g600.py watch
goto done

:c4
"%PY%" -u g600.py learn
goto done

:c5
"%PY%" -u g600.py cal
goto done

:c6
"%PY%" -u g600.py raw
goto done

:c7
"%PY%" -u g600.py scan
goto done

:done
echo.
pause
goto menu
