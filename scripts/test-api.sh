#!/bin/bash
# API 文档测试脚本

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "=== LlamacppServer API 文档测试 ==="
echo ""

# 检查服务状态
echo "1. 检查服务状态:"
if systemctl is-active --quiet llama-server.service; then
    echo "   ✅ llama-server 服务正在运行"
    pid=$(systemctl show -p MainPID --value llama-server.service)
    echo "   📍 PID: $pid"
else
    echo "   ❌ llama-server 服务未运行"
    echo "   💡 启动服务: sudo systemctl start llama-server.service"
    exit 1
fi

echo ""
echo "2. 测试 OpenAPI JSON 端点:"
response=$(curl -s http://localhost:8080/api/docs 2>/dev/null)
if [ $? -eq 0 ]; then
    endpoint_count=$(echo "$response" | jq '.paths | length' 2>/dev/null)
    if [ "$endpoint_count" -gt 0 ]; then
        echo "   ✅ OpenAPI JSON 正常"
        echo "   📊 API 端点数量: $endpoint_count"
    else
        echo "   ⚠️  OpenAPI JSON 返回 0 个端点"
        echo "   💡 服务可能运行旧代码，需要重启:"
        echo "      sudo systemctl restart llama-server.service"
    fi
else
    echo "   ❌ 无法连接到 API 端点"
    echo "   💡 检查服务是否正常启动"
fi

echo ""
echo "3. 测试 Swagger UI 端点:"
if curl -s http://localhost:8080/api/docs/ui | grep -q "swagger-ui"; then
    echo "   ✅ Swagger UI 可访问"
else
    echo "   ❌ Swagger UI 不可访问"
fi

echo ""
echo "4. API 端点列表:"
curl -s http://localhost:8080/api/docs | jq -r '.paths | keys[]' 2>/dev/null | sort | while IFS= read -r path; do
    methods=$(curl -s http://localhost:8080/api/docs | jq -r ".paths[\"$path\"] | keys[]" 2>/dev/null)
    echo "   $path ($methods)"
done | head -15

echo ""
echo "5. 快速访问链接:"
echo "   📄 OpenAPI JSON:  http://localhost:8080/api/docs"
echo "   🌐 Swagger UI:    http://localhost:8080/api/docs/ui"
echo "   🔄 重新生成文档:  curl -X POST http://localhost:8080/api/docs/regenerate"

echo ""
echo "=== 测试完成 ==="
