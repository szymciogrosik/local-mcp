package com.codefromheaven.mcp.model.response;

import java.util.List;

public record PaginatedResponse<T>(List<T> data, PaginationMeta pagination) {}
