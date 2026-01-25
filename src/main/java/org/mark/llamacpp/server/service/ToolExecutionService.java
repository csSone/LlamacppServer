package org.mark.llamacpp.server.service;

import java.io.IOException;

import org.mark.llamacpp.server.mcp.McpClientService;
import org.mark.llamacpp.server.tools.JsonUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;



/**
 * 	调用工具的服务。
 */
public class ToolExecutionService {
	
	
	/**
	 * 	这是智谱的实现。
	 */
	private static final ZhipuWebSearchService zhipuWebSearchService = new ZhipuWebSearchService();

	/**
	 * 	空构造器。
	 */
	public ToolExecutionService() {
		
	}
	
	/**
	 * 	执行工具并生成文字内容。
	 * @param toolName
	 * @param toolArguments
	 * @param preparedQuery
	 * @return
	 */
	public String executeToText(String toolName, String toolArguments, String preparedQuery) {
		if (toolName == null || toolName.isBlank()) {
			return "检测到 tool_calls，但无法解析工具名称";
		}
		String name = toolName.trim();
		if (!"builtin_web_search".equals(name)) {
			try {
				JsonObject resp = McpClientService.getInstance().callTool(name, toolArguments);
				return formatMcpToolResult(resp);
			} catch (Exception e) {
				return "MCP工具调用失败：" + e.getMessage();
			}
		}

		JsonObject args = null;
		try {
			if (toolArguments != null && !toolArguments.isBlank()) {
				args = JsonUtil.fromJson(toolArguments, JsonObject.class);
			}
		} catch (Exception ignore) {
		}
		String additionalContext = safeString(args, "additionalContext");

		String base = (preparedQuery == null ? "" : preparedQuery.trim());
		String extra = (additionalContext == null ? "" : additionalContext.trim());
		String query = base;
		if (!extra.isBlank()) {
			query = query.isBlank() ? extra : (query + "\n" + extra);
		}
		if (query.isBlank()) {
			return "联网搜索参数缺失：additionalContext";
		}
		// 最多查询10个结果。
		try {
			JsonObject resp = zhipuWebSearchService.search(query, 10);
			return formatZhipuResult(resp, query);
		} catch (IllegalStateException | IOException e) {
			throw new RuntimeException(e.getMessage(), e);
		} catch (Exception e) {
			return "联网搜索失败：" + e.getMessage();
		}
	}
	
	private String formatMcpToolResult(JsonObject resp) {
		if (resp == null) {
			return "";
		}
		if (resp.has("error") && resp.get("error") != null && resp.get("error").isJsonObject()) {
			JsonObject err = resp.getAsJsonObject("error");
			String msg = safeString(err, "message");
			return msg == null ? "MCP工具调用失败" : ("MCP工具调用失败：" + msg);
		}
		if (!resp.has("result") || resp.get("result") == null || !resp.get("result").isJsonObject()) {
			return resp.toString();
		}
		JsonObject result = resp.getAsJsonObject("result");
		if (!result.has("content") || result.get("content") == null || !result.get("content").isJsonArray()) {
			return result.toString();
		}
		JsonArray contentArr = result.getAsJsonArray("content");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < contentArr.size(); i++) {
			JsonElement el = contentArr.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			JsonObject item = el.getAsJsonObject();
			String type = safeString(item, "type");
			if (type != null && type.equals("text")) {
				String text = safeString(item, "text");
				if (text != null && !text.isBlank()) {
					if (sb.length() > 0) {
						sb.append("\n");
					}
					sb.append(text.trim());
				}
			} else {
				if (sb.length() > 0) {
					sb.append("\n");
				}
				sb.append(item.toString());
			}
		}
		return sb.toString().trim();
	}
	
	/**
	 * 	格式化智谱响应的内容。
	 * @param resp
	 * @param query
	 * @return
	 */
	private String formatZhipuResult(JsonObject resp, String query) {
		JsonArray arr = null;
		if (resp != null && resp.has("search_result") && resp.get("search_result").isJsonArray()) {
			arr = resp.getAsJsonArray("search_result");
		}
		if (arr == null || arr.size() == 0) {
			return "联网搜索（智谱）未找到结果：\n" + query;
		}
		StringBuilder sb = new StringBuilder();
		int limit = Math.min(arr.size(), 10);
		for (int i = 0; i < limit; i++) {
			JsonElement el = arr.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			String title = safeString(o, "title");
			String link = safeString(o, "link");
			String content = safeString(o, "content");
			if (title == null) {
				title = "";
			}
			if (link == null) {
				link = "";
			}
			if (content == null) {
				content = "";
			}
			sb.append(i + 1).append(". ").append(title).append("\n");
			if (!link.isBlank()) {
				sb.append(link).append("\n");
			}
			if (!content.isBlank()) {
				sb.append(trimForUi(content, 400)).append("\n");
			}
			sb.append("\n");
		}
		return sb.toString().trim();
	}

	private String trimForUi(String s, int maxChars) {
		String t = s == null ? "" : s.trim();
		if (t.length() <= maxChars) {
			return t;
		}
		return t.substring(0, Math.max(0, maxChars)) + "…";
	}

	private String safeString(JsonObject obj, String key) {
		try {
			if (obj == null || key == null) {
				return null;
			}
			JsonElement el = obj.get(key);
			if (el == null || el.isJsonNull()) {
				return null;
			}
			return el.getAsString();
		} catch (Exception e) {
			return null;
		}
	}
}
