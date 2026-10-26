@echo off
setlocal
rem semua berkas diperbarui di folder tempat update.bat ini berada
set TARGET=%~dp0
cd /d "%TARGET%"
rem Ganti dengan repo GitHub kamu, format pemilik/nama-repo
set REPO=cloudmail91-dev/czn-patch-indonesia
set BRANCH=main

if "%REPO%"=="GANTI/NAMA-REPO" goto norepo
set WORK=%TEMP%\czn_update
rmdir /s /q "%WORK%" 2>nul
mkdir "%WORK%"

echo Mengunduh versi terbaru dari github.com/%REPO% ...
powershell -NoProfile -Command "try { $ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://github.com/%REPO%/archive/refs/heads/%BRANCH%.zip' -OutFile '%WORK%\src.zip' -UseBasicParsing; Expand-Archive '%WORK%\src.zip' '%WORK%\src' -Force } catch { Write-Host $_.Exception.Message; exit 1 }"
if errorlevel 1 goto fail
for /d %%D in ("%WORK%\src\*") do set SRC=%%D
if not defined SRC goto fail

rem terjemahan lokal yang sudah kamu edit disimpan dulu, karena data\cache.dat akan diganti
for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS=%%T
if exist data\cache.dat copy /y data\cache.dat "data\cache.sebelum-update-%TS%.dat" >nul

if not exist data mkdir data
if not exist src mkdir src
xcopy /e /y /q "%SRC%\data\*" data\ >nul
xcopy /e /y /q "%SRC%\src\*" src\ >nul
if exist "%SRC%\README.md" copy /y "%SRC%\README.md" README.md >nul

echo Mengunduh program exe dari rilis terbaru ...
rem exe dirilis di dalam CZN_Patch_PC.zip; hanya kedua exe yang diambil dari zip itu
powershell -NoProfile -Command "try { $ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://github.com/%REPO%/releases/latest/download/CZN_Patch_PC.zip' -OutFile '%WORK%\pc.zip' -UseBasicParsing; Expand-Archive '%WORK%\pc.zip' '%WORK%\pc' -Force } catch { Write-Host 'Lewati exe - zip tidak ada di rilis.'; exit 0 }; foreach ($e in 'CZN_Patch.exe','CZN_Editor.exe') { $f = Get-ChildItem '%WORK%\pc' -Recurse -Filter $e | Select-Object -First 1; if ($f) { try { Move-Item -Force $f.FullName $e } catch { Write-Host ('Lewati ' + $e + ' - sedang dipakai.') } } }"

rmdir /s /q "%WORK%" 2>nul
echo.
echo Selesai. Terjemahan lama ada di data\cache.sebelum-update-%TS%.dat
echo Jalankan CZN_Patch.exe lagi untuk memasang versi terbaru ke game.
pause
exit /b 0

:norepo
echo Edit update.bat dulu: ganti cloudmail91-dev/czn-patch-indonesia dengan repo GitHub kamu.
pause
exit /b 1

:fail
echo Gagal mengunduh. Cek koneksi internet dan nama repo di update.bat.
pause
exit /b 1
