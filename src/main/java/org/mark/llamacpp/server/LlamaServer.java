package org.mark.llamacpp.server;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.mark.llamacpp.server.io.ConsoleBroadcastOutputStream;
import org.mark.llamacpp.server.websocket.WebSocketManager;
import org.mark.llamacpp.server.websocket.WebSocketServerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

public class LlamaServer {
    
	private static final Logger logger = LoggerFactory.getLogger(LlamaServer.class);
    
    private static final int DEFAULT_WEB_PORT = 8080;
    private static final Path CONSOLE_LOG_PATH = Paths.get("logs", "console.log");
    private static final String WEBSOCKET_PATH = "/ws";
    
    
    public static void main(String[] args) {
        try {
            Files.createDirectories(CONSOLE_LOG_PATH.getParent());
            ConsoleBroadcastOutputStream out = new ConsoleBroadcastOutputStream(new FileOutputStream(CONSOLE_LOG_PATH.toFile(), true), StandardCharsets.UTF_8);
            PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8.name());
            System.setOut(ps);
            System.setErr(ps);
        } catch (Exception e) {
            e.printStackTrace();
        }
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_WEB_PORT;
        
        // 初始化配置管理器并加载配置
        logger.info("正在初始化配置管理器...");
        ConfigManager configManager = ConfigManager.getInstance();
        
        // 预加载启动配置到内存中
        logger.info("正在加载启动配置...");
        configManager.loadAllLaunchConfigs();
        
        // 初始化LlamaServerManager并预加载模型列表
        logger.info("正在初始化模型管理器...");
        LlamaServerManager serverManager = LlamaServerManager.getInstance();
        
        // 预加载模型列表，这会同时保存模型信息到配置文件
        logger.info("正在扫描模型目录...");
        serverManager.listModel();
        
        // 初始化并启动系统监控服务
        logger.info("正在启动系统监控服务...");
        SystemMonitorService monitorService = SystemMonitorService.getInstance();
        //monitorService.start(1); // 每30秒监控一次
        
        logger.info("系统初始化完成，启动Web服务器...");
        
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
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
                                    .addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true, Integer.MAX_VALUE))
                                    .addLast(new WebSocketServerHandler())
                                    .addLast(new LlamaServerHandler());
                        }
                    });
            
            ChannelFuture future = bootstrap.bind(port).sync();
            logger.info("LlammServer启动成功，端口: {}", port);
            logger.info("访问地址: http://localhost:{}", port);
            
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            logger.error("服务器被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("服务器启动失败", e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            
            // 停止系统监控服务
            logger.info("正在停止系统监控服务...");
            if (monitorService.isStarted()) {
                monitorService.stop();
            }
            
            logger.info("服务器已关闭");
        }
    }

    public static Path getConsoleLogPath() {
        return CONSOLE_LOG_PATH;
    }
    
    /**
     * 广播WebSocket消息
     */
    public static void broadcastWebSocketMessage(String message) {
        WebSocketManager.getInstance().broadcast(message);
    }
    
    /**
     * 获取当前WebSocket连接数
     */
    public static int getWebSocketConnectionCount() {
        return WebSocketManager.getInstance().getConnectionCount();
    }
    
    /**
     * 发送模型加载事件
     */
    public static void sendModelLoadEvent(String modelId, boolean success, String message) {
        WebSocketManager.getInstance().sendModelLoadEvent(modelId, success, message);
    }
    
    /**
     * 发送模型停止事件
     */
    public static void sendModelStopEvent(String modelId, boolean success, String message) {
        WebSocketManager.getInstance().sendModelStopEvent(modelId, success, message);
    }
    
    public static void sendConsoleLineEvent(String modelId, String line) {
        WebSocketManager.getInstance().sendConsoleLineEvent(modelId, line);
    }
}
