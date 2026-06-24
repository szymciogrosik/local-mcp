package com.codefromheaven.mcp.model.response;

public record FullDocumentResponse(String file, int totalLines, String content) {}
