package com.github.agentos.tool.builtin.file.reader;

import java.nio.file.Path;

/** 支持按物理页和页内字符偏移读取内容的文件解析器。 */
public interface PagedFileReader extends FileReader {

    /**
     * 读取指定物理页的一段文本。
     *
     * @param path 文件路径
     * @param page 从 1 开始的物理页码
     * @param offset 当前页内从 0 开始的字符偏移
     * @param maxChars 本次最多返回的正文字符数
     * @return 带续读位置的分页结果
     */
    PagedReadResult readPage(Path path, int page, int offset, int maxChars);
}
