#!/bin/bash
# OpenAPI 文档生成脚本
# 从 scripts/ 目录执行

# 获取项目根目录（脚本在 scripts/ 子目录中）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"

# 生成 OpenAPI JSON
echo "正在生成 OpenAPI 文档..."
echo "项目根目录: $PROJECT_ROOT"

JAVA_FILE="build/classes/org/mark/llamacpp/server/docs/OpenApiGenerator.class"

if [ ! -f "$JAVA_FILE" ]; then
    echo "错误: 请先编译项目: ./scripts/javac-linux.sh"
    exit 1
fi

# 运行测试程序生成文档
cat > /tmp/TestDocGen.java << 'JAVA_EOF'
import org.mark.llamacpp.server.docs.OpenApiGenerator;
import org.mark.llamacpp.server.tools.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Paths;

public class TestDocGen {
    public static void main(String[] args) throws Exception {
        OpenApiGenerator generator = new OpenApiGenerator();
        JsonObject openApi = generator.generateOpenApiSpec();
        String json = JsonUtil.toJson(openApi);
        Files.writeString(Paths.get("openapi.json"), json);
        System.out.println("文档已生成: openapi.json");
        System.out.println("端点数量: " + openApi.getAsJsonObject("paths").size());
    }
}
JAVA_EOF

# 编译并运行测试程序
javac -cp "build/classes:lib/*" /tmp/TestDocGen.java 2>/dev/null
if [ $? -eq 0 ]; then
    java -cp "/tmp:build/classes:lib/*" TestDocGen
    rm -f /tmp/TestDocGen.java /tmp/TestDocGen.class
    echo "✅ OpenAPI 文档生成成功"
else
    echo "❌ 文档生成失败（测试程序编译失败）"
    exit 1
fi
