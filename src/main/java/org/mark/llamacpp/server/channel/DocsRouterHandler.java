package org.mark.llamacpp.server.channel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * API 文档路由处理器
 * 提供 OpenAPI 文档和 Swagger UI
 */
public class DocsRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(DocsRouterHandler.class);

	private String cachedOpenApiJson;

	public DocsRouterHandler() {
		// 直接加载预生成的 openapi.json 文件，而不是运行时扫描源代码
		// 支持多个部署场景：项目根目录或 build/ 子目录
		try {
			Path openApiFile = null;

			// 尝试多个可能的位置
			Path[] possiblePaths = {
				Paths.get("openapi.json"),           // 当前目录
				Paths.get("../openapi.json"),        // 上级目录（用于 build/ 目录运行）
				Paths.get("../classes/../../../openapi.json")  // 从 build/classes 向上
			};

			for (Path path : possiblePaths) {
				if (Files.exists(path)) {
					openApiFile = path.toAbsolutePath();
					break;
				}
			}

			if (openApiFile != null) {
				this.cachedOpenApiJson = Files.readString(openApiFile, StandardCharsets.UTF_8);
				logger.info("已加载 OpenAPI 文档: {}", openApiFile);
				// 计算端点数量
				com.google.gson.JsonObject jsonObject = JsonUtil.fromJson(cachedOpenApiJson, com.google.gson.JsonObject.class);
				if (jsonObject.has("paths")) {
					int endpointCount = jsonObject.getAsJsonObject("paths").size();
					logger.info("OpenAPI 文档包含 {} 个端点", endpointCount);
				}
			} else {
				logger.warn("OpenAPI 文档文件不存在，请运行: ./scripts/generate-openapi.sh");
				this.cachedOpenApiJson = null;
			}
		} catch (Exception e) {
			logger.error("加载 OpenAPI 文档失败", e);
			this.cachedOpenApiJson = null;
		}
	}

	/**
	 * 处理文档相关请求
	 */
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		this.handleDocsRequest(ctx, request);
	}

	/**
	 * 处理文档相关请求的内部方法
	 */
	private void handleDocsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			String uri = request.uri();
			String path = uri;
			int queryIndex = uri.indexOf('?');
			if (queryIndex >= 0) {
				path = uri.substring(0, queryIndex);
			}

			HttpMethod method = request.method();

			// GET /api/docs - 返回 OpenAPI JSON
			if ("/api/docs".equals(path) && HttpMethod.GET.equals(method)) {
				this.handleOpenApiSpec(ctx);
				return;
			}

			// GET /api/docs/ui - 返回 Swagger UI 页面
			if ("/api/docs/ui".equals(path) && HttpMethod.GET.equals(method)) {
				this.handleSwaggerUI(ctx);
				return;
			}

			// POST /api/docs/regenerate - 重新生成文档
			if ("/api/docs/regenerate".equals(path) && HttpMethod.POST.equals(method)) {
				this.handleRegenerateDocs(ctx);
				return;
			}

			// 不是文档请求，传递给下一个 handler
			ctx.fireChannelRead(request.retain());
		} catch (Exception e) {
			logger.error("处理文档请求失败", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
				"服务器内部错误: " + e.getMessage());
		}
	}

	/**
	 * 返回 OpenAPI JSON 规范
	 */
	private void handleOpenApiSpec(ChannelHandlerContext ctx) {
		try {
			if (cachedOpenApiJson == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
					"OpenAPI 文档未加载，请运行: ./scripts/generate-openapi.sh");
				return;
			}

			byte[] content = cachedOpenApiJson.getBytes(StandardCharsets.UTF_8);

			DefaultFullHttpResponse response = new DefaultFullHttpResponse(
				HttpVersion.HTTP_1_1,
				HttpResponseStatus.OK
			);

			response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
			response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

			response.content().writeBytes(content);

			ctx.writeAndFlush(response).addListener(future -> {
				ctx.close();
			});

			logger.info("已返回 OpenAPI 规范");
		} catch (Exception e) {
			logger.error("返回 OpenAPI 规范失败", e);
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
				"返回文档失败: " + e.getMessage());
		}
	}

	/**
	 * 返回 Swagger UI HTML 页面
	 */
	private void handleSwaggerUI(ChannelHandlerContext ctx) {
		String html = """
			<!DOCTYPE html>
			<html lang="zh-CN">
			<head>
				<meta charset="UTF-8">
				<meta name="viewport" content="width=device-width, initial-scale=1.0">
				<title>Llamacpp Server API Documentation</title>
				<link rel="stylesheet" type="text/css"
					href="https://unpkg.com/swagger-ui-dist@5.10.0/swagger-ui.css">
				<style>
					body { margin: 0; padding: 0; }
					#swagger-ui { max-width: 1460px; margin: 0 auto; }
				</style>
			</head>
			<body>
				<div id="swagger-ui"></div>
				<script src="https://unpkg.com/swagger-ui-dist@5.10.0/swagger-ui-bundle.js"></script>
				<script src="https://unpkg.com/swagger-ui-dist@5.10.0/swagger-ui-standalone-preset.js"></script>
				<script>
					window.onload = function() {
						SwaggerUIBundle({
							url: '/api/docs',
							dom_id: '#swagger-ui',
							deepLinking: true,
							presets: [
								SwaggerUIBundle.presets.apis,
								SwaggerUIStandalonePreset
							],
							plugins: [
								SwaggerUIBundle.plugins.DownloadUrl
							],
							layout: "StandaloneLayout",
							defaultModelsExpandDepth: 1,
							defaultModelExpandDepth: 1,
							docExpansion: "list",
							filter: true,
							showRequestHeaders: true,
							tryItOutEnabled: true
						});
					};
				</script>
			</body>
			</html>
			""";

		byte[] content = html.getBytes(StandardCharsets.UTF_8);

		DefaultFullHttpResponse response = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1,
			HttpResponseStatus.OK
		);

		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");

		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(future -> {
			ctx.close();
		});

		logger.info("已返回 Swagger UI 页面");
	}

	/**
	 * 重新生成文档（已禁用 - 请使用 ./scripts/generate-openapi.sh）
	 */
	private void handleRegenerateDocs(ChannelHandlerContext ctx) {
		LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_IMPLEMENTED,
			"文档重新生成已禁用，请使用命令: ./scripts/generate-openapi.sh");
	}
}
