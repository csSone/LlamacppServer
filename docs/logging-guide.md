# 日志组件配置说明

## 概述

LlamacppServer 使用 Log4j2 + SLF4J 作为日志框架，提供了完整的日志记录功能，包括控制台输出、文件滚动存储、错误日志分离等特性。

## 日志格式

### 控制台格式（带颜色）

```
HH:mm:ss.SSS [线程名] 级别 Logger名称 - 消息内容
```

**示例：**
```
11:41:16.227 [main] INFO  org.mark.llamacpp.server.LlamaServer - 正在加载application.json配置...
11:41:30.337 [测试线程-1] INFO  org.mark.llamacpp.test.LoggerTest - 来自测试线程的日志
```

### 文件格式（无颜色）

```
yyyy-MM-dd HH:mm:ss.SSS [线程名] 级别 Logger名称 - 消息内容
```

**示例：**
```
2026-02-07 11:41:16.227 [main] INFO  org.mark.llamacpp.server.LlamaServer - 正在加载application.json配置...
```

### 格式说明

| 字段 | 说明 | 示例 |
|------|------|------|
| `HH:mm:ss.SSS` | 时间（时:分:秒.毫秒） | `11:41:16.227` |
| `[线程名]` | 执行日志的线程名称 | `[main]`、`[llama-loader-1]` |
| `级别` | 日志级别（INFO/WARN/ERROR） | `INFO`、`WARN`、`ERROR` |
| `Logger名称` | 类名（自动缩写） | `o.m.l.s.LlamaServer` |
| `消息内容` | 实际的日志消息 | `正在加载application.json配置...` |

## 日志级别

| 级别 | 用途 | 示例 |
|------|------|------|
| TRACE | 最详细的调试信息 | 方法入口/出口参数 |
| DEBUG | 调试信息 | 变量值、中间结果 |
| INFO | 重要的业务操作 | 模型加载/卸载、API 请求 |
| WARN | 警告信息（可恢复） | 配置缺失、降级处理 |
| ERROR | 错误信息（需关注） | 异常、操作失败 |

## 日志文件

### 文件位置

所有日志文件存储在项目根目录的 `logs/` 目录下：

```
LlamacppServer/
├── logs/
│   ├── llamacpp-server.log (所有日志)
│   ├── llamacpp-server-error.log (仅错误日志)
│   └── llamacpp-server-2026-02-07-1.log (历史滚动文件)
```

### 滚动策略

**主日志文件** (`llamacpp-server.log`)：
- 按日期滚动：每天午夜创建新文件
- 按大小滚动：文件超过 100MB 时滚动
- 保留策略：保留最近 30 天的日志

**错误日志文件** (`llamacpp-server-error.log`)：
- 只记录 ERROR 级别的日志
- 按日期滚动：每天午夜创建新文件
- 按大小滚动：文件超过 50MB 时滚动
- 保留策略：保留最近 10 个错误日志文件

## 使用方法

### 在代码中使用日志

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyClass {
    // 创建 Logger 实例
    private static final Logger logger = LoggerFactory.getLogger(MyClass.class);

    public void doSomething() {
        logger.debug("调试信息");
        logger.info("普通信息");
        logger.warn("警告信息");
        logger.error("错误信息");
    }

    public void handleException(Exception e) {
        // 记录异常堆栈
        logger.error("操作失败", e);
    }

    public void logWithParams(String user, String action) {
        // 使用参数化日志（性能更好）
        logger.info("用户 {} 执行操作 {}", user, action);
    }
}
```

### 运行日志测试

```bash
# 运行日志组件测试
./scripts/test-logger.sh
```

## 配置调优

### 修改日志级别

编辑 `src/main/resources/log4j2.xml`：

```xml
<!-- 生产环境推荐使用 INFO -->
<Logger name="org.mark.llamacpp" level="INFO" additivity="false">

<!-- 开发环境可以使用 DEBUG -->
<Logger name="org.mark.llamacpp" level="DEBUG" additivity="false">
```

### 修改第三方库日志级别

```xml
<!-- Netty 日志级别 -->
<Logger name="io.netty" level="INFO" additivity="false">

<!-- Gson 日志级别 -->
<Logger name="com.google.gson" level="WARN" additivity="false">
```

### 自定义日志格式

修改 `CONSOLE_PATTERN` 或 `FILE_PATTERN_FORMAT` 属性：

```xml
<Property name="CONSOLE_PATTERN">
  %d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n
</Property>
```

**可用的模式占位符：**

| 占位符 | 说明 |
|--------|------|
| `%d{pattern}` | 日期时间 |
| `%t` | 线程名 |
| `%p` 或 `%level` | 日志级别 |
| `%logger{length}` | Logger 名称（可指定长度） |
| `%m` 或 `%msg` | 消息内容 |
| `%n` | 换行符 |
| `%ex` | 异常堆栈 |
| `%highlight{...}` | 高亮显示（控制台） |
| `%style{...}{color}` | 自定义颜色 |

## 性能优化

### 1. 使用参数化日志

**推荐：**
```java
logger.info("用户 {} 执行操作 {}", user, action);
```

**不推荐：**
```java
logger.info("用户 " + user + " 执行操作 " + action);
```

### 2. 条件日志

对于复杂的日志操作，先检查日志级别：

```java
if (logger.isDebugEnabled()) {
    String complexInfo = buildComplexInfo();
    logger.debug("详细信息: {}", complexInfo);
}
```

### 3. 异步日志（可选）

如果需要更高的性能，可以添加 disruptor 依赖并使用 AsyncLogger：

```xml
<dependency>
    <groupId>com.lmax</groupId>
    <artifactId>disruptor</artifactId>
    <version>4.0.0</version>
</dependency>
```

然后将 `Logger` 改为 `AsyncLogger`：

```xml
<AsyncLogger name="org.mark.llamacpp" level="INFO" additivity="false">
```

## 故障排查

### 问题：日志文件未生成

**可能原因：**
1. 日志目录权限不足
2. 配置文件路径错误

**解决方法：**
```bash
# 手动创建日志目录
mkdir -p logs

# 检查配置文件是否在正确位置
ls -la build/classes/log4j2.xml
```

### 问题：日志格式不显示颜色

**可能原因：**
终端不支持 ANSI 颜色代码

**解决方法：**
- 使用支持颜色的终端（如大多数现代终端）
- 或者移除 `%highlight{}` 和 `%style{}` 标记

### 问题：日志输出重复

**可能原因：**
Logger 的 `additivity` 属性设置不当

**解决方法：**
确保 `additivity="false"` 以避免日志重复：

```xml
<Logger name="org.mark.llamacpp" level="INFO" additivity="false">
```

## 参考资源

- [Log4j 2 官方文档](https://logging.apache.org/log4j/2.x/)
- [SLF4J 用户手册](http://www.slf4j.org/manual.html)
- 项目日志配置：`src/main/resources/log4j2.xml`
- 日志测试工具：`src/test/java/org/mark/llamacpp/test/LoggerTest.java`
