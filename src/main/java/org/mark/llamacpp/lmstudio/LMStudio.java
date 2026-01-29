package org.mark.llamacpp.lmstudio;

import org.mark.llamacpp.lmstudio.websocket.LMStudioWebSocketHandler;
import org.mark.llamacpp.server.channel.BasicRouterHandler;
import org.mark.llamacpp.server.channel.CompletionRouterHandler;
import org.mark.llamacpp.server.channel.FileDownloadRouterHandler;
import org.mark.llamacpp.server.channel.OpenAIRouterHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;

public class LMStudio {
	
	/**
	 * 	
	 */
	private static final Logger logger = LoggerFactory.getLogger(LMStudio.class);
	
	
	private static final String WEBSOCKET_LLM_PATH = "/llm";
	
	private static final String WEBSOCKET_SYSTEM_PATH = "/system";
	
	
	private Thread worker;
	
	/**
	 * 	默认端口
	 */
	private int port = 1234;
	
	
	
	
	
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
	                                    .addLast(new LMStudioWsPathSelectHandler())
	                                    
	                                    .addLast(new BasicRouterHandler())
	                                    .addLast(new CompletionRouterHandler())
	                                    .addLast(new FileDownloadRouterHandler())
	                                    .addLast(new OpenAIRouterHandler());
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

	private static final class LMStudioWsPathSelectHandler extends io.netty.channel.ChannelInboundHandlerAdapter {
		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			if (!(msg instanceof FullHttpRequest request)) {
				ctx.fireChannelRead(msg);
				return;
			}

			try {
				if (!isWebSocketUpgrade(request)) {
					ctx.fireChannelRead(request.retain());
					return;
				}

				String uri = request.uri();
				String path = uri == null ? null : uri.split("\\?", 2)[0];
				if (!WEBSOCKET_SYSTEM_PATH.equals(path) && !WEBSOCKET_LLM_PATH.equals(path)) {
					FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
					resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
					ctx.writeAndFlush(resp).addListener(f -> ctx.close());
					return;
				}

				String selfName = ctx.name();
				ctx.pipeline().addAfter(selfName, "lmstudio-ws-protocol", new WebSocketServerProtocolHandler(path, null, true, Integer.MAX_VALUE));
				ctx.pipeline().addAfter("lmstudio-ws-protocol", "lmstudio-ws-handler", new LMStudioWebSocketHandler(uri));
				ctx.fireChannelRead(request.retain());
				ctx.pipeline().remove(this);
			} finally {
				ReferenceCountUtil.release(request);
			}
		}

		private static boolean isWebSocketUpgrade(FullHttpRequest request) {
			if (request == null) return false;
			String upgrade = request.headers().get(HttpHeaderNames.UPGRADE);
			if (upgrade == null || !HttpHeaderValues.WEBSOCKET.toString().equalsIgnoreCase(upgrade)) return false;
			String connection = request.headers().get(HttpHeaderNames.CONNECTION);
			if (connection == null) return false;
			return connection.toLowerCase().contains(HttpHeaderValues.UPGRADE.toString());
		}
	}
	
	
	
	public void stop() {
		
	}
	
	
}
