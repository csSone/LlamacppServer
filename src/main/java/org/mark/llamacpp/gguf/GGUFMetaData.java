package org.mark.llamacpp.gguf;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 高度优化版本的GGUF元数据读取器，只读取和缓存必要字段，大幅减少内存占用
 * 专为内存受限的Web应用设计（128MB JVM）
 */
public class GGUFMetaData {
    // --- 基本元数据，始终加载 ---
    private final String magic;
    private final int version;
    private final long tensorCount;
    private final long kvCount;
    private final String fileName;
    
    // --- 文件路径，用于延迟加载 ---
    private final String filePath;
    
    // --- 关键字段的轻量级缓存，只存储实际需要的字段 ---
    private volatile String type;
    private volatile String architecture;
    private volatile String name;
    private volatile Integer fileType;
    private volatile Integer splitNo;
    private volatile Integer contextLength;
    private volatile Integer embeddingLength;
    private volatile Integer nLayer;
    private volatile Integer nHead;
    private volatile Integer nKvHead;
    private volatile boolean keyFieldsLoaded = false;
    private final Object keyFieldsLock = new Object();
    
    // --- 全局轻量级缓存，只缓存基本信息 ---
    private static final Map<String, GGUFMetaData> cache = new ConcurrentHashMap<>();
    
    // --- 最大缓存数量限制，防止内存溢出 ---
    private static final int MAX_CACHE_SIZE = 10;
    
    // --- 私有构造函数，由静态工厂方法调用 ---
    private GGUFMetaData(String magic, int version, long tensorCount, long kvCount, String fileName, String filePath) {
        this.magic = magic;
        this.version = version;
        this.tensorCount = tensorCount;
        this.kvCount = kvCount;
        this.fileName = fileName;
        this.filePath = filePath;
    }
    
    // --- 静态工厂方法，从文件路径读取 ---
    public static GGUFMetaData readFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            System.err.println("File path cannot be null or empty.");
            return null;
        }
        
        // 检查缓存
        GGUFMetaData cached = cache.get(filePath);
        if (cached != null) {
            return cached;
        }
        
        return readFile(new File(filePath));
    }
    
    // --- 静态工厂方法，从 File 对象读取 ---
    public static GGUFMetaData readFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            System.err.println("Invalid file: " + file);
            return null;
        }
        
        String filePath = file.getAbsolutePath();
        
        // 检查缓存
        GGUFMetaData cached = cache.get(filePath);
        if (cached != null) {
            return cached;
        }
        
        try (FileInputStream fis = new FileInputStream(file);
             DataInputStream dis = new DataInputStream(fis)) {
            
            // 1. 读取并验证 Magic Number (4 bytes)
            byte[] magicBytes = new byte[4];
            dis.readFully(magicBytes);
            String magic = new String(magicBytes, StandardCharsets.US_ASCII);
            if (!"GGUF".equals(magic)) {
                System.err.println("Not a valid GGUF file. Magic: " + magic);
                return null;
            }
            
            // 2. 读取版本号 (uint32, 4 bytes, little-endian)
            int version = readUInt32(dis);
            
            // 3. 读取张量数量 (uint64, 8 bytes, little-endian)
            long tensorCount = readUInt64(dis);
            
            // 4. 读取键值对数量 (uint64, 8 bytes, little-endian)
            long kvCount = readUInt64(dis);
            
            // 创建GGUFMetaData实例，但不加载所有元数据
            GGUFMetaData metaData = new GGUFMetaData(magic, version, tensorCount, kvCount, file.getName(), filePath);
            
            // 添加到缓存，限制缓存大小
            if (cache.size() >= MAX_CACHE_SIZE) {
                // 简单的LRU：清除第一个缓存项
                String firstKey = cache.keySet().iterator().next();
                cache.remove(firstKey);
            }
            cache.put(filePath, metaData);
            
            return metaData;
        } catch (EOFException e) {
            System.err.println("Reached end of file unexpectedly while parsing header.");
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("Failed to read or parse the GGUF file.");
            e.printStackTrace();
        }
        return null; // 如果发生任何错误，返回 null
    }
    
    /**
     * 延迟加载关键字段，只读取实际需要的字段，大幅减少内存占用
     */
    private void loadKeyFields() {
        if (keyFieldsLoaded) {
            return;
        }
        
        synchronized (keyFieldsLock) {
            if (keyFieldsLoaded) {
                return;
            }
            
            try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
                // 跳过文件头的基本信息
                raf.seek(4 + 4 + 8 + 8); // magic + version + tensorCount + kvCount
                
                // 只读取我们关心的关键字段
                for (long i = 0; i < kvCount; i++) {
                    // 读取 Key
                    long keyLength = readUInt64(raf);
                    String key = readString(raf, (int) keyLength);
                    
                    // 读取 Value Type
                    int valueTypeId = readUInt32(raf);
                    GgufType valueType = GgufType.fromId(valueTypeId);
                    
                    // 只读取我们关心的字段，其他字段直接跳过
                    if ("general.type".equals(key)) {
                        Object value = readValue(raf, valueType);
                        type = value instanceof String ? (String) value : null;
                    } else if ("general.architecture".equals(key)) {
                        Object value = readValue(raf, valueType);
                        architecture = value instanceof String ? (String) value : null;
                    } else if ("general.name".equals(key)) {
                        Object value = readValue(raf, valueType);
                        name = value instanceof String ? (String) value : null;
                    } else if ("general.file_type".equals(key)) {
                        Object value = readValue(raf, valueType);
                        fileType = value instanceof Integer ? (Integer) value :
                                  (value instanceof Long ? ((Long) value).intValue() : null);
                    } else if ("split.no".equals(key)) {
                        Object value = readValue(raf, valueType);
                        splitNo = value instanceof Integer ? (Integer) value :
                                 (value instanceof Long ? ((Long) value).intValue() : null);
                    } else if (key != null) {
                        boolean handled = false;
                        if (key.endsWith(".context_length") || (architecture != null && key.equals(architecture + ".context_length"))) {
                            Object value = readValue(raf, valueType);
                            contextLength = value instanceof Integer ? (Integer) value :
                                           (value instanceof Long ? ((Long) value).intValue() : null);
                            handled = true;
                        }
                        if (!handled && (key.endsWith(".embedding_length") || (architecture != null && key.equals(architecture + ".embedding_length")))) {
                            Object value = readValue(raf, valueType);
                            embeddingLength = value instanceof Integer ? (Integer) value :
                                             (value instanceof Long ? ((Long) value).intValue() : null);
                            handled = true;
                        }
                        if (!handled && (key.endsWith(".n_layer") || key.endsWith(".block_count") || (architecture != null && (key.equals(architecture + ".n_layer") || key.equals(architecture + ".block_count"))))) {
                            Object value = readValue(raf, valueType);
                            nLayer = value instanceof Integer ? (Integer) value :
                                     (value instanceof Long ? ((Long) value).intValue() : null);
                            handled = true;
                        }
                        if (!handled && (key.endsWith(".n_head") || (architecture != null && key.equals(architecture + ".n_head")))) {
                            Object value = readValue(raf, valueType);
                            nHead = value instanceof Integer ? (Integer) value :
                                    (value instanceof Long ? ((Long) value).intValue() : null);
                            handled = true;
                        }
                        if (!handled && (key.endsWith(".n_kv_head") || key.endsWith(".n_head_kv") || (architecture != null && (key.equals(architecture + ".n_kv_head") || key.equals(architecture + ".n_head_kv"))))) {
                            Object value = readValue(raf, valueType);
                            nKvHead = value instanceof Integer ? (Integer) value :
                                      (value instanceof Long ? ((Long) value).intValue() : null);
                            handled = true;
                        }
                        if (!handled) {
                            skipValue(raf, valueType);
                        }
                    } else {
                        // 跳过我们不关心的字段，节省内存
                        skipValue(raf, valueType);
                    }
                }
                
                keyFieldsLoaded = true;
            } catch (IOException e) {
                System.err.println("Failed to load key fields from GGUF file: " + filePath);
                e.printStackTrace();
            }
        }
    }
    
    /**
     * 跳过不需要的值
     */
    private static void skipValue(RandomAccessFile raf, GgufType type) throws IOException {
        switch (type) {
            case UINT8:  raf.skipBytes(1); break;
            case INT8:   raf.skipBytes(1); break;
            case UINT16: raf.skipBytes(2); break;
            case INT16:  raf.skipBytes(2); break;
            case UINT32: raf.skipBytes(4); break;
            case INT32:  raf.skipBytes(4); break;
            case UINT64: raf.skipBytes(8); break;
            case INT64:  raf.skipBytes(8); break;
            case FLOAT32: raf.skipBytes(4); break;
            case FLOAT64: raf.skipBytes(8); break;
            case BOOL:   raf.skipBytes(1); break;
            case STRING:
                long strLen = readUInt64(raf);
                raf.skipBytes((int) strLen);
                break;
            case ARRAY:
                GgufType elemType = GgufType.fromId(readUInt32(raf));
                long elemCount = readUInt64(raf);
                for (int i = 0; i < elemCount; i++) {
                    skipValue(raf, elemType);
                }
                break;
            default:
                System.err.println("Cannot skip unknown type: " + type);
                break;
        }
    }
    
    // --- Getter 方法，用于访问元数据 ---
    public String getMagic() {
        return magic;
    }
    
    public int getVersion() {
        return version;
    }
    
    public long getTensorCount() {
        return tensorCount;
    }
    
    public long getKvCount() {
        return kvCount;
    }
    
    public String getFileName() {
        return fileName;
    }
    
    /**
     * 获取完整的元数据映射（已禁用以节省内存）。
     * @return null，因为完整元数据加载已被禁用
     */
    @Deprecated
    public Map<String, Object> getAllMetadata() {
        System.err.println("警告：getAllMetadata()已被禁用以节省内存。请使用特定的getter方法获取需要的字段。");
        return null;
    }
    
    /**
     * 根据键获取元数据值（只支持关键字段）。
     * @param key 元数据的键，例如 "general.architecture"。
     * @return 对应的值，如果键不存在则返回 null。
     */
    public Object getValue(String key) {
        // 只支持关键字段，以节省内存
        if ("general.type".equals(key)) {
            return getStringValue(key);
        } else if ("general.architecture".equals(key)) {
            return getStringValue(key);
        } else if ("general.name".equals(key)) {
            return getStringValue(key);
        } else if ("general.file_type".equals(key)) {
            return getIntValue(key);
        } else if ("split.no".equals(key)) {
            return getIntValue(key);
        } else if (architecture != null && key.startsWith(architecture + ".")) {
            return getIntValue(key);
        }
        
        // 不支持的字段返回null，避免加载完整元数据
        return null;
    }
    
    /**
     * 根据键获取字符串类型的元数据值（轻量级版本）。
     * @param key 元数据的键。
     * @return 对应的字符串值，如果键不存在或类型不匹配则返回 null。
     */
    public String getStringValue(String key) {
        // 确保关键字段已加载
        if (!keyFieldsLoaded) {
            loadKeyFields();
        }
        
        // 只返回关键字段，节省内存
        if ("general.type".equals(key)) {
            return type;
        } else if ("general.architecture".equals(key)) {
            return architecture;
        } else if ("general.name".equals(key)) {
            return name;
        }
        
        // 不支持的字段返回null，避免加载完整元数据
        return null;
    }
    
    /**
     * 根据键获取整数类型的元数据值（轻量级版本）。
     * @param key 元数据的键。
     * @return 对应的整数值，如果键不存在或类型不匹配则返回 null。
     */
    public Integer getIntValue(String key) {
        // 确保关键字段已加载
        if (!keyFieldsLoaded) {
            loadKeyFields();
        }
        
        // 只返回关键字段，节省内存
        if ("general.file_type".equals(key)) {
            return fileType;
        } else if ("split.no".equals(key)) {
            return splitNo;
        } else {
            if ((architecture != null && key.equals(architecture + ".context_length")) || key.endsWith(".context_length")) {
                return contextLength;
            }
            if ((architecture != null && key.equals(architecture + ".embedding_length")) || key.endsWith(".embedding_length")) {
                return embeddingLength;
            }
            if ((architecture != null && (key.equals(architecture + ".n_layer") || key.equals(architecture + ".block_count"))) || key.endsWith(".n_layer") || key.endsWith(".block_count")) {
                return nLayer;
            }
            if ((architecture != null && key.equals(architecture + ".n_head")) || key.endsWith(".n_head")) {
                return nHead;
            }
            if ((architecture != null && (key.equals(architecture + ".n_kv_head") || key.equals(architecture + ".n_head_kv"))) || key.endsWith(".n_kv_head") || key.endsWith(".n_head_kv")) {
                return nKvHead;
            }
        }
        
        // 不支持的字段返回null，避免加载完整元数据
        return null;
    }

    public Integer getNLayer() {
        if (!keyFieldsLoaded) { loadKeyFields(); }
        return nLayer;
    }
    public Integer getNHead() {
        if (!keyFieldsLoaded) { loadKeyFields(); }
        return nHead;
    }
    public Integer getNKvHead() {
        if (!keyFieldsLoaded) { loadKeyFields(); }
        return nKvHead;
    }
    public Integer getEmbeddingLength() {
        if (!keyFieldsLoaded) { loadKeyFields(); }
        return embeddingLength;
    }
    public Integer getContextLength() {
        if (!keyFieldsLoaded) { loadKeyFields(); }
        return contextLength;
    }
    
    /**
     * 清除所有缓存，释放内存
     */
    public static void clearCache() {
        cache.clear();
    }
    
    /**
     * 清除指定文件的缓存
     */
    public static void clearCache(String filePath) {
        cache.remove(filePath);
    }
    
    /**
     * 获取缓存大小
     */
    public static int getCacheSize() {
        return cache.size();
    }
    
    /**
     * 强制垃圾回收，释放内存
     */
    public static void forceGC() {
        clearCache();
        System.gc();
    }
    
    // --- 内部辅助方法和枚举 ---
    
    // GGUF Value Types (from gguf.h)
    private enum GgufType {
        UINT8(0), INT8(1), UINT16(2), INT16(3), UINT32(4), INT32(5),
        FLOAT32(6), BOOL(7), STRING(8), ARRAY(9), UINT64(10), INT64(11),
        FLOAT64(12), UNKNOWN(-1);
        private final int id;
        GgufType(int id) { this.id = id; }
        public static GgufType fromId(int id) {
            for (GgufType type : values()) { if (type.id == id) return type; }
            return UNKNOWN;
        }
    }
    
    private static Object readValue(DataInput in, GgufType type) throws IOException {
        switch (type) {
            case UINT8:  return in.readUnsignedByte();
            case INT8:   return in.readByte();
            case UINT16: return readUInt16(in);
            case INT16:  return in.readShort();
            case UINT32: return readUInt32(in);
            case INT32:  return in.readInt();
            case UINT64: return readUInt64(in);
            case INT64:  return in.readLong();
            case FLOAT32:return in.readFloat();
            case FLOAT64:return in.readDouble();
            case BOOL:   return in.readByte() != 0;
            case STRING:
                long strLen = readUInt64(in);
                return readString(in, (int) strLen);
            case ARRAY:
                GgufType elemType = GgufType.fromId(readUInt32(in));
                long elemCount = readUInt64(in);
                Object[] array = new Object[(int) elemCount];
                for (int i = 0; i < elemCount; i++) {
                    array[i] = readValue(in, elemType);
                }
                return array;
            default:
                System.err.println("Skipping unknown or unsupported type: " + type);
                return null;
        }
    }
    
    private static String readString(DataInput in, int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    private static int readUInt16(DataInput in) throws IOException {
        return Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
    }
    
    private static int readUInt32(DataInput in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }
    
    private static long readUInt64(DataInput in) throws IOException {
        return Long.reverseBytes(in.readLong());
    }
    
    // --- toString() 方法，用于方便地打印对象信息 ---
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("GGUFMetaData {\n");
        sb.append(String.format("  Magic: '%s',\n", magic));
        sb.append(String.format("  Version: %d,\n", version));
        sb.append(String.format("  Tensor Count: %d,\n", tensorCount));
        sb.append(String.format("  KV Count: %d,\n", kvCount));
        sb.append(String.format("  File Name: '%s',\n", fileName));
        
        // 只显示关键字段，避免加载所有元数据
        sb.append("  Key Fields: {\n");
        sb.append(String.format("    \"general.type\": \"%s\",\n", getStringValue("general.type")));
        sb.append(String.format("    \"general.architecture\": \"%s\",\n", getStringValue("general.architecture")));
        sb.append(String.format("    \"general.name\": \"%s\",\n", getStringValue("general.name")));
        sb.append(String.format("    \"general.file_type\": %s,\n", getIntValue("general.file_type")));
        sb.append(String.format("    \"split.no\": %s\n", getIntValue("split.no")));
        sb.append("  }\n");
        sb.append("}");
        return sb.toString();
    }
    
    // --- Main 方法，用于演示如何使用这个类 ---
    public static void main(String[] args) {
        // 请将这里的路径替换为您的实际 GGUF 文件路径
        String ggufFilePath = "/home/mark/Models/Q8/Qwen3-VL-8B-Q8_0/Qwen3-VL-8B-Instruct-UD-Q8_K_XL.gguf";
        
        System.out.println("Reading GGUF file: " + ggufFilePath);
        GGUFMetaData metaData = GGUFMetaData.readFile(ggufFilePath);
        if (metaData != null) {
            System.out.println("\n--- Successfully Parsed ---");
            
            // 打印基本信息
            System.out.println(metaData.toString());
            
            // 或者，使用 getter 方法获取特定信息
            System.out.println("\n--- Accessing Specific Values ---");
            System.out.println("Model Type: " + metaData.getStringValue("general.type"));
            System.out.println("Architecture: " + metaData.getStringValue("general.architecture"));
            System.out.println("Model Name: " + metaData.getStringValue("general.name"));
            System.out.println("File Type: " + metaData.getIntValue("general.file_type"));
            System.out.println("Split No: " + metaData.getIntValue("split.no"));
            
            String architecture = metaData.getStringValue("general.architecture");
            if (architecture != null) {
                System.out.println("Context Length: " + metaData.getIntValue(architecture + ".context_length"));
                System.out.println("Embedding Length: " + metaData.getIntValue(architecture + ".embedding_length"));
            }
            
            // 测试缓存
            System.out.println("\n--- Testing Cache ---");
            GGUFMetaData cached = GGUFMetaData.readFile(ggufFilePath);
            System.out.println("Same instance? " + (metaData == cached));
            System.out.println("Cache size: " + GGUFMetaData.getCacheSize());
            
            // 测试内存优化
            System.out.println("\n--- Testing Memory Optimization ---");
            Runtime runtime = Runtime.getRuntime();
            long beforeMemory = runtime.totalMemory() - runtime.freeMemory();
            System.out.println("Memory before loading multiple files: " + (beforeMemory / 1024 / 1024) + " MB");
            
            // 模拟加载多个文件（使用相同文件来测试缓存效果）
            for (int i = 0; i < 5; i++) {
                GGUFMetaData.readFile(ggufFilePath);
            }
            
            long afterMemory = runtime.totalMemory() - runtime.freeMemory();
            System.out.println("Memory after loading multiple files: " + (afterMemory / 1024 / 1024) + " MB");
            System.out.println("Memory increase: " + ((afterMemory - beforeMemory) / 1024 / 1024) + " MB");
        } else {
            System.out.println("\n--- Failed to parse file. ---");
        }
    }
}
