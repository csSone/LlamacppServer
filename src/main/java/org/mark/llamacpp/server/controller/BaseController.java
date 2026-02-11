package org.mark.llamacpp.server.controller;

import org.mark.llamacpp.server.exception.RequestMethodException;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;


/**
 * 	基本的控制器接口
 */
public interface BaseController {

	/**
	 * 	处理请求
	 * @param uri
	 * @param ctx
	 * @param request
	 * @return
	 * @throws RequestMethodException
	 */
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException;
	
	
	/**
	 * 	断开连接的事件。
	 * @param ctx
	 */
	default public void inactive(ChannelHandlerContext ctx) {}
	
	
	/**
	 * 	简单的断言。
	 * @param check
	 * @param message
	 * @throws RequestMethodException
	 */
	default public void assertRequestMethod(boolean check, String message) throws RequestMethodException {
		if (check)
			throw new RequestMethodException(message);
	}
}
