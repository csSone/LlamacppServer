package org.mark.llamacpp.ollama;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * 	
 */
public class OllamaChatService {
	
	private static final Logger logger = LoggerFactory.getLogger(OllamaChatService.class);
	
	
	/**
	 * 	
	 */
	private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	
	public OllamaChatService() {
		
	}
	
	/**
	 * 	处理聊天补全。
	 * @param ctx
	 * @param request
	 */
	public void handleChat(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
			return;
		}
		
		String content = request.content().toString(StandardCharsets.UTF_8);
		logger.info("收到 Ollama chat 请求: {}", content);
		if (content == null || content.trim().isEmpty()) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body is empty");
			return;
		}
		
		JsonObject ollamaReq = null;
		try {
			ollamaReq = JsonUtil.fromJson(content, JsonObject.class);
		} catch (Exception e) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
			return;
		}
		if (ollamaReq == null) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
			return;
		}
		
		LlamaServerManager manager = LlamaServerManager.getInstance();
		final String modelName = JsonUtil.getJsonString(ollamaReq, "model", null);
		if (modelName == null || modelName.isBlank()) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: model");
			return;
		}
		
		if (!manager.getLoadedProcesses().containsKey(modelName)) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
			return;
		}
		
		Integer port = manager.getModelPort(modelName);
		if (port == null) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Model port not found: " + modelName);
			return;
		}
		
		boolean isStream = true;
		try {
			if (ollamaReq.has("stream") && ollamaReq.get("stream").isJsonPrimitive()) {
				isStream = ollamaReq.get("stream").getAsBoolean();
			}
		} catch (Exception ignore) {
		}
		
		boolean hasTools = false;
		try {
			JsonElement tools = ollamaReq.get("tools");
			hasTools = tools != null && !tools.isJsonNull() && tools.isJsonArray() && tools.getAsJsonArray().size() > 0;
		} catch (Exception ignore) {
		}
		if (hasTools) {
			isStream = false;
		}
		
		JsonElement messages = ollamaReq.get("messages");
		if (messages == null || !messages.isJsonArray()) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: messages");
			return;
		}
		
		JsonObject openAiReq = new JsonObject();
		openAiReq.addProperty("model", modelName);
		openAiReq.add("messages", OllamaApiTool.normalizeOllamaMessagesForOpenAI(messages.getAsJsonArray()));
		openAiReq.addProperty("stream", isStream);
		this.applyOllamaOptionsToOpenAI(openAiReq, ollamaReq.get("options"));
		OllamaApiTool.applyOllamaToolsToOpenAI(openAiReq, ollamaReq);

		String requestBody = JsonUtil.toJson(openAiReq);

		boolean finalIsStream = isStream;
		this.worker.execute(() -> {
			HttpURLConnection connection = null;
			try {
				String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port.intValue());
				URL url = URI.create(targetUrl).toURL();
				connection = (HttpURLConnection) url.openConnection();
				connection.setRequestMethod("POST");
				connection.setConnectTimeout(36000 * 1000);
				connection.setReadTimeout(36000 * 1000);
				connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
				connection.setDoOutput(true);
				byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
				connection.setRequestProperty("Content-Length", String.valueOf(input.length));
				try (OutputStream os = connection.getOutputStream()) {
					os.write(input, 0, input.length);
				}

				int responseCode = connection.getResponseCode();
				if (finalIsStream) {
					this.handleOllamaChatStreamResponse(ctx, connection, responseCode, modelName);
				} else {
					this.handleOllamaChatNonStreamResponse(ctx, connection, responseCode, modelName);
				}
			} catch (Exception e) {
				logger.info("处理Ollama chat请求时发生错误", e);
				Ollama.sendOllamaError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
			} finally {
				if (connection != null) {
					connection.disconnect();
				}
			}
		});
	}
	
	
	/**
	 * 	处理非流式响应。
	 * @param ctx
	 * @param connection
	 * @param responseCode
	 * @param modelName
	 * @throws IOException
	 */
	private void handleOllamaChatNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
		String responseBody = OllamaApiTool.readBody(connection, responseCode >= 200 && responseCode < 300);
		if (!(responseCode >= 200 && responseCode < 300)) {
			String msg = OllamaApiTool.extractOpenAIErrorMessage(responseBody);
			Ollama.sendOllamaError(ctx, HttpResponseStatus.valueOf(responseCode), msg == null ? responseBody : msg);
			return;
		}
		
		JsonObject parsed = null;
		try {
			parsed = JsonUtil.fromJson(responseBody, JsonObject.class);
		} catch (Exception ignore) {
		}
		String content = null;
		String thinking = null;
		String doneReason = "stop";
		JsonElement toolCalls = null;
		
		long totalDuration = 0L;
		long loadDuration = 0L;
		long promptEvalCount = 0L;
		long promptEvalDuration = 0L;
		long evalCount = 0L;
		long evalDuration = 0L;
		
		if (parsed != null) {
			JsonObject timings = parsed.has("timings") && parsed.get("timings").isJsonObject() ? parsed.getAsJsonObject("timings") : null;
			if (timings != null) {
				// 计算统计数据并嵌入响应中。
				Map<String, Object> timingFields = OllamaApiTool.buildOllamaTimingFields(timings);
				totalDuration = OllamaApiTool.readLong(timingFields, "total_duration");
				loadDuration = OllamaApiTool.readLong(timingFields, "load_duration");
				promptEvalCount = OllamaApiTool.readLong(timingFields, "prompt_eval_count");
				promptEvalDuration = OllamaApiTool.readLong(timingFields, "prompt_eval_duration");
				evalCount = OllamaApiTool.readLong(timingFields, "eval_count");
				evalDuration = OllamaApiTool.readLong(timingFields, "eval_duration");
			}
			try {
				JsonArray choices = parsed.getAsJsonArray("choices");
				if (choices != null && choices.size() > 0 && choices.get(0).isJsonObject()) {
					JsonObject c0 = choices.get(0).getAsJsonObject();
					JsonObject msg = c0.has("message") && c0.get("message").isJsonObject() ? c0.getAsJsonObject("message") : null;
					if (msg != null && msg.has("content")) {
						content = JsonUtil.jsonValueToString(msg.get("content"));
					}
					if (msg != null && msg.has("reasoning_content")) {
						thinking = JsonUtil.jsonValueToString(msg.get("reasoning_content"));
					}
					if (msg != null) {
						toolCalls = OllamaApiTool.extractToolCallsFromOpenAIMessage(msg, new HashMap<>(), true);
					}
					JsonElement fr = c0.get("finish_reason");
					if (fr != null && !fr.isJsonNull()) {
						doneReason = JsonUtil.jsonValueToString(fr);
					}
				}
			} catch (Exception ignore) {
			}
		}
		if (content == null) {
			content = "";
		}
		
		Map<String, Object> out = new HashMap<>();
		out.put("model", modelName);
		out.put("created_at", OllamaApiTool.formatOllamaTime(Instant.now()));
		
		Map<String, Object> message = new HashMap<>();
		message.put("role", "assistant");
		message.put("content", content);
		if (thinking != null && !thinking.isBlank()) {
			message.put("thinking", thinking);
		}
		if (toolCalls != null && !toolCalls.isJsonNull()) {
			JsonElement ollamaToolCalls = OllamaApiTool.toOllamaToolCalls(toolCalls);
			if (ollamaToolCalls != null && !ollamaToolCalls.isJsonNull()) {
				message.put("tool_calls", ollamaToolCalls);
			}
		}
		out.put("message", message);
		
		out.put("done", Boolean.TRUE);
		out.put("done_reason", doneReason);
		out.put("total_duration", Long.valueOf(totalDuration));
		out.put("load_duration", Long.valueOf(loadDuration));
		out.put("prompt_eval_count", Long.valueOf(promptEvalCount));
		out.put("prompt_eval_duration", Long.valueOf(promptEvalDuration));
		out.put("eval_count", Long.valueOf(evalCount));
		out.put("eval_duration", Long.valueOf(evalDuration));
		
		Ollama.sendOllamaJson(ctx, HttpResponseStatus.OK, out);
	}
	
	/**
	 * 	处理流式响应。
	 * @param ctx
	 * @param connection
	 * @param responseCode
	 * @param modelName
	 * @throws IOException
	 */
	private void handleOllamaChatStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
		if (!(responseCode >= 200 && responseCode < 300)) {
			String responseBody = OllamaApiTool.readBody(connection, false);
			String msg = OllamaApiTool.extractOpenAIErrorMessage(responseBody);
			Ollama.sendOllamaError(ctx, HttpResponseStatus.valueOf(responseCode), msg == null ? responseBody : msg);
			return;
		}
		
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-ndjson; charset=UTF-8");
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		HttpUtil.setTransferEncodingChunked(response, true);
		ctx.writeAndFlush(response);
		
		String doneReason = "stop";
		Map<Integer, String> toolCallIndexToId = new HashMap<>();
		String functionCallId = null;
		String functionCallName = null;
		JsonObject timings = null;
		
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(
				responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream(),
				StandardCharsets.UTF_8
			)
		)) {
			String line;
			while ((line = br.readLine()) != null) {
				if (!ctx.channel().isActive()) {
					if (connection != null) {
						connection.disconnect();
					}
					break;
				}
				if (!line.startsWith("data: ")) {
					continue;
				}
				String data = line.substring(6);
				if ("[DONE]".equals(data)) {
					Map<String, Object> timingFields = OllamaApiTool.buildOllamaTimingFields(timings);
					writeOllamaStreamChunk(ctx, modelName, "", null, true, doneReason, timingFields);
					break;
				}
				JsonObject chunk = ParamTool.tryParseObject(data);
				if (chunk == null) {
					continue;
				}
				JsonObject extractedTimings = chunk.has("timings") && chunk.get("timings").isJsonObject() ? chunk.getAsJsonObject("timings") : null;
				if (extractedTimings != null) {
					timings = extractedTimings;
				}
				
				String deltaContent = null;
				String deltaThinking = null;
				String finish = null;
				JsonElement deltaToolCalls = null;
				
				try {
					JsonArray choices = chunk.getAsJsonArray("choices");
					if (choices != null && choices.size() > 0 && choices.get(0).isJsonObject()) {
						JsonObject c0 = choices.get(0).getAsJsonObject();
						JsonObject delta = c0.has("delta") && c0.get("delta").isJsonObject() ? c0.getAsJsonObject("delta") : null;
						if (delta != null && delta.has("content")) {
							deltaContent = JsonUtil.jsonValueToString(delta.get("content"));
						}
						if (delta != null && delta.has("reasoning_content")) {
							deltaThinking = JsonUtil.jsonValueToString(delta.get("reasoning_content"));
						}
						if (delta != null) {
							deltaToolCalls = OllamaApiTool.extractToolCallsFromOpenAIMessage(delta, toolCallIndexToId, false);
							if (deltaToolCalls == null) {
								JsonObject fc = (delta.has("function_call") && delta.get("function_call").isJsonObject()) ? delta.getAsJsonObject("function_call") : null;
								if (fc != null) {
									String fcName = JsonUtil.getJsonString(fc, "name", null);
									if (fcName != null && !fcName.isBlank()) {
										functionCallName = fcName;
									}
									if (functionCallId == null) {
										functionCallId = "call_" + UUID.randomUUID().toString().replace("-", "");
									}
									JsonObject enriched = fc.deepCopy();
									if ((JsonUtil.getJsonString(enriched, "name", null) == null || JsonUtil.getJsonString(enriched, "name", null).isBlank())
											&& functionCallName != null && !functionCallName.isBlank()) {
										enriched.addProperty("name", functionCallName);
									}
									deltaToolCalls = OllamaApiTool.toolCallsFromFunctionCall(enriched, functionCallId);
								}
							}
						}
						JsonElement fr = c0.get("finish_reason");
						if (fr != null && !fr.isJsonNull()) {
							finish = JsonUtil.jsonValueToString(fr);
						}
					}
				} catch (Exception ignore) {
				}
				
				if (finish != null && !finish.isBlank()) {
					doneReason = finish;
				}
				boolean hasContent = deltaContent != null && !deltaContent.isEmpty();
				boolean hasThinking = deltaThinking != null && !deltaThinking.isEmpty();
				boolean hasToolCalls = deltaToolCalls != null && !deltaToolCalls.isJsonNull();
				if (hasContent || hasThinking || hasToolCalls) {
					JsonElement ollamaToolCalls = hasToolCalls ? OllamaApiTool.toOllamaToolCalls(deltaToolCalls) : null;
					writeOllamaStreamChunk(ctx, modelName, hasContent ? deltaContent : "", hasThinking ? deltaThinking : null, ollamaToolCalls, false, null, null);
				}
			}
		} catch (Exception e) {
			logger.info("处理Ollama chat流式响应时发生错误", e);
			throw e;
		}
		
		LastHttpContent last = LastHttpContent.EMPTY_LAST_CONTENT;
		ctx.writeAndFlush(last).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	
	/**
	 * 	写入Ollama流式传输的数据。
	 * @param ctx
	 * @param modelName
	 * @param content
	 * @param toolCalls
	 * @param done
	 * @param doneReason
	 * @param doneFields
	 */
	private void writeOllamaStreamChunk(ChannelHandlerContext ctx, String modelName, String content, JsonElement toolCalls, boolean done, String doneReason, Map<String, Object> doneFields) {
		this.writeOllamaStreamChunk(ctx, modelName, content, null, toolCalls, done, doneReason, doneFields);
	}
	
	/**
	 * 	写入Ollama流式传输的数据。
	 * @param ctx
	 * @param modelName
	 * @param content
	 * @param thinking
	 * @param toolCalls
	 * @param done
	 * @param doneReason
	 * @param doneFields
	 */
	private void writeOllamaStreamChunk(ChannelHandlerContext ctx, String modelName, String content, String thinking, JsonElement toolCalls, boolean done, String doneReason, Map<String, Object> doneFields) {
		Map<String, Object> out = new HashMap<>();
		out.put("model", modelName);
		out.put("created_at", OllamaApiTool.formatOllamaTime(Instant.now()));
		
		Map<String, Object> message = new HashMap<>();
		message.put("role", "assistant");
		message.put("content", content == null ? "" : content);
		if (thinking != null && !thinking.isBlank()) {
			message.put("thinking", thinking);
		}
		if (toolCalls != null && !toolCalls.isJsonNull()) {
			message.put("tool_calls", toolCalls);
		}
		out.put("message", message);
		
		out.put("done", Boolean.valueOf(done));
		if (done) {
			out.put("done_reason", doneReason == null || doneReason.isBlank() ? "stop" : doneReason);
			if (doneFields != null && !doneFields.isEmpty()) {
				out.putAll(doneFields);
			}
		}
		
		String json = JsonUtil.toJson(out) + "\n";
		ByteBuf buf = ctx.alloc().buffer();
		buf.writeBytes(json.getBytes(StandardCharsets.UTF_8));
		HttpContent httpContent = new DefaultHttpContent(buf);
		ChannelFuture f = ctx.writeAndFlush(httpContent);
		f.addListener((ChannelFutureListener) future -> {
			if (!future.isSuccess()) {
				ctx.close();
			}
		});
	}
	
	
	/**
	 * 	将ollama请求中的参数转换为openai的。
	 * @param openAiReq
	 * @param optionsEl
	 */
	private void applyOllamaOptionsToOpenAI(JsonObject openAiReq, JsonElement optionsEl) {
		if (openAiReq == null || optionsEl == null || optionsEl.isJsonNull() || !optionsEl.isJsonObject()) {
			return;
		}
		JsonObject options = optionsEl.getAsJsonObject();

		OllamaApiTool.copyNumber(options, "temperature", openAiReq, "temperature");
		OllamaApiTool.copyNumber(options, "top_p", openAiReq, "top_p");
		OllamaApiTool.copyNumber(options, "top_k", openAiReq, "top_k");
		OllamaApiTool.copyNumber(options, "repeat_penalty", openAiReq, "repeat_penalty");
		OllamaApiTool.copyNumber(options, "frequency_penalty", openAiReq, "frequency_penalty");
		OllamaApiTool.copyNumber(options, "presence_penalty", openAiReq, "presence_penalty");
		OllamaApiTool.copyNumber(options, "seed", openAiReq, "seed");

		Integer numPredict = null;
		try {
			if (options.has("num_predict") && options.get("num_predict").isJsonPrimitive()) {
				numPredict = options.get("num_predict").getAsInt();
			}
		} catch (Exception ignore) {
		}
		if (numPredict != null) {
			openAiReq.addProperty("max_tokens", numPredict.intValue());
		}

		JsonElement stop = options.get("stop");
		if (stop != null && !stop.isJsonNull()) {
			if (stop.isJsonArray()) {
				openAiReq.add("stop", stop.deepCopy());
			} else if (stop.isJsonPrimitive()) {
				openAiReq.add("stop", stop.deepCopy());
			}
		}
	}
}
