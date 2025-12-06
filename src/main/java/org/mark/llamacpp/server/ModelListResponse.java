package org.mark.llamacpp.server;

import java.util.List;

/**
 * 模型列表响应
 */
public class ModelListResponse {
    /**
     * 请求是否成功
     */
    private boolean success;
    
    /**
     * 模型列表
     */
    private List<Object> models;
    
    /**
     * 是否为刷新后的数据（可选）
     */
    private Boolean refreshed;
    
    /**
     * 错误信息（可选）
     */
    private String error;
    
    public ModelListResponse() {
    }
    
    public ModelListResponse(boolean success, List<Object> models) {
        this.success = success;
        this.models = models;
    }
    
    public ModelListResponse(boolean success, List<Object> models, Boolean refreshed) {
        this.success = success;
        this.models = models;
        this.refreshed = refreshed;
    }
    
    public ModelListResponse(boolean success, String error) {
        this.success = success;
        this.error = error;
    }
    
    public static ModelListResponse success(List<Object> models) {
        return new ModelListResponse(true, models);
    }
    
    public static ModelListResponse success(List<Object> models, Boolean refreshed) {
        return new ModelListResponse(true, models, refreshed);
    }
    
    public static ModelListResponse error(String message) {
        return new ModelListResponse(false, message);
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public List<Object> getModels() {
        return models;
    }
    
    public void setModels(List<Object> models) {
        this.models = models;
    }
    
    public Boolean getRefreshed() {
        return refreshed;
    }
    
    public void setRefreshed(Boolean refreshed) {
        this.refreshed = refreshed;
    }
    
    public String getError() {
        return error;
    }
    
    public void setError(String error) {
        this.error = error;
    }
    
    @Override
    public String toString() {
        return "ModelListResponse{" +
                "success=" + success +
                ", models=" + models +
                ", refreshed=" + refreshed +
                ", error='" + error + '\'' +
                '}';
    }
}