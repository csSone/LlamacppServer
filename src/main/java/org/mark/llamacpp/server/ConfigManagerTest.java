package org.mark.llamacpp.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置管理器测试类
 */
public class ConfigManagerTest {
    
    public static void main(String[] args) {
        System.out.println("开始测试配置管理器...");
        
        ConfigManager configManager = ConfigManager.getInstance();
        
        // 测试1: 保存和加载启动配置
        System.out.println("\n=== 测试1: 保存和加载启动配置 ===");
        String testModelId = "test-model-1";
        Map<String, Object> testConfig = new HashMap<>();
        testConfig.put("ctxSize", 4096);
        testConfig.put("batchSize", 512);
        testConfig.put("ubatchSize", 1024);
        testConfig.put("noMmap", true);
        testConfig.put("mlock", false);
        
        boolean saveResult = configManager.saveLaunchConfig(testModelId, testConfig);
        System.out.println("保存启动配置结果: " + (saveResult ? "成功" : "失败"));
        
        Map<String, Object> loadedConfig = configManager.getLaunchConfig(testModelId);
        System.out.println("加载的启动配置: " + loadedConfig);
        
        // 验证配置是否一致
        boolean configMatch = testConfig.equals(loadedConfig);
        System.out.println("配置一致性验证: " + (configMatch ? "通过" : "失败"));
        
        // 测试2: 加载所有启动配置
        System.out.println("\n=== 测试2: 加载所有启动配置 ===");
        Map<String, Map<String, Object>> allConfigs = configManager.loadAllLaunchConfigs();
        System.out.println("所有启动配置数量: " + allConfigs.size());
        
        // 测试3: 获取默认配置
        System.out.println("\n=== 测试3: 获取默认配置 ===");
        Map<String, Object> defaultConfig = configManager.getLaunchConfig("non-existent-model");
        System.out.println("默认配置: " + defaultConfig);
        
        // 测试4: 保存和加载模型配置
        System.out.println("\n=== 测试4: 保存和加载模型配置 ===");
        List<Map<String, Object>> modelsConfig = configManager.loadModelsConfig();
        System.out.println("加载的模型配置数量: " + modelsConfig.size());
        
        System.out.println("\n配置管理器测试完成！");
    }
}