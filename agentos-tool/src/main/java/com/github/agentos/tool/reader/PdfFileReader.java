package com.github.agentos.tool.reader;

import com.github.agentos.tool.PagedFileReader;
import com.github.agentos.tool.PagedReadResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;

/**
 * PDF 文件读取器。
 *
 * <p>
 * 负责从 PDF 文件中提取文本内容。
 * </p>
 */
public final class PdfFileReader implements PagedFileReader {


    /**
     * 判断是否支持 PDF 文件。
     */
    @Override
    public boolean supports(Path path) {

        String fileName = path.getFileName().toString().toLowerCase();

        return fileName.endsWith(".pdf");
    }


    /**
     * 读取 PDF 文本内容。
     */
    @Override
    public String read(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (Exception exception) {
            throw new RuntimeException("pdf read failed: " + path, exception);
        }
    }

    @Override
    public PagedReadResult readPage(Path path, int page, int offset, int maxChars) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be at least 1");
        }
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            int totalPages = document.getNumberOfPages();
            if (totalPages < 1) {
                throw new IllegalArgumentException("pdf does not contain any pages");
            }
            if (page > totalPages) {
                throw new IllegalArgumentException(
                        "page " + page + " exceeds totalPages " + totalPages);
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText = stripper.getText(document).strip();
            if (offset > pageText.length()) {
                throw new IllegalArgumentException(
                        "offset " + offset + " exceeds pageChars " + pageText.length());
            }

            int end = Math.min(pageText.length(), offset + maxChars);
            if (end < pageText.length()
                    && end > offset
                    && Character.isHighSurrogate(pageText.charAt(end - 1))) {
                end--;
            }
            String content = pageText.substring(offset, end);
            boolean truncated = end < pageText.length();
            boolean hasMore = truncated || page < totalPages;
            int nextPage = !hasMore ? 0 : truncated ? page : page + 1;
            int nextOffset = truncated ? end : 0;
            return new PagedReadResult(
                    content,
                    page,
                    totalPages,
                    offset,
                    pageText.length(),
                    hasMore,
                    nextPage,
                    nextOffset,
                    truncated);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("pdf page read failed: " + path, exception);
        }
    }
}
