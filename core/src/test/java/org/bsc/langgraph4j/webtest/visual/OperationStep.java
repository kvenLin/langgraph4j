package org.bsc.langgraph4j.webtest.visual;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;

/**
 * GPT4-Vision 识别出的操作步骤
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OperationStep implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 元素的标记编号
     */
    @JsonProperty("index")
    private String elementIndex;

    /**
     * 操作类型（click, input, verify等）
     */
    @JsonProperty("action")
    private String action;

    /**
     * 操作值（如输入的文本）
     */
    @JsonProperty("value")
    private String value;

    /**
     * 操作描述
     */
    @JsonProperty("description")
    private String description;
}
