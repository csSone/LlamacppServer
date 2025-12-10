package org.mark.llamacpp.server.struct;

/**
 * 停止模型请求参数
 */
public class StopModelRequest {
    /**
     * 模型ID（必需）
     */
    private String modelId;
    
    public StopModelRequest() {
    }
    
    public StopModelRequest(String modelId) {
        this.modelId = modelId;
    }
    
    public String getModelId() {
        return modelId;
    }
    
    public void setModelId(String modelId) {
        this.modelId = modelId;
    }
    
    @Override
    public String toString() {
        return "StopModelRequest{" +
                "modelId='" + modelId + '\'' +
                '}';
    }
}