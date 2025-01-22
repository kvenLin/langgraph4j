package org.bsc.langgraph4j.webtest.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用 GPT4-Vision 分析页面截图并识别操作步骤
 */
@Slf4j
public class VisionAnalyzer {
    private final GPT4VisionClient visionClient;
    private final ObjectMapper objectMapper;
    private final Map<String, VisualMarker.ElementCoords> elementCoords;

    private static final String SYSTEM_PROMPT = """
        你是一个网页测试助手。我会上传一张带有数字标记的网页截图。
        请分析图片，识别各个标记元素的功能，并根据用户的任务需求，
        生成具体的操作步骤。在描述步骤时，请使用图片中的数字标记。
        
        示例输出格式：
        {
          "steps": [
            {
              "index": "1",
              "action": "click/input/verify",
              "value": "要输入的文本或验证的内容",
              "description": "点击搜索按钮/在搜索框输入文本/验证搜索结果"
            }
          ]
        }
        """;

    public VisionAnalyzer(String apiKey, String apiBaseUrl, File coordsFile) {
        this.visionClient = new GPT4VisionClient(apiKey, apiBaseUrl);
        this.objectMapper = new ObjectMapper();

        try {
            if (!coordsFile.exists()) {
                log.info("坐标文件不存在，创建空的坐标映射");
                // 确保父目录存在
                coordsFile.getParentFile().mkdirs();
                // 创建空的映射
                this.elementCoords = new HashMap<>();
                // 保存空文件
                objectMapper.writeValue(coordsFile, this.elementCoords);
            } else {
                this.elementCoords = objectMapper.readValue(coordsFile,
                    objectMapper.getTypeFactory().constructMapType(
                        Map.class, String.class, VisualMarker.ElementCoords.class));
            }
        } catch (Exception e) {
            throw new RuntimeException("无法加载元素坐标信息", e);
        }
    }


    /**
     * 从 markdown 中提取 JSON
     */
    private String extractJsonFromMarkdown(String response) {
        // 如果响应包含 markdown 代码块标记
        if (response.contains("```")) {
            // 提取 json 内容
            int start = response.indexOf("```json") + 7;
            if (start == 6) { // 没有找到 "```json"
                start = response.indexOf("```") + 3;
            }
            int end = response.lastIndexOf("```");
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }
        return response;
    }




    /**
     * 分析截图并生成操作步骤
     */
    public List<OperationStep> analyzeScreenshot(Path screenshotPath, String taskDescription) {
        try {
            // 构建提示词
            String prompt = SYSTEM_PROMPT + "\n\n任务描述：" + taskDescription;

            // 调用 GPT4-Vision 分析图片
            log.info("开始分析截图：{}", screenshotPath);
            String response = visionClient.analyzeImage(prompt, screenshotPath);
//            String response = """
//                    ```json
//                    {"steps":[{"index":"0","action":"click","value":"","description":"点击进入新闻板块"},{"index":"1","action":"click","value":"","description":"点击进入hao123导航"},{"index":"2","action":"click","value":"","description":"点击进入地图功能"},{"index":"3","action":"click","value":"","description":"点击进入贴吧社区"},{"index":"4","action":"click","value":"","description":"点击进入视频板块"},{"index":"5","action":"click","value":"","description":"点击进入图片板块"},{"index":"6","action":"click","value":"","description":"点击进入网盘功能"},{"index":"7","action":"click","value":"","description":"点击进入更多应用"},{"index":"9","action":"click","value":"","description":"点击进入文学频道"},{"index":"11","action":"click","value":"","description":"点击设置选项"},{"index":"12","action":"click","value":"","description":"点击登录进行身份验证"},{"index":"13","action":"input","value":"搜索关键词","description":"在搜索框中输入您想搜索的内容"},{"index":"14","action":"click","value":"","description":"点击“百度一下”按钮执行搜索"},{"index":"17","action":"click","value":"","description":"点击体验百度AI搜索"},{"index":"29","action":"click","value":"","description":"点击换一换以查看更多新闻内容"},{"index":"32","action":"click","value":"","description":"点击查看关于习近平举行视频会晤的新闻"},{"index":"37","action":"click","value":"","description":"点击查看关于解除对美新任商务卿制裁的新闻"},{"index":"44","action":"click","value":"","description":"点击查看关于国泰航空的新闻"},{"index":"50","action":"click","value":"","description":"点击查看关于商品假日消费的新闻"},{"index":"61","action":"click","value":"","description":"点击进入关于K歌的板块"}]}
//                    ```
//                    """;
            log.info("GPT4-Vision 返回结果：{}", response);

            // 从 markdown 中提取 JSON
            String jsonStr = extractJsonFromMarkdown(response);
            log.info("提取的 JSON：{}", jsonStr);

            // 解析返回的 JSON 格式步骤
            StepResponse stepResponse = objectMapper.readValue(jsonStr, StepResponse.class);
            if (stepResponse == null || stepResponse.getSteps() == null || stepResponse.getSteps().isEmpty()) {
                throw new RuntimeException("无法从 GPT4-Vision 响应中解析出操作步骤");
            }

            return stepResponse.getSteps();
        } catch (Exception e) {
            throw new RuntimeException("分析截图失败", e);
        }
    }

    /**
     * 获取元素的坐标信息
     */
    public VisualMarker.ElementCoords getElementCoords(String elementIndex) {
        return elementCoords.get(elementIndex);
    }
}
