package org.bsc.langgraph4j.webtest;

import com.microsoft.playwright.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.webtest.state.WebTestState;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.utils.CollectionsUtils.mapOf;

/**
 * 自动化测试示例
 */
@Slf4j(topic = "WebTest")
public class Complete3NodeGraphTest {

    @Test
    void test3NodeGraph() throws Exception {
        log.info("开始初始化测试环境");

        // 初始化AI模型
        ChatLanguageModel model = OpenAiChatModel.builder()
                .baseUrl(System.getenv("OPENAI_API_URL"))
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .timeout(Duration.ofMinutes(2))
                .maxRetries(2)
                .temperature(0.0)
                .build();
        log.info("AI模型初始化完成");

        // 初始化Playwright
        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;

        try {
            log.info("开始初始化Playwright");
            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(false)
            );

            // 创建浏览器上下文，并配置视频录制
            context = browser.newContext(new Browser.NewContextOptions()
                    .setRecordVideoDir(Paths.get("test-results/videos"))  // 设置视频保存目录
                    .setRecordVideoSize(1280, 720));  // 设置视频分辨率

            page = context.newPage();
            log.info("Playwright初始化完成");

            // 创建测试执行器
            WebTestExecutor executor = new WebTestExecutor(model, page, new AtomicInteger());
            log.info("测试执行器创建完成");

            // 构建状态图
            var graph = new StateGraph<>(WebTestState.SCHEMA, WebTestState::new)
                    // 定义节点
                    .addNode("parse", executor::parsePage)
                    .addNode("execute", executor::execute)
                    // 构建图
                    .addEdge(START, "parse")
                    .addEdge("parse", "execute")
                    .addConditionalEdges("execute",
                            edge_async(state -> String.valueOf(state.success()
                                    .map(success -> !success && executor.counter.get() < 3)
                                    .orElse(false))),
                            mapOf(
                                    "false", "parse",
                                    "true", END
                            )
                    );
            log.info("状态图构建完成");

            var mermaid = graph.getGraph(GraphRepresentation.Type.MERMAID, "Web Test Flow");
            log.info("Mermaid图:\n{}", mermaid.getContent());

            // 编译并运行工作流
            log.info("开始编译工作流");
            var workflow = graph.compile();
            System.out.println(workflow.getGraph(GraphRepresentation.Type.MERMAID, "Web Test Flow"));

            log.info("开始执行工作流");
            var result = workflow.invoke(mapOf(
                    "url", "https://www.baidu.com/",
                    "description", "输入框输入 'LangChain4j' 然后点击 '百度一下'"
            ));

            if (result.isPresent()) {
                List<String> messages = result.get().messages();
                if (!messages.isEmpty()) {
                    for (String message : messages) {
                        log.info("{}", message);
                    }
                }
            }
            log.info("工作流执行完成");

        } catch (Exception e) {
            log.error("测试执行失败", e);
            throw new RuntimeException(e);
        } finally {
            log.info("开始清理资源");
            if (context != null) {
                // 关闭上下文前先保存视频
                context.close();
            }
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
            log.info("资源清理完成");
        }
    }
}
