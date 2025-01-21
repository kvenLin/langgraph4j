package org.bsc.langgraph4j.webtest.visual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * GPT-4 Vision API 客户端
 */
@Slf4j
public class GPT4VisionClient {
    private static final String API_VERSION = "2024-01-01";  // 使用最新的API版本
    private static final MediaType JSON = MediaType.get("application/json");

    private final String apiKey;
    private final String apiBaseUrl;
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;

    public GPT4VisionClient(String apiKey, String apiBaseUrl) {
        this.apiKey = apiKey;
        this.apiBaseUrl = apiBaseUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)  // 连接超时
                .writeTimeout(30, TimeUnit.SECONDS)    // 写入超时
                .readTimeout(120, TimeUnit.SECONDS)    // 读取超时，GPT-4 Vision 可能需要较长时间
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 发送图像分析请求
     */
    public String analyzeImage(String prompt, Path imagePath) throws IOException {
                // 读取图片并转换为Base64
                byte[] imageBytes = Files.readAllBytes(imagePath);
                String base64Image = Base64.getEncoder().encodeToString(imageBytes);

                // 构建请求体
                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("model", "gpt-4-vision-preview");
                requestBody.put("max_tokens", 4096);
                requestBody.put("temperature", 0);

                ArrayNode messages = requestBody.putArray("messages");
                ObjectNode message = messages.addObject();
                message.put("role", "user");

                ArrayNode content = message.putArray("content");

                // 添加文本内容
                ObjectNode textContent = content.addObject();
                textContent.put("type", "text");
                textContent.put("text", prompt);

                // 添加图片内容
                ObjectNode imageContent = content.addObject();
                imageContent.put("type", "image_url");
                ObjectNode imageUrl = imageContent.putObject("image_url");
                imageUrl.put("url", "data:image/png;base64," + base64Image);

                // 发送请求
                Request request = new Request.Builder()
                        .url(apiBaseUrl + "/chat/completions")
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("OpenAI-Version", API_VERSION)
                        .post(RequestBody.create(requestBody.toString(), JSON))
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("API请求失败: " + response);
                    }

                    JsonNode responseJson = objectMapper.readTree(response.body().string());
                    return responseJson.path("choices")
                            .path(0)
                            .path("message")
                            .path("content")
                            .asText();
        }
    }
}
