package org.mark.llamacpp.server.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * OpenAPI 文档生成器
 * 自动扫描路由处理器代码，生成 OpenAPI 3.0 规范
 */
public class OpenApiGenerator {

	private static final Logger logger = LoggerFactory.getLogger(OpenApiGenerator.class);

	// 路由模式正则表达式
	private static final Pattern PATH_PATTERN_EQUALS = Pattern.compile("\"([^\"]+)\"\\.equals\\(path\\)");
	private static final Pattern PATH_PATTERN_STARTS = Pattern.compile("startsWith\\(\"([^\"]+)\"\\)");
	private static final Pattern PATH_PATTERN_DIRECT = Pattern.compile("\\(\"([^\"]+)\"\\)");

	/**
	 * API 端点信息
	 */
	public static class ApiEndpoint {
		private String path;
		private String method;
		private String handler;
		private String description;
		private List<ApiParameter> parameters;
		private Map<String, Object> requestBody;

		public ApiEndpoint() {
			this.parameters = new ArrayList<>();
		}

		// Getters and Setters
		public String getPath() { return path; }
		public void setPath(String path) { this.path = path; }
		public String getMethod() { return method; }
		public void setMethod(String method) { this.method = method; }
		public String getHandler() { return handler; }
		public void setHandler(String handler) { this.handler = handler; }
		public String getDescription() { return description; }
		public void setDescription(String description) { this.description = description; }
		public List<ApiParameter> getParameters() { return parameters; }
		public void setParameters(List<ApiParameter> parameters) { this.parameters = parameters; }
		public Map<String, Object> getRequestBody() { return requestBody; }
		public void setRequestBody(Map<String, Object> requestBody) { this.requestBody = requestBody; }
	}

	/**
	 * API 参数信息
	 */
	public static class ApiParameter {
		private String name;
		private String in; // query, path, header
		private String type;
		private boolean required;
		private String description;

		public ApiParameter() {}

		public ApiParameter(String name, String in, String type, boolean required, String description) {
			this.name = name;
			this.in = in;
			this.type = type;
			this.required = required;
			this.description = description;
		}

		// Getters and Setters
		public String getName() { return name; }
		public void setName(String name) { this.name = name; }
		public String getIn() { return in; }
		public void setIn(String in) { this.in = in; }
		public String getType() { return type; }
		public void setType(String type) { this.type = type; }
		public boolean isRequired() { return required; }
		public void setRequired(boolean required) { this.required = required; }
		public String getDescription() { return description; }
		public void setDescription(String description) { this.description = description; }
	}

	/**
	 * 扫描路由处理器源代码，提取 API 端点信息
	 */
	public List<ApiEndpoint> scanRouterHandlers() {
		List<ApiEndpoint> endpoints = new ArrayList<>();

		// 定义要扫描的路由处理器
		Map<String, String> handlerClasses = Map.of(
			"OpenAIRouterHandler", "OpenAI 兼容 API",
			"AnthropicRouterHandler", "Anthropic 兼容 API",
			"CompletionRouterHandler", "聊天完成 API",
			"FileDownloadRouterHandler", "文件下载 API",
			"OllamaRouterHandler", "Ollama 兼容 API",
			"LMStudioRouterHandler", "LMStudio 兼容 API"
		);

		for (Map.Entry<String, String> entry : handlerClasses.entrySet()) {
			String className = entry.getKey();
			String description = entry.getValue();
			scanRouterClass(className, description, endpoints);
		}

		return endpoints;
	}

	/**
	 * 扫描单个路由处理器类
	 */
	private void scanRouterClass(String className, String handlerDescription, List<ApiEndpoint> endpoints) {
		try {
			Path sourcePath = findSourceFile(className);
			if (sourcePath == null) {
				logger.warn("路由处理器源文件不存在: {}", className);
				return;
			}

			String content = Files.readString(sourcePath);
			List<ApiEndpoint> classEndpoints = parseRouterClass(content, className, handlerDescription);
			endpoints.addAll(classEndpoints);

			logger.info("扫描 {} 完成，发现 {} 个端点", className, classEndpoints.size());
		} catch (IOException e) {
			logger.error("扫描路由处理器失败: " + className, e);
		}
	}

	/**
	 * 查找源文件（支持多种部署场景）
	 */
	private Path findSourceFile(String className) {
		String relativePath = String.format("src/main/java/org/mark/llamacpp/server/channel/%s.java", className);

		// 尝试多个可能的位置
		Path[] searchPaths = {
			Paths.get(""),                           // 当前工作目录
			Paths.get("build/classes").getParent(),  // 从 build/classes 向上
			Paths.get(".").toAbsolutePath(),          // 绝对路径的当前目录
		};

		for (Path basePath : searchPaths) {
			if (basePath == null) continue;
			Path fullPath = basePath.resolve(relativePath);
			if (Files.exists(fullPath)) {
				logger.debug("找到源文件: {}", fullPath);
				return fullPath;
			}
		}

		return null;
	}

	/**
	 * 解析路由处理器类源代码
	 */
	private List<ApiEndpoint> parseRouterClass(String content, String className, String handlerDescription) {
		List<ApiEndpoint> endpoints = new ArrayList<>();

		String[] lines = content.split("\n");
		HttpMethod currentMethod = null;

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].trim();

			// 检测 HTTP 方法
			if (line.contains("HttpMethod.GET.equals(method)") || line.contains("request.method() == HttpMethod.GET")) {
				currentMethod = HttpMethod.GET;
			} else if (line.contains("HttpMethod.POST.equals(method)") || line.contains("request.method() == HttpMethod.POST")) {
				currentMethod = HttpMethod.POST;
			} else if (line.contains("HttpMethod.DELETE.equals(method)") || line.contains("request.method() == HttpMethod.DELETE")) {
				currentMethod = HttpMethod.DELETE;
			} else if (line.contains("HttpMethod.PUT.equals(method)") || line.contains("request.method() == HttpMethod.PUT")) {
				currentMethod = HttpMethod.PUT;
			}

			// 检测路径匹配
			String path = extractPath(line);
			if (path != null) {
				// 如果没有明确的HTTP方法，尝试从函数名推断
				HttpMethod method = currentMethod;
				if (method == null) {
					method = inferHttpMethodFromContext(line, className);
				}

				if (method != null) {
					ApiEndpoint endpoint = new ApiEndpoint();
					endpoint.setPath(path);
					endpoint.setMethod(method.name());
					endpoint.setHandler(className);
					endpoint.setDescription(handlerDescription);

					// 提取查询参数
					List<ApiParameter> params = extractQueryParams(content, path);
					endpoint.setParameters(params);

					endpoints.add(endpoint);
				}

				currentMethod = null; // 重置
			}
		}

		return endpoints;
	}

	/**
	 * 从代码行中提取路径
	 */
	private String extractPath(String line) {
		// 尝试 equals 模式: "/api/test".equals(path)
		Matcher matcherEquals = PATH_PATTERN_EQUALS.matcher(line);
		if (matcherEquals.find()) {
			return matcherEquals.group(1);
		}

		// 尝试 startsWith 模式: startsWith("/api/test")
		Matcher matcherStarts = PATH_PATTERN_STARTS.matcher(line);
		if (matcherStarts.find()) {
			return matcherStarts.group(1);
		}

		return null;
	}

	/**
	 * 从源代码中提取查询参数
	 */
	private List<ApiParameter> extractQueryParams(String content, String path) {
		List<ApiParameter> params = new ArrayList<>();

		// 查找 getQueryParam 调用
		Pattern pattern = Pattern.compile("getQueryParam\\(query,\\s*\"([^\"]+)\"\\)");
		Matcher matcher = pattern.matcher(content);

		while (matcher.find()) {
			String paramName = matcher.group(1);
			params.add(new ApiParameter(paramName, "query", "string", false, "查询参数"));
		}

		return params;
	}

	/**
	 * 从上下文推断 HTTP 方法
	 */
	private HttpMethod inferHttpMethodFromContext(String line, String className) {
		// OpenAI 和 Anthropic 端点通常支持多种方法，默认使用 GET
		if (line.contains("startsWith(\"/v1/models") || line.contains("startsWith(\"/models")) {
			return HttpMethod.GET;
		}
		if (line.contains("startsWith(\"/v1/chat/completions") || line.contains("startsWith(\"/v1/completions")) {
			return HttpMethod.POST;
		}
		if (line.contains("startsWith(\"/v1/embeddings")) {
			return HttpMethod.POST;
		}
		if (line.contains("startsWith(\"/v1/messages")) {
			return HttpMethod.POST;
		}
		if (line.contains("startsWith(\"/v1/complete")) {
			return HttpMethod.POST;
		}

		// FileDownload 下载相关
		if (className.contains("FileDownload") && line.contains("list")) {
			return HttpMethod.GET;
		}
		if (className.contains("FileDownload") && line.contains("create")) {
			return HttpMethod.POST;
		}

		return null;
	}

	/**
	 * 生成 OpenAPI 3.0 规范
	 */
	public JsonObject generateOpenApiSpec() {
		JsonObject openApi = new JsonObject();

		// OpenAPI 版本
		openApi.addProperty("openapi", "3.0.0");

		// API 信息
		JsonObject info = new JsonObject();
		info.addProperty("title", "Llamacpp Server API");
		info.addProperty("description", "llama.cpp 服务器的 OpenAI 兼容 API");
		info.addProperty("version", "1.0.0");
		openApi.add("info", info);

		// 服务器配置
		JsonArray servers = new JsonArray();
		JsonObject server = new JsonObject();
		server.addProperty("url", "http://localhost:8080");
		server.addProperty("description", "本地服务器");
		servers.add(server);
		openApi.add("servers", servers);

		// 扫描并添加所有端点
		List<ApiEndpoint> endpoints = scanRouterHandlers();

		JsonObject paths = new JsonObject();
		for (ApiEndpoint endpoint : endpoints) {
			JsonObject pathItem = paths.has(endpoint.getPath()) ?
				paths.getAsJsonObject(endpoint.getPath()) : new JsonObject();

			JsonObject operation = new JsonObject();
			operation.addProperty("summary", endpoint.getDescription());
			operation.addProperty("operationId", endpoint.getHandler() + "_" + endpoint.getMethod().toLowerCase());

			// 添加参数
			if (!endpoint.getParameters().isEmpty()) {
				JsonArray parameters = new JsonArray();
				for (ApiParameter param : endpoint.getParameters()) {
					JsonObject paramObj = new JsonObject();
					paramObj.addProperty("name", param.getName());
					paramObj.addProperty("in", param.getIn());
					paramObj.addProperty("schema", "{\"type\":\"" + param.getType() + "\"}");
					if (param.isRequired()) {
						paramObj.addProperty("required", true);
					}
					parameters.add(paramObj);
				}
				operation.add("parameters", parameters);
			}

			// 添加响应
			JsonObject responses = new JsonObject();
			JsonObject response200 = new JsonObject();
			response200.addProperty("description", "成功");
			responses.add("200", response200);
			operation.add("responses", responses);

			pathItem.add(endpoint.getMethod().toLowerCase(), operation);
			paths.add(endpoint.getPath(), pathItem);
		}
		openApi.add("paths", paths);

		return openApi;
	}

	/**
	 * 生成并保存 OpenAPI JSON 文件
	 */
	public void generateOpenApiJsonFile(String outputPath) {
		try {
			JsonObject openApi = generateOpenApiSpec();
			String json = JsonUtil.toJson(openApi);

			Path path = Paths.get(outputPath).toAbsolutePath();
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(path, json);

			logger.info("OpenAPI JSON 已生成: {}", path.toAbsolutePath());
		} catch (IOException e) {
			logger.error("生成 OpenAPI JSON 失败", e);
		}
	}

	enum HttpMethod {
		GET, POST, PUT, DELETE, PATCH
	}
}
