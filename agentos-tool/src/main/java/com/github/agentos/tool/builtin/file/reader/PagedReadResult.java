package com.github.agentos.tool.builtin.file.reader;

/**
 * 文件分页读取结果。
 *
 * @param content 本次返回的正文
 * @param page 当前物理页码
 * @param totalPages 文件总页数
 * @param offset 当前页内起始字符偏移
 * @param pageChars 当前物理页提取后的总字符数
 * @param hasMore 文件是否仍有未返回内容
 * @param nextPage 下次读取的物理页码；没有后续内容时为 0
 * @param nextOffset 下次读取的页内字符偏移；没有后续内容时为 0
 * @param truncated 当前物理页是否因本次字符预算而只返回了一部分
 */
public record PagedReadResult(
        String content,
        int page,
        int totalPages,
        int offset,
        int pageChars,
        boolean hasMore,
        int nextPage,
        int nextOffset,
        boolean truncated) {

    /** 创建并校验不可变分页结果。 */
    public PagedReadResult {
        content = content == null ? "" : content;
        if (page < 1 || totalPages < 1 || page > totalPages) {
            throw new IllegalArgumentException("page must be within totalPages");
        }
        if (offset < 0 || pageChars < 0 || offset > pageChars) {
            throw new IllegalArgumentException("offset must be within pageChars");
        }
        if (hasMore && (nextPage < 1 || nextPage > totalPages || nextOffset < 0)) {
            throw new IllegalArgumentException("hasMore result must contain a valid next position");
        }
        if (!hasMore && (nextPage != 0 || nextOffset != 0)) {
            throw new IllegalArgumentException("completed result must not contain a next position");
        }
    }
}
