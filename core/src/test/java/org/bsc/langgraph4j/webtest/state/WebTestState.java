package org.bsc.langgraph4j.webtest.state;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AppenderChannel;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.webtest.visual.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Web测试状态类，用于在测试流程中传递状态
 */
public class WebTestState extends AgentState {
    public static Map<String, Channel<?>> SCHEMA = Map.of(
            "messages", AppenderChannel.<String>of(ArrayList::new)
    );


    public WebTestState(Map<String, Object> initData) {
        super(initData);
    }

    public String url() {
        Optional<String> result = value("url");
        return result.orElseThrow(() -> new IllegalStateException("url is not set!"));
    }

    public String description() {
        Optional<String> result = value("description");
        return result.orElseThrow(() -> new IllegalStateException("description is not set!"));
    }

    public Optional<String> plan() {
        return value("plan");
    }

    public Optional<String> analysis() {
        return value("analysis");
    }

    public Optional<String> nextStep() {
        return value("nextStep");
    }

    public Optional<Boolean> success() {
        return value("success");
    }

    public Optional<String> error() {
        return value("error");
    }

    public Optional<Operation> operation() {
        return value("operation");
    }

    public Optional<String> expected() {
        return value("expected");
    }

    public List<String> messages() {
        return this.<List<String>>value( "messages" )
                .orElseThrow( () -> new RuntimeException( "messages not found" ) );
    }
}
