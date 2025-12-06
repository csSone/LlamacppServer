package org.mark.llamacpp.server;

import java.util.List;

/**
 * OpenAI格式的模型列表响应
 */
public class OpenAIModelsResponse {
    /**
     * 对象类型，固定为"list"
     */
    private String object = "list";
    
    /**
     * 模型数据列表
     */
    private List<Object> data;
    
    public OpenAIModelsResponse() {
    }
    
    public OpenAIModelsResponse(List<Object> data) {
        this.data = data;
    }
    
    public String getObject() {
        return object;
    }
    
    public void setObject(String object) {
        this.object = object;
    }
    
    public List<Object> getData() {
        return data;
    }
    
    public void setData(List<Object> data) {
        this.data = data;
    }
    
    @Override
    public String toString() {
        return "OpenAIModelsResponse{" +
                "object='" + object + '\'' +
                ", data=" + data +
                '}';
    }
}