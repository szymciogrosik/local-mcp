package com.codefromheaven.mcp.model.response;

public record PaginationMeta(int offset, int limit, int total, boolean hasMore) {}
