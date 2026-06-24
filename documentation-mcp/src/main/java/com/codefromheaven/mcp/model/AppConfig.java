package com.codefromheaven.mcp.model;

import lombok.Data;
import java.util.List;

@Data
public class AppConfig {
    private List<SourceConfig> sources;
}
