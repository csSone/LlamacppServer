package org.mark.file.server;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;

import org.mark.llamacpp.download.BasicDownloader;
import org.mark.llamacpp.gguf.GGUFBundle;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.mcp.McpClientService;
import org.mark.llamacpp.server.mcp.McpSseClient;
import org.mark.llamacpp.server.tools.VramEstimator;
import org.mark.llamacpp.server.tools.VramEstimator.Estimate;
import org.mark.llamacpp.server.tools.VramEstimator.KvCacheType;
import org.mark.llamacpp.server.tools.struct.VramEstimation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class LlamaServerTest {
	
	private static Gson gson = new Gson();

	public static void main(String[] args) throws Exception {

	}

}
