@echo off
setlocal enabledelayedexpansion

REM ==================================================
REM 1. 设置 Java 环境（请根据你的 JDK 路径修改）
REM ==================================================
REM set "JAVA_HOME=C:\Program Files\Java\jdk-21"

REM ==================================================
REM 2. 自动获取项目根目录
REM ==================================================
for %%I in ("%~dp0.") do set "PROJECT_ROOT=%%~fI"
set "SRC_DIR=%PROJECT_ROOT%\src\main\java"
set "CLASSES_DIR=%PROJECT_ROOT%\build\classes"
set "LIB_DIR=%PROJECT_ROOT%\lib"
set "BUILD_LIB_DIR=%PROJECT_ROOT%\build\lib"

REM ==================================================
REM 3. 验证 JAVA_HOME
REM ==================================================
if "%JAVA_HOME%"=="" (
    echo Error: JAVA_HOME not set.
    echo Please set JAVA_HOME to your JDK 21 installation path.
    pause
    exit /b 1
)

if not exist "%JAVA_HOME%" (
    echo Error: JAVA_HOME directory does not exist: %JAVA_HOME%
    pause
    exit /b 1
)

set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not exist "%JAVAC%" (
    echo Error: javac.exe not found at: %JAVAC%
    echo Please verify your JDK 21 installation.
    pause
    exit /b 1
)

REM 获取版本信息（仅用于显示）
for /f "tokens=*" %%a in ('"%JAVAC%" -version 2^>^&1') do set "JAVA_VERSION=%%a"

REM 检查是否为 JDK 21
echo !JAVA_VERSION! | findstr "21\." >nul
if !errorlevel! neq 0 (
    echo Warning: Java compiler version is not JDK 21: !JAVA_VERSION!
    echo Suggestion: Use JDK 21 for optimal compatibility.
)

REM ==================================================
REM 4. 清理并创建目录，复制 JAR 文件
REM ==================================================
if exist "!CLASSES_DIR!" rmdir /s /q "!CLASSES_DIR!"
mkdir "!CLASSES_DIR!" >nul

if not exist "!BUILD_LIB_DIR!" mkdir "!BUILD_LIB_DIR!"

for %%f in ("!LIB_DIR!\*.jar") do (
    copy /y "%%f" "!BUILD_LIB_DIR!" >nul
)

REM ==================================================
REM 5. 构建 classpath（仅用于编译时，启动用 * 通配符）
REM ==================================================
set "CLASSPATH="
for %%f in ("!LIB_DIR!\*.jar") do (
    if "!CLASSPATH!"=="" (
        set "CLASSPATH=%%~ff"
    ) else (
        set "CLASSPATH=!CLASSPATH!;%%~ff"
    )
)
if defined CLASSPATH (
    set "CLASSPATH=!CLASSPATH!;!CLASSES_DIR!"
) else (
    set "CLASSPATH=!CLASSES_DIR!"
)

REM ==================================================
REM 6. 查找所有 .java 文件
REM ==================================================
set "JAVA_FILES="
for /r "%SRC_DIR%" %%f in (*.java) do (
    if "!JAVA_FILES!"=="" (
        set "JAVA_FILES=%%~ff"
    ) else (
        set "JAVA_FILES=!JAVA_FILES! %%~ff"
    )
)

if not defined JAVA_FILES (
    echo Error: No .java files found in !SRC_DIR!
    pause
    exit /b 1
)

REM ==================================================
REM 7. 编译（关键：-encoding UTF-8 必须有）
REM ==================================================
echo Building with JDK 21 to !CLASSES_DIR!...

"%JAVAC%" ^
    -source 21 ^
    -target 21 ^
    -encoding UTF-8 ^
    -d "!CLASSES_DIR!" ^
    -cp "!CLASSPATH!" ^
    !JAVA_FILES!

if %errorlevel% neq 0 (
    echo Error: Compilation failed.
    pause
    exit /b 1
)

REM ==================================================
REM 8. 生成 run.bat（使用英文脚本，中文通过变量输出）
REM ==================================================
echo Success! Generating launch script...

(
    echo @echo off
    echo chcp 65001 ^>nul
    echo.
    echo java -Xms64m -Xmx96m -cp ".\classes;.\lib\*" org.mark.llamacpp.server.LlamaServer
) > "!PROJECT_ROOT!\build\run.bat"

REM ==================================================
REM 9. 输出成功信息（使用英文 + 中文变量，避免脚本内直接写中文）
REM ==================================================


echo.
echo Complete! build\run.bat

pause
endlocal
