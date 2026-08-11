package com.github.agentos.tool.reader;

import com.github.agentos.tool.FileReader;
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
public final class PdfFileReader implements FileReader {


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
}