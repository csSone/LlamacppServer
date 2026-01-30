package org.mark.llamacpp.ollama;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.mark.llamacpp.ollama.channel.OllamaRouterHandler;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedWriteHandler;

public class Ollama {
	/**
	 * 	
	 */
	private static final Logger logger = LoggerFactory.getLogger(Ollama.class);
	
	
	private Thread worker;
	
	/**
	 * 	默认端口
	 */
	private int port = 11435;
	
	
	public Ollama() {
		
	}
	
	
	
	public void start() {
		this.worker = new Thread(() -> {
	        EventLoopGroup bossGroup = new NioEventLoopGroup();
	        EventLoopGroup workerGroup = new NioEventLoopGroup();
	        
	        try {
	            ServerBootstrap bootstrap = new ServerBootstrap();
	            bootstrap.group(bossGroup, workerGroup)
	                    .channel(NioServerSocketChannel.class)
	                    .childHandler(new ChannelInitializer<SocketChannel>() {
	                        @Override
	                        protected void initChannel(SocketChannel ch) throws Exception {
	                            ch.pipeline()
	                                    .addLast(new HttpServerCodec())
	                                    .addLast(new HttpObjectAggregator(Integer.MAX_VALUE)) // 最大！
	                                    .addLast(new ChunkedWriteHandler())
	                                    
	                                    .addLast(new OllamaRouterHandler());
	                        }
	                        @Override
	                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
	                        		logger.info("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
	                            ctx.close();
	                        }
	                    });
	            
	            ChannelFuture future = bootstrap.bind(this.port).sync();
	            logger.info("LlammServer启动成功，端口: {}", this.port);
	            logger.info("访问地址: http://localhost:{}", this.port);
	            
	            future.channel().closeFuture().sync();
	        } catch (InterruptedException e) {
	            logger.info("服务器被中断", e);
	            Thread.currentThread().interrupt();
	        } catch (Exception e) {
	            logger.info("服务器启动失败", e);
	        } finally {
	            bossGroup.shutdownGracefully();
	            workerGroup.shutdownGracefully();
	            
	            logger.info("服务器已关闭");
	        }
		});
		this.worker.start();
	}
	
	/**
	 * 	发送错误消息。
	 * @param ctx
	 * @param status
	 * @param message
	 */
	public static void sendOllamaError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("error", message == null ? "" : message);
		sendOllamaJson(ctx, status == null ? HttpResponseStatus.INTERNAL_SERVER_ERROR : status, payload);
	}
	
	/**
	 * 	发送JSON消息。
	 * @param ctx
	 * @param status
	 * @param data
	 */
	public static void sendOllamaJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);
		
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		//response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
		//response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		//response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
		//
		//response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(content));
		//response.headers().set("X-Powered-By", "Express");
		//response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.content().writeBytes(content);
		
		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param status
	 * @param data
	 */
	public static void sendOllamaChunkedJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);
		
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
		response.content().writeBytes(content);
		
		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
}
