package org.mark.llamacpp.server.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.struct.CharactorDataStruct;

import com.google.gson.Gson;

/**
 * 	用来搞本地RP的。
 */
public class CompletionService {
	
	private static final Gson gson = new Gson();
	private static final long MAX_CHAT_UPLOAD_BYTES = 16L * 1024L * 1024L;
	
	public CompletionService() {
		
	}
	
	private Path getCompletionsDir() {
		try {
			Path dir = LlamaServer.getCachePath().resolve("charactors");
			if (!Files.exists(dir)) {
				Files.createDirectories(dir);
			}
			return dir;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private Path getLegacyCompletionsDirIfExists() {
		try {
			Path dir = LlamaServer.getCachePath().resolve("completions");
			if (Files.exists(dir) && Files.isDirectory(dir)) {
				return dir;
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}
	
	private Path fileOfId(long id) {
		return this.getCompletionsDir().resolve(Long.toString(id) + ".json");
	}

	private Path getChatDir() {
		try {
			Path dir = LlamaServer.getCachePath().resolve("chat");
			if (!Files.exists(dir)) {
				Files.createDirectories(dir);
			}
			return dir;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static boolean isDigitsOnly(String s) {
		if (s == null || s.isEmpty()) return false;
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch < '0' || ch > '9') return false;
		}
		return true;
	}

	public synchronized String saveChatFile(byte[] bytes) {
		if (bytes == null) {
			throw new IllegalArgumentException("文件内容为空");
		}
		if (bytes.length > MAX_CHAT_UPLOAD_BYTES) {
			throw new IllegalArgumentException("文件超过最大限制: 16MB");
		}
		Path dir = this.getChatDir();
		long ts = System.currentTimeMillis();
		Path out = dir.resolve(Long.toString(ts));
		while (Files.exists(out)) {
			ts++;
			out = dir.resolve(Long.toString(ts));
		}
		try {
			Files.write(out, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			return Long.toString(ts);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public Path getChatFilePath(String fileName) {
		if (!isDigitsOnly(fileName)) {
			return null;
		}
		Path dir = this.getChatDir();
		Path p = dir.resolve(fileName).normalize();
		if (!p.startsWith(dir)) {
			return null;
		}
		return p;
	}
	
	private static Long parseId(String name) {
		if (name == null) return null;
		String t = name.trim();
		if (t.isEmpty()) return null;
		try {
			return Long.parseLong(t);
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * 	
	 * @return
	 */
	public synchronized CharactorDataStruct createDefaultCharactor() {
		CharactorDataStruct charactorDataStruct = new CharactorDataStruct();
		
		long now = System.currentTimeMillis();
		charactorDataStruct.setId(now);
		charactorDataStruct.setCreatedAt(now);
		charactorDataStruct.setPrompt("");
		charactorDataStruct.setSystemPrompt("");
		charactorDataStruct.setApiModel(1);
		charactorDataStruct.setTitle("默认角色-" + now);
		charactorDataStruct.setUpdatedAt(now);
		// 写入本地磁盘
		this.saveCharactor(charactorDataStruct);
		return charactorDataStruct;
	}
	
	
	/**
	 * 	保存到本地文件。
	 * @param charactorDataStruct
	 */
	public void saveCharactor(CharactorDataStruct charactorDataStruct) {
		if (charactorDataStruct == null) return;
		long now = System.currentTimeMillis();
		if (charactorDataStruct.getId() <= 0) {
			charactorDataStruct.setId(now);
		}
		if (charactorDataStruct.getCreatedAt() <= 0) {
			charactorDataStruct.setCreatedAt(now);
		}
		charactorDataStruct.setUpdatedAt(now);
		if (charactorDataStruct.getTitle() == null || charactorDataStruct.getTitle().trim().isEmpty()) {
			charactorDataStruct.setTitle("默认角色-" + charactorDataStruct.getId());
		}
		try {
			Path file = this.fileOfId(charactorDataStruct.getId());
			byte[] bytes = gson.toJson(charactorDataStruct).getBytes(StandardCharsets.UTF_8);
			Files.write(file, bytes);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	
	public boolean deleteCharactor(String name) {
		Long id = parseId(name);
		if (id == null) return false;
		try {
			boolean ok = Files.deleteIfExists(this.fileOfId(id.longValue()));
			Path legacyDir = this.getLegacyCompletionsDirIfExists();
			if (legacyDir != null) {
				try {
					ok = Files.deleteIfExists(legacyDir.resolve(Long.toString(id.longValue()) + ".json")) || ok;
				} catch (Exception ignore) {
				}
			}
			return ok;
		} catch (Exception e) {
			return false;
		}
	}
	
	
	public CharactorDataStruct getCharactor(String name) {
		Long id = parseId(name);
		if (id == null) return null;
		try {
			Path file = this.fileOfId(id.longValue());
			if (Files.exists(file)) {
				String json = Files.readString(file, StandardCharsets.UTF_8);
				if (json == null || json.trim().isEmpty()) return null;
				return gson.fromJson(json, CharactorDataStruct.class);
			}
			Path legacyDir = this.getLegacyCompletionsDirIfExists();
			if (legacyDir == null) return null;
			Path legacyFile = legacyDir.resolve(Long.toString(id.longValue()) + ".json");
			if (!Files.exists(legacyFile)) return null;
			String json = Files.readString(legacyFile, StandardCharsets.UTF_8);
			if (json == null || json.trim().isEmpty()) return null;
			return gson.fromJson(json, CharactorDataStruct.class);
		} catch (Exception e) {
			return null;
		}
	}

	private void readCharactorsFromDir(Path dir, Map<Long, CharactorDataStruct> byId) {
		if (dir == null || byId == null) return;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
			for (Path p : ds) {
				try {
					String json = Files.readString(p, StandardCharsets.UTF_8);
					if (json == null || json.trim().isEmpty()) continue;
					CharactorDataStruct c = gson.fromJson(json, CharactorDataStruct.class);
					if (c == null || c.getId() <= 0) continue;
					CharactorDataStruct prev = byId.get(c.getId());
					if (prev == null || prev.getUpdatedAt() < c.getUpdatedAt()) {
						byId.put(c.getId(), c);
					}
				} catch (Exception ignore) {
				}
			}
		} catch (Exception ignore) {
		}
	}
	
	/**
	 * 	列出全部的角色
	 * @return
	 */
	public List<CharactorDataStruct> listCharactor() {
		Map<Long, CharactorDataStruct> byId = new HashMap<>();
		this.readCharactorsFromDir(this.getCompletionsDir(), byId);
		this.readCharactorsFromDir(this.getLegacyCompletionsDirIfExists(), byId);
		List<CharactorDataStruct> out = new ArrayList<>(byId.values());
		out.sort(Comparator.comparingLong(CharactorDataStruct::getUpdatedAt).reversed());
		return out;
	}
	
	
	/**
	 * 	查询指定角色的聊天记录
	 * @param charactorDataStruct
	 * @return
	 */
	public String queryCharactorLog(CharactorDataStruct charactorDataStruct) {
		
		
		return null;
	}
	
}
