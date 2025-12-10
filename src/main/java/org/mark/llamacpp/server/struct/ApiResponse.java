package org.mark.llamacpp.server.struct;

/**
 * API响应基础类
 */
public class ApiResponse {
    /**
     * 请求是否成功
     */
    private boolean success;
    
    /**
     * 错误信息（可选）
     */
    private String error;
    
    /**
     * 响应数据（可选）
     */
    private Object data;
    
    public ApiResponse() {
    }
    
    public ApiResponse(boolean success) {
        this.success = success;
    }
    
    public ApiResponse(boolean success, String error) {
        this.success = success;
        this.error = error;
    }
    
    public ApiResponse(boolean success, Object data) {
        this.success = success;
        this.data = data;
    }
    
    public static ApiResponse success() {
        return new ApiResponse(true);
    }
    
    public static ApiResponse success(Object data) {
        return new ApiResponse(true, data);
    }
    
    public static ApiResponse error(String message) {
        return new ApiResponse(false, message);
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public String getError() {
        return error;
    }
    
    public void setError(String error) {
        this.error = error;
    }
    
    public Object getData() {
        return data;
    }
    
    public void setData(Object data) {
        this.data = data;
    }
    
    @Override
    public String toString() {
        return "ApiResponse{" +
                "success=" + success +
                ", error='" + error + '\'' +
                ", data=" + data +
                '}';
    }
}