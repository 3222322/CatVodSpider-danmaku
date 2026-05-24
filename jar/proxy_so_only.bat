@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

set "JAR_DIR=%~dp0"
set "OUT_JAR=%JAR_DIR%proxy.jar"
set "APK_PATH=%JAR_DIR%..\app\build\outputs\apk\release\app-release-unsigned.apk"
set "SMALI_TMP=%JAR_DIR%Smali_classes"
set "SPIDER_DIR=%JAR_DIR%spider.jar"
set "SPIDER_ASSETS=%SPIDER_DIR%assets"
set "SRC_ASSETS=%JAR_DIR%assets_so"

del "%OUT_JAR%"
del "%OUT_JAR%.md5"
rd /s /q "%SMALI_TMP%"

java -jar "%JAR_DIR%3rd\apktool_2.11.0.jar" d -f --only-main-classes "%APK_PATH%" -o "%SMALI_TMP%"

rd /s /q "%SPIDER_DIR%\smali\com\github\catvod\spider"
rd /s /q "%SPIDER_DIR%\smali\com\github\catvod\js"
rd /s /q "%SPIDER_DIR%\smali\com\github\catvod\net"
rd /s /q "%SPIDER_DIR%\smali\org\slf4j"

if not exist "%SPIDER_DIR%\smali\com\github\catvod\" md "%SPIDER_DIR%\smali\com\github\catvod\"
if not exist "%SPIDER_DIR%\smali\org\slf4j\" md "%SPIDER_DIR%\smali\org\slf4j\"

move "%SMALI_TMP%\smali\com\github\catvod\spider" "%SPIDER_DIR%\smali\com\github\catvod\"
move "%SMALI_TMP%\smali\com\github\catvod\js" "%SPIDER_DIR%\smali\com\github\catvod\"
move "%SMALI_TMP%\smali\com\github\catvod\net" "%SPIDER_DIR%\smali\com\github\catvod\"
move "%SMALI_TMP%\smali\org\slf4j" "%SPIDER_DIR%\smali\org\slf4j\"

if exist "%SPIDER_ASSETS%" rd /s /q "%SPIDER_ASSETS%"
md "%SPIDER_ASSETS%"

if exist "%SRC_ASSETS%\arm64-v8a" (
    xcopy "%SRC_ASSETS%\arm64-v8a" "%SPIDER_ASSETS%\arm64-v8a\" /E /I /Y >nul
)

if exist "%SRC_ASSETS%\armeabi-v7a" (
    xcopy "%SRC_ASSETS%\armeabi-v7a" "%SPIDER_ASSETS%\armeabi-v7a\" /E /I /Y >nul
)

java -jar "%JAR_DIR%3rd\apktool_2.11.0.jar" b "%SPIDER_DIR%" -c

move "%SPIDER_DIR%\dist\dex.jar" "%OUT_JAR%"
certUtil -hashfile "%OUT_JAR%" MD5 | find /i /v "md5" | find /i /v "certutil" > "%OUT_JAR%.md5"

rd /s /q "%SPIDER_DIR%\build"
rd /s /q "%SPIDER_DIR%\smali"
rd /s /q "%SPIDER_DIR%\dist"
rd /s /q "%SMALI_TMP%"

echo Built: %OUT_JAR%
