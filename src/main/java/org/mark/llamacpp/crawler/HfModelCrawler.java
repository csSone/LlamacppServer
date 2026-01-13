package org.mark.llamacpp.crawler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class HfModelCrawler {
  private static final String HF_BASE = "https://huggingface.co";
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  public static void main(String[] args) throws Exception {
    CliArgs cli = CliArgs.parse(args);
    if (cli.help || cli.target == null || cli.target.isBlank()) {
      printHelp();
      System.exit(cli.help ? 0 : 2);
      return;
    }

    String repoId = normalizeToRepoId(cli.target);
    if (repoId == null) {
      System.err.println("无法从输入解析出 repoId： " + cli.target);
      System.exit(2);
      return;
    }

    JsonObject model = fetchModelInfo(repoId, cli.timeoutSeconds);
    if (cli.rawJson) {
      String pretty = GSON.toJson(model);
      if (cli.outPath != null) {
        writeUtf8(cli.outPath, pretty);
      } else {
        System.out.println(pretty);
      }
      return;
    }

    String summary = renderSummary(repoId, model);
    if (cli.outPath != null) {
      writeUtf8(cli.outPath, summary);
    } else {
      System.out.println(summary);
    }
  }

  private static void printHelp() {
    System.out.println("用法：");
    System.out.println("  java -jar hf-model-crawler.jar <model_url_or_repoId> [--raw] [--out <path>] [--timeout <seconds>]");
    System.out.println();
    System.out.println("示例：");
    System.out.println("  java -jar target/hf-model-crawler-0.0.1.jar https://huggingface.co/cerebras/GLM-4.7-REAP-218B-A32B");
    System.out.println("  java -jar target/hf-model-crawler-0.0.1.jar cerebras/GLM-4.7-REAP-218B-A32B --raw --out model.json");
    System.out.println();
    System.out.println("说明：");
    System.out.println("  - 数据来源： https://huggingface.co/api/models/{repoId}");
    System.out.println("  - 若设置环境变量 HF_TOKEN，将自动带上 Authorization 以访问受限模型（不会打印 token）。");
  }

  private static JsonObject fetchModelInfo(String repoId, int timeoutSeconds) throws IOException, InterruptedException {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    URI uri = URI.create(HF_BASE + "/api/models/" + repoId);
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
        .uri(uri)
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("User-Agent", "hf-model-crawler/0.0.1 (+https://huggingface.co)")
        .header("Accept", "application/json");

    String token = System.getenv("HF_TOKEN");
    if (token != null && !token.isBlank()) {
      requestBuilder.header("Authorization", "Bearer " + token.trim());
    }

    HttpResponse<String> response = client.send(requestBuilder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String bodyPreview = response.body() == null ? "" : response.body();
      if (bodyPreview.length() > 800) bodyPreview = bodyPreview.substring(0, 800) + "...";
      throw new IOException("请求失败: HTTP " + response.statusCode() + " " + uri + "\n" + bodyPreview);
    }

    JsonElement root = JsonParser.parseString(response.body());
    if (!root.isJsonObject()) throw new IOException("响应不是 JSON 对象: " + uri);
    return root.getAsJsonObject();
  }

  private static String normalizeToRepoId(String input) {
    String trimmed = input.trim();

    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      try {
        URI uri = URI.create(trimmed);
        if (uri.getHost() == null) return null;
        if (!uri.getHost().toLowerCase(Locale.ROOT).endsWith("huggingface.co")) return null;
        String path = Optional.ofNullable(uri.getPath()).orElse("");
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isBlank()) return null;
        String[] segments = path.split("/");
        if (segments.length < 2) return null;
        String owner = segments[0];
        String name = segments[1];
        if (owner.isBlank() || name.isBlank()) return null;
        return owner + "/" + name;
      } catch (Exception e) {
        return null;
      }
    }

    String noPrefix = trimmed;
    while (noPrefix.startsWith("/")) noPrefix = noPrefix.substring(1);
    if (noPrefix.contains("?")) noPrefix = noPrefix.substring(0, noPrefix.indexOf('?'));
    if (noPrefix.contains("#")) noPrefix = noPrefix.substring(0, noPrefix.indexOf('#'));
    if (!noPrefix.contains("/")) return null;
    String[] parts = noPrefix.split("/");
    if (parts.length < 2) return null;
    String owner = parts[0];
    String name = parts[1];
    if (owner.isBlank() || name.isBlank()) return null;
    return owner + "/" + name;
  }

  private static String renderSummary(String repoId, JsonObject model) {
    StringBuilder sb = new StringBuilder();
    sb.append("repoId: ").append(repoId).append("\n");
    sb.append("api: ").append(HF_BASE).append("/api/models/").append(repoId).append("\n");

    appendLine(sb, "sha", getString(model, "sha"));
    appendLine(sb, "private", getBooleanString(model, "private"));
    appendLine(sb, "gated", getAnyBooleanLike(model, List.of("gated", "gatedModel")));
    appendLine(sb, "disabled", getBooleanString(model, "disabled"));
    appendLine(sb, "pipeline_tag", getString(model, "pipeline_tag"));
    appendLine(sb, "library_name", getString(model, "library_name"));
    appendLine(sb, "license", extractLicense(model));
    appendLine(sb, "likes", getNumberString(model, "likes"));
    appendLine(sb, "downloads", getNumberString(model, "downloads"));
    appendLine(sb, "createdAt", getString(model, "createdAt"));
    appendLine(sb, "lastModified", getString(model, "lastModified"));

    List<String> tags = getStringList(model, "tags");
    if (!tags.isEmpty()) {
      sb.append("tags: ").append(String.join(", ", tags)).append("\n");
    }

    JsonObject cardData = getObject(model, "cardData");
    if (cardData != null) {
      String language = extractCardField(cardData, "language");
      String datasets = extractCardField(cardData, "datasets");
      String baseModel = extractCardField(cardData, "base_model");
      appendLine(sb, "card.language", language);
      appendLine(sb, "card.datasets", datasets);
      appendLine(sb, "card.base_model", baseModel);
    }

    List<String> files = extractSiblings(model);
    if (!files.isEmpty()) {
      sb.append("files(top20): ").append(String.join(", ", files.subList(0, Math.min(20, files.size())))).append("\n");
    }

    return sb.toString();
  }

  private static String extractLicense(JsonObject model) {
    JsonObject cardData = getObject(model, "cardData");
    if (cardData != null) {
      String license = extractCardField(cardData, "license");
      if (license != null && !license.isBlank()) return license;
    }
    String licenseFromRoot = getString(model, "license");
    if (licenseFromRoot != null && !licenseFromRoot.isBlank()) return licenseFromRoot;
    return null;
  }

  private static String extractCardField(JsonObject cardData, String key) {
    JsonElement el = cardData.get(key);
    if (el == null || el.isJsonNull()) return null;
    if (el.isJsonPrimitive()) return el.getAsString();
    if (el.isJsonArray()) {
      List<String> items = new ArrayList<>();
      for (JsonElement e : el.getAsJsonArray()) {
        if (e != null && !e.isJsonNull() && e.isJsonPrimitive()) items.add(e.getAsString());
      }
      return items.isEmpty() ? null : String.join(", ", items);
    }
    return GSON.toJson(el);
  }

  private static List<String> extractSiblings(JsonObject model) {
    JsonArray siblings = getArray(model, "siblings");
    if (siblings == null) return List.of();
    List<String> files = new ArrayList<>();
    for (JsonElement el : siblings) {
      if (el != null && el.isJsonObject()) {
        String rfilename = getString(el.getAsJsonObject(), "rfilename");
        if (rfilename != null && !rfilename.isBlank()) files.add(rfilename);
      }
    }
    return files;
  }

  private static void writeUtf8(Path path, String content) throws IOException {
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) Files.createDirectories(parent);
    Files.writeString(path.toAbsolutePath(), content, StandardCharsets.UTF_8);
  }

  private static void appendLine(StringBuilder sb, String key, String value) {
    if (value == null || value.isBlank()) return;
    sb.append(key).append(": ").append(value).append("\n");
  }

  private static String getString(JsonObject obj, String key) {
    if (obj == null) return null;
    JsonElement el = obj.get(key);
    if (el == null || el.isJsonNull()) return null;
    if (!el.isJsonPrimitive()) return null;
    try {
      return el.getAsString();
    } catch (Exception e) {
      return null;
    }
  }

  private static String getNumberString(JsonObject obj, String key) {
    if (obj == null) return null;
    JsonElement el = obj.get(key);
    if (el == null || el.isJsonNull()) return null;
    if (!el.isJsonPrimitive()) return null;
    try {
      return Objects.toString(el.getAsNumber());
    } catch (Exception e) {
      return null;
    }
  }

  private static String getBooleanString(JsonObject obj, String key) {
    if (obj == null) return null;
    JsonElement el = obj.get(key);
    if (el == null || el.isJsonNull()) return null;
    if (!el.isJsonPrimitive()) return null;
    try {
      return String.valueOf(el.getAsBoolean());
    } catch (Exception e) {
      return null;
    }
  }

  private static String getAnyBooleanLike(JsonObject obj, List<String> keys) {
    for (String key : keys) {
      String v = getBooleanString(obj, key);
      if (v != null) return v;
    }
    return null;
  }

  private static JsonObject getObject(JsonObject obj, String key) {
    if (obj == null) return null;
    JsonElement el = obj.get(key);
    if (el == null || el.isJsonNull() || !el.isJsonObject()) return null;
    return el.getAsJsonObject();
  }

  private static JsonArray getArray(JsonObject obj, String key) {
    if (obj == null) return null;
    JsonElement el = obj.get(key);
    if (el == null || el.isJsonNull() || !el.isJsonArray()) return null;
    return el.getAsJsonArray();
  }

  private static List<String> getStringList(JsonObject obj, String key) {
    JsonArray arr = getArray(obj, key);
    if (arr == null) return List.of();
    List<String> out = new ArrayList<>();
    for (JsonElement el : arr) {
      if (el != null && !el.isJsonNull() && el.isJsonPrimitive()) {
        try {
          out.add(el.getAsString());
        } catch (Exception ignored) {
        }
      }
    }
    return out;
  }

  private record CliArgs(String target, boolean rawJson, Path outPath, int timeoutSeconds, boolean help) {
    static CliArgs parse(String[] args) {
      if (args == null || args.length == 0) return new CliArgs(null, false, null, 20, false);

      String target = null;
      boolean raw = false;
      Path out = null;
      int timeout = 20;
      boolean help = false;

      List<String> tokens = List.of(args);
      for (int i = 0; i < tokens.size(); i++) {
        String t = tokens.get(i);
        if (t == null) continue;
        if (t.equals("--help") || t.equals("-h")) {
          help = true;
          continue;
        }
        if (t.equals("--raw")) {
          raw = true;
          continue;
        }
        if (t.equals("--out")) {
          if (i + 1 < tokens.size()) {
            out = Path.of(tokens.get(i + 1));
            i++;
          }
          continue;
        }
        if (t.equals("--timeout")) {
          if (i + 1 < tokens.size()) {
            try {
              timeout = Integer.parseInt(tokens.get(i + 1));
            } catch (Exception ignored) {
            }
            i++;
          }
          continue;
        }

        if (t.startsWith("--")) continue;
        if (target == null) target = t;
      }

      return new CliArgs(target, raw, out, Math.max(1, timeout), help);
    }
  }
}
