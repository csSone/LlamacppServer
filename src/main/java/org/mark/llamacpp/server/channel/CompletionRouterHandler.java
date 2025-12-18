package org.mark.llamacpp.server.channel;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.service.CompletionService;
import org.mark.llamacpp.server.service.OpenAIService;
import org.mark.llamacpp.server.struct.CharactorDataStruct;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * 	这是自用的创作服务的路由控制器。
 */
public class CompletionRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
	
	/**
	 * 	
	 */
	private static final Gson gson = new Gson();
	
	/**
	 * 	
	 */
	private CompletionService completionService = new CompletionService();
	
	
	private OpenAIService openAIService = new OpenAIService();
	
	
	public CompletionRouterHandler() {
		
	}
	
	
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
		String uri = msg.uri();
		if (uri == null) {
			sendError(ctx, HttpResponseStatus.BAD_REQUEST, "缺少URI");
			return;
		}
		if(uri.startsWith("/v1/completions")) {
			// TODO 在这里保存传入的提示词（聊天内容）
			String content = msg.content().toString(StandardCharsets.UTF_8);
			// 按照角色的title保存到本地
			JsonObject requestJson = gson.fromJson(content, JsonObject.class);
			// 
			
			System.err.println(requestJson);
			
		}
		
		if (uri.startsWith("/api/chat/completion")) {
			this.handleCompletionApi(ctx, msg, uri);
			return;
		}
		ctx.fireChannelRead(msg.retain());
	}
	
	/**
	 * 	处理API请求。
	 * @param ctx
	 * @param msg
	 * @param uri
	 */
	private void handleCompletionApi(ChannelHandlerContext ctx, FullHttpRequest msg, String uri) {
		try {
			String path = uri;
			String query = null;
			int qIdx = uri.indexOf('?');
			if (qIdx >= 0) {
				path = uri.substring(0, qIdx);
				query = uri.substring(qIdx + 1);
			}

			HttpMethod method = msg.method();
			
			if ("/api/chat/completion/list".equals(path) && HttpMethod.GET.equals(method)) {
				this.handleCharactorList(ctx);
				return;
			}

			if ("/api/chat/completion/create".equals(path) && HttpMethod.POST.equals(method)) {
				String body = msg.content().toString(CharsetUtil.UTF_8);
				this.handleCharactorCreate(ctx, body);
				return;
			}

			if ("/api/chat/completion/get".equals(path) && HttpMethod.GET.equals(method)) {
				String name = getQueryParam(query, "name");
				this.handleCharactorGet(ctx, name);
				return;
			}

			if ("/api/chat/completion/save".equals(path) && HttpMethod.POST.equals(method)) {
				String name = getQueryParam(query, "name");
				String body = msg.content().toString(CharsetUtil.UTF_8);
				this.handleCharactorSave(ctx, name, body);
				return;
			}

			if ("/api/chat/completion/delete".equals(path) && HttpMethod.DELETE.equals(method)) {
				String id = getQueryParam(query, "name");
				this.handleCharactorDelete(ctx, id);
				return;
			}

			sendError(ctx, HttpResponseStatus.NOT_FOUND, "404 Not Found");
		} catch (Exception e) {
			sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage());
		}
	}
	
	/**
	 * 	列出全部的character，以JSON格式返回
	 * @param ctx
	 */
	private void handleCharactorList(ChannelHandlerContext ctx) {
		List<CharactorDataStruct> list = this.completionService.listCharactor();
		
		Map<String, Object> response = new HashMap<String, Object>();
		response.put("data", list);
		response.put("success", true);
		
		CompletionRouterHandler.sendJson(ctx, response, HttpResponseStatus.OK);
	}
	
	/**
	 * 	创建一个新的character
	 * @param ctx
	 * @param body
	 */
	private void handleCharactorCreate(ChannelHandlerContext ctx, String body) {
		CharactorDataStruct created = this.completionService.createDefaultCharactor();
		try {
			JsonObject json = gson.fromJson(body, JsonObject.class);
			if (json != null && json.has("title")) {
				String title = json.get("title").getAsString();
				if (title != null && !title.trim().isEmpty()) {
					created.setTitle(title.trim());
					created.setUpdatedAt(System.currentTimeMillis());
					this.completionService.saveCharactor(created);
				}
			}
		} catch (Exception ignore) {
		}
		
		Map<String, Object> response = new HashMap<String, Object>();
		response.put("data", created);
		response.put("success", true);
		CompletionRouterHandler.sendJson(ctx, response, HttpResponseStatus.OK);
	}
	
	/**
	 * 	获取指定角色的信息
	 * @param ctx
	 * @param id
	 */
	private void handleCharactorGet(ChannelHandlerContext ctx, String name) {
		Map<String, Object> response = new HashMap<String, Object>();
		
		CharactorDataStruct charactorDataStruct =  this.completionService.getCharactor(name);
		// 
		if(charactorDataStruct == null) {
			response.put("success", false);
			response.put("message", "找不到指定的角色：" + name);
			CompletionRouterHandler.sendJson(ctx, response, HttpResponseStatus.NOT_FOUND);
			return;
		}
		// 
		response.put("success", true);
		response.put("message", "success");
		response.put("data", charactorDataStruct);
		
		CompletionRouterHandler.sendJson(ctx, response, HttpResponseStatus.OK);
	}
	
	/**
	 * 	保存角色信息。
	 * @param ctx
	 * @param id
	 * @param body
	 */
	private void handleCharactorSave(ChannelHandlerContext ctx, String name, String body) {
		Map<String, Object> response = new HashMap<String, Object>();
		try {
			CharactorDataStruct charactorDataStruct = gson.fromJson(body, CharactorDataStruct.class);
			try {
				Long id = name == null ? null : Long.parseLong(name.trim());
				if (id != null && id.longValue() > 0) {
					if (charactorDataStruct != null && charactorDataStruct.getId() != id.longValue()) {
						charactorDataStruct.setId(id.longValue());
					}
				}
			} catch (Exception ignore) {
			}
			this.completionService.saveCharactor(charactorDataStruct);
			response.put("success", true);
			CompletionRouterHandler.sendJson(ctx, response, HttpResponseStatus.OK);
			return;
		}catch (Exception e) {
			e.printStackTrace();
			response.put("success", false);
			response.put("message", e.getMessage());
			CompletionRouterHandler.sendJson(ctx, response, HttpResponseStatus.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * 	删除一个角色
	 * @param ctx
	 * @param name
	 */
	private void handleCharactorDelete(ChannelHandlerContext ctx, String name) {
		Map<String, Object> response = new HashMap<String, Object>();
		try {
			boolean ok = this.completionService.deleteCharactor(name);
			response.put("success", ok);
			if (!ok) {
				response.put("message", "找不到指定的角色：" + name);
				CompletionRouterHandler.sendJson(ctx, response, HttpResponseStatus.NOT_FOUND);
				return;
			}
		}catch (Exception e) {
			e.printStackTrace();
			response.put("success", false);
			response.put("message", e.getMessage());
			CompletionRouterHandler.sendJson(ctx, response, HttpResponseStatus.INTERNAL_SERVER_ERROR);
			return;
		}
		CompletionRouterHandler.sendJson(ctx, response, HttpResponseStatus.OK);
	}

	/**
	 * 	
	 * @param query
	 * @param key
	 * @return
	 */
	private static String getQueryParam(String query, String key) {
		if (query == null || query.isEmpty() || key == null || key.isEmpty())
			return null;
		String[] parts = query.split("&");
		for (String p : parts) {
			int idx = p.indexOf('=');
			if (idx < 0)
				continue;
			String k = p.substring(0, idx);
			if (!key.equals(k))
				continue;
			String v = p.substring(idx + 1);
			try {
				return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
			} catch (Exception e) {
				return v;
			}
		}
		return null;
	}

	private static void sendJson(ChannelHandlerContext ctx, Object payload, HttpResponseStatus status) {
		String json = gson.toJson(payload);
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		FullHttpResponse resp = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1,
			status,
			Unpooled.wrappedBuffer(bytes)
		);
		resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
	}

	private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("status", "error");
		payload.put("message", message);
		sendJson(ctx, payload, status);
	}
}
