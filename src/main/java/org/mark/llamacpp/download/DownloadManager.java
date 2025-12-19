package org.mark.llamacpp.download;



/**
 * 	下载管理器。
 */
public class DownloadManager {
	
	
	
	
	private static final DownloadManager INSTANCE = new DownloadManager();
	
	
	public static DownloadManager getInstance() {
		return INSTANCE;
	}
	
	
	
	private DownloadManager() {
		
	}
	
	
	/**
	 * 	创建下载任务
	 * @param url
	 * @param path
	 */
	public void createTask(String url, String path) {
		
	}
	
	
	/**
	 * 	指定文件名
	 * @param url
	 * @param path
	 * @param fileName
	 */
	public void createTask(String url, String path, String fileName) {
	
	}
	
	/**
	 * 	暂停指定任务
	 * @param taskId
	 */
	public void pause(String taskId) {
		
	}
	
	/**
	 * 	删除指定任务
	 * @param taskId
	 */
	public void delete(String taskId) {
		
		
	}
}
