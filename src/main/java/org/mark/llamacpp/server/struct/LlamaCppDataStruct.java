package org.mark.llamacpp.server.struct;


/**
 * 	llamacpp的配置信息的结构。
 */
public class LlamaCppDataStruct {
	
	/**
	 * 	名称。
	 */
	private String name;
	
	/**
	 * 	路径
	 */
	private String path;
	
	/**
	 * 	描述。
	 */
	private String description;
	
	
	public LlamaCppDataStruct() {
		
	}
	
	
	public LlamaCppDataStruct(String name, String path, String description) {
		this.name = name;
		this.path = path;
		this.description = description;
	}

	
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}
}
