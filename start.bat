@echo off
title HenTie
echo ============================================
echo   HenTie - starting up...
echo ============================================
echo.
echo The app will open in your browser automatically.
echo If it does not, open:  http://localhost:8080
echo.
echo To use it from your phone or another device on the
echo same network, open:    http://THIS-PC-IP:8080
echo (run `ipconfig` to find THIS-PC-IP)
echo.
echo Keep this window open while you use the app.
echo To stop it, press Ctrl+C in this window, or use Shut down
echo on the Settings page.
echo ============================================
echo.

rem The runtime bundled next to the app, else an installed Java.
set "JAVA=java"
if exist "%~dp0jre\bin\java.exe" set "JAVA=%~dp0jre\bin\java.exe"

"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
    echo Java was not found. Please install Java 21 or newer from:
    echo   https://adoptium.net/
    echo then double-click this file again.
    echo.
    pause
    exit /b 1
)

"%JAVA%" -jar "%~dp0HenTie.jar"
echo.
echo HenTie has stopped.
pause
