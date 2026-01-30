package org.mark.llamacpp.ollama;



/**
 * Ollama API 兼容层服务：
 * - /api/tags（模型列表）
 * - /api/show（模型详情）
 * - /api/chat（聊天）
 */
@Deprecated
public class OllamaService {
//	
//	private static final Logger logger = LoggerFactory.getLogger(OllamaService.class);
//	private final ExecutorService worker = Executors.newCachedThreadPool();
//	
//	/**
//	 * 	返回指定模型的详情信息（兼容 /api/show）。
//	 * @param ctx
//	 * @param request
//	 */
//	public void handleShow(ChannelHandlerContext ctx, FullHttpRequest request) {
//		if (request.method() != HttpMethod.POST && request.method() != HttpMethod.GET) {
//			sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST/GET method is supported");
//			return;
//		}
//
//		String modelName = null;
//		boolean verbose = false;
//		
//		if (request.method() == HttpMethod.POST) {
//			String content = request.content().toString(StandardCharsets.UTF_8);
//			logger.debug("收到 Ollama show 请求: {}", content);
//
//			if (content == null || content.trim().isEmpty()) {
//				sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body is empty");
//				return;
//			}
//			JsonObject obj = null;
//			try {
//				obj = JsonUtil.fromJson(content, JsonObject.class);
//			} catch (Exception e) {
//				sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
//				return;
//			}
//			if (obj == null) {
//				sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
//				return;
//			}
//			modelName = JsonUtil.getJsonString(obj, "name", null);
//			if (modelName == null || modelName.isBlank()) {
//				modelName = JsonUtil.getJsonString(obj, "model", null);
//			}
//			verbose = ParamTool.parseJsonBoolean(obj, "verbose", false);
//		} else {
//			Map<String, String> params = ParamTool.getQueryParam(request.uri());
//			modelName = params.get("name");
//			if (modelName == null || modelName.isBlank()) {
//				modelName = params.get("model");
//			}
//			String v = params.get("verbose");
//			if (v != null) {
//				String t = v.trim().toLowerCase();
//				verbose = "true".equals(t) || "1".equals(t) || "yes".equals(t) || "on".equals(t);
//			}
//		}
//
//		LlamaServerManager manager = LlamaServerManager.getInstance();
//		if (modelName == null || modelName.isBlank()) {
//			if (manager.getLoadedProcesses().size() == 1) {
//				modelName = manager.getFirstModelName();
//			} else {
//				sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: name");
//				return;
//			}
//		}
//
//		List<GGUFModel> allModels = manager.listModel();
//		GGUFModel model = manager.findModelById(modelName);
//		if (model == null && modelName.contains(":")) {
//			String base = modelName.substring(0, modelName.indexOf(':')).trim();
//			if (!base.isEmpty()) {
//				model = manager.findModelById(base);
//			}
//		}
//		if (model == null && allModels != null) {
//			for (GGUFModel m : allModels) {
//				if (m == null) {
//					continue;
//				}
//				String alias = m.getAlias();
//				if (alias != null && alias.equals(modelName)) {
//					model = m;
//					break;
//				}
//			}
//		}
//
//		if (model == null) {
//			sendOllamaError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
//			return;
//		}
//
//		String modelId = model.getModelId();
//		GGUFMetaData primary = model.getPrimaryModel();
//
//		Map<String, Object> modelInfo = new HashMap<>();
//		Instant modifiedAt = Instant.now();
//		if (primary != null) {
//			Instant lm = safeModifiedAt(primary.getFilePath());
//			if (lm != null) {
//				modifiedAt = lm;
//			}
//
//			File primaryFile = new File(primary.getFilePath());
//			Map<String, Object> m = GGUFMetaDataReader.read(primaryFile);
//			if (m != null) {
//				if (!verbose) {
//					m.remove("tokenizer.ggml.tokens.size");
//					m.put("tokenizer.ggml.merges", null);
//					m.put("tokenizer.ggml.token_type", null);
//					m.put("tokenizer.ggml.tokens", null);
//				} else {
//					m.remove("tokenizer.ggml.tokens.size");
//					if (!m.containsKey("tokenizer.ggml.merges")) {
//						m.put("tokenizer.ggml.merges", new ArrayList<>());
//					}
//					if (!m.containsKey("tokenizer.ggml.token_type")) {
//						m.put("tokenizer.ggml.token_type", new ArrayList<>());
//					}
//					if (!m.containsKey("tokenizer.ggml.tokens")) {
//						m.put("tokenizer.ggml.tokens", new ArrayList<>());
//					}
//				}
//				modelInfo.putAll(m);
//			}
//		}
//
//		String template = null;
//		try {
//			template = ChatTemplateFileTool.readChatTemplateFromCacheFile(modelId);
//		} catch (Exception ignore) {
//		}
//		if (template == null || template.isBlank()) {
//			Object tpl = modelInfo.get("tokenizer.chat_template");
//			template = tpl == null ? "" : String.valueOf(tpl);
//		}
//
//		String family = null;
//		String quant = null;
//		if (primary != null) {
//			try {
//				family = primary.getStringValue("general.architecture");
//			} catch (Exception ignore) {
//			}
//			try {
//				quant = primary.getQuantizationType();
//			} catch (Exception ignore) {
//			}
//		}
//		if (family == null || family.isBlank()) {
//			Object fam = modelInfo.get("general.architecture");
//			if (fam != null) {
//				family = String.valueOf(fam);
//			}
//		}
//
//		Map<String, Object> details = new HashMap<>();
//		details.put("parent_model", "");
//		details.put("format", "gguf");
//		if (family != null && !family.isBlank()) {
//			details.put("family", family);
//			List<String> families = new ArrayList<>();
//			families.add(family);
//			details.put("families", families);
//		}
//		details.put("parameter_size", OllamaApiTool.guessParameterSize(modelId, model.getSize()));
//		if (quant != null && !quant.isBlank()) {
//			details.put("quantization_level", quant);
//		}
//
//		List<Map<String, Object>> tensors = new ArrayList<>();
//		if (primary != null) {
//			try {
//				tensors = OllamaApiTool.readGgufTensors(new File(primary.getFilePath()));
//			} catch (Exception ignore) {
//			}
//		}
//
//		String license = "";
//		Object lic = modelInfo.get("general.license");
//		if (lic != null) {
//			license = String.valueOf(lic);
//		}
//
//		Map<String, Object> out = new HashMap<>();
//		out.put("license", license);
//		out.put("modelfile", "");
//		out.put("parameters", "");
//		out.put("template", template == null ? "" : template);
//		out.put("details", details);
//		out.put("model_info", modelInfo);
//		out.put("tensors", tensors);
//		List<String> caps = new ArrayList<>();
//		caps.add("completion");
//		caps.add("tools");
//		out.put("capabilities", caps);
//		out.put("modified_at", OllamaApiTool.formatOllamaTime(modifiedAt));
//
//		sendOllamaJson(ctx, HttpResponseStatus.OK, out);
//	}
//	
//	
//	/**
//	 * 	处理chat请求。
//	 * @param ctx
//	 * @param request
//	 */
//	public void handleChat(ChannelHandlerContext ctx, FullHttpRequest request) {
//		if (request.method() != HttpMethod.POST) {
//			sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
//			return;
//		}
//		
//		String content = request.content().toString(StandardCharsets.UTF_8);
//		logger.info("收到 Ollama chat 请求: {}", content);
//		if (content == null || content.trim().isEmpty()) {
//			sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body is empty");
//			return;
//		}
//		
//		JsonObject ollamaReq = null;
//		try {
//			ollamaReq = JsonUtil.fromJson(content, JsonObject.class);
//		} catch (Exception e) {
//			sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
//			return;
//		}
//		if (ollamaReq == null) {
//			sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
//			return;
//		}
//		
//		LlamaServerManager manager = LlamaServerManager.getInstance();
//		final String modelName = JsonUtil.getJsonString(ollamaReq, "model", null);
//		if (modelName == null || modelName.isBlank()) {
//			sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: model");
//			return;
//		}
//		
//		if (!manager.getLoadedProcesses().containsKey(modelName)) {
//			sendOllamaError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
//			return;
//		}
//		
//		Integer port = manager.getModelPort(modelName);
//		if (port == null) {
//			sendOllamaError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Model port not found: " + modelName);
//			return;
//		}
//		
//		boolean isStream = true;
//		try {
//			if (ollamaReq.has("stream") && ollamaReq.get("stream").isJsonPrimitive()) {
//				isStream = ollamaReq.get("stream").getAsBoolean();
//			}
//		} catch (Exception ignore) {
//		}
//		
//		boolean hasTools = false;
//		try {
//			JsonElement tools = ollamaReq.get("tools");
//			hasTools = tools != null && !tools.isJsonNull() && tools.isJsonArray() && tools.getAsJsonArray().size() > 0;
//		} catch (Exception ignore) {
//		}
//		if (hasTools) {
//			isStream = false;
//		}
//		
//		JsonElement messages = ollamaReq.get("messages");
//		if (messages == null || !messages.isJsonArray()) {
//			sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: messages");
//			return;
//		}
//		
//		JsonObject openAiReq = new JsonObject();
//		openAiReq.addProperty("model", modelName);
//		openAiReq.add("messages", OllamaApiTool.normalizeOllamaMessagesForOpenAI(messages.getAsJsonArray()));
//		openAiReq.addProperty("stream", isStream);
//		OllamaApiTool.applyOllamaOptionsToOpenAI(openAiReq, ollamaReq.get("options"));
//		OllamaApiTool.applyOllamaToolsToOpenAI(openAiReq, ollamaReq);
//
//		String requestBody = JsonUtil.toJson(openAiReq);
//
//		boolean finalIsStream = isStream;
//		this.worker.execute(() -> {
//			HttpURLConnection connection = null;
//			try {
//				String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port.intValue());
//				URL url = URI.create(targetUrl).toURL();
//				connection = (HttpURLConnection) url.openConnection();
//				connection.setRequestMethod("POST");
//				connection.setConnectTimeout(36000 * 1000);
//				connection.setReadTimeout(36000 * 1000);
//				connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
//				connection.setDoOutput(true);
//				byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
//				connection.setRequestProperty("Content-Length", String.valueOf(input.length));
//				try (OutputStream os = connection.getOutputStream()) {
//					os.write(input, 0, input.length);
//				}
//
//				int responseCode = connection.getResponseCode();
//				if (finalIsStream) {
//					handleOllamaChatStreamResponse(ctx, connection, responseCode, modelName);
//				} else {
//					handleOllamaChatNonStreamResponse(ctx, connection, responseCode, modelName);
//				}
//			} catch (Exception e) {
//				logger.info("处理Ollama chat请求时发生错误", e);
//				sendOllamaError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
//			} finally {
//				if (connection != null) {
//					connection.disconnect();
//				}
//			}
//		});
//	}
//	
//	private static void handleOllamaChatNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
//		String responseBody = OllamaApiTool.readBody(connection, responseCode >= 200 && responseCode < 300);
//		if (!(responseCode >= 200 && responseCode < 300)) {
//			String msg = OllamaApiTool.extractOpenAIErrorMessage(responseBody);
//			sendOllamaError(ctx, HttpResponseStatus.valueOf(responseCode), msg == null ? responseBody : msg);
//			return;
//		}
//		
//		JsonObject parsed = null;
//		try {
//			parsed = JsonUtil.fromJson(responseBody, JsonObject.class);
//		} catch (Exception ignore) {
//		}
//		String content = null;
//		String thinking = null;
//		String doneReason = "stop";
//		JsonElement toolCalls = null;
//		
//		long totalDuration = 0L;
//		long loadDuration = 0L;
//		long promptEvalCount = 0L;
//		long promptEvalDuration = 0L;
//		long evalCount = 0L;
//		long evalDuration = 0L;
//		
//		if (parsed != null) {
//			JsonObject timings = parsed.has("timings") && parsed.get("timings").isJsonObject() ? parsed.getAsJsonObject("timings") : null;
//			if (timings != null) {
//				Map<String, Object> timingFields = OllamaApiTool.buildOllamaTimingFields(timings);
//				totalDuration = readLong(timingFields, "total_duration");
//				loadDuration = readLong(timingFields, "load_duration");
//				promptEvalCount = readLong(timingFields, "prompt_eval_count");
//				promptEvalDuration = readLong(timingFields, "prompt_eval_duration");
//				evalCount = readLong(timingFields, "eval_count");
//				evalDuration = readLong(timingFields, "eval_duration");
//			}
//			try {
//				JsonArray choices = parsed.getAsJsonArray("choices");
//				if (choices != null && choices.size() > 0 && choices.get(0).isJsonObject()) {
//					JsonObject c0 = choices.get(0).getAsJsonObject();
//					JsonObject msg = c0.has("message") && c0.get("message").isJsonObject() ? c0.getAsJsonObject("message") : null;
//					if (msg != null && msg.has("content")) {
//						content = JsonUtil.jsonValueToString(msg.get("content"));
//					}
//					if (msg != null && msg.has("reasoning_content")) {
//						thinking = JsonUtil.jsonValueToString(msg.get("reasoning_content"));
//					}
//					if (msg != null) {
//						toolCalls = OllamaApiTool.extractToolCallsFromOpenAIMessage(msg, new HashMap<>(), true);
//					}
//					JsonElement fr = c0.get("finish_reason");
//					if (fr != null && !fr.isJsonNull()) {
//						doneReason = JsonUtil.jsonValueToString(fr);
//					}
//				}
//			} catch (Exception ignore) {
//			}
//		}
//		if (content == null) {
//			content = "";
//		}
//		
//		Map<String, Object> out = new HashMap<>();
//		out.put("model", modelName);
//		out.put("created_at", OllamaApiTool.formatOllamaTime(Instant.now()));
//		
//		Map<String, Object> message = new HashMap<>();
//		message.put("role", "assistant");
//		message.put("content", content);
//		if (thinking != null && !thinking.isBlank()) {
//			message.put("thinking", thinking);
//		}
//		if (toolCalls != null && !toolCalls.isJsonNull()) {
//			JsonElement ollamaToolCalls = OllamaApiTool.toOllamaToolCalls(toolCalls);
//			if (ollamaToolCalls != null && !ollamaToolCalls.isJsonNull()) {
//				message.put("tool_calls", ollamaToolCalls);
//			}
//		}
//		out.put("message", message);
//		
//		out.put("done", Boolean.TRUE);
//		out.put("done_reason", doneReason);
//		out.put("total_duration", Long.valueOf(totalDuration));
//		out.put("load_duration", Long.valueOf(loadDuration));
//		out.put("prompt_eval_count", Long.valueOf(promptEvalCount));
//		out.put("prompt_eval_duration", Long.valueOf(promptEvalDuration));
//		out.put("eval_count", Long.valueOf(evalCount));
//		out.put("eval_duration", Long.valueOf(evalDuration));
//		
//		sendOllamaJson(ctx, HttpResponseStatus.OK, out);
//	}
//	
//	private static void handleOllamaChatStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
//		if (!(responseCode >= 200 && responseCode < 300)) {
//			String responseBody = OllamaApiTool.readBody(connection, false);
//			String msg = OllamaApiTool.extractOpenAIErrorMessage(responseBody);
//			sendOllamaError(ctx, HttpResponseStatus.valueOf(responseCode), msg == null ? responseBody : msg);
//			return;
//		}
//		
//		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
//		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-ndjson; charset=UTF-8");
//		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
//		HttpUtil.setTransferEncodingChunked(response, true);
//		ctx.writeAndFlush(response);
//		
//		String doneReason = "stop";
//		Map<Integer, String> toolCallIndexToId = new HashMap<>();
//		String functionCallId = null;
//		String functionCallName = null;
//		JsonObject timings = null;
//		
//		try (BufferedReader br = new BufferedReader(
//			new InputStreamReader(
//				responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream(),
//				StandardCharsets.UTF_8
//			)
//		)) {
//			String line;
//			while ((line = br.readLine()) != null) {
//				if (!ctx.channel().isActive()) {
//					if (connection != null) {
//						connection.disconnect();
//					}
//					break;
//				}
//				if (!line.startsWith("data: ")) {
//					continue;
//				}
//				String data = line.substring(6);
//				if ("[DONE]".equals(data)) {
//					Map<String, Object> timingFields = OllamaApiTool.buildOllamaTimingFields(timings);
//					writeOllamaStreamChunk(ctx, modelName, "", null, true, doneReason, timingFields);
//					break;
//				}
//				JsonObject chunk = ParamTool.tryParseObject(data);
//				if (chunk == null) {
//					continue;
//				}
//				JsonObject extractedTimings = chunk.has("timings") && chunk.get("timings").isJsonObject() ? chunk.getAsJsonObject("timings") : null;
//				if (extractedTimings != null) {
//					timings = extractedTimings;
//				}
//				
//				String deltaContent = null;
//				String deltaThinking = null;
//				String finish = null;
//				JsonElement deltaToolCalls = null;
//				
//				try {
//					JsonArray choices = chunk.getAsJsonArray("choices");
//					if (choices != null && choices.size() > 0 && choices.get(0).isJsonObject()) {
//						JsonObject c0 = choices.get(0).getAsJsonObject();
//						JsonObject delta = c0.has("delta") && c0.get("delta").isJsonObject() ? c0.getAsJsonObject("delta") : null;
//						if (delta != null && delta.has("content")) {
//							deltaContent = JsonUtil.jsonValueToString(delta.get("content"));
//						}
//						if (delta != null && delta.has("reasoning_content")) {
//							deltaThinking = JsonUtil.jsonValueToString(delta.get("reasoning_content"));
//						}
//						if (delta != null) {
//							deltaToolCalls = OllamaApiTool.extractToolCallsFromOpenAIMessage(delta, toolCallIndexToId, false);
//							if (deltaToolCalls == null) {
//								JsonObject fc = (delta.has("function_call") && delta.get("function_call").isJsonObject()) ? delta.getAsJsonObject("function_call") : null;
//								if (fc != null) {
//									String fcName = JsonUtil.getJsonString(fc, "name", null);
//									if (fcName != null && !fcName.isBlank()) {
//										functionCallName = fcName;
//									}
//									if (functionCallId == null) {
//										functionCallId = "call_" + UUID.randomUUID().toString().replace("-", "");
//									}
//									JsonObject enriched = fc.deepCopy();
//									if ((JsonUtil.getJsonString(enriched, "name", null) == null || JsonUtil.getJsonString(enriched, "name", null).isBlank())
//											&& functionCallName != null && !functionCallName.isBlank()) {
//										enriched.addProperty("name", functionCallName);
//									}
//									deltaToolCalls = OllamaApiTool.toolCallsFromFunctionCall(enriched, functionCallId);
//								}
//							}
//						}
//						JsonElement fr = c0.get("finish_reason");
//						if (fr != null && !fr.isJsonNull()) {
//							finish = JsonUtil.jsonValueToString(fr);
//						}
//					}
//				} catch (Exception ignore) {
//				}
//				
//				if (finish != null && !finish.isBlank()) {
//					doneReason = finish;
//				}
//				boolean hasContent = deltaContent != null && !deltaContent.isEmpty();
//				boolean hasThinking = deltaThinking != null && !deltaThinking.isEmpty();
//				boolean hasToolCalls = deltaToolCalls != null && !deltaToolCalls.isJsonNull();
//				if (hasContent || hasThinking || hasToolCalls) {
//					JsonElement ollamaToolCalls = hasToolCalls ? OllamaApiTool.toOllamaToolCalls(deltaToolCalls) : null;
//					writeOllamaStreamChunk(ctx, modelName, hasContent ? deltaContent : "", hasThinking ? deltaThinking : null, ollamaToolCalls, false, null, null);
//				}
//			}
//		} catch (Exception e) {
//			logger.info("处理Ollama chat流式响应时发生错误", e);
//			throw e;
//		}
//		
//		LastHttpContent last = LastHttpContent.EMPTY_LAST_CONTENT;
//		ctx.writeAndFlush(last).addListener(new ChannelFutureListener() {
//			@Override
//			public void operationComplete(ChannelFuture future) {
//				ctx.close();
//			}
//		});
//	}
//
//	private static void writeOllamaStreamChunk(ChannelHandlerContext ctx, String modelName, String content, JsonElement toolCalls, boolean done, String doneReason, Map<String, Object> doneFields) {
//		writeOllamaStreamChunk(ctx, modelName, content, null, toolCalls, done, doneReason, doneFields);
//	}
//	
//	private static void writeOllamaStreamChunk(ChannelHandlerContext ctx, String modelName, String content, String thinking, JsonElement toolCalls, boolean done, String doneReason, Map<String, Object> doneFields) {
//		Map<String, Object> out = new HashMap<>();
//		out.put("model", modelName);
//		out.put("created_at", OllamaApiTool.formatOllamaTime(Instant.now()));
//		
//		Map<String, Object> message = new HashMap<>();
//		message.put("role", "assistant");
//		message.put("content", content == null ? "" : content);
//		if (thinking != null && !thinking.isBlank()) {
//			message.put("thinking", thinking);
//		}
//		if (toolCalls != null && !toolCalls.isJsonNull()) {
//			message.put("tool_calls", toolCalls);
//		}
//		out.put("message", message);
//		
//		out.put("done", Boolean.valueOf(done));
//		if (done) {
//			out.put("done_reason", doneReason == null || doneReason.isBlank() ? "stop" : doneReason);
//			if (doneFields != null && !doneFields.isEmpty()) {
//				out.putAll(doneFields);
//			}
//		}
//		
//		String json = JsonUtil.toJson(out) + "\n";
//		ByteBuf buf = ctx.alloc().buffer();
//		buf.writeBytes(json.getBytes(StandardCharsets.UTF_8));
//		HttpContent httpContent = new DefaultHttpContent(buf);
//		ChannelFuture f = ctx.writeAndFlush(httpContent);
//		f.addListener((ChannelFutureListener) future -> {
//			if (!future.isSuccess()) {
//				ctx.close();
//			}
//		});
//	}
//	
//	private static void sendOllamaError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
//		Map<String, Object> payload = new HashMap<>();
//		payload.put("error", message == null ? "" : message);
//		sendOllamaJson(ctx, status == null ? HttpResponseStatus.INTERNAL_SERVER_ERROR : status, payload);
//	}
//
//	/**
//	 * 	发送JSON响应
//	 * @param ctx
//	 * @param status
//	 * @param data
//	 */
//	private static void sendOllamaJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
//		String json = JsonUtil.toJson(data);
//		byte[] content = json.getBytes(StandardCharsets.UTF_8);
//		
//		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
//		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
//		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
//		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
//		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
//		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
//		//
//		response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(content));
//		response.headers().set("X-Powered-By", "Express");
//		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
//		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
//		response.content().writeBytes(content);
//		
//		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
//			@Override
//			public void operationComplete(ChannelFuture future) {
//				ctx.close();
//			}
//		});
//	}
//
//	private static long readLong(Map<String, Object> map, String key) {
//		if (map == null || key == null || key.isBlank()) {
//			return 0L;
//		}
//		Object v = map.get(key);
//		if (v == null) {
//			return 0L;
//		}
//		if (v instanceof Number) {
//			return ((Number) v).longValue();
//		}
//		try {
//			return Long.parseLong(String.valueOf(v).trim());
//		} catch (Exception ignore) {
//			return 0L;
//		}
//	}
//
//	private static Instant resolveModifiedAt(GGUFModel model) {
//		if (model == null) {
//			return Instant.now();
//		}
//		GGUFMetaData primary = model.getPrimaryModel();
//		if (primary != null) {
//			Instant lm = safeModifiedAt(primary.getFilePath());
//			if (lm != null) {
//				return lm;
//			}
//		}
//		Instant lm = safeModifiedAt(model.getPath());
//		return lm == null ? Instant.now() : lm;
//	}
//
//	private static Instant safeModifiedAt(String path) {
//		if (path == null || path.isBlank()) {
//			return null;
//		}
//		try {
//			File f = new File(path);
//			if (!f.exists() || !f.isFile()) {
//				return null;
//			}
//			long lm = f.lastModified();
//			return lm > 0 ? Instant.ofEpochMilli(lm) : null;
//		} catch (Exception ignore) {
//			return null;
//		}
//	}
//
//	private static String readArchitecture(GGUFModel model) {
//		if (model == null) {
//			return null;
//		}
//		GGUFMetaData primary = model.getPrimaryModel();
//		if (primary == null) {
//			return null;
//		}
//		try {
//			return primary.getStringValue("general.architecture");
//		} catch (Exception ignore) {
//			return null;
//		}
//	}
//
//	private static String readQuantization(GGUFModel model) {
//		if (model == null) {
//			return null;
//		}
//		GGUFMetaData primary = model.getPrimaryModel();
//		if (primary == null) {
//			return null;
//		}
//		try {
//			return primary.getQuantizationType();
//		} catch (Exception ignore) {
//			return null;
//		}
//	}

}
