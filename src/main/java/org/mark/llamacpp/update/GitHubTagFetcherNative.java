package org.mark.llamacpp.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitHubTagFetcherNative {

	public static void main(String[] args) {
        String apiUrl = "https://api.github.com/repos/IIIIIllllIIIIIlllll/LlamacppServer/releases?per_page=1";

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .GET()
                    .build();

            // 发送请求
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String responseBody = response.body();
                System.err.println(responseBody);
            } else {
                System.out.println("Error: " + response.statusCode());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
