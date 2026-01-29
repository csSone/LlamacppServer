package org.mark.llamacpp.lmstudio.channel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.controller.BaseController;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;



/**
 * 	模拟LM Studio的API服务。
 */
public class LMStudioRouterHandler implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(LMStudioRouterHandler.class);

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		// 模型列表
		if (uri.startsWith("/api/v0/models")) {
			this.handleModelList(uri, ctx, request);
			return true;
		}
		
		// 聊天补全
		if (uri.startsWith("/api/v0/chat/completions")) {
			
			return true;
		}
		
		// 文本补全
		if (uri.startsWith("/api/v0/completions")) {
			return true;
		}
		
		// 文本嵌入
		if (uri.startsWith("/api/v0/embeddings")) {
			return true;
		}
		return false;
	}
	
	
	
	
	
	
	
	
	
	
	private void handleModelList(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Map<String, LlamaCppProcess> loadedProcesses = manager.getLoadedProcesses();
			List<GGUFModel> allModels = manager.listModel();
			List<Map<String, Object>> data = new ArrayList<>();

			for (Map.Entry<String, LlamaCppProcess> entry : loadedProcesses.entrySet()) {
				String modelId = entry.getKey();
				GGUFModel modelInfo = findModelInfo(allModels, modelId);
				Map<String, Object> modelData = new HashMap<>();
				modelData.put("id", modelId);
				modelData.put("object", "model");

				String modelType = "llm";
				String architecture = null;
				Integer contextLength = null;
				String quantization = null;

				if (modelInfo != null) {
					GGUFMetaData primaryModel = modelInfo.getPrimaryModel();
					if (primaryModel != null) {
						architecture = primaryModel.getStringValue("general.architecture");
						contextLength = primaryModel.getIntValue(architecture + ".context_length");
						quantization = primaryModel.getQuantizationType();
					}
					modelType = this.resolveModelType(architecture, modelInfo.getMmproj() != null);
				}
				
				// 模型类型
				modelData.put("type", modelType);
				if (architecture != null) {
					modelData.put("arch", architecture);
				}
				// 这个固定写这玩意
				modelData.put("publisher", "GGUF");
				modelData.put("compatibility_type", "gguf");
				// 量化等级
				if (quantization != null) {
					modelData.put("quantization", quantization);
				}
				// 状态
				modelData.put("state", "loaded");
				if (contextLength != null) {
					modelData.put("max_context_length", contextLength);
				}
				// 能力
				List<String> capabilities = new ArrayList<>(4);
				capabilities.add("tool_use");
				
				modelData.put("capabilities", capabilities);
				data.add(modelData);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("data", data);
			response.put("object", "list");
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取模型列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型列表时发生错误: " + e.getMessage()));
		}
	}

	private GGUFModel findModelInfo(List<GGUFModel> allModels, String modelId) {
		if (allModels == null || modelId == null) {
			return null;
		}
		for (GGUFModel model : allModels) {
			if (modelId.equals(model.getModelId())) {
				return model;
			}
		}
		return null;
	}

	private String resolveModelType(String architecture, boolean multimodal) {
		if (multimodal) {
			return "vlm";
		}
		if (architecture == null || architecture.isEmpty()) {
			return "llm";
		}
		String arch = architecture.toLowerCase(Locale.ROOT);
		if (arch.contains("embed") || arch.contains("embedding") || arch.contains("bert")) {
			return "embeddings";
		}
		return "llm";
	}
}
