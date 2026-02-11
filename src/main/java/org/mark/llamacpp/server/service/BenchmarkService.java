package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class BenchmarkService {
	
	private static final Logger logger = LoggerFactory.getLogger(BenchmarkService.class);
	
	public static class BenchmarkTokenOptions {
		public String unitText = " a";
		public int maxIterations = 24;
		public int tolerance = 0;
		public int sampleCount = 64;
		public boolean addSpecial = true;
		public boolean parseSpecial = true;
	}
	
	private static class PromptTokenResult {
		private final String prompt;
		private final int tokenCount;
		
		private PromptTokenResult(String prompt, int tokenCount) {
			this.prompt = prompt;
			this.tokenCount = tokenCount;
		}
	}
	
	public JsonObject generatePromptForTargetTokens(String modelId, JsonArray messages, int targetTokens) {
		return generatePromptForTargetTokens(modelId, messages, targetTokens, null);
	}
	
	public JsonObject generatePromptForTargetTokens(String modelId, JsonArray messages, int targetTokens,
			BenchmarkTokenOptions options) {
		if (targetTokens <= 0) {
			throw new IllegalArgumentException("targetTokens必须大于0");
		}
		BenchmarkTokenOptions opt = options == null ? new BenchmarkTokenOptions() : options;
		String finalModelId = resolveModelId(modelId);
		JsonArray workingMessages = messages == null ? new JsonArray() : messages.deepCopy();
		JsonObject targetMsg = ensureUserMessage(workingMessages);
		String baseContent = readMessageContent(targetMsg);
		
		PromptTokenResult base = countPromptTokens(finalModelId, workingMessages, opt.addSpecial, opt.parseSpecial);
		int baseTokens = base.tokenCount;
		if (baseTokens >= targetTokens) {
			JsonObject out = new JsonObject();
			out.addProperty("modelId", finalModelId);
			out.addProperty("targetTokens", targetTokens);
			out.addProperty("promptTokens", baseTokens);
			out.addProperty("baseTokens", baseTokens);
			out.addProperty("content", baseContent);
			out.addProperty("prompt", base.prompt);
			out.addProperty("iterations", 0);
			return out;
		}
		
		int sampleCount = Math.max(1, opt.sampleCount);
		String sampleText = repeatUnit(opt.unitText, sampleCount);
		setMessageContent(targetMsg, baseContent + sampleText);
		PromptTokenResult sample = countPromptTokens(finalModelId, workingMessages, opt.addSpecial, opt.parseSpecial);
		int delta = sample.tokenCount - baseTokens;
		if (delta <= 0) {
			delta = sampleCount;
		}
		double tokensPerUnit = delta / (double) sampleCount;
		int needed = (int) Math.ceil((targetTokens - baseTokens) / Math.max(tokensPerUnit, 0.01d));
		int low = 0;
		int high = Math.max(needed, 1);
		
		int expand = 0;
		while (expand < 8) {
			setMessageContent(targetMsg, baseContent + repeatUnit(opt.unitText, high));
			PromptTokenResult r = countPromptTokens(finalModelId, workingMessages, opt.addSpecial, opt.parseSpecial);
			if (r.tokenCount >= targetTokens) {
				break;
			}
			high = high * 2;
			expand++;
		}
		
		int bestUnits = 0;
		PromptTokenResult bestResult = base;
		int iterations = 0;
		while (low <= high && iterations < opt.maxIterations) {
			int mid = low + (high - low) / 2;
			setMessageContent(targetMsg, baseContent + repeatUnit(opt.unitText, mid));
			PromptTokenResult r = countPromptTokens(finalModelId, workingMessages, opt.addSpecial, opt.parseSpecial);
			iterations++;
			if (r.tokenCount == targetTokens) {
				bestUnits = mid;
				bestResult = r;
				break;
			}
			if (r.tokenCount < targetTokens) {
				bestUnits = mid;
				bestResult = r;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		
		int bestTokens = bestResult.tokenCount;
		if (bestTokens < targetTokens) {
			int start = Math.max(bestUnits + 1, 1);
			int limit = bestUnits + 16;
			for (int i = start; i <= limit; i++) {
				setMessageContent(targetMsg, baseContent + repeatUnit(opt.unitText, i));
				PromptTokenResult r = countPromptTokens(finalModelId, workingMessages, opt.addSpecial, opt.parseSpecial);
				iterations++;
				if (r.tokenCount >= targetTokens) {
					bestUnits = i;
					bestResult = r;
					bestTokens = r.tokenCount;
					break;
				}
			}
		}
		
		if (bestTokens > targetTokens && opt.tolerance > 0 && bestTokens - targetTokens <= opt.tolerance) {
			bestTokens = bestResult.tokenCount;
		}
		
		String finalContent = baseContent + repeatUnit(opt.unitText, bestUnits);
		setMessageContent(targetMsg, finalContent);
		PromptTokenResult finalResult = bestResult;
		if (finalResult.prompt == null || finalResult.tokenCount != bestTokens) {
			finalResult = countPromptTokens(finalModelId, workingMessages, opt.addSpecial, opt.parseSpecial);
		}
		
		JsonObject out = new JsonObject();
		out.addProperty("modelId", finalModelId);
		out.addProperty("targetTokens", targetTokens);
		out.addProperty("promptTokens", finalResult.tokenCount);
		out.addProperty("baseTokens", baseTokens);
		out.addProperty("content", finalContent);
		out.addProperty("prompt", finalResult.prompt);
		out.addProperty("iterations", iterations);
		out.addProperty("unitText", opt.unitText);
		return out;
	}
	
	private String resolveModelId(String modelId) {
		LlamaServerManager manager = LlamaServerManager.getInstance();
		String id = modelId == null ? null : modelId.trim();
		if (id == null || id.isEmpty()) {
			id = manager.getFirstModelName();
		}
		if (id == null || id.isEmpty()) {
			throw new IllegalStateException("模型未加载");
		}
		if (!manager.getLoadedProcesses().containsKey(id)) {
			throw new IllegalStateException("模型未加载: " + id);
		}
		return id;
	}
	
	private PromptTokenResult countPromptTokens(String modelId, JsonArray messages, boolean addSpecial, boolean parseSpecial) {
		String prompt = applyTemplate(modelId, messages);
		int tokenCount = tokenizePrompt(modelId, prompt, addSpecial, parseSpecial);
		return new PromptTokenResult(prompt, tokenCount);
	}
	
	private String applyTemplate(String modelId, JsonArray messages) {
		JsonObject payload = new JsonObject();
		payload.add("messages", messages == null ? new JsonArray() : messages);
		JsonObject resp = postJson(modelId, "/apply-template", payload);
		if (resp == null || !resp.has("prompt") || resp.get("prompt").isJsonNull()) {
			throw new IllegalStateException("apply-template响应缺少prompt字段");
		}
		return resp.get("prompt").getAsString();
	}
	
	private int tokenizePrompt(String modelId, String content, boolean addSpecial, boolean parseSpecial) {
		JsonObject payload = new JsonObject();
		payload.addProperty("content", content == null ? "" : content);
		payload.addProperty("add_special", addSpecial);
		payload.addProperty("parse_special", parseSpecial);
		payload.addProperty("with_pieces", false);
		JsonObject resp = postJson(modelId, "/tokenize", payload);
		int count = extractTokenCount(resp);
		if (count < 0) {
			throw new IllegalStateException("tokenize响应缺少tokens字段");
		}
		return count;
	}
	
	private int extractTokenCount(JsonObject resp) {
		if (resp == null || !resp.has("tokens") || resp.get("tokens") == null || !resp.get("tokens").isJsonArray()) {
			return -1;
		}
		JsonArray arr = resp.getAsJsonArray("tokens");
		return arr.size();
	}
	
	private JsonObject postJson(String modelId, String path, JsonObject payload) {
		HttpURLConnection connection = null;
		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				throw new IllegalStateException("未找到模型端口: " + modelId);
			}
			String targetUrl = String.format("http://localhost:%d%s", port.intValue(), path);
			URL url = URI.create(targetUrl).toURL();
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			byte[] outBytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Length", String.valueOf(outBytes.length));
			try (OutputStream os = connection.getOutputStream()) {
				os.write(outBytes);
			}
			int responseCode = connection.getResponseCode();
			String responseBody = readBody(connection, responseCode >= 200 && responseCode < 300);
			JsonElement parsed = null;
			try {
				parsed = JsonUtil.fromJson(responseBody, JsonElement.class);
			} catch (Exception ignore) {
			}
			if (parsed != null && parsed.isJsonObject()) {
				return parsed.getAsJsonObject();
			}
			if (responseBody != null && !responseBody.isBlank()) {
				throw new IllegalStateException(responseBody);
			}
			throw new IllegalStateException("模型返回了非JSON响应");
		} catch (Exception e) {
			logger.info("调用模型接口失败: " + path, e);
			throw new RuntimeException("调用模型接口失败: " + e.getMessage(), e);
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ignore) {
				}
			}
		}
	}
	
	private static String readBody(HttpURLConnection connection, boolean ok) {
		if (connection == null) return "";
		InputStream in = null;
		try {
			in = ok ? connection.getInputStream() : connection.getErrorStream();
			if (in == null) return "";
			try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = br.readLine()) != null) {
					sb.append(line);
				}
				return sb.toString();
			}
		} catch (Exception e) {
			return "";
		}
	}
	
	private JsonObject ensureUserMessage(JsonArray messages) {
		JsonObject lastUser = null;
		if (messages != null) {
			for (int i = messages.size() - 1; i >= 0; i--) {
				JsonElement el = messages.get(i);
				if (el == null || !el.isJsonObject()) {
					continue;
				}
				JsonObject obj = el.getAsJsonObject();
				String role = readString(obj.get("role"));
				if ("user".equals(role)) {
					lastUser = obj;
					break;
				}
			}
		}
		if (lastUser == null) {
			lastUser = new JsonObject();
			lastUser.addProperty("role", "user");
			lastUser.addProperty("content", "");
			messages.add(lastUser);
		}
		return lastUser;
	}
	
	private String readMessageContent(JsonObject msg) {
		if (msg == null) return "";
		JsonElement el = msg.get("content");
		if (el == null || el.isJsonNull()) return "";
		if (el.isJsonPrimitive()) {
			try {
				return el.getAsString();
			} catch (Exception ignore) {
			}
		}
		return JsonUtil.jsonValueToString(el);
	}
	
	private void setMessageContent(JsonObject msg, String content) {
		if (msg == null) return;
		msg.addProperty("content", content == null ? "" : content);
	}
	
	private String readString(JsonElement el) {
		if (el == null || el.isJsonNull()) return "";
		try {
			return el.getAsString();
		} catch (Exception e) {
			return "";
		}
	}
	
	private String repeatUnit(String unit, int count) {
		if (count <= 0) return "";
		String u = unit == null ? "" : unit;
		StringBuilder sb = new StringBuilder(u.length() * count);
		for (int i = 0; i < count; i++) {
			sb.append(u);
		}
		return sb.toString();
	}
}
