package org.mark.llamacpp.lmstudio.channel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.lmstudio.LMStudioService;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;



/**
 * 	模拟LM Studio的API服务。
 */
public class LMStudioRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(LMStudioRouterHandler.class);
	
	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
	
	private LMStudioService lmStudioService = new LMStudioService();
	
	
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		FullHttpRequest retained = request.retainedDuplicate();
		async.execute(() -> {
			try {
				this.handleRequest(ctx, retained);
			} finally {
				ReferenceCountUtil.release(retained);
			}
		});
	}
	
	
	public LMStudioRouterHandler() {
		
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 */
	private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (!request.decoderResult().isSuccess()) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求解析失败");
			return;
		}
		String uri = request.uri();
		logger.info("收到请求: {} {}", request.method().name(), uri);
		logger.info("请求头：{}", request.headers());
		// 傻逼浏览器不知道为什么一直在他妈的访问/.well-known/appspecific/com.chrome.devtools.json
		if ("/.well-known/appspecific/com.chrome.devtools.json".equals(uri)) {
			ctx.close();
			return;
		}
		//
		try {
			boolean handled = this.handleRequest(uri, ctx, request);
			if(!handled) {
				ctx.fireChannelRead(request.retain());
			}
		} catch (RequestMethodException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		}
	}
	
	/**
	 * 	正经处理请求的地方。
	 * @param uri
	 * @param ctx
	 * @param request
	 * @return
	 * @throws RequestMethodException
	 */
	private boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		// 模型列表
		if (uri.startsWith("/api/v0/models")) {
			//this.handleModelList(uri, ctx, request);
			this.lmStudioService.handleModelList(ctx, request);
			return true;
		}
		
		// 聊天补全
		if (uri.startsWith("/api/v0/chat/completions")) {
			this.lmStudioService.handleOpenAIChatCompletionsRequest(ctx, request);
			
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
	
	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		//logger.info("客户端连接关闭：{}", ctx);
		// 事件通知
		this.lmStudioService.channelInactive(ctx);
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.info("处理请求时发生异常", cause);
		ctx.close();
	}
}
