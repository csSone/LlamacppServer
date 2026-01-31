package org.mark.llamacpp.server.channel;


import org.mark.llamacpp.server.service.AnthropicService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;


/**
 * 	
 */
public class AnthropicRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(OpenAIRouterHandler.class);

	/**
	 * 	OpenAI接口的实现。
	 */
	private AnthropicService anthropicService = new AnthropicService();
	
	
	public AnthropicRouterHandler() {

	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		String uri = request.uri();
		this.handleApiRequest(ctx, request, uri);
		return;
	}

	/**
	 * 处理API请求
	 */
    private void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
		try {
			logger.info("Anthropic 收到请求：{}", uri);
			// OpenAI API 端点
			// 获取模型列表
			if (uri.startsWith("/v1/models")) {
				this.anthropicService.handleModelsRequest(ctx, request);
				return;
			}
			// 
			if (uri.startsWith("/v1/messages/count_tokens")) {
				this.anthropicService.handleMessagesCountTokensRequest(ctx, request);
				return;
			}
			// Anthropic API 端点 (Messages)
			if (uri.startsWith("/v1/messages")) {
				this.anthropicService.handleMessagesRequest(ctx, request);
				return;
			}
			
			// Anthropic API 端点 (Legacy Complete)
			if (uri.startsWith("/v1/complete")) {
				this.anthropicService.handleCompleteRequest(ctx, request);
				return;
			}
			this.sendJsonResponse(ctx, ApiResponse.error("404 Not Found"));
		} catch (Exception e) {
			logger.info("处理API请求时发生错误", e);
			this.sendJsonResponse(ctx, ApiResponse.error("服务器内部错误"));
		}
    }

	/**
	 * 	
	 * @param ctx
	 * @param data
	 */
	private void sendJsonResponse(ChannelHandlerContext ctx, Object data) {
		String json = JsonUtil.toJson(data);
		logger.info("Anthropic router response body={}", json);
		byte[] content = json.getBytes(CharsetUtil.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		//logger.info("客户端连接关闭：{}", ctx);
		// 事件通知
		this.anthropicService.channelInactive(ctx);
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.info("处理请求时发生异常", cause);
		ctx.close();
	}
}
