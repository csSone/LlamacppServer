package org.mark.llamacpp.server.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.mark.llamacpp.server.tools.JsonUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * MCP (Model Context Protocol) 客户端服务类。
 * 负责管理 MCP 服务器的注册、连接（SSE 方式）以及工具的调用。
 */
public class McpClientService {

	private static final String JSONRPC_VERSION = "2.0";
	/** MCP 协议版本 */
	private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
	/** 工具调用默认超时时间（秒） */
	private static final int DEFAULT_CALL_TIMEOUT_SECONDS = 120;
	/** 服务就绪默认超时时间（秒） */
	private static final int DEFAULT_READY_TIMEOUT_SECONDS = 30;

	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) CherryStudio/1.7.13 Chrome/140.0.7339.249 Electron/38.7.0 Safari/537.36";
	private static final String HEADER_ACCEPT = "Accept";
	private static final String HEADER_USER_AGENT = "User-Agent";
	private static final String HEADER_CACHE_CONTROL = "Cache-Control";
	private static final String HEADER_CONNECTION = "Connection";
	private static final String HEADER_REFERER = "http-referer";
	private static final String HEADER_X_TITLE = "x-title";

	private static final McpClientService INSTANCE = new McpClientService(Paths.get("config", "mcp-tools.json"));

	public static McpClientService getInstance() {
		return INSTANCE;
	}

	/** 注册表文件路径，存储已配置的 MCP 服务信息 */
	private final Path registryPath;
	/** 按 URL 存储的 SSE 会话管理器 */
	private final Map<String, McpSseSession> sessionsByUrl = new ConcurrentHashMap<>();
	/** 工具名称到服务器 URL 的映射索引 */
	private final Map<String, String> toolToUrl = new ConcurrentHashMap<>();
	/** 每个服务器对应的自定义请求头 */
	private final Map<String, JsonObject> headersByUrl = new ConcurrentHashMap<>();

	private McpClientService(Path registryPath) {
		this.registryPath = registryPath;
	}

	/**
	 * 从注册表文件初始化 MCP 服务。
	 * 加载配置，启动激活状态的 SSE 会话，并建立工具索引。
	 */
	public synchronized void initializeFromRegistry() throws Exception {
		JsonObject registry = loadRegistry();
		Map<String, JsonObject> serverConfigs = extractServerConfigs(registry);
		toolToUrl.clear();
		
		Set<String> activeUrls = new HashSet<>();
		for (Map.Entry<String, JsonObject> entry : serverConfigs.entrySet()) {
			String url = entry.getKey();
			JsonObject server = entry.getValue();
			if (url == null || url.isBlank() || server == null) {
				continue;
			}
			String type = getString(server, "type");
			if (type == null || !type.equalsIgnoreCase("sse")) {
				continue;
			}
			// 仅处理激活状态的服务
			if (!getBoolean(server, "isActive", true)) {
				continue;
			}
			activeUrls.add(url);
		}
		
		// 清理不再需要的请求头和会话
		headersByUrl.keySet().removeIf(u -> !activeUrls.contains(u));
		for (String existingUrl : sessionsByUrl.keySet()) {
			if (!activeUrls.contains(existingUrl)) {
				McpSseSession old = sessionsByUrl.remove(existingUrl);
				if (old != null) {
					old.stop();
				}
			}
		}

		// 启动并索引激活的服务
		for (Map.Entry<String, JsonObject> entry : serverConfigs.entrySet()) {
			String url = entry.getKey();
			JsonObject server = entry.getValue();
			if (!activeUrls.contains(url)) {
				continue;
			}

			String type = getString(server, "type");
			if (type == null || !type.equalsIgnoreCase("sse")) {
				continue;
			}

			JsonObject headers = server.has("headers") && server.get("headers").isJsonObject() ? server.getAsJsonObject("headers") : null;
			if (headers == null || headers.size() == 0) {
				headersByUrl.remove(url);
			} else {
				headersByUrl.put(url, headers);
			}
			indexTools(url, server);
			sessionsByUrl.computeIfAbsent(url, u -> {
				McpSseSession s = new McpSseSession(u);
				s.start();
				return s;
			});
		}
	}

	/**
	 * 从 JSON 配置字符串添加 MCP 服务。
	 * 格式应包含 "mcpServers" 对象，每个键为服务 ID。
	 * 
	 * @param configJson 符合格式的 JSON 字符串
	 */
	public synchronized void addFromConfigJson(String configJson) throws Exception {
		JsonObject root = parseObject(configJson);
		JsonObject mcpServers = root.has("mcpServers") && root.get("mcpServers").isJsonObject()
				? root.getAsJsonObject("mcpServers")
				: new JsonObject();

		for (Map.Entry<String, JsonElement> entry : mcpServers.entrySet()) {
			if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
				continue;
			}
			String serverId = entry.getKey();
			JsonObject cfg = entry.getValue().getAsJsonObject();
			boolean active = getBoolean(cfg, "isActive", true);
			if (!active) {
				continue;
			}
			Boolean activeValue = cfg.has("isActive") ? Boolean.valueOf(active) : null;

			String type = getString(cfg, "type");
			String url = firstNonBlank(getString(cfg, "baseUrl"), getString(cfg, "url"));
			if (url == null || url.isBlank()) {
				continue;
			}
			String normalizedUrl = url.trim();
			if (type == null || !type.equalsIgnoreCase("sse")) {
				continue;
			}

			JsonObject headers = extractHeaders(cfg.get("headers"));
			// 预先从 SSE 获取工具列表，以验证连接并保存
			JsonElement tools = fetchToolsFromSse(normalizedUrl, headers);
			String displayName = getString(cfg, "name");
			String description = getString(cfg, "description");
			upsertServer(serverId, displayName, description, activeValue, "sse", normalizedUrl, headers, tools);
		}

		// 更新后重新初始化
		initializeFromRegistry();
	}

	/**
	 * 获取指定 URL 服务的已保存工具列表。
	 * 
	 * @param url 服务 URL
	 * @return 工具列表的 JsonElement，如果不存在则返回 null
	 */
	public synchronized JsonElement getSavedTools(String url) throws IOException {
		if (url == null || url.isBlank()) {
			return null;
		}
		JsonObject registry = loadRegistry();
		JsonObject servers = registry.getAsJsonObject("servers");
		if (servers == null || !servers.has(url) || !servers.get(url).isJsonObject()) {
			return null;
		}
		JsonObject server = servers.getAsJsonObject(url);
		return server.has("tools") ? server.get("tools") : null;
	}

	/**
	 * 获取完整的工具注册表内容。
	 */
	public synchronized JsonObject getSavedToolsRegistry() throws IOException {
		return loadRegistry();
	}

	/**
	 * 获取所有可用服务的全部工具列表，并在工具对象中注入服务器信息。
	 * 
	 * @return 包含所有工具的 JsonArray
	 */
	public synchronized JsonArray getAllAvailableTools() throws IOException {
		JsonObject registry = loadRegistry();
		JsonObject servers = registry.getAsJsonObject("servers");
		if (servers == null) {
			return new JsonArray();
		}

		Set<String> seen = new HashSet<>();
		JsonArray all = new JsonArray();
		for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
			String url = entry.getKey();
			JsonElement v = entry.getValue();
			if (url == null || url.isBlank() || v == null || !v.isJsonObject()) {
				continue;
			}
			JsonObject server = v.getAsJsonObject();
			String serverName = getString(server, "name");
			JsonElement toolsEl = server.get("tools");
			if (toolsEl == null || !toolsEl.isJsonArray()) {
				continue;
			}
			JsonArray tools = toolsEl.getAsJsonArray();
			for (int i = 0; i < tools.size(); i++) {
				JsonElement t = tools.get(i);
				if (t == null || !t.isJsonObject()) {
					continue;
				}
				String toolName = getString(t.getAsJsonObject(), "name");
				if (toolName == null || toolName.isBlank()) {
					continue;
				}
				String tn = toolName.trim();
				// 避免跨服务的同名工具重复（简单去重）
				if (!seen.add(tn)) {
					continue;
				}
				JsonObject tool = t.deepCopy().getAsJsonObject();
				tool.addProperty("mcpServerUrl", url);
				if (serverName != null && !serverName.isBlank()) {
					tool.addProperty("mcpServerName", serverName);
				}
				all.add(tool);
			}
		}
		return all;
	}

	/**
	 * 根据 URL 移除已注册的 MCP 服务，并停止相关会话。
	 * 
	 * @param url 要移除的服务 URL
	 * @return 是否移除成功
	 */
	public synchronized boolean removeServerByUrl(String url) throws IOException {
		if (url == null || url.isBlank()) {
			return false;
		}
		String normalizedUrl = url.trim();
		JsonObject registry = loadRegistry();
		JsonObject servers = registry.getAsJsonObject("servers");
		if (servers == null || !servers.has(normalizedUrl)) {
			return false;
		}
		servers.remove(normalizedUrl);
		saveRegistry(registry);
		toolToUrl.entrySet().removeIf(e -> normalizedUrl.equals(e.getValue()));
		headersByUrl.remove(normalizedUrl);
		McpSseSession session = sessionsByUrl.remove(normalizedUrl);
		if (session != null) {
			session.stop();
		}
		return true;
	}

	/**
	 * 调用指定名称的工具。会自动查找包含该工具的服务器。
	 * 
	 * @param toolName 工具名称
	 * @param toolArguments JSON 格式的参数字符串
	 * @return 调用结果 JsonObject
	 */
	public synchronized JsonObject callTool(String toolName, String toolArguments) throws Exception {
		if (toolName == null || toolName.isBlank()) {
			throw new IllegalArgumentException("toolName不能为空");
		}
		String name = toolName.trim();
		String url = toolToUrl.get(name);
		if (url == null) {
			url = findFirstServerUrlByToolName(name);
		}
		if (url == null) {
			throw new IllegalStateException("未找到包含该工具的MCP服务: " + name);
		}
		return callToolByUrl(url, name, toolArguments);
	}

	/**
	 * 在指定 URL 的服务器上调用工具。
	 * 
	 * @param url 服务器 URL
	 * @param toolName 工具名称
	 * @param toolArguments JSON 格式的参数字符串
	 * @return 调用结果 JsonObject
	 */
	public synchronized JsonObject callToolByUrl(String url, String toolName, String toolArguments) throws Exception {
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("url不能为空");
		}
		if (toolName == null || toolName.isBlank()) {
			throw new IllegalArgumentException("toolName不能为空");
		}

		JsonObject toolInfo = findToolInfo(url.trim(), toolName.trim());
		if (toolInfo == null) {
			throw new IllegalStateException("该MCP服务未记录此工具: url=" + url.trim() + ", tool=" + toolName.trim());
		}

		JsonObject argsObj = parseArgsObject(toolArguments);
		String finalUrl = url.trim();
		// 获取或创建会话
		McpSseSession session = sessionsByUrl.computeIfAbsent(finalUrl, u -> {
			McpSseSession s = new McpSseSession(u);
			s.start();
			return s;
		});
		return session.callTool(toolName.trim(), argsObj);
	}

	/**
	 * 从 SSE 服务获取工具列表。
	 * 该方法会临时建立连接并进行 MCP 握手，以获取服务器支持的工具。
	 */
	private JsonElement fetchToolsFromSse(String sseUrl, JsonObject headers) throws Exception {
		HttpURLConnection connection = createSseConnection(sseUrl, headers);

		boolean initialized = false;
		URI postUri = null;
		String lastEvent = null;

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String event = readSseFieldValue(line, "event");
				if (event != null) {
					lastEvent = event;
					continue;
				}

				String data = readSseFieldValue(line, "data");
				if (data == null) {
					continue;
				}

				// 处理 endpoint 事件，获取后续发送 POST 请求的地址
				if ("endpoint".equals(lastEvent)) {
					postUri = resolveEndpoint(sseUrl, data);
					initializeAndListTools(postUri, headers);
					initialized = true;
					continue;
				}

				if (!initialized) {
					continue;
				}
				if (!data.startsWith("{")) {
					continue;
				}
				JsonObject json = parseObject(data);
				// 查找 id 为 2 的响应（对应 tools/list 请求）
				if (json.has("id") && json.get("id").getAsInt() == 2) {
					if (json.has("result") && json.get("result").isJsonObject()) {
						JsonObject result = json.getAsJsonObject("result");
						if (result.has("tools")) {
							return result.get("tools");
						}
					}
				}
			}
		} finally {
			connection.disconnect();
		}

		throw new IOException("未收到 tools/list 响应");
	}

	private HttpURLConnection createSseConnection(String sseUrl) throws IOException, URISyntaxException {
		return createSseConnection(sseUrl, headersByUrl.get(sseUrl));
	}

	/**
	 * 创建到 SSE 服务器的 HTTP 连接。
	 */
	private HttpURLConnection createSseConnection(String sseUrl, JsonObject headers) throws IOException, URISyntaxException {
		URI uri = new URI(sseUrl);
		URL url = uri.toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setRequestMethod("GET");

		// 设置 SSE 标准请求头
		connection.setRequestProperty(HEADER_ACCEPT, "text/event-stream");
		connection.setRequestProperty(HEADER_USER_AGENT, USER_AGENT);
		connection.setRequestProperty(HEADER_CACHE_CONTROL, "no-cache");
		connection.setRequestProperty(HEADER_CONNECTION, "keep-alive");
		connection.setRequestProperty(HEADER_REFERER, "https://cherry-ai.com");
		connection.setRequestProperty(HEADER_X_TITLE, "Cherry Studio");
		applyResolvedHeaders(connection, headers);

		int responseCode = connection.getResponseCode();
		if (responseCode != 200) {
			String err = readAll(connection.getErrorStream());
			throw new IOException("SSE连接失败，状态码=" + responseCode + ", body=" + (err == null ? "" : err));
		}
		return connection;
	}

	/**
	 * 执行 MCP 握手并请求工具列表。
	 */
	private void initializeAndListTools(URI postUri, JsonObject headers) throws IOException {
		performMcpHandshake(postUri, headers);

		JsonObject listTools = new JsonObject();
		listTools.addProperty("jsonrpc", JSONRPC_VERSION);
		listTools.addProperty("id", 2);
		listTools.addProperty("method", "tools/list");
		sendPost(postUri, listTools, headers);
	}

	/**
	 * 执行标准的 MCP 握手流程：initialize -> notifications/initialized。
	 */
	private void performMcpHandshake(URI postUri, JsonObject headers) throws IOException {
		JsonObject initMsg = new JsonObject();
		initMsg.addProperty("jsonrpc", JSONRPC_VERSION);
		initMsg.addProperty("id", 1);
		initMsg.addProperty("method", "initialize");
		JsonObject initParams = new JsonObject();
		initParams.addProperty("protocolVersion", MCP_PROTOCOL_VERSION);
		JsonObject clientInfo = new JsonObject();
		clientInfo.addProperty("name", "JavaMcpClient");
		clientInfo.addProperty("version", "1.0.0");
		initParams.add("clientInfo", clientInfo);
		JsonObject capabilities = new JsonObject();
		capabilities.add("roots", new JsonObject());
		initParams.add("capabilities", capabilities);
		initMsg.add("params", initParams);
		sendPost(postUri, initMsg, headers);

		JsonObject initializedMsg = new JsonObject();
		initializedMsg.addProperty("jsonrpc", JSONRPC_VERSION);
		initializedMsg.addProperty("method", "notifications/initialized");
		sendPost(postUri, initializedMsg, headers);
	}

	/**
	 * 更新或插入服务器配置，并过滤同名工具。
	 */
	private void upsertServer(String serverId, String displayName, String description, Boolean isActive, String type,
			String url, JsonObject headers, JsonElement tools) throws IOException {
		JsonObject registry = loadRegistry();
		JsonObject servers = registry.has("servers") && registry.get("servers").isJsonObject()
				? registry.getAsJsonObject("servers")
				: new JsonObject();

		String normalizedUrl = url == null ? "" : url.trim();
		if (normalizedUrl.isEmpty()) {
			return;
		}
		// 收集现有其他服务器的工具名，用于过滤重复
		Set<String> existingToolNames = new HashSet<>();
		for (Map.Entry<String, JsonElement> e : servers.entrySet()) {
			String serverUrl = e.getKey();
			if (serverUrl == null || serverUrl.isBlank()) {
				continue;
			}
			if (!normalizedUrl.isEmpty() && normalizedUrl.equals(serverUrl.trim())) {
				continue;
			}
			JsonElement serverEl = e.getValue();
			if (serverEl == null || !serverEl.isJsonObject()) {
				continue;
			}
			JsonElement toolsEl = serverEl.getAsJsonObject().get("tools");
			collectToolNames(existingToolNames, toolsEl);
		}

		JsonObject server = new JsonObject();
		server.addProperty("id", serverId);
		server.addProperty("name", (displayName == null || displayName.isBlank()) ? serverId : displayName);
		if (description != null && !description.isBlank()) {
			server.addProperty("description", description);
		}
		if (isActive != null) {
			server.addProperty("isActive", isActive.booleanValue());
		}
		server.addProperty("type", type);
		server.addProperty("url", normalizedUrl);
		server.addProperty("savedAt", System.currentTimeMillis());
		if (headers != null && headers.size() > 0) {
			server.add("headers", headers.deepCopy());
		}
		if (tools != null) {
			server.add("tools", filterNewTools(existingToolNames, tools));
		}

		servers.add(normalizedUrl, server);
		registry.add("servers", servers);
		registry.addProperty("version", 1);
		saveRegistry(registry);
		if (headers == null || headers.size() == 0) {
			headersByUrl.remove(normalizedUrl);
		} else {
			headersByUrl.put(normalizedUrl, headers);
		}
	}
	
	private static void collectToolNames(Set<String> out, JsonElement toolsEl) {
		if (out == null || toolsEl == null || !toolsEl.isJsonArray()) {
			return;
		}
		JsonArray arr = toolsEl.getAsJsonArray();
		for (int i = 0; i < arr.size(); i++) {
			JsonElement el = arr.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			String name = getString(el.getAsJsonObject(), "name");
			if (name == null || name.isBlank()) {
				continue;
			}
			out.add(name.trim());
		}
	}
	
	private static JsonElement filterNewTools(Set<String> existingToolNames, JsonElement tools) {
		if (tools == null || !tools.isJsonArray()) {
			return tools;
		}
		Set<String> exist = existingToolNames == null ? Set.of() : existingToolNames;
		Set<String> seen = new HashSet<>();
		JsonArray in = tools.getAsJsonArray();
		JsonArray out = new JsonArray();
		for (int i = 0; i < in.size(); i++) {
			JsonElement el = in.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			JsonObject tool = el.getAsJsonObject();
			String name = getString(tool, "name");
			if (name == null || name.isBlank()) {
				continue;
			}
			String tn = name.trim();
			if (!seen.add(tn)) {
				continue;
			}
			if (exist.contains(tn)) {
				continue;
			}
			out.add(tool.deepCopy());
		}
		return out;
	}

	private JsonObject loadRegistry() throws IOException {
		if (!Files.exists(registryPath)) {
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			root.add("servers", new JsonObject());
			return root;
		}
		String raw = Files.readString(registryPath, StandardCharsets.UTF_8);
		JsonObject parsed = parseObject(raw);
		if (!parsed.has("servers") || !parsed.get("servers").isJsonObject()) {
			parsed.add("servers", new JsonObject());
		}
		if (!parsed.has("version")) {
			parsed.addProperty("version", 1);
		}
		return parsed;
	}

	private void saveRegistry(JsonObject registry) throws IOException {
		Path parent = registryPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(registryPath, JsonUtil.toJson(registry), StandardCharsets.UTF_8);
	}

	private void sendPost(URI uri, JsonObject json, JsonObject headers) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("User-Agent", "JavaMcpClient");
		applyResolvedHeaders(conn, headers);
		conn.setDoOutput(true);

		try (OutputStream os = conn.getOutputStream()) {
			byte[] input = JsonUtil.toJson(json).getBytes(StandardCharsets.UTF_8);
			os.write(input, 0, input.length);
		}

		int code = conn.getResponseCode();
		InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
		readAll(stream);
		conn.disconnect();
	}

	private static String readAll(InputStream is) throws IOException {
		if (is == null) {
			return null;
		}
		try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		}
	}

	private static String readSseFieldValue(String line, String field) {
		if (line == null || field == null || field.isBlank()) {
			return null;
		}
		String trimmed = line.trim();
		int idx = trimmed.indexOf(':');
		if (idx <= 0) {
			return null;
		}
		String k = trimmed.substring(0, idx).trim();
		if (!field.equals(k)) {
			return null;
		}
		String v = trimmed.substring(idx + 1);
		if (!v.isEmpty() && v.charAt(0) == ' ') {
			v = v.substring(1);
		}
		return v;
	}

	private static URI resolveEndpoint(String sseUrl, String endpoint) {
		if (endpoint == null || endpoint.isBlank()) {
			throw new IllegalArgumentException("endpoint不能为空");
		}
		String value = endpoint.trim();
		if (!value.startsWith("/") && value.startsWith("api/")) {
			value = "/" + value;
		}
		if (value.startsWith("http://") || value.startsWith("https://")) {
			return URI.create(value);
		}
		return URI.create(sseUrl).resolve(value);
	}

	private static JsonObject parseObject(String json) {
		if (json == null || json.isBlank()) {
			return new JsonObject();
		}
		JsonElement el = JsonParser.parseString(json);
		if (el == null || !el.isJsonObject()) {
			return new JsonObject();
		}
		return el.getAsJsonObject();
	}

	private static String getString(JsonObject obj, String key) {
		if (obj == null || key == null || !obj.has(key) || obj.get(key) == null || obj.get(key).isJsonNull()) {
			return null;
		}
		try {
			return obj.get(key).getAsString();
		} catch (Exception e) {
			return null;
		}
	}
	
	private static boolean getBoolean(JsonObject obj, String key, boolean defaultValue) {
		if (obj == null || key == null || !obj.has(key) || obj.get(key) == null || obj.get(key).isJsonNull()) {
			return defaultValue;
		}
		try {
			return obj.get(key).getAsBoolean();
		} catch (Exception e) {
			return defaultValue;
		}
	}
	
	private static String firstNonBlank(String a, String b) {
		if (a != null && !a.isBlank()) {
			return a;
		}
		return b;
	}
	
	private static JsonObject extractHeaders(JsonElement el) {
		if (el == null || el.isJsonNull() || !el.isJsonObject()) {
			return null;
		}
		JsonObject in = el.getAsJsonObject();
		JsonObject out = new JsonObject();
		for (Map.Entry<String, JsonElement> e : in.entrySet()) {
			String k = e.getKey();
			JsonElement v = e.getValue();
			if (k == null || k.isBlank() || v == null || v.isJsonNull()) {
				continue;
			}
			if (v.isJsonPrimitive()) {
				out.addProperty(k, v.getAsString());
			}
		}
		return out.size() == 0 ? null : out;
	}
	
	private static void applyResolvedHeaders(HttpURLConnection conn, JsonObject headers) {
		if (conn == null || headers == null || headers.size() == 0) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : headers.entrySet()) {
			String key = e.getKey();
			JsonElement valueEl = e.getValue();
			if (key == null || key.isBlank() || valueEl == null || valueEl.isJsonNull() || !valueEl.isJsonPrimitive()) {
				continue;
			}
			String raw = valueEl.getAsString();
			if (raw == null) {
				continue;
			}
			conn.setRequestProperty(key, resolveEnvPlaceholders(raw));
		}
	}
	
	/**
	 * 解析环境变占位符，例如将 "${API_KEY}" 替换为环境变量中的值。
	 */
	private static String resolveEnvPlaceholders(String input) {
		if (input == null || input.isEmpty()) {
			return input;
		}
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < input.length()) {
			int start = input.indexOf("${", i);
			if (start < 0) {
				out.append(input, i, input.length());
				break;
			}
			out.append(input, i, start);
			int end = input.indexOf("}", start + 2);
			if (end < 0) {
				out.append(input.substring(start));
				break;
			}
			String var = input.substring(start + 2, end);
			String env = (var == null || var.isBlank()) ? null : System.getenv(var);
			out.append(env == null ? input.substring(start, end + 1) : env);
			i = end + 1;
		}
		return out.toString();
	}

	private static Map<String, JsonObject> extractServerConfigs(JsonObject registry) {
		if (registry == null) {
			return Map.of();
		}
		JsonObject servers = registry.getAsJsonObject("servers");
		if (servers == null) {
			return Map.of();
		}
		Map<String, JsonObject> result = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : servers.entrySet()) {
			String url = e.getKey();
			JsonElement v = e.getValue();
			if (url == null || url.isBlank() || v == null || !v.isJsonObject()) {
				continue;
			}
			result.put(url, v.getAsJsonObject());
		}
		return result;
	}

	/**
	 * 建立工具名称到服务器 URL 的索引，方便快速查找。
	 */
	private void indexTools(String url, JsonObject server) {
		if (url == null || url.isBlank() || server == null) {
			return;
		}
		JsonElement toolsEl = server.get("tools");
		if (toolsEl == null || !toolsEl.isJsonArray()) {
			return;
		}
		JsonArray arr = toolsEl.getAsJsonArray();
		for (int i = 0; i < arr.size(); i++) {
			JsonElement el = arr.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			String toolName = getString(el.getAsJsonObject(), "name");
			if (toolName == null || toolName.isBlank()) {
				continue;
			}
			toolToUrl.putIfAbsent(toolName.trim(), url);
		}
	}

	/**
	 * 管理单个 MCP 服务器 SSE 会话的内部类。
	 * 负责保持 SSE 连接、处理握手、并支持异步工具调用请求。
	 */
	private final class McpSseSession {
		private final String sseUrl;
		private final AtomicBoolean running = new AtomicBoolean(false);
		private final AtomicBoolean handshakeCompleted = new AtomicBoolean(false);
		private final AtomicInteger requestId = new AtomicInteger(10);
		/** 存储等待响应的请求 id 与对应的 CompletableFuture */
		private final Map<Integer, CompletableFuture<JsonObject>> pendingById = new ConcurrentHashMap<>();
		private String lastEvent;

		private volatile URI postUri;
		private volatile HttpURLConnection connection;
		private volatile Thread readerThread;
		/** 用于同步等待 endpoint 事件 */
		private volatile CountDownLatch endpointLatch = new CountDownLatch(1);
		/** 用于同步等待握手完成 */
		private volatile CountDownLatch handshakeLatch = new CountDownLatch(1);

		private McpSseSession(String sseUrl) {
			this.sseUrl = Objects.requireNonNull(sseUrl);
		}

		/**
		 * 启动 SSE 接收线程。
		 */
		private void start() {
			if (!running.compareAndSet(false, true)) {
				return;
			}
			Thread t = new Thread(this::runLoop, "mcp-sse-" + safeThreadName(sseUrl));
			t.setDaemon(true);
			readerThread = t;
			t.start();
		}

		/**
		 * 停止会话，断开连接并清理待处理请求。
		 */
		private void stop() {
			running.set(false);
			HttpURLConnection c = connection;
			if (c != null) {
				try {
					c.disconnect();
				} catch (Exception ignore) {
				}
			}
			Thread t = readerThread;
			if (t != null) {
				try {
					t.interrupt();
				} catch (Exception ignore) {
				}
			}
			failAllPending(new IOException("MCP SSE session stopped"));
		}

		/**
		 * 执行工具调用。会等待会话就绪。
		 */
		private JsonObject callTool(String toolName, JsonObject args) throws Exception {
			awaitReady(DEFAULT_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			URI uri = postUri;
			if (uri == null) {
				throw new IllegalStateException("MCP endpoint 未就绪: " + sseUrl);
			}

			int id = requestId.getAndIncrement();
			CompletableFuture<JsonObject> future = new CompletableFuture<>();
			pendingById.put(id, future);
			try {
				JsonObject call = new JsonObject();
				call.addProperty("jsonrpc", JSONRPC_VERSION);
				call.addProperty("id", id);
				call.addProperty("method", "tools/call");

				JsonObject params = new JsonObject();
				params.addProperty("name", toolName);
				params.add("arguments", args == null ? new JsonObject() : args);
				call.add("params", params);

				sendPost(uri, call, headersByUrl.get(sseUrl));
				// 阻塞等待响应
				return future.get(DEFAULT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (Exception e) {
				pendingById.remove(id);
				throw e;
			}
		}

		/**
		 * 等待 SSE 连接和握手完成。
		 */
		private void awaitReady(long timeout, TimeUnit unit) throws Exception {
			boolean endpointOk = endpointLatch.await(timeout, unit);
			if (!endpointOk) {
				throw new IOException("等待 MCP endpoint 超时: " + sseUrl);
			}
			boolean handshakeOk = handshakeLatch.await(timeout, unit);
			if (!handshakeOk) {
				throw new IOException("等待 MCP handshake 超时: " + sseUrl);
			}
		}

		/**
		 * 运行主循环，处理连接、读取 SSE 数据和自动重连。
		 */
		private void runLoop() {
			long backoffMs = 500;
			while (running.get()) {
				try {
					resetSessionState();
					connection = createSseConnection(sseUrl);
					try (BufferedReader reader = new BufferedReader(
							new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
						String line;
						while (running.get() && (line = reader.readLine()) != null) {
							handleSseLine(reader, line);
						}
					}
				} catch (Exception e) {
					failAllPending(e);
				} finally {
					HttpURLConnection c = connection;
					if (c != null) {
						try {
							c.disconnect();
						} catch (Exception ignore) {
						}
					}
					connection = null;
				}

				if (!running.get()) {
					return;
				}

				// 指数退避重连
				try {
					Thread.sleep(backoffMs);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
				backoffMs = Math.min(10_000, backoffMs * 2);
			}
		}

		private void resetSessionState() {
			postUri = null;
			handshakeCompleted.set(false);
			endpointLatch = new CountDownLatch(1);
			handshakeLatch = new CountDownLatch(1);
			lastEvent = null;
		}

		/**
		 * 处理 SSE 的每一行数据。
		 */
		private void handleSseLine(BufferedReader reader, String line) throws Exception {
			String event = readSseFieldValue(line, "event");
			if (event != null) {
				lastEvent = event;
				return;
			}

			String data = readSseFieldValue(line, "data");
			if (data == null) {
				return;
			}

			if ("endpoint".equals(lastEvent)) {
				postUri = resolveEndpoint(sseUrl, data);
				endpointLatch.countDown();
				performHandshakeOnce();
				return;
			}

			if (!data.startsWith("{")) {
				return;
			}
			JsonObject json = parseObject(data);
			// 匹配响应 ID 并通知等待中的 CompletableFuture
			if (json.has("id") && json.get("id").isJsonPrimitive()) {
				try {
					int id = json.get("id").getAsInt();
					CompletableFuture<JsonObject> pending = pendingById.remove(id);
					if (pending != null) {
						pending.complete(json);
					}
				} catch (Exception ignore) {
				}
			}
		}

		/**
		 * 执行一次 MCP 握手流程。
		 */
		private void performHandshakeOnce() throws IOException {
			if (!handshakeCompleted.compareAndSet(false, true)) {
				return;
			}
			URI uri = postUri;
			if (uri == null) {
				handshakeLatch.countDown();
				return;
			}

			CompletableFuture<JsonObject> initFuture = new CompletableFuture<>();
			pendingById.put(1, initFuture);
			try {
				JsonObject initMsg = new JsonObject();
				initMsg.addProperty("jsonrpc", JSONRPC_VERSION);
				initMsg.addProperty("id", 1);
				initMsg.addProperty("method", "initialize");
				JsonObject initParams = new JsonObject();
				initParams.addProperty("protocolVersion", MCP_PROTOCOL_VERSION);
				JsonObject clientInfo = new JsonObject();
				clientInfo.addProperty("name", "JavaMcpClient");
				clientInfo.addProperty("version", "1.0.0");
				initParams.add("clientInfo", clientInfo);
				JsonObject capabilities = new JsonObject();
				capabilities.add("roots", new JsonObject());
				initParams.add("capabilities", capabilities);
				initMsg.add("params", initParams);
				sendPost(uri, initMsg, headersByUrl.get(sseUrl));

				try {
					// 等待初始化响应
					initFuture.get(10, TimeUnit.SECONDS);
				} catch (Exception ignore) {
				} finally {
					pendingById.remove(1);
				}

				// 发送初始化完成通知
				JsonObject initializedMsg = new JsonObject();
				initializedMsg.addProperty("jsonrpc", JSONRPC_VERSION);
				initializedMsg.addProperty("method", "notifications/initialized");
				sendPost(uri, initializedMsg, headersByUrl.get(sseUrl));
			} finally {
				handshakeLatch.countDown();
			}
		}

		/**
		 * 连接异常时失败所有等待中的请求。
		 */
		private void failAllPending(Exception e) {
			for (Map.Entry<Integer, CompletableFuture<JsonObject>> entry : pendingById.entrySet()) {
				CompletableFuture<JsonObject> f = entry.getValue();
				if (f != null && !f.isDone()) {
					f.completeExceptionally(e);
				}
			}
			pendingById.clear();
		}
	}

	/**
	 * 生成安全的线程名称。
	 */
	private static String safeThreadName(String s) {
		if (s == null || s.isBlank()) {
			return "unknown";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (Character.isLetterOrDigit(ch)) {
				sb.append(ch);
				continue;
			}
			sb.append('_');
		}
		String out = sb.toString();
		return out.length() > 80 ? out.substring(0, 80) : out;
	}

	/**
	 * 解析工具参数字符串为 JsonObject。
	 */
	private JsonObject parseArgsObject(String toolArguments) {
		if (toolArguments == null || toolArguments.isBlank()) {
			return new JsonObject();
		}
		try {
			JsonElement el = JsonParser.parseString(toolArguments);
			if (el != null && el.isJsonObject()) {
				return el.getAsJsonObject();
			}
		} catch (Exception ignore) {
		}
		return new JsonObject();
	}

	/**
	 * 遍历注册表，查找第一个包含指定工具的服务器 URL。
	 */
	private String findFirstServerUrlByToolName(String toolName) throws IOException {
		JsonObject registry = loadRegistry();
		JsonObject servers = registry.getAsJsonObject("servers");
		if (servers == null) {
			return null;
		}
		for (Map.Entry<String, JsonElement> entry : servers.entrySet()) {
			String url = entry.getKey();
			JsonElement v = entry.getValue();
			if (v == null || !v.isJsonObject()) {
				continue;
			}
			JsonObject server = v.getAsJsonObject();
			JsonObject tool = findToolInfoInServer(server, toolName);
			if (tool != null) {
				return url;
			}
		}
		return null;
	}

	/**
	 * 在指定 URL 的服务中查找工具详情。
	 */
	private JsonObject findToolInfo(String url, String toolName) throws IOException {
		JsonObject registry = loadRegistry();
		JsonObject servers = registry.getAsJsonObject("servers");
		if (servers == null || !servers.has(url) || !servers.get(url).isJsonObject()) {
			return null;
		}
		return findToolInfoInServer(servers.getAsJsonObject(url), toolName);
	}

	/**
	 * 在给定的服务器 JSON 对象中查找指定名称的工具。
	 */
	private JsonObject findToolInfoInServer(JsonObject server, String toolName) {
		if (server == null || toolName == null || toolName.isBlank()) {
			return null;
		}
		if (!server.has("tools") || server.get("tools") == null || server.get("tools").isJsonNull()) {
			return null;
		}
		JsonElement toolsEl = server.get("tools");
		if (!toolsEl.isJsonArray()) {
			return null;
		}
		JsonArray arr = toolsEl.getAsJsonArray();
		for (int i = 0; i < arr.size(); i++) {
			JsonElement el = arr.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			JsonObject tool = el.getAsJsonObject();
			String name = getString(tool, "name");
			if (name != null && name.equals(toolName)) {
				return tool;
			}
		}
		return null;
	}
}
