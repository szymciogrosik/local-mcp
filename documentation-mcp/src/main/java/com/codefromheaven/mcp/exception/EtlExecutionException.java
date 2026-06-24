package com.codefromheaven.mcp.exception;

public class EtlExecutionException extends RuntimeException {
    public EtlExecutionException(String message) {
        super(message);
    }

    public EtlExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
