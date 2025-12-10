package org.mark.llamacpp.server.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * 跨平台的端口检查工具类
 * 用于检查指定端口是否被占用，并查找下一个可用端口
 */
public class PortChecker {
    
    private static final int MAX_PORT = 65535;
    private static final int SOCKET_TIMEOUT_MS = 1000; // 1秒超时
    
    /**
     * 检查指定端口是否可用
     * @param port 要检查的端口号
     * @return 如果端口可用返回true，否则返回false
     */
    public static boolean isPortAvailable(int port) {
        if (port < 0 || port > MAX_PORT) {
            return false;
        }
        
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(false);
            serverSocket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    // 忽略关闭异常
                }
            }
        }
    }
    
    /**
     * 检查指定端口是否被占用（使用Socket连接方式）
     * 这种方法可以检测到其他进程占用的端口
     * @param port 要检查的端口号
     * @return 如果端口被占用返回true，否则返回false
     */
    public static boolean isPortInUse(int port) {
        if (port < 0 || port > MAX_PORT) {
            return false;
        }
        
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("localhost", port), SOCKET_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // 忽略关闭异常
                }
            }
        }
    }
    
    /**
     * 使用操作系统特定命令检查端口是否被占用
     * @param port 要检查的端口号
     * @return 如果端口被占用返回true，否则返回false
     */
    public static boolean isPortInUseByOSCommand(int port) {
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            ProcessBuilder processBuilder;
            
            if (osName.contains("win")) {
                // Windows系统使用netstat命令
                processBuilder = new ProcessBuilder("netstat", "-ano");
            } else {
                // Linux/Unix系统使用ss或netstat命令
                try {
                    processBuilder = new ProcessBuilder("ss", "-tuln");
                } catch (Exception e) {
                    // 如果ss命令不可用，尝试使用netstat
                    processBuilder = new ProcessBuilder("netstat", "-tuln");
                }
            }
            
            // 启动进程并读取输出
            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            // 等待进程完成
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                // 如果命令执行失败，回退到Socket方式
                return isPortInUse(port);
            }
            
            // 检查输出中是否包含指定端口
            String result = output.toString();
            return result.contains(":" + port + " ") || result.contains(":" + port + "\n") ||
                   result.contains("0.0.0.0:" + port) || result.contains("127.0.0.1:" + port);
            
        } catch (IOException | InterruptedException e) {
            // 如果OS命令执行失败或被中断，回退到Socket方式
            Thread.currentThread().interrupt(); // 恢复中断状态
            return isPortInUse(port);
        }
    }
    
    /**
     * 综合检查端口是否可用（使用多种方法验证）
     * @param port 要检查的端口号
     * @return 如果端口可用返回true，否则返回false
     */
    public static boolean isPortDefinitelyAvailable(int port) {
        // 首先使用ServerSocket方式检查
        if (!isPortAvailable(port)) {
            return false;
        }
        
        // 然后使用Socket连接方式检查
        if (isPortInUse(port)) {
            return false;
        }
        
        // 最后使用OS命令检查（可选，更准确但可能较慢）
        return !isPortInUseByOSCommand(port);
    }
    
    /**
     * 从指定端口开始查找下一个可用端口
     * @param startPort 起始端口号
     * @return 可用的端口号
     * @throws IllegalStateException 如果在有效端口范围内找不到可用端口
     */
    public static int findNextAvailablePort(int startPort) {
        if (startPort < 0 || startPort > MAX_PORT) {
            throw new IllegalArgumentException("起始端口必须在0-" + MAX_PORT + "范围内");
        }
        
        int port = startPort;
        while (port <= MAX_PORT) {
            if (isPortDefinitelyAvailable(port)) {
                return port;
            }
            port++;
        }
        
        throw new IllegalStateException("在端口范围 " + startPort + "-" + MAX_PORT + " 内找不到可用端口");
    }
    
    /**
     * 从指定端口开始查找下一个可用端口，带有最大尝试次数限制
     * @param startPort 起始端口号
     * @param maxAttempts 最大尝试次数
     * @return 可用的端口号
     * @throws IllegalStateException 如果超过最大尝试次数仍找不到可用端口
     */
    public static int findNextAvailablePort(int startPort, int maxAttempts) {
        if (startPort < 0 || startPort > MAX_PORT) {
            throw new IllegalArgumentException("起始端口必须在0-" + MAX_PORT + "范围内");
        }
        
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("最大尝试次数必须大于0");
        }
        
        int port = startPort;
        int attempts = 0;
        
        while (port <= MAX_PORT && attempts < maxAttempts) {
            if (isPortDefinitelyAvailable(port)) {
                return port;
            }
            port++;
            attempts++;
        }
        
        throw new IllegalStateException("在 " + maxAttempts + " 次尝试内找不到可用端口");
    }
    
    /**
     * 等待端口变为可用状态
     * @param port 要等待的端口号
     * @param timeoutMs 超时时间（毫秒）
     * @param checkIntervalMs 检查间隔（毫秒）
     * @return 如果端口在超时前变为可用返回true，否则返回false
     */
    public static boolean waitForPortAvailable(int port, long timeoutMs, long checkIntervalMs) {
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isPortDefinitelyAvailable(port)) {
                return true;
            }
            
            try {
                TimeUnit.MILLISECONDS.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        return false;
    }
}