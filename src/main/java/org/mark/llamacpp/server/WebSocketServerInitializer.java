package org.mark.llamacpp.server;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * WebSocket服务器初始化器
 */
public class WebSocketServerInitializer extends ChannelInitializer<SocketChannel> {
    
    private final String websocketPath;
    
    public WebSocketServerInitializer(String websocketPath) {
        this.websocketPath = websocketPath;
    }
    
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        
        // 添加HTTP编解码器
        pipeline.addLast(new HttpServerCodec());
        
        // 添加HTTP对象聚合器，将多个消息转换为单一的FullHttpRequest或FullHttpResponse
        pipeline.addLast(new HttpObjectAggregator(65536));
        
        // 添加块写入处理器，支持大文件传输
        pipeline.addLast(new ChunkedWriteHandler());
        
        // 添加空闲状态处理器，用于心跳检测
        pipeline.addLast(new IdleStateHandler(60, 30, 90, TimeUnit.SECONDS));
        
        // 添加WebSocket协议处理器，处理握手和升级
        pipeline.addLast(new WebSocketServerProtocolHandler(websocketPath));
        
        // 添加自定义WebSocket处理器
        pipeline.addLast(new WebSocketServerHandler());
    }
}