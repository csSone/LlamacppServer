package org.mark.llamacpp.server.controller;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;



/**
 * 	系统相关。
 */
public class SystemController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(SystemController.class);
	
	
	
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 停止服务API
		if (uri.startsWith("/api/shutdown")) {
			this.handleShutdownRequest(ctx, request);
			return true;
		}
		// 控制台
		if (uri.startsWith("/api/sys/console")) {
			this.handleSysConsoleRequest(ctx, request);
			return true;
		}
		
		// 列出可用的设备，基于当前选择的llamacpp
		if (uri.startsWith("/api/model/device/list")) {
			this.handleDeviceListRequest(ctx, request);
			return true;
		}
		
		// 显存估算API
		if (uri.startsWith("/api/models/vram/estimate")) {
			this.handleVramEstimateRequest(ctx, request);
			return true;
		}
		
		
		return false;
	}
	
	
	/**
	 * 处理停止服务请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleShutdownRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		
		try {
			logger.info("收到停止服务请求");

			// 先发送响应，然后再执行关闭操作
			Map<String, Object> data = new HashMap<>();
			data.put("message", "服务正在停止，所有模型进程将被终止");

			// 发送响应
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

			// 在新线程中执行关闭操作，避免阻塞响应发送
			new Thread(() -> {
				try {
					// 等待一小段时间确保响应已发送
					Thread.sleep(500);

					// 调用LlamaServerManager停止所有进程并退出
					LlamaServerManager manager = LlamaServerManager.getInstance();
					manager.shutdownAll();
					//
					System.exit(0);
				} catch (Exception e) {
					logger.info("停止服务时发生错误", e);
				}
			}).start();

		} catch (Exception e) {
			logger.info("处理停止服务请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("停止服务失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 处理控制台的请求。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleSysConsoleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Path logPath = LlamaServer.getConsoleLogPath();
			File file = logPath.toFile();
			if (!file.exists()) {
				LlamaServer.sendTextResponse(ctx, "");
				return;
			}
			long max = 1L * 256 * 1024;
			try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
				long len = raf.length();
				long start = Math.max(0, len - max);
				raf.seek(start);
				int toRead = (int) Math.min(max, len - start);
				byte[] buf = new byte[toRead];
				int read = raf.read(buf);
				if (read <= 0) {
					LlamaServer.sendTextResponse(ctx, "");
					return;
				}
				String text = new String(buf, 0, read, StandardCharsets.UTF_8);
				LlamaServer.sendTextResponse(ctx, text);
			}
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("读取控制台日志失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 处理设备列表请求 执行 llama-bench --list-devices 命令获取可用设备列表
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleDeviceListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		
		try {
			// 从URL参数中提取 llamaBinPath
			String query = request.uri();
			String llamaBinPath = null;
			
			Map<String, String> params = ParamTool.getQueryParam(query);
			llamaBinPath = params.get("llamaBinPath");

			// 验证必需的参数
			if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的llamaBinPath参数"));
				return;
			}

			List<String> devices = LlamaServerManager.getInstance().handleListDevices(llamaBinPath);

			String executableName = "llama-bench";
			// 拼接完整命令路径
			String command = llamaBinPath.trim();
			command += File.separator;

			command += executableName + " --list-devices";

			// 执行命令
			CommandLineRunner.CommandResult result = CommandLineRunner.execute(command, 30);
			// 构建响应数据
			Map<String, Object> data = new HashMap<>();
			data.put("command", command);
			data.put("exitCode", result.getExitCode());
			data.put("output", result.getOutput());
			data.put("error", result.getError());
			data.put("devices", devices);

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取设备列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取设备列表失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 估算模型显存需求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleVramEstimateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		
		try {
			// 读取请求体
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			JsonElement root = JsonUtil.fromJson(content, JsonElement.class);
			if (root == null || !root.isJsonObject()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体必须为JSON对象"));
				return;
			}

			JsonObject obj = root.getAsJsonObject();
			String cmd = JsonUtil.getJsonString(obj, "cmd", "");
			String extraParams = JsonUtil.getJsonString(obj, "extraParams", "");
			if (cmd != null) cmd = cmd.trim();
			if (extraParams != null) extraParams = extraParams.trim();
			if ((cmd == null || cmd.isEmpty()) && (extraParams == null || extraParams.isEmpty())) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的启动参数"));
				return;
			}
			String combinedCmd = "";
			if (cmd != null && !cmd.isEmpty()) combinedCmd = cmd;
			if (extraParams != null && !extraParams.isEmpty()) combinedCmd = combinedCmd.isEmpty() ? extraParams : (combinedCmd + " " + extraParams);
			boolean enableVision = ParamTool.parseJsonBoolean(obj, "enableVision", true);
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			String llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPathSelect", null);
			if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
				llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPath", null);
			}
			// 预留返回值
			Map<String, Object> data = new HashMap<>();
			
			// 只保留部分参数：--ctx-size --flash-attn --batch-size --ubatch-size --parallel --kv-unified --cache-type-k --cache-type-v
			List<String> cmdlist = ParamTool.splitCmdArgs(combinedCmd);
			// 运行fit-param
			String output = LlamaServerManager.getInstance().handleFitParam(llamaBinPathSelect, modelId, enableVision, cmdlist);
			// 提取第一个数值
			Pattern numberPattern = Pattern.compile("llama_params_fit_impl: projected to use (\\d+) MiB");
			Matcher numberMatcher = numberPattern.matcher(output);
			if (numberMatcher.find()) {
			    String value = numberMatcher.group(1);
			    data.put("vram", value);
			}
			// 如果没有找到值，就去找错误信息
			else {
				
				Pattern pattern = Pattern.compile("^.*llama_init_from_model.*$", Pattern.MULTILINE);
		        Matcher matcher = pattern.matcher(output);
		        if (matcher.find()) {
		            data.put("message", matcher.group(0));
		        }
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("估算显存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("估算显存失败: " + e.getMessage()));
		}
	}
}
