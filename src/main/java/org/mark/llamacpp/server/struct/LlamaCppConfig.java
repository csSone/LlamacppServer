package org.mark.llamacpp.server.struct;

import java.util.ArrayList;
import java.util.List;




public class LlamaCppConfig {
	
	
	private List<String> paths = new ArrayList<>();
	
	
	public LlamaCppConfig() {
		
	}
	
	
	public List<String> getPaths(){
		return this.paths;
	}
	
	
	public void setPaths(List<String> paths) {
		this.paths = paths;
	}
}
