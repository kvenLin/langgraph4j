package org.bsc.langgraph4j.webtest.visual;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;

/**
 * GPT4-Vision 识别出的操作步骤
 */
@Description("GPT4-Vision 识别出的操作步骤")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OperationStep implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 元素的标记编号
     */
    @Description("图片中的元素的标记编号")
    private String elementIndex;

    /**
     * 操作类型（click, input, verify等）
     */
    @Description("操作类型（click, input, verify等）")
    private String action;

    /**
     * 操作值（如输入的文本）
     */
    @Description("操作值（如输入的文本 或 期望的结果文本）,如果是点击操作，则为空")
    private String value;

    /**
     * 操作描述
     */
    @Description("操作描述")
    private String description;
}
