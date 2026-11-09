@echo off
rem Puts the two GitHub secrets the build needs on the clipboard, one at a time,
rem and opens the GitHub page where they are added. Nothing is written to disk.
cd /d "%~dp0"
set "SECRETS_URL=https://github.com/demirajvazi10-max/UltraTalkingPulse/settings/secrets/actions/new"

if not exist "app\src\main\res\xml\presage_services.xml" (
  echo Nema fajla app\src\main\res\xml\presage_services.xml. Prvo njega stavi na mesto.
  pause
  exit /b 1
)
if not exist "%USERPROFILE%\.android\debug.keystore" (
  echo Nema debug kljuca u %USERPROFILE%\.android\debug.keystore. Prvo uradi gradlew assembleDebug.
  pause
  exit /b 1
)

echo.
echo KORAK 1 od 2
echo Sadrzaj presage_services.xml je sada u clipboardu.
echo Na GitHub stranici koja se otvara: u polje Name upisi PRESAGE_SERVICES_XML,
echo u polje Secret nalepi sa Ctrl+V, pa pritisni Add secret.
powershell -NoProfile -Command "Get-Content -Raw 'app\src\main\res\xml\presage_services.xml' | Set-Clipboard"
start "" "%SECRETS_URL%"
echo Kad zavrsis, pritisni bilo koji taster ovde.
pause >nul

echo.
echo KORAK 2 od 2
echo Debug kljuc (base64) je sada u clipboardu.
echo Na GitHub stranici: u polje Name upisi DEBUG_KEYSTORE_BASE64,
echo u polje Secret nalepi sa Ctrl+V, pa pritisni Add secret.
powershell -NoProfile -Command "[Convert]::ToBase64String([IO.File]::ReadAllBytes('%USERPROFILE%\.android\debug.keystore')) | Set-Clipboard"
start "" "%SECRETS_URL%"
echo Kad zavrsis, pritisni bilo koji taster ovde.
pause >nul

powershell -NoProfile -Command "Set-Clipboard -Value ' '"
echo.
echo Gotovo. Clipboard je ociscen. Sledeci push na GitHub pravi APK koji radi sa Presage-om.
pause
