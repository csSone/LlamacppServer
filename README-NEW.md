# LlamacppServer（Java）

一个基于 Netty 的本地 Web 服务，用来管理 GGUF 模型并以 OpenAI/Anthropic 兼容接口把请求转发给本机的 llama.cpp `llama-server` 进程；同时提供 Web 管理界面、WebSocket 实时事件与若干工具能力（基准测试、显存估算、HuggingFace 查询、下载管理等）。

## 适用场景

- 本机有已编译好的 llama.cpp `llama-server` 可执行文件
- 模型以 GGUF 形式存放在一个或多个目录下（包含分卷 `*-00001-of-*.gguf`、以及可选的 `mmproj-*.gguf`）
- 需要一个轻量的“本地模型面板”：模型扫描/加载/停止 + Web UI + OpenAI API 兼容端点

## 功能概览

- 模型扫描与信息读取
  - 扫描多个“模型根目录”，识别每个模型目录下的 `.gguf` 文件并聚合为一个模型条目
  - 识别分卷模型（`*-00001-of-*.gguf`）与多模态 `mmproj` 文件
  - 读取 GGUF 元数据（上下文长度、架构等）并持久化模型列表到本地
- 模型进程管理（llama.cpp）
  - 以子进程方式启动/停止 llama.cpp `llama-server`
  - 自动为每个已加载模型分配端口（从 8081 起递增，自动探测可用端口）
  - 通过 WebSocket 广播模型加载/停止事件与状态变化
- Web 管理界面（静态资源由服务端直接提供）
  - 模型列表：搜索、排序、详情查看、别名/收藏
  - 加载参数编辑：保存每个模型的启动参数
  - 控制台：实时查看本服务的控制台日志
  - 下载管理：下载任务列表、暂停/恢复/删除、进度推送
  - 基准测试：对模型执行基准测试并管理结果文件
  - HuggingFace：搜索模型与抓取 GGUF 文件列表（用于辅助下载/选择）
- API 兼容层
  - OpenAI API（默认 8080）：`/v1/models`、`/v1/chat/completions`、`/v1/completions`、`/v1/embeddings`
  - Anthropic API（默认 8070）：`/v1/models`、`/v1/messages`、`/v1/complete`（实现不完整）
- 工具与增强能力
  - 显存估算：基于 GGUF 元数据与 KV 类型、上下文等参数估算权重 + KV + 额外开销
  - KV slot（缓存）相关：查询 slots、保存/加载 slot（依赖 llama.cpp server 端点）
  - Chat Template 管理：读取/设置/删除/恢复默认聊天模板（与模型元数据/本地模板文件联动）
  - 创作/对话素材（自用）：`/api/chat/completion/*` 管理本地“角色/预设”，并支持文件上传/下载

## 目录结构（关键）

- `src/main/java/`：Java 源码
- `src/main/resources/web/`：Web 静态资源（`/`、`/chat/*`、`/tools/*` 等）
- `config/`：运行时配置与持久化数据（会被程序读写）
  - `application.json`：端口与下载目录
  - `modelpaths.json`：模型根目录列表
  - `llamacpp.json`：llama.cpp 可执行文件/版本路径列表
  - `models.json`：扫描后的模型列表缓存
- `downloads/`：默认下载目录（可在 Web UI 或 `application.json` 修改）
- `benchmarks/`：基准测试输出文件目录
- `logs/console.log`：控制台日志文件（同时会广播到 WebSocket）
- `lib/`：运行所需的第三方 jar（netty/gson/slf4j 等）

## 快速开始

### 1) 准备环境

- JDK 21
- 已编译好的 llama.cpp `llama-server`（Windows/Linux 均可）
- 至少一个 GGUF 模型目录（建议每个模型一个目录）

### 2) 编译

项目提供了脚本编译（使用 `lib/` 下的 jar 作为 classpath）：

```bash
# Windows
javac-win.bat

# Linux
./javac-linux.sh
```

编译成功后会生成：

- `build/classes/`：编译输出
- `build/lib/`：依赖 jar 拷贝
- `build/run.bat` 或 `build/run.sh`：启动脚本

### 3) 启动

```bash
# Windows
.\build\run.bat

# Linux
./build/run.sh
```

默认端口：

- Web + OpenAI API：`http://localhost:8080`
- Anthropic API：`http://localhost:8070`

WebSocket：

- `ws://localhost:8080/ws`

### 4) 访问 Web UI

- 首页：`http://localhost:8080/`
- 对话页：`http://localhost:8080/chat/completion.html`
- 压力测试：`http://localhost:8080/chat/concurrency.html`
- 工具页：`http://localhost:8080/tools/llm-performance-test.html`

## 配置说明

### application.json（端口与下载目录）

文件：`config/application.json`

示例：

```json
{
  "server": { "webPort": 8080, "anthropicPort": 8070 },
  "download": { "directory": "C:\\\\path\\\\to\\\\downloads" }
}
```

### 模型目录（modelpaths.json）

文件：`config/modelpaths.json`

- 支持多个模型根目录
- Web UI 中可新增/移除/更新；程序启动时会扫描并刷新 `config/models.json`

### llama.cpp 可执行路径（llamacpp.json）

文件：`config/llamacpp.json`

- 用于在“加载模型/跑基准测试”时选择具体的 `llama-server` 路径（可配置多个版本）

## 端口与路由（重要）

### Web + OpenAI 端口（默认 8080）

- Web 静态资源：`/`、`/index.html`、`/chat/*`、`/tools/*` 等
- WebSocket：`/ws`
- OpenAI API（兼容转发到已加载的 llama.cpp 进程）
  - `GET /v1/models`
  - `POST /v1/chat/completions`（支持 `stream`）
  - `POST /v1/completions`（支持 `stream`）
  - `POST /v1/embeddings`
- 内部管理 API（Web UI 调用，部分示例）
  - 模型：`/api/models/list`、`/api/models/load`、`/api/models/stop`、`/api/models/details`
  - 显存估算：`/api/models/vram/estimate`
  - 设备枚举：`/api/model/device/list`
  - 基准测试：`/api/models/benchmark/*`
  - HuggingFace：`/api/hf/search`、`/api/hf/gguf`
  - 下载：`/api/downloads/*`
  - 角色/预设：`/api/chat/completion/*`

### Anthropic 端口（默认 8070）

- `GET /v1/models`
- `POST /v1/messages`
- `POST /v1/complete`

## 工作原理（简述）

- 本服务在启动时读取 `config/*.json`，初始化配置与模型目录，然后扫描 GGUF 模型生成/刷新 `config/models.json`
- 当你在 Web UI 中加载某个模型时，本服务会根据保存的启动参数与所选 llama.cpp 路径，启动一个 `llama-server` 子进程
- 每个已加载模型会被分配一个独立端口（从 8081 起），OpenAI/Anthropic 兼容接口会把请求转发到对应端口
- Web UI 通过 WebSocket 获取模型状态、下载进度、控制台日志等实时事件

## 注意与限制

- 这是个人自用项目，默认不做鉴权与权限控制；在不可信网络环境下请勿直接暴露端口
- Anthropic 实现不完整，且代码中存在写死的 API key 占位符，不适合作为生产实现
- 下载功能仍在迭代中（可用但并非完整下载器）

## 关键代码位置（便于二次开发）

- 程序入口与 Netty pipeline：[LlamaServer.java](file:///c:/Users/Mark/Workspace/Java/LlamacppServer/src/main/java/org/mark/llamacpp/server/LlamaServer.java)
- 模型扫描/进程管理：[LlamaServerManager.java](file:///c:/Users/Mark/Workspace/Java/LlamacppServer/src/main/java/org/mark/llamacpp/server/LlamaServerManager.java)
- Web UI / 内部 API 路由：[BasicRouterHandler.java](file:///c:/Users/Mark/Workspace/Java/LlamacppServer/src/main/java/org/mark/llamacpp/server/channel/BasicRouterHandler.java)
- OpenAI 兼容接口：[OpenAIService.java](file:///c:/Users/Mark/Workspace/Java/LlamacppServer/src/main/java/org/mark/llamacpp/server/service/OpenAIService.java)
- Anthropic 兼容接口：[AnthropicService.java](file:///c:/Users/Mark/Workspace/Java/LlamacppServer/src/main/java/org/mark/llamacpp/server/service/AnthropicService.java)
- 下载路由与服务：[FileDownloadRouterHandler.java](file:///c:/Users/Mark/Workspace/Java/LlamacppServer/src/main/java/org/mark/llamacpp/server/channel/FileDownloadRouterHandler.java)
- WebSocket 事件：[WebSocketManager.java](file:///c:/Users/Mark/Workspace/Java/LlamacppServer/src/main/java/org/mark/llamacpp/server/websocket/WebSocketManager.java)
- 显存估算工具：[VramEstimator.java](file:///c:/Users/Mark/Workspace/Java/LlamacppServer/src/main/java/org/mark/llamacpp/server/tools/VramEstimator.java)

