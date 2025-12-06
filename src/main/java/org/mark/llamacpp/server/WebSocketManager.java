package org.mark.llamacpp.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket连接管理器
 */
public class WebSocketManager {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSocketManager.class);
    
    // 单例实例
    private static volatile WebSocketManager instance;
    
    // 存储所有活跃的WebSocket连接
    private final ConcurrentMap<String, ChannelHandlerContext> connections = new ConcurrentHashMap<>();
    
    // 存储连接的确认状态
    private final ConcurrentMap<String, Boolean> connectionStatus = new ConcurrentHashMap<>();
    
    // 连接计数器
    private int connectionCounter = 0;
    
    // 定时任务执行器，用于发送心跳和定期消息
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    private WebSocketManager() {
        // 启动定时任务，每30秒发送一次心跳
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 30, 30, TimeUnit.SECONDS);
        
        // 启动定时任务，每60秒发送一次系统状态
        scheduler.scheduleAtFixedRate(this::sendSystemStatus, 60, 60, TimeUnit.SECONDS);
    }
    
    /**
     * 获取单例实例
     */
    public static WebSocketManager getInstance() {
        if (instance == null) {
            synchronized (WebSocketManager.class) {
                if (instance == null) {
                    instance = new WebSocketManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 添加新的WebSocket连接
     */
    public String addConnection(ChannelHandlerContext ctx) {
        connectionCounter++;
        String connectionId = "conn-" + connectionCounter;
        connections.put(connectionId, ctx);
        connectionStatus.put(connectionId, false); // 初始状态为未确认
        logger.info("新的WebSocket连接建立: {}, 当前连接数: {}", connectionId, connections.size());
        return connectionId;
    }
    
    /**
     * 移除WebSocket连接
     */
    public void removeConnection(String connectionId) {
        connections.remove(connectionId);
        connectionStatus.remove(connectionId);
        logger.info("WebSocket连接关闭: {}, 当前连接数: {}", connectionId, connections.size());
    }
    
    /**
     * 向指定连接发送消息
     */
    public void sendMessage(String connectionId, String message) {
        ChannelHandlerContext ctx = connections.get(connectionId);
        if (ctx != null && ctx.channel().isActive()) {
            ctx.writeAndFlush(new TextWebSocketFrame(message));
            logger.debug("向连接 {} 发送消息: {}", connectionId, message);
        } else {
            // 移除不活跃的连接
            connections.remove(connectionId);
            logger.warn("连接 {} 不存在或已关闭，无法发送消息", connectionId);
        }
    }
    
    /**
     * 向所有连接广播消息
     */
    public void broadcast(String message) {
        //logger.info("向所有WebSocket客户端广播消息: {}", message);
        
        // 使用迭代器安全地遍历并发集合
        connections.entrySet().removeIf(entry -> {
            String connectionId = entry.getKey();
            ChannelHandlerContext ctx = entry.getValue();
            
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(new TextWebSocketFrame(message));
                return false; // 保留连接
            } else {
                logger.info("移除不活跃的连接: {}", connectionId);
                return true; // 移除连接
            }
        });
    }
    
    /**
     * 获取当前连接数
     */
    public int getConnectionCount() {
        return connections.size();
    }
    
    /**
     * 确认WebSocket连接
     */
    public void confirmConnection(String connectionId) {
        if (connections.containsKey(connectionId)) {
            connectionStatus.put(connectionId, true);
            logger.info("WebSocket连接已确认: {}", connectionId);
        }
    }
    
    /**
     * 检查连接是否已确认
     */
    public boolean isConnectionConfirmed(String connectionId) {
        return connectionStatus.getOrDefault(connectionId, false);
    }
    
    /**
     * 获取已确认的连接数
     */
    public int getConfirmedConnectionCount() {
        return (int) connectionStatus.values().stream().mapToInt(status -> status ? 1 : 0).sum();
    }
    
    /**
     * 发送心跳消息
     */
    private void sendHeartbeat() {
        if (getConnectionCount() > 0) {
            long timestamp = System.currentTimeMillis();
            broadcast("{\"type\":\"heartbeat\",\"timestamp\":" + timestamp + "}");
        }
    }
    
    /**
     * 发送系统状态
     */
    private void sendSystemStatus() {
        if (getConnectionCount() > 0) {
            try {
                LlamaServerManager serverManager = LlamaServerManager.getInstance();
                int loadedModelsCount = serverManager.getLoadedProcesses().size();
                
                String statusMessage = String.format(
                    "{\"type\":\"systemStatus\",\"timestamp\":%d,\"loadedModels\":%d,\"connections\":%d,\"confirmedConnections\":%d}",
                    System.currentTimeMillis(),
                    loadedModelsCount,
                    getConnectionCount(),
                    getConfirmedConnectionCount()
                );
                
                broadcast(statusMessage);
            } catch (Exception e) {
                logger.error("发送系统状态时发生错误", e);
            }
        }
    }
    
    /**
     * 发送模型加载事件
     */
    public void sendModelLoadEvent(String modelId, boolean success, String message) {
        String eventMessage = String.format(
            "{\"type\":\"modelLoad\",\"modelId\":\"%s\",\"success\":%s,\"message\":\"%s\",\"timestamp\":%d}",
            modelId,
            success,
            message != null ? message.replace("\"", "\\\"") : "",
            System.currentTimeMillis()
        );
        
        broadcast(eventMessage);
    }
    
    /**
     * 发送模型停止事件
     */
    public void sendModelStopEvent(String modelId, boolean success, String message) {
        String eventMessage = String.format(
            "{\"type\":\"modelStop\",\"modelId\":\"%s\",\"success\":%s,\"message\":\"%s\",\"timestamp\":%d}",
            modelId,
            success,
            message != null ? message.replace("\"", "\\\"") : "",
            System.currentTimeMillis()
        );
        
        broadcast(eventMessage);
    }
    
    /**
     * 关闭管理器，释放资源
     */
    public void shutdown() {
        logger.info("关闭WebSocket管理器");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 关闭所有连接
        connections.values().forEach(ctx -> {
            if (ctx.channel().isActive()) {
                ctx.close();
            }
        });
        connections.clear();
        connectionStatus.clear();
    }
}