package org.bsc.langgraph4j.webtest.visual;

import lombok.Data;

import java.io.Serializable;

@Data
public class Operation implements Serializable {
    public String type;
    public String target;
    public String value;
}
