@echo off
chcp 65001 >nul 2>&1

del "%~dp0\danmu.jar"
rd /s/q "%~dp0\Smali_classes"

java -jar "%~dp0\3rd\apktool_2.11.0.jar" d -f --only-main-classes "%~dp0\..\app\build\outputs\apk\release\app-release-unsigned.apk" -o "%~dp0\Smali_classes"

rd /s/q "%~dp0\spider.jar\smali\com\github\catvod\spider"
rd /s/q "%~dp0\spider.jar\smali\com\github\catvod\js"
rd /s/q "%~dp0\spider.jar\smali\com\github\catvod\net"
rd /s/q "%~dp0\spider.jar\smali\org\slf4j"

if not exist "%~dp0\spider.jar\smali\com\github\catvod\" md "%~dp0\spider.jar\smali\com\github\catvod\"
if not exist "%~dp0\spider.jar\smali\org\slf4j\" md "%~dp0\spider.jar\smali\org\slf4j\"

move "%~dp0\Smali_classes\smali\com\github\catvod\spider" "%~dp0\spider.jar\smali\com\github\catvod\"
move "%~dp0\Smali_classes\smali\com\github\catvod\js" "%~dp0\spider.jar\smali\com\github\catvod\"
move "%~dp0\Smali_classes\smali\com\github\catvod\net" "%~dp0\spider.jar\smali\com\github\catvod\"
move "%~dp0\Smali_classes\smali\org\slf4j" "%~dp0\spider.jar\smali\org\slf4j\"

if exist "%~dp0\spider.jar\assets" rd /s/q "%~dp0\spider.jar\assets"
java -jar "%~dp0\3rd\apktool_2.11.0.jar" b "%~dp0\spider.jar" -c

move "%~dp0\spider.jar\dist\dex.jar" "%~dp0\danmu.jar"

certUtil -hashfile "%~dp0\danmu.jar" MD5 | find /i /v "md5" | find /i /v "certutil" > "%~dp0\danmu.jar.md5"

rd /s/q "%~dp0\spider.jar\build"
rd /s/q "%~dp0\spider.jar\smali"
rd /s/q "%~dp0\spider.jar\dist"
rd /s/q "%~dp0\Smali_classes"
