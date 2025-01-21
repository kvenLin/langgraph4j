package org.bsc.langgraph4j.webtest.visual;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * GPT4-Vision 返回的操作步骤响应
 */
@Data
public class StepResponse {
    @JsonProperty("steps")
    private List<OperationStep> steps;
}
