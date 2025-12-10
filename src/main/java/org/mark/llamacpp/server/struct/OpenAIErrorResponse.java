package org.mark.llamacpp.server.struct;

/**
 * OpenAI格式的错误响应
 */
public class OpenAIErrorResponse {
    /**
     * 错误信息
     */
    private ErrorInfo error;
    
    public OpenAIErrorResponse() {
    }
    
    public OpenAIErrorResponse(ErrorInfo error) {
        this.error = error;
    }
    
    public static OpenAIErrorResponse error(String message, String type, int code) {
        return new OpenAIErrorResponse(new ErrorInfo(message, type, code));
    }
    
    public ErrorInfo getError() {
        return error;
    }
    
    public void setError(ErrorInfo error) {
        this.error = error;
    }
    
    /**
     * 错误信息内部类
     */
    public static class ErrorInfo {
        /**
         * 错误消息
         */
        private String message;
        
        /**
         * 错误类型
         */
        private String type;
        
        /**
         * 错误代码
         */
        private int code;
        
        public ErrorInfo() {
        }
        
        public ErrorInfo(String message, String type, int code) {
            this.message = message;
            this.type = type;
            this.code = code;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public int getCode() {
            return code;
        }
        
        public void setCode(int code) {
            this.code = code;
        }
        
        @Override
        public String toString() {
            return "ErrorInfo{" +
                    "message='" + message + '\'' +
                    ", type='" + type + '\'' +
                    ", code=" + code +
                    '}';
        }
    }
    
    @Override
    public String toString() {
        return "OpenAIErrorResponse{" +
                "error=" + error +
                '}';
    }
}