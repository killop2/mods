@echo off
rem Build with JDK 21, Gradle caches and temp files on drive D.
set "JAVA_HOME=D:\mods\tools\jdk-21.0.13+9"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE_USER_HOME=D:\mods\.gradle"
set "TEMP=D:\mods\tmp"
set "TMP=D:\mods\tmp"
set "GRADLE_OPTS=-Djava.io.tmpdir=D:\mods\tmp"
cd /d "%~dp0"
call "%~dp0gradlew.bat" %* --console=plain
