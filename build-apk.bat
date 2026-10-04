@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title Сборка Courier Analytics Pro

set "ROOT=%~dp0"
set "TC=%ROOT%.toolchain"
set "SDK=%TC%\android-sdk"

echo ============================================
echo   Courier Analytics Pro - сборка APK
echo ============================================
echo.

if not exist "%ROOT%app\build.gradle.kts" (
  echo [ОШИБКА] Положите этот файл в корень проекта, рядом с settings.gradle.kts
  goto :fail
)

powershell -NoProfile -Command "if ('%ROOT%' -match '[^\x00-\x7F]') { exit 1 } else { exit 0 }"
if errorlevel 1 (
  echo [ВНИМАНИЕ] В пути к проекту есть русские буквы или спецсимволы:
  echo   %ROOT%
  echo Android-сборка с таким путём часто падает. Лучше переместить папку, например в C:\CourierApp
  echo.
  choice /M "Всё равно продолжить"
  if errorlevel 2 goto :fail
)

if not exist "%TC%" mkdir "%TC%"

rem ---------- 1. JDK 17 ----------
if exist "%TC%\jdk\jdk-17*" goto :jdk_ready
echo [1/4] Скачиваю JDK 17 ^(один раз, около 180 МБ^)...
call :download "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" "%TC%\jdk.zip"
if errorlevel 1 goto :fail
mkdir "%TC%\jdk"
tar -xf "%TC%\jdk.zip" -C "%TC%\jdk"
if errorlevel 1 goto :fail
del "%TC%\jdk.zip"
:jdk_ready
for /d %%D in ("%TC%\jdk\jdk-17*") do set "JAVA_HOME=%%~fD"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ОШИБКА] JDK не найден в "%TC%\jdk"
  goto :fail
)
echo [1/4] JDK готов.

rem ---------- 2. Android SDK ----------
if exist "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" goto :cmd_ready
echo [2/4] Скачиваю Android command-line tools...
call :download "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" "%TC%\cmdtools.zip"
if errorlevel 1 goto :fail
if exist "%TC%\cmdtmp" rmdir /s /q "%TC%\cmdtmp"
mkdir "%TC%\cmdtmp"
tar -xf "%TC%\cmdtools.zip" -C "%TC%\cmdtmp"
if errorlevel 1 goto :fail
if not exist "%SDK%\cmdline-tools" mkdir "%SDK%\cmdline-tools"
move "%TC%\cmdtmp\cmdline-tools" "%SDK%\cmdline-tools\latest" >nul
if errorlevel 1 goto :fail
rmdir /s /q "%TC%\cmdtmp"
del "%TC%\cmdtools.zip"
:cmd_ready

if exist "%SDK%\platforms\android-35" if exist "%SDK%\build-tools\34.0.0" goto :sdk_ready
echo [2/4] Устанавливаю Android SDK 35 ^(один раз, около 200-400 МБ^)...
(for /l %%i in (1,1,40) do @echo y) | "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="%SDK%" --licenses >nul
"%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="%SDK%" "platform-tools" "platforms;android-35" "build-tools;34.0.0"
if errorlevel 1 goto :fail
:sdk_ready
echo [2/4] Android SDK готов.

rem ---------- 3. Gradle ----------
if exist "%TC%\gradle\gradle-8.9" goto :gradle_ready
echo [3/4] Скачиваю Gradle 8.9...
call :download "https://services.gradle.org/distributions/gradle-8.9-bin.zip" "%TC%\gradle.zip"
if errorlevel 1 goto :fail
mkdir "%TC%\gradle"
tar -xf "%TC%\gradle.zip" -C "%TC%\gradle"
if errorlevel 1 goto :fail
del "%TC%\gradle.zip"
:gradle_ready
echo [3/4] Gradle готов.

rem ---------- 4. Сборка ----------
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "SDKFWD=%SDK:\=/%"
> "%ROOT%local.properties" echo sdk.dir=%SDKFWD%

echo [4/4] Собираю APK ^(первый раз 5-10 минут, нужен интернет^)...
echo.
call "%TC%\gradle\gradle-8.9\bin\gradle.bat" assembleDebug --no-daemon
if errorlevel 1 goto :fail

set "APK=%ROOT%app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
  echo [ОШИБКА] Сборка завершилась, но APK не найден.
  goto :fail
)
copy /y "%APK%" "%ROOT%CourierAnalyticsPro.apk" >nul

echo.
echo ============================================
echo   ГОТОВО!
echo   %ROOT%CourierAnalyticsPro.apk
echo ============================================
echo Скопируйте файл на телефон и установите его.
explorer /select,"%ROOT%CourierAnalyticsPro.apk"
pause
exit /b 0

:download
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri '%~1' -OutFile '%~2'"
exit /b %errorlevel%

:fail
echo.
echo [!] Сборка не удалась. Прокрутите окно вверх и посмотрите первую ошибку.
echo     Если ошибка про сеть - проверьте интернет/VPN и запустите файл снова:
echo     скачанное не загружается повторно.
pause
exit /b 1
