package com.github.agentos.tool.code;

/** 代码执行器支持的受控语言集合。 */
public enum CodeLanguage {

    /** Python 源码（以解释器单文件方式执行）。 */
    PYTHON("python"),
    /** POSIX shell 脚本。 */
    SHELL("shell"),
    /** Java 单文件源码（约定入口类为 {@code Main}）。 */
    JAVA("java"),
    /** JavaScript 源码（由 Node.js 执行）。 */
    JAVASCRIPT("javascript"),
    /** Go 单文件源码。 */
    GO("go");

    private final String label;

    CodeLanguage(String label) {
        this.label = label;
    }

    /** 规范化语言标识（大小写与空白不敏感），未知标识抛出异常。 */
    public static CodeLanguage parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        if ("js".equals(normalized) || "node".equals(normalized)
                || "nodejs".equals(normalized)) {
            return JAVASCRIPT;
        }
        if ("golang".equals(normalized)) {
            return GO;
        }
        for (CodeLanguage language : values()) {
            if (language.name().toLowerCase(java.util.Locale.ROOT).equals(normalized)
                    || language.label.equals(normalized)) {
                return language;
            }
        }
        throw new IllegalArgumentException(
                "unsupported language: " + value
                        + " (expected one of python/shell/java/javascript/go)");
    }

    /** 返回面向模型与日志的稳定标识。 */
    public String label() {
        return label;
    }
}
