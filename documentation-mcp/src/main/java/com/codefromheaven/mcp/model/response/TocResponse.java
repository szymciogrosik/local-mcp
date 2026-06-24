package com.codefromheaven.mcp.model.response;

import java.util.List;

public record TocResponse(String file, List<TocEntry> toc) {}
