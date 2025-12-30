package org.mark.llamacpp.server.exception;




/**
 * 	请求方式错误的异常。
 */
public class RequestMethodException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	
	public RequestMethodException(String message) {
		super(message);
	}
	
}
