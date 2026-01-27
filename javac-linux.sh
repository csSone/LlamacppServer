#!/bin/bash
# ============================================================
# LlamacppServer Linux Build Script
# Compatible with javac-win.bat logic
# ============================================================

set -e  # Exit on error

# 设置项目根目录
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

# 设置目录路径
SRC_DIR="$PROJECT_ROOT/src/main/java"
RES_DIR="$PROJECT_ROOT/src/main/resources"
LIB_DIR="$PROJECT_ROOT/lib"
BUILD_DIR="$PROJECT_ROOT/build"
CLASSES_DIR="$BUILD_DIR/classes"
BUILD_LIB_DIR="$BUILD_DIR/lib"

EXITCODE=0

echo ============================================================
echo Building project...
echo Project: $PROJECT_ROOT
echo Output : $CLASSES_DIR
echo ============================================================
echo

# === 清理并创建输出目录 ===
if [ -d "$CLASSES_DIR" ]; then
    rm -rf "$CLASSES_DIR"
fi
if [ -d "$BUILD_LIB_DIR" ]; then
    rm -rf "$BUILD_LIB_DIR"
fi
mkdir -p "$CLASSES_DIR"
mkdir -p "$BUILD_LIB_DIR"

# === 检测 Java 环境 ===
JAVAC="javac"
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
fi

# 检查 javac 是否可用
if ! command -v "$JAVAC" &> /dev/null; then
    echo "ERROR: javac not found."
    echo "Install JDK 21 and set JAVA_HOME or add javac to PATH."
    EXITCODE=1
    exit $EXITCODE
fi

# 检查 java runtime
if ! command -v java &> /dev/null; then
    echo "ERROR: java runtime not found."
    echo "Install JDK 21 and set JAVA_HOME or add java to PATH."
    EXITCODE=1
    exit $EXITCODE
fi

# === 构建 classpath 并复制 jar 文件 ===
CLASSPATH=""
if ls "$LIB_DIR"/*.jar 1> /dev/null 2>&1; then
    echo "Copying jars to $BUILD_LIB_DIR ..."
    for jar in "$LIB_DIR"/*.jar; do
        if [ -n "$CLASSPATH" ]; then
            CLASSPATH="$CLASSPATH:$jar"
        else
            CLASSPATH="$jar"
        fi
    done
    cp -f "$LIB_DIR"/*.jar "$BUILD_LIB_DIR/" 2>/dev/null || true
    echo "Jars copied."
    echo
else
    echo "No jars found under $LIB_DIR ."
    echo
fi

# === 检查源码目录 ===
if [ ! -d "$SRC_DIR" ]; then
    echo "ERROR: source directory not found: $SRC_DIR"
    EXITCODE=1
    exit $EXITCODE
fi

# === 收集源码文件 ===
SOURCES=$(find "$SRC_DIR" -name "*.java" 2>/dev/null || true)
if [ -z "$SOURCES" ]; then
    echo "ERROR: no .java files found under: $SRC_DIR"
    EXITCODE=1
    exit $EXITCODE
fi

# === 执行编译 ===
echo "Compiling Java sources..."
if [ -n "$CLASSPATH" ]; then
    "$JAVAC" --release 21 -encoding UTF-8 -d "$CLASSES_DIR" -cp "$CLASSPATH" $SOURCES
else
    "$JAVAC" --release 21 -encoding UTF-8 -d "$CLASSES_DIR" $SOURCES
fi

if [ $? -ne 0 ]; then
    echo
    echo "ERROR: compilation failed. Exit code: $?"
    EXITCODE=$?
    exit $EXITCODE
fi
echo "Compilation succeeded."
echo

# === 复制资源文件 ===
if [ -d "$RES_DIR" ]; then
    echo "Copying resources to $CLASSES_DIR ..."
    cp -r "$RES_DIR"/* "$CLASSES_DIR/" 2>/dev/null || true
    echo "Resources copied."
    echo
else
    echo "No resources directory found: $RES_DIR"
    echo
fi

# === 创建启动脚本 ===
RUN_SCRIPT="$BUILD_DIR/run.sh"
cat > "$RUN_SCRIPT" << 'EOF'
#!/bin/bash
cd "$(dirname "$0")"
java -Xms64m -Xmx96m -cp "./classes:./lib/*" org.mark.llamacpp.server.LlamaServer
EOF
chmod +x "$RUN_SCRIPT"

# === 构建结果 ===
echo ============================================================
if [ $EXITCODE -eq 0 ]; then
    echo "Build SUCCESS."
    echo "Build output: $CLASSES_DIR"
    echo "Run script  : $RUN_SCRIPT"
else
    echo "Build FAILED."
    echo "Exit code: $EXITCODE"
fi
echo ============================================================
echo

exit $EXITCODE
