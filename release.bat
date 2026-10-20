@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
cd /d "%~dp0"

rem ============================================================
rem Phát hành phiên bản mới: tăng version, build, commit, tag, push, gh release.
rem Mỗi lần chạy tự tăng số cuối của versionName (1.2.4 -> 1.2.5) và versionCode (+1).
rem Cách dùng: bấm đúp release.bat hoặc chạy .\release.bat trong terminal.
rem ============================================================

set GRADLE_FILE=app\build.gradle.kts
set APK_PATH=app\build\outputs\apk\release\app-release.apk

echo.
echo [1/8] Kiểm tra công cụ cần thiết
where git >nul 2>nul || (echo   LỖI: chưa cài git & goto :loi)
rem Terminal mở trước khi cài gh thì PATH chưa có gh, thêm thư mục cài mặc định của gh
where gh >nul 2>nul || set "PATH=%PATH%;C:\Program Files\GitHub CLI"
where gh >nul 2>nul || (echo   LỖI: chưa cài GitHub CLI, chạy: winget install --id GitHub.cli & goto :loi)
gh auth status >nul 2>nul || (echo   LỖI: chưa đăng nhập gh, chạy: gh auth login & goto :loi)
echo   OK: git, gh, đã đăng nhập GitHub

rem Gradle cần JDK, nếu JAVA_HOME chưa đúng thì dùng JDK đi kèm Android Studio
if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (echo   LỖI: không tìm thấy JDK, hãy đặt JAVA_HOME & goto :loi)
echo   JDK: %JAVA_HOME%

echo.
echo [2/8] Đọc phiên bản hiện tại
for /f "tokens=3" %%a in ('findstr /c:"versionCode = " %GRADLE_FILE%') do set OLD_CODE=%%a
for /f "tokens=3" %%a in ('findstr /c:"versionName = " %GRADLE_FILE%') do set OLD_NAME=%%a
set OLD_NAME=%OLD_NAME:"=%
if "%OLD_CODE%"=="" (echo   LỖI: không đọc được versionCode & goto :loi)
if "%OLD_NAME%"=="" (echo   LỖI: không đọc được versionName & goto :loi)
echo   Hiện tại: versionName=%OLD_NAME%, versionCode=%OLD_CODE%

echo.
echo [3/8] Tính phiên bản mới
for /f "tokens=1-3 delims=." %%a in ("%OLD_NAME%") do (
    set MAJOR=%%a
    set MINOR=%%b
    set PATCH=%%c
)
set /a NEW_PATCH=%PATCH%+1
set /a NEW_CODE=%OLD_CODE%+1
set NEW_NAME=%MAJOR%.%MINOR%.%NEW_PATCH%
set TAG=v%NEW_NAME%
echo   Mới: versionName=%NEW_NAME%, versionCode=%NEW_CODE%, tag=%TAG%

rem Không cho chạy nếu tag đã tồn tại (local hoặc trên GitHub)
git rev-parse -q --verify "refs/tags/%TAG%" >nul 2>nul && (echo   LỖI: tag %TAG% đã tồn tại & goto :loi)

echo.
echo   Các file đang thay đổi, sẽ được đưa vào commit release:
git status --short
echo.
set /p NOTES=  Nhập mô tả bản phát hành (để trống để GitHub tự sinh từ commit):
set /p XAC_NHAN=  Phát hành %TAG%? (y/n):
if /i not "%XAC_NHAN%"=="y" (echo   Đã hủy, chưa thay đổi gì & goto :ket)

echo.
echo [4/8] Ghi phiên bản mới vào %GRADLE_FILE%
call :ghi_phien_ban %NEW_CODE% %NEW_NAME%
echo   OK: đã ghi %NEW_NAME% (%NEW_CODE%)

echo.
echo [5/8] Build APK release (mất khoảng 1 phút)
if exist "%APK_PATH%" del "%APK_PATH%"
call gradlew.bat assembleRelease
if errorlevel 1 (
    echo   LỖI: build thất bại, trả phiên bản về %OLD_NAME%
    call :ghi_phien_ban %OLD_CODE% %OLD_NAME%
    goto :loi
)
if not exist "%APK_PATH%" (
    echo   LỖI: không thấy %APK_PATH%, có thể keystore chưa cấu hình trong local.properties nên APK chưa được ký
    call :ghi_phien_ban %OLD_CODE% %OLD_NAME%
    goto :loi
)
for %%f in ("%APK_PATH%") do echo   OK: %%~ff, %%~zf bytes

echo.
echo [6/8] Commit và tạo tag %TAG%
git add -A
git commit -m "release %NEW_NAME%"
if errorlevel 1 (echo   LỖI: commit thất bại & goto :loi)
git tag %TAG%
if errorlevel 1 (echo   LỖI: tạo tag thất bại & goto :loi)
echo   OK: đã commit và tag %TAG%

echo.
echo [7/8] Push lên GitHub
git push origin HEAD
if errorlevel 1 (echo   LỖI: push code thất bại & goto :loi)
git push origin %TAG%
if errorlevel 1 (echo   LỖI: push tag thất bại & goto :loi)
echo   OK: đã push code và tag

echo.
echo [8/8] Tạo GitHub Release và đính kèm APK
if "%NOTES%"=="" (
    gh release create %TAG% "%APK_PATH%" --title "%TAG%" --generate-notes
) else (
    gh release create %TAG% "%APK_PATH%" --title "%TAG%" --notes "%NOTES%"
)
if errorlevel 1 (echo   LỖI: tạo release thất bại, tag đã push nên có thể tạo tay trên web & goto :loi)

echo.
echo ============================================================
echo   HOÀN TẤT: đã phát hành %TAG% (versionCode %NEW_CODE%)
echo   https://github.com/truonghaui0910/autoOrder/releases/tag/%TAG%
echo ============================================================
goto :ket

:loi
echo.
echo ============================================================
echo   DỪNG: có lỗi ở bước phía trên, xem thông báo LỖI
echo ============================================================
endlocal
pause
exit /b 1

:ket
endlocal
pause
exit /b 0

rem Ghi versionCode và versionName vào build.gradle.kts, tham số: %1 = versionCode, %2 = versionName
rem Dùng chung cho lúc tăng phiên bản và lúc trả về bản cũ khi build lỗi
:ghi_phien_ban
set GHI_CODE=%1
set GHI_NAME=%2
powershell -NoProfile -Command "$f = Join-Path (Get-Location) $env:GRADLE_FILE; $t = [IO.File]::ReadAllText($f); $t = $t -replace 'versionCode = \d+', ('versionCode = ' + $env:GHI_CODE); $t = $t -replace 'versionName = [^\r\n]+', ('versionName = ' + [char]34 + $env:GHI_NAME + [char]34); [IO.File]::WriteAllText($f, $t)"
exit /b 0
