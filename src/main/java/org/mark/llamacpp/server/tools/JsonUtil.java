package org.mark.llamacpp.server.tools;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class JsonUtil {
	
	
	private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	
	
	public static String toJson(Object obj) {
		return gson.toJson(obj);
	}
	
	
	public static <T> T fromJson(String json, Class<T> type) {
		return gson.fromJson(json, type);
	}
	
	
	public static <T> T fromJson(String json, Type type) {
		return gson.fromJson(json, type);
	}
	
	public static <T> T fromJson(JsonElement json, Class<T> type) {
		return gson.fromJson(json, type);
	}
	
	public static <T> T fromJson(JsonElement json, Type type) {
		return gson.fromJson(json, type);
	}
	
	
	public static String getJsonString(JsonObject o, String key, String fallback) {
		if (o == null || key == null || !o.has(key) || o.get(key) == null || o.get(key).isJsonNull())
			return fallback;
		try {
			return o.get(key).getAsString();
		} catch (Exception e) {
			return fallback;
		}
	}

	public static Integer getJsonInt(JsonObject o, String key, Integer fallback) {
		if (o == null || key == null || !o.has(key) || o.get(key) == null || o.get(key).isJsonNull())
			return fallback;
		try {
			return o.get(key).getAsInt();
		} catch (Exception e) {
			try {
				String s = o.get(key).getAsString();
				return parseInteger(s);
			} catch (Exception e2) {
				return fallback;
			}
		}
	}

	public static List<String> getJsonStringList(JsonElement el) {
		if (el == null || el.isJsonNull())
			return null;
		try {
			if (el.isJsonArray()) {
				JsonArray arr = el.getAsJsonArray();
				List<String> out = new ArrayList<>();
				for (int i = 0; i < arr.size(); i++) {
					JsonElement it = arr.get(i);
					if (it == null || it.isJsonNull())
						continue;
					String s = null;
					try {
						s = it.getAsString();
					} catch (Exception e) {
						s = jsonValueToString(it);
					}
					if (s != null && !s.trim().isEmpty())
						out.add(s.trim());
				}
				return out;
			}
			String s = el.getAsString();
			if (s == null || s.trim().isEmpty())
				return null;
			return Arrays.asList(s.trim());
		} catch (Exception e) {
			return null;
		}
	}

	public static String jsonValueToString(JsonElement el) {
		if (el == null || el.isJsonNull())
			return "";
		try {
			if (el.isJsonArray()) {
				return el.toString();
			}
			if (el.isJsonObject()) {
				return el.toString();
			}
			return el.getAsString();
		} catch (Exception e) {
			try {
				return el.toString();
			} catch (Exception e2) {
				return "";
			}
		}
	}
	
	
	private static Integer parseInteger(String s) {
		if (s == null)
			return null;
		String t = s.trim();
		if (t.isEmpty())
			return null;
		try {
			return Integer.valueOf(Integer.parseInt(t, 10));
		} catch (Exception e) {
			return null;
		}
	}
}
