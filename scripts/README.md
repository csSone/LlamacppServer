# LlamacppServer 脚本说明

本目录包含 LlamacppServer 项目的构建和管理脚本。

## 脚本列表

### 1. javac-linux.sh
**功能**: Linux 系统编译脚本

**用法**:
```bash
cd /home/user/workspace/LlamacppServer
./scripts/javac-linux.sh
```

**说明**:
- 自动检测 JAVA_HOME（需 JDK 21+）
- 编译所有 Java 源代码到 `build/classes/`
- 复制资源文件
- 生成启动脚本 `build/run.sh`

**依赖**:
- JDK 21 或更高版本
- lib/ 目录下的依赖 jar 包

---

### 2. javac-win.bat
**功能**: Windows 系统编译脚本

**用法**:
```cmd
cd C:\path\to\LlamacppServer
scripts\javac-win.bat
```

**说明**:
- Windows 版本的编译脚本
- 功能与 Linux 版本相同

---

### 3. generate-openapi.sh
**功能**: 生成 OpenAPI 3.0 文档

**用法**:
```bash
cd /home/user/workspace/LlamacppServer
./scripts/generate-openapi.sh
```

**说明**:
- 自动扫描路由处理器代码
- 生成 OpenAPI 3.0 规范的 JSON 文件
- 输出文件: `openapi.json`（项目根目录）

**生成的文档包含**:
- OpenAI 兼容 API（/v1/*）
- Anthropic 兼容 API
- 聊天完成 API
- 文件下载 API

---

### 4. test-api.sh
**功能**: 测试 API 文档端点

**用法**:
```bash
cd /home/user/workspace/LlamacppServer
./scripts/test-api.sh
```

**说明**:
- 检查服务运行状态
- 测试 OpenAPI JSON 端点
- 测试 Swagger UI 端点
- 列出所有可用的 API 端点

**输出信息**:
- 服务状态和 PID
- API 端点数量
- 端点列表和 HTTP 方法
- 快速访问链接

---

## 快速开始

### 首次编译

```bash
cd /home/user/workspace/LlamacppServer

# 1. 编译项目
./scripts/javac-linux.sh

# 2. 生成 API 文档（可选）
./scripts/generate-openapi.sh

# 3. 启动服务
sudo systemctl restart llama-server.service

# 4. 测试 API 端点
./scripts/test-api.sh

# 5. 访问 API 文档
# 浏览器打开: http://localhost:8080/api/docs/ui
```

### 常用操作

**编译后重启服务**:
```bash
./scripts/javac-linux.sh && sudo systemctl restart llama-server.service
```

**只生成文档**:
```bash
./scripts/generate-openapi.sh
```

**测试 API 状态**:
```bash
./scripts/test-api.sh
```

---

## 常见问题

### Q: 编译失败，提示找不到 JDK
**A**: 确保已安装 JDK 21，或手动设置 JAVA_HOME：
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./scripts/javac-linux.sh
```

### Q: 文档生成失败
**A**: 确保已先编译项目：
```bash
./scripts/javac-linux.sh  # 先编译
./scripts/generate-openapi.sh  # 再生成文档
```

### Q: 修改代码后如何重新部署
**A**:
```bash
# 1. 重新编译
./scripts/javac-linux.sh

# 2. 重启服务
sudo systemctl restart llama-server.service
```

---

## API 文档访问

编译并启动服务后，可以通过以下地址访问 API 文档：

| 端点 | 说明 |
|------|------|
| `http://localhost:8080/api/docs` | OpenAPI JSON 规范 |
| `http://localhost:8080/api/docs/ui` | Swagger UI 交互式文档 |
| `http://localhost:8080/api/docs/regenerate` | 重新生成文档（POST）|

---

## 维护说明

- 所有脚本都从项目根目录（`scripts/` 的父目录）执行
- 脚本会自动处理路径问题
- 修改脚本时请保持路径相对关系
