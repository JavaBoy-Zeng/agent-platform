package com.github.agentos.tool;

import com.github.agentos.tool.file.FileAccessException;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;

/** 文件工具共享的参数读取和异常分类逻辑。 */
public final class FileToolSupport {

    private FileToolSupport() {
    }

    public static String requiredString(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing or blank required argument: " + name);
        }
        return text;
    }

    public static int integer(
            Object value, String name, int defaultValue, int minimum, int maximum) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        int result = number.intValue();
        if (number.doubleValue() != result || result < minimum || result > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return result;
    }

    public static boolean bool(Object value, String name, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException(name + " must be boolean");
        }
        return result;
    }

    public static ToolResult failure(Exception exception, String operation) {
        if (exception instanceof java.io.UncheckedIOException uncheckedIOException) {
            return failure(uncheckedIOException.getCause(), operation);
        }
        ToolFailureType type;
        if (exception instanceof FileAccessException accessException) {
            type = accessException.failureType();
        } else if (exception instanceof InvalidPathException || exception instanceof IllegalArgumentException) {
            type = ToolFailureType.INVALID_ARGUMENT;
        } else if (exception instanceof NoSuchFileException) {
            type = ToolFailureType.NOT_FOUND;
        } else if (exception instanceof AccessDeniedException) {
            type = ToolFailureType.PERMISSION_DENIED;
        } else if (exception instanceof SecurityException) {
            type = ToolFailureType.SECURITY_DENIED;
        } else if (exception instanceof IOException) {
            type = ToolFailureType.ACCESS_DENIED;
        } else {
            type = ToolFailureType.TOOL_INTERNAL_ERROR;
        }
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = exception.getClass().getSimpleName();
        }
        return ToolResult.failure(type, operation + " failed: " + detail);
    }
}
