package org.bsc.langgraph4j.webtest.visual;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * GPT4-Vision 返回的操作步骤响应
 */
@Description("操作步骤响应")
@Data
public class StepResponse implements Serializable {
    @JsonProperty("steps")
    @Description("操作步骤")
    private List<OperationStep> steps;
}
