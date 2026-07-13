@if "%DEBUG%"=="" @echo off
@rem Gradle wrapper script for Windows
setlocal
set DIR=%~dp0
if "%DIR%"=="" set DIR=.
set APP_HOME=%DIR%..
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
java -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*