package org.mark.llamacpp.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.mark.llamacpp.gguf.GGUFModel;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置文件管理类，用于保存和加载模型信息及启动配置
 */
public class ConfigManager {
    private static final ConfigManager INSTANCE = new ConfigManager();
    
    // 配置文件路径
    private static final String CONFIG_DIR = "config";
    private static final String MODELS_CONFIG_FILE = CONFIG_DIR + "/models.json";
    private static final String LAUNCH_CONFIG_FILE = CONFIG_DIR + "/launch_config.json";
    
    private final Gson gson;
    
    private ConfigManager() {
        // 创建Gson实例，设置美观格式化
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        
        // 确保配置目录存在
        ensureConfigDirectoryExists();
    }
    
    public static ConfigManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 确保配置目录存在
     */
    private void ensureConfigDirectoryExists() {
        Path configPath = Paths.get(CONFIG_DIR);
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(configPath);
                System.out.println("创建配置目录: " + CONFIG_DIR);
            } catch (IOException e) {
                System.err.println("创建配置目录失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 保存模型列表信息到JSON文件
     * @param models 模型列表
     * @return 是否保存成功
     */
    public boolean saveModelsConfig(List<GGUFModel> models) {
        try {
            // 将GGUFModel列表转换为可序列化的Map列表
            List<Map<String, Object>> modelsData = models.stream()
                .map(this::modelToMap)
                .toList();
            
            // 写入文件
            try (FileWriter writer = new FileWriter(MODELS_CONFIG_FILE)) {
                gson.toJson(modelsData, writer);
                System.out.println("模型配置已保存到: " + MODELS_CONFIG_FILE);
                return true;
            }
        } catch (IOException e) {
            System.err.println("保存模型配置失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 保存模型启动配置到JSON文件
     * @param modelId 模型ID
     * @param launchConfig 启动配置
     * @return 是否保存成功
     */
    public boolean saveLaunchConfig(String modelId, Map<String, Object> launchConfig) {
        try {
            // 读取现有的启动配置
            Map<String, Map<String, Object>> allConfigs = loadAllLaunchConfigs();
            
            // 更新或添加新配置
            allConfigs.put(modelId, launchConfig);
            
            // 写入文件
            try (FileWriter writer = new FileWriter(LAUNCH_CONFIG_FILE)) {
                gson.toJson(allConfigs, writer);
                System.out.println("启动配置已保存到: " + LAUNCH_CONFIG_FILE);
                return true;
            }
        } catch (IOException e) {
            System.err.println("保存启动配置失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 加载模型列表信息
     * @return 模型列表数据，如果加载失败返回空列表
     */
    public List<Map<String, Object>> loadModelsConfig() {
        File configFile = new File(MODELS_CONFIG_FILE);
        if (!configFile.exists()) {
            System.out.println("模型配置文件不存在，返回空列表: " + MODELS_CONFIG_FILE);
            return List.of();
        }
        
        try (FileReader reader = new FileReader(configFile)) {
            Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> modelsData = gson.fromJson(reader, listType);
            System.out.println("成功加载模型配置: " + MODELS_CONFIG_FILE);
            return modelsData != null ? modelsData : List.of();
        } catch (IOException e) {
            System.err.println("加载模型配置失败: " + e.getMessage());
            return List.of();
        }
    }
    
    /**
     * 加载所有模型的启动配置
     * @return 所有启动配置的映射，如果加载失败返回空Map
     */
    public Map<String, Map<String, Object>> loadAllLaunchConfigs() {
        File configFile = new File(LAUNCH_CONFIG_FILE);
        if (!configFile.exists()) {
            System.out.println("启动配置文件不存在，返回空配置: " + LAUNCH_CONFIG_FILE);
            return new HashMap<>();
        }
        
        try (FileReader reader = new FileReader(configFile)) {
            Type mapType = new TypeToken<Map<String, Map<String, Object>>>() {}.getType();
            Map<String, Map<String, Object>> configs = gson.fromJson(reader, mapType);
            System.out.println("成功加载启动配置: " + LAUNCH_CONFIG_FILE);
            return configs != null ? configs : new HashMap<>();
        } catch (IOException e) {
            System.err.println("加载启动配置失败: " + e.getMessage());
            return new HashMap<>();
        }
    }
    
    /**
     * 获取指定模型的启动配置
     * @param modelId 模型ID
     * @return 模型的启动配置，如果不存在返回默认配置
     */
    public Map<String, Object> getLaunchConfig(String modelId) {
        Map<String, Map<String, Object>> allConfigs = loadAllLaunchConfigs();
        return allConfigs.getOrDefault(modelId, createDefaultLaunchConfig());
    }
    
    /**
     * 创建默认启动配置
     * @return 默认启动配置
     */
    private Map<String, Object> createDefaultLaunchConfig() {
        Map<String, Object> defaultConfig = new HashMap<>();
        defaultConfig.put("ctxSize", 32768);
        defaultConfig.put("batchSize", 1024);
        defaultConfig.put("ubatchSize", 2048);
        defaultConfig.put("noMmap", false);
        defaultConfig.put("mlock", false);
        defaultConfig.put("temperature", 0.7);
        defaultConfig.put("topP", 0.95);
        defaultConfig.put("topK", 40);
        defaultConfig.put("minP", 0.05);
        defaultConfig.put("presencePenalty", 0.0);
        defaultConfig.put("repeatPenalty", 1.0);
        defaultConfig.put("embedding", false);
        defaultConfig.put("reranking", false);
        defaultConfig.put("enableVision", true);
        defaultConfig.put("mg", -1);
        return defaultConfig;
    }
    
    /**
     * 将GGUFModel转换为可序列化的Map
     * @param model GGUFModel对象
     * @return 包含模型信息的Map
     */
    private Map<String, Object> modelToMap(GGUFModel model) {
        Map<String, Object> modelMap = new HashMap<>();
        
        // 基本信息
        modelMap.put("modelId", model.getModelId());
        modelMap.put("path", model.getPath());
        modelMap.put("size", model.getSize());
        modelMap.put("favourite", model.isFavourite());
        if (model.getAlias() != null && !model.getAlias().isEmpty()) {
            modelMap.put("alias", model.getAlias());
        }
        
        // 主模型信息
        if (model.getPrimaryModel() != null) {
            Map<String, Object> primaryModel = new HashMap<>();
            primaryModel.put("fileName", model.getPrimaryModel().getFileName());
            primaryModel.put("name", model.getPrimaryModel().getStringValue("general.name"));
            primaryModel.put("architecture", model.getPrimaryModel().getStringValue("general.architecture"));
            primaryModel.put("contextLength", model.getPrimaryModel().getIntValue(
                model.getPrimaryModel().getStringValue("general.architecture") + ".context_length"));
            primaryModel.put("embeddingLength", model.getPrimaryModel().getIntValue(
                model.getPrimaryModel().getStringValue("general.architecture") + ".embedding_length"));
            modelMap.put("primaryModel", primaryModel);
        }
        
        // 多模态投影信息
        if (model.getMmproj() != null) {
            Map<String, Object> mmproj = new HashMap<>();
            mmproj.put("fileName", model.getMmproj().getFileName());
            mmproj.put("name", model.getMmproj().getStringValue("general.name"));
            mmproj.put("architecture", model.getMmproj().getStringValue("general.architecture"));
            modelMap.put("mmproj", mmproj);
        }
        
        return modelMap;
    }

    /**
     * 保存/更新模型别名到models.json
     */
    public boolean saveModelAlias(String modelId, String alias) {
        try {
            List<Map<String, Object>> models = new java.util.ArrayList<>(loadModelsConfig());
            boolean found = false;
            for (Map<String, Object> m : models) {
                Object id = m.get("modelId");
                if (id != null && modelId.equals(String.valueOf(id))) {
                    m.put("alias", alias);
                    found = true;
                    break;
                }
            }
            // 如果未找到，追加一个最小记录以保留别名
            if (!found) {
                Map<String, Object> minimal = new HashMap<>();
                minimal.put("modelId", modelId);
                minimal.put("alias", alias);
                models.add(minimal);
            }
            try (FileWriter writer = new FileWriter(MODELS_CONFIG_FILE)) {
                gson.toJson(models, writer);
                return true;
            }
        } catch (IOException e) {
            System.err.println("保存模型别名失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 加载别名映射
     */
    public Map<String, String> loadAliasMap() {
        Map<String, String> aliases = new HashMap<>();
        List<Map<String, Object>> models = loadModelsConfig();
        for (Map<String, Object> m : models) {
            Object id = m.get("modelId");
            Object alias = m.get("alias");
            if (id != null && alias != null) {
                aliases.put(String.valueOf(id), String.valueOf(alias));
            }
        }
        return aliases;
    }

    public boolean saveModelFavourite(String modelId, boolean favourite) {
        try {
            List<Map<String, Object>> models = new java.util.ArrayList<>(loadModelsConfig());
            boolean found = false;
            for (Map<String, Object> m : models) {
                Object id = m.get("modelId");
                if (id != null && modelId.equals(String.valueOf(id))) {
                    m.put("favourite", favourite);
                    found = true;
                    break;
                }
            }
            if (!found) {
                Map<String, Object> minimal = new HashMap<>();
                minimal.put("modelId", modelId);
                minimal.put("favourite", favourite);
                models.add(minimal);
            }
            try (FileWriter writer = new FileWriter(MODELS_CONFIG_FILE)) {
                gson.toJson(models, writer);
                return true;
            }
        } catch (IOException e) {
            System.err.println("保存模型喜好失败: " + e.getMessage());
            return false;
        }
    }

    public Map<String, Boolean> loadFavouriteMap() {
        Map<String, Boolean> favourites = new HashMap<>();
        List<Map<String, Object>> models = loadModelsConfig();
        for (Map<String, Object> m : models) {
            Object id = m.get("modelId");
            Object fav = m.get("favourite");
            if (id == null || fav == null) continue;
            boolean v;
            if (fav instanceof Boolean) {
                v = (Boolean) fav;
            } else {
                v = Boolean.parseBoolean(String.valueOf(fav));
            }
            favourites.put(String.valueOf(id), v);
        }
        return favourites;
    }
}
