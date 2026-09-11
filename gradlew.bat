@rem
@rem Gradle startup script for Windows
@rem Downloads gradle-wrapper.jar if missing, then runs Gradle
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script
@rem
@rem ##########################################################################

@rem Set local scope for the variables
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Download gradle-wrapper.jar if not present
set WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
if exist "%WRAPPER_JAR%" goto execute

echo Downloading gradle-wrapper.jar...
powershell -Command "& { Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.0.0/gradle/wrapper/gradle-wrapper.jar' -OutFile '%WRAPPER_JAR%' -UseBasicParsing }"
if not exist "%WRAPPER_JAR%" (
    echo ERROR: Failed to download gradle-wrapper.jar
    exit /b 1
)

:execute
@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
exit /b 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
exit /b 1

:execute
@rem Execute Gradle
"%JAVA_EXE%" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*

