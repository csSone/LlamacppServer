package org.mark.llamacpp.server.tools;

/**
 * PortChecker工具类的简单测试类
 */
public class PortCheckerTest {
    
    public static void main(String[] args) {
        System.out.println("开始测试端口检查功能...");
        
        // 测试1: 检查常见端口状态
        testCommonPorts();
        
        // 测试2: 查找可用端口
        testFindAvailablePort();
        
        // 测试3: 测试端口范围查找
        testPortRangeSearch();
        
        System.out.println("端口检查功能测试完成。");
    }
    
    /**
     * 测试常见端口状态
     */
    private static void testCommonPorts() {
        System.out.println("\n=== 测试常见端口状态 ===");
        
        int[] commonPorts = {22, 80, 443, 8080, 8081, 3306, 5432};
        
        for (int port : commonPorts) {
            boolean available = PortChecker.isPortDefinitelyAvailable(port);
            System.out.println("端口 " + port + " 是否可用: " + available);
        }
    }
    
    /**
     * 测试查找可用端口功能
     */
    private static void testFindAvailablePort() {
        System.out.println("\n=== 测试查找可用端口功能 ===");
        
        int startPort = 8080;
        try {
            int availablePort = PortChecker.findNextAvailablePort(startPort);
            System.out.println("从端口 " + startPort + " 开始找到的第一个可用端口: " + availablePort);
            
            // 验证找到的端口确实可用
            boolean isAvailable = PortChecker.isPortDefinitelyAvailable(availablePort);
            System.out.println("验证端口 " + availablePort + " 是否可用: " + isAvailable);
        } catch (IllegalStateException e) {
            System.err.println("无法找到可用端口: " + e.getMessage());
        }
    }
    
    /**
     * 测试端口范围查找功能
     */
    private static void testPortRangeSearch() {
        System.out.println("\n=== 测试端口范围查找功能 ===");
        
        int startPort = 9000;
        int maxAttempts = 10;
        
        try {
            int availablePort = PortChecker.findNextAvailablePort(startPort, maxAttempts);
            System.out.println("在 " + maxAttempts + " 次尝试内找到的可用端口: " + availablePort);
        } catch (IllegalStateException e) {
            System.err.println("在 " + maxAttempts + " 次尝试内无法找到可用端口: " + e.getMessage());
        }
        
        // 测试极端情况：从接近最大端口的值开始查找
        try {
            int highStartPort = 65530;
            int availablePort = PortChecker.findNextAvailablePort(highStartPort, 5);
            System.out.println("从高端口 " + highStartPort + " 开始找到的可用端口: " + availablePort);
        } catch (IllegalStateException e) {
            System.err.println("从高端口开始无法找到可用端口: " + e.getMessage());
        }
    }
}