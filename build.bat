@echo off
setlocal enabledelayedexpansion
title SIREN Home Proxy - Build Debug APK

:start_build
echo.
echo  ====================================================
echo   SIREN Home Proxy - Build Debug APK
echo  ====================================================
echo.
echo  Starting Android Gradle build process...
echo.

:: Use Android Studio JBR as configured on machine
if exist "F:\Program Files\Android\Android Studio\jbr" (
    set "JAVA_HOME=F:\Program Files\Android\Android Studio\jbr"
    set "PATH=F:\Program Files\Android\Android Studio\jbr\bin;!PATH!"
)

:: Redirect Gradle cache to F: drive if available to avoid C: drive out of space
if exist "F:\" (
    set "GRADLE_USER_HOME=F:\.gradle"
)

:: Auto-detect Android SDK location
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else if exist "F:\Android\Sdk" (
        set "ANDROID_HOME=F:\Android\Sdk"
    ) else if exist "F:\Android\sdk" (
        set "ANDROID_HOME=F:\Android\sdk"
    ) else if exist "C:\Android\Sdk" (
        set "ANDROID_HOME=C:\Android\Sdk"
    )
)
if defined ANDROID_HOME (
    set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
)

:: Ensure debug.keystore exists
if not exist ".\debug.keystore" (
    if exist ".\debug.keystore.base64" (
        certutil -decode ".\debug.keystore.base64" ".\debug.keystore" >nul 2>&1
    ) else if exist "%USERPROFILE%\.android\debug.keystore" (
        copy /y "%USERPROFILE%\.android\debug.keystore" ".\debug.keystore" >nul 2>&1
    )
)

:: Use 'call' so the script continues running after gradlew finishes
call .\gradlew.bat assembleDebug --no-daemon --no-watch-fs

echo.
echo  ====================================================
echo   Build process completed.
echo  ====================================================
echo.

:ask_restart
set "RESTART_CHOICE="
set /p "RESTART_CHOICE=  Do you want to go again? [1 = Go Again, 2 = Exit]: "
if /i "!RESTART_CHOICE!"=="1" (
    cls
    goto :start_build
) else if /i "!RESTART_CHOICE!"=="2" (
    exit
) else (
    echo   [ERROR] Invalid choice. Please enter 1 or 2.
    goto :ask_restart
)