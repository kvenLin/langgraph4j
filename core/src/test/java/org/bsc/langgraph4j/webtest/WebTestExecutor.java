package org.bsc.langgraph4j.webtest;

import com.microsoft.playwright.Page;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.structured.StructuredPrompt;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.webtest.state.WebTestState;
import org.bsc.langgraph4j.webtest.visual.Operation;
import org.bsc.langgraph4j.webtest.visual.OperationStep;
import org.bsc.langgraph4j.webtest.visual.VisionAnalyzer;
import org.bsc.langgraph4j.webtest.visual.VisualMarker;

import java.nio.file.Path;
import java.nio.file.Paths;
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

    /**
     * 测试计划请求结构
     */
    @StructuredPrompt("""
        {
            "url": "{{url}}",
            "description": "{{description}}"
        }
        """)
    record TestPlanRequest(String url, String description) {}

    interface TestPlannerService {
        @SystemMessage("""
            你是一个网页测试自动化专家。请分析测试场景，并给出详细的测试计划，包括：
            1. 测试目标
            2. 前置条件
            3. 具体步骤（每个步骤都要清晰明确）
            4. 预期结果
            """)
        String generateTestPlan(TestPlanRequest request);
    }

    /**
     * 步骤提取请求结构
     */
    @StructuredPrompt("""
        {
            "analysis": "{{analysis}}",
            "plan": "{{plan}}"
        }
        """)
    record StepExtractionRequest(String analysis, String plan) {}

    interface StepExtractorService {
        @SystemMessage("""
            你是一个测试步骤提取专家。
            根据页面分析结果和测试计划，提取下一个具体的操作步骤。
            操作类型可以是：click（点击）、input（输入）、verify（验证）
            """)
        Operation extractNextStep(StepExtractionRequest request);
    }


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

    /**
     * 分析用户输入
     */
    public CompletableFuture<Map<String, Object>> analyzeInput(WebTestState state) {
        log.info("开始分析用户输入: url={}, description={}", state.url(), state.description());

        TestPlanRequest request = new TestPlanRequest(state.url(), state.description());
        TestPlannerService planner = AiServices.create(TestPlannerService.class, model);
        String plan = planner.generateTestPlan(request);

        log.info("生成测试计划: {}", plan);
        state.messages().add("生成测试计划: " + plan);

        return CompletableFuture.completedFuture(mapOf("plan", plan));
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

            // 截图
            Path screenshotPath = screenshotDir.resolve("page-" + counter.get() + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));

            // 使用 GPT4-Vision 分析页面内容
            List<OperationStep> steps = visionAnalyzer.analyzeScreenshot(screenshotPath, "分析页面内容，包括标题、主要区域、可交互元素等");

            // 清除标记
            visualMarker.clearMarkers();

            log.info("页面分析结果: {}", steps);
            state.messages().add("页面分析完成");
            return CompletableFuture.completedFuture(mapOf("analysis", steps));

        } catch (Exception e) {
            log.error("页面分析失败: {}", e.getMessage(), e);
            state.messages().add("页面分析失败: " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 提取下一个操作步骤
     */
    public CompletableFuture<Map<String, Object>> extractStep(WebTestState state) {
        log.info("开始提取下一个操作步骤");

        try {
            // 获取最新的分析结果
            Object analysisObj = state.analysis().get();
            String analysis;
            if (analysisObj instanceof List<?>) {
                // 如果是列表，取第一个元素
                analysis = ((List<?>) analysisObj).get(0).toString();
            } else {
                analysis = analysisObj.toString();
            }
            
            String plan = state.plan().get();

            StepExtractionRequest request = new StepExtractionRequest(analysis, plan);
            StepExtractorService extractor = AiServices.create(StepExtractorService.class, model);
            Operation operation = extractor.extractNextStep(request);

            log.info("提取到的操作步骤: type={}, target={}, value={}",
                    operation.getType(), operation.getTarget(), operation.getValue());

            state.messages().add("步骤提取完成");
            return CompletableFuture.completedFuture(mapOf("operation", operation));

        } catch (Exception e) {
            log.error("步骤提取失败: {}", e.getMessage(), e);
            state.messages().add("步骤提取失败: " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 执行验证操作
     */
    private boolean executeVerifyOperation(Operation operation) {
        String selector = operation.target;
        String expectedValue = operation.value;

        log.info("执行验证操作: selector={}, expected={}", selector, expectedValue);

        try {
            // 如果是验证链接的href属性
            if (selector.startsWith("a[href=")) {
                String href = page.getAttribute("a", "href");
                log.info("验证链接href: actual={}, expected={}", href, expectedValue);
                return expectedValue.equals(href);
            }

            // 如果是验证元素的文本内容
            String actualText = page.textContent(selector);
            log.info("验证文本内容: actual={}, expected={}", actualText, expectedValue);
            return expectedValue.equals(actualText);

        } catch (Exception e) {
            log.error("验证操作执行失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 执行具体操作
     */
    private boolean executeOperation(Operation operation) {
        if (operation == null) {
            return false;
        }

        try {

            boolean result = false;
            switch (operation.type.toLowerCase()) {
                case "click":
                    log.info("执行点击操作: selector={}", operation.target);
                    // 高亮要点击的元素
                    page.evaluate("selector => { const el = document.querySelector(selector); if(el) { el.style.boxShadow = '0 0 0 2px red'; } }",
                                operation.target);
                    page.waitForTimeout(500); // 等待高亮效果显示
                    page.click(operation.target);
                    result = true;
                    break;

                case "input":
                    log.info("执行输入操作: selector={}, value={}",
                            operation.target, operation.value);
                    // 高亮输入框
                    page.evaluate("selector => { const el = document.querySelector(selector); if(el) { el.style.boxShadow = '0 0 0 2px blue'; } }",
                                operation.target);
                    page.waitForTimeout(500);
                    page.fill(operation.target, operation.value);
                    result = true;
                    break;

                case "verify":
                    result = executeVerifyOperation(operation);
                    break;

                default:
                    log.warn("不支持的操作类型: {}", operation.type);
                    result = false;
            }

            // 清除高亮效果
            page.evaluate("selector => { const el = document.querySelector(selector); if(el) { el.style.boxShadow = ''; } }",
                         operation.target);

            return result;
        } catch (Exception e) {
            log.error("执行操作失败: {}", e.getMessage(), e);
            return false;
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
        Operation step = state.operation().get();
        log.info("开始执行操作: {}", step);

        try {
            // 标记页面元素
            visualMarker.markElements();

            // 截图
            Path screenshotPath = screenshotDir.resolve("step-" + counter.get() + ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));

            // 使用 GPT4-Vision 分析截图
            List<OperationStep> steps = visionAnalyzer.analyzeScreenshot(screenshotPath, step.toString());

            // 执行识别出的操作步骤
            boolean success = true;
            for (OperationStep opStep : steps) {
                success &= executeVisualOperation(opStep);
                if (!success) break;
            }

            // 清除标记
            visualMarker.clearMarkers();

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
