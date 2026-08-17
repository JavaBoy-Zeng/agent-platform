package com.github.agentos.tool.builtin.file.writer;

import java.io.IOException;
import java.nio.file.Path;

/** 按文件格式写入文本内容的内部协议。 */
public interface FileContentWriter {

    /** 判断当前 Writer 是否支持目标文件。 */
    boolean supports(Path path);

    /** 返回用于工具结果和日志的稳定格式名称。 */
    String format();

    /** 创建新文件；目标已存在时必须失败。 */
    void create(Path path, String content) throws IOException;

    /** 创建或覆盖目标文件。 */
    void overwrite(Path path, String content) throws IOException;

    /** 创建目标文件或向已有内容末尾追加。 */
    void append(Path path, String content) throws IOException;
}
