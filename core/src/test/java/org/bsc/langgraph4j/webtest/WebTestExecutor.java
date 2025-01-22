package org.bsc.langgraph4j.webtest;

import cn.hutool.json.JSONUtil;
import com.microsoft.playwright.Page;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.input.structured.StructuredPrompt;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.webtest.state.WebTestState;
import org.bsc.langgraph4j.webtest.visual.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bsc.langgraph4j.utils.CollectionsUtils.mapOf;

/**
 * Web测试执行器，负责执行自动化测试流程
 */
@Slf4j(topic = "WebTestExecutor")
public class WebTestExecutor {

    // 运行时组件
    final ChatLanguageModel model;
    final Page page;
    final AtomicInteger counter;
    final VisualMarker visualMarker;
    final VisionAnalyzer visionAnalyzer;
    final Path screenshotDir;

    /**
     * 创建 WebTestExecutor 实例
     */
    public WebTestExecutor(ChatLanguageModel model, Page page, AtomicInteger counter) {
        this.model = model;
        this.page = page;
        this.counter = counter;

        // 初始化视觉分析组件
        this.screenshotDir = Paths.get("test-results/screenshots");
        this.screenshotDir.toFile().mkdirs();

        Path coordsFile = Paths.get("test-results/element-coords.json");
        this.visualMarker = new VisualMarker(page, coordsFile.toString());
        this.visionAnalyzer = new VisionAnalyzer(
                System.getenv("OPENAI_API_KEY"),
                System.getenv("OPENAI_API_URL"),
                coordsFile.toFile()
        );
    }



    //{
    //  "steps": [
    //    {
    //      "index": "1",
    //      "action": "input",
    //      "value": "LangChain4j",
    //      "description": "输入搜索框内容"
    //    }
    //  ]
    //}
    private JsonSchema buildJsonSchema() {

        JsonSchemaElement stepSchema = JsonObjectSchema.builder()
                .description("执行步骤, elementIndex为步骤序号, action为操作类型(click, input, verify), value为操作值(如果action是input则是输入内容, 如果verify则是验证内容, click则为空即可), description为操作描述")
                .addStringProperty("elementIndex")
                .addStringProperty("action")
                .addStringProperty("value")
                .addStringProperty("description")
                .build();


        return JsonSchema.builder()
                .name("action-steps")
                .rootElement(JsonObjectSchema.builder()
                        .addProperty("steps", JsonArraySchema.builder()
                                .items(stepSchema)
                                .build())
                        .required(List.of("steps"))
                        .build())
                .build();
    }

    /**
     * 分析页面内容
     */
    public CompletableFuture<Map<String, Object>> parsePage(WebTestState state) {
        log.info("开始分析页面内容");

        try {
            // 导航到目标页面
            String url = state.url();
            log.info("导航到页面: {}", url);
            page.navigate(url);
            page.waitForLoadState();
            log.info("页面加载完成");

            // 标记页面元素
            visualMarker.markElements();

            ChatLanguageModel chatModel = OpenAiChatModel.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY")) // Please use your own OpenAI API key
                    .baseUrl(System.getenv("OPENAI_API_URL")) // Please use your own OpenAI API URL
                    .modelName("gpt-4-vision-preview")
                    .timeout(Duration.ofMinutes(2))
                    .responseFormat("json_schema")
                    .build();


            // 截图
            Path screenshotPath = screenshotDir.resolve("page-" + counter.get() + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));

            //base64编码
            // 读取图片并转换为Base64
            byte[] imageBytes = Files.readAllBytes(screenshotPath);
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);


            ResponseFormat responseFormat = ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(buildJsonSchema())
                    .build();
            UserMessage userMessage = UserMessage.from(
                    TextContent.from(state.description()),
                    ImageContent.from(base64Image, "image/png")
            );
            SystemMessage systemMessage = SystemMessage.from(
                    "根据图片和用户的操作, 解析出对应的步骤.解析步骤的index需要和图片中的元素index对应.");
            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(systemMessage, userMessage)
                    .responseFormat(responseFormat)
                    .build();

            ChatResponse chatResponse = chatModel.chat(chatRequest);
            String text = chatResponse.aiMessage().text();


            // 使用 GPT4-Vision 分析页面内容
            // 修改为AIService的调动方式, 并且传入图片内容使用 @see dev.langchain4j.data.message.ImageContent
            StepResponse response = JSONUtil.toBean(text, StepResponse.class);

            // 清除标记
            visualMarker.clearMarkers();

            log.info("页面分析执行结果: {}", response);
            state.messages().add("页面分析完成");
            return CompletableFuture.completedFuture(mapOf("steps", response.getSteps()));

        } catch (Exception e) {
            log.error("页面分析失败: {}", e.getMessage(), e);
            state.messages().add("页面分析失败: " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 使用视觉分析执行操作
     */
    private boolean executeVisualOperation(OperationStep step) {
        try {
            VisualMarker.ElementCoords coords = visionAnalyzer.getElementCoords(step.getElementIndex());
            if (coords == null) {
                log.error("找不到元素的坐标信息: index={}", step.getElementIndex());
                return false;
            }

            switch (step.getAction().toLowerCase()) {
                case "click":
                    log.info("执行点击操作: 坐标=({}, {})", coords.getCenterX(), coords.getCenterY());
                    page.mouse().click(coords.getCenterX(), coords.getCenterY());
                    // 等待页面加载完成
                    page.waitForLoadState();
                    return true;

                case "input":
                    log.info("执行输入操作: 坐标=({}, {}), 文本={}",
                            coords.getCenterX(), coords.getCenterY(), step.getValue());
                    page.mouse().click(coords.getCenterX(), coords.getCenterY());
                    page.keyboard().insertText(step.getValue());
                    return true;

                case "verify":
                    // 获取元素位置的文本内容
                    Map<String, Integer> coordinates = Map.of(
                            "x", coords.getCenterX(),
                            "y", coords.getCenterY()
                    );

                    String actualText = (String) page.evaluate("""
                                    coords => {
                                        const x = coords.x;
                                        const y = coords.y;
                                        let el = document.elementFromPoint(x, y);
                                        if (!el) return '';
                                        
                                        // 如果点击到的是文本节点的父元素，尝试找到实际的文本节点
                                        const walker = document.createTreeWalker(
                                            el,
                                            NodeFilter.SHOW_TEXT,
                                            null,
                                            false
                                        );
                                        
                                        let text = '';
                                        let node;
                                        while (node = walker.nextNode()) {
                                            const range = document.createRange();
                                            range.selectNodeContents(node);
                                            const rect = range.getBoundingClientRect();
                                            
                                            // 检查坐标是否在文本节点范围内
                                            if (x >= rect.left && x <= rect.right &&
                                                y >= rect.top && y <= rect.bottom) {
                                                text = node.textContent.trim();
                                                break;
                                            }
                                        }
                                        
                                        return text || el.textContent.trim();
                                    }
                                    """,
                            coordinates);

                    log.info("执行验证操作: 实际文本={}, 期望文本={}", actualText, step.getValue());
                    return step.getValue().equals(actualText);

                default:
                    log.warn("不支持的操作类型: {}", step.getAction());
                    return false;
            }
        } catch (Exception e) {
            log.error("执行视觉操作失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 执行操作
     */
    public CompletableFuture<Map<String, Object>> execute(WebTestState state) {
        List<OperationStep> operationSteps = state.steps().get();
        log.info("开始执行操作: {}", operationSteps);

        try {
            // 执行识别出的操作步骤
            boolean success = true;
            for (OperationStep opStep : operationSteps) {
                success = executeVisualOperation(opStep);
                if (!success) break;
            }

            if (!success) {
                state.messages().add("操作执行失败");
                return CompletableFuture.completedFuture(mapOf("success", false));
            }

            state.messages().add("操作执行成功");
            return CompletableFuture.completedFuture(mapOf("success", true));

        } catch (Exception e) {
            log.error("执行失败: {}", e.getMessage(), e);
            state.messages().add("执行异常: " + e.getMessage());
            return CompletableFuture.completedFuture(mapOf("error", e.getMessage()));
        }
    }
}
