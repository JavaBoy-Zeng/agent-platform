package com.github.agentos.tool.writer;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** 使用 Apache POI 生成和更新真实的 Word OOXML 文档。 */
public final class DocxFileContentWriter implements FileContentWriter {

    @Override
    public boolean supports(Path path) {
        return path != null && path.getFileName() != null
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    @Override
    public String format() {
        return "DOCX";
    }

    @Override
    public void create(Path path, String content) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
                OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            addContent(document, content);
            document.write(output);
        }
    }

    @Override
    public void overwrite(Path path, String content) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
                OutputStream output = Files.newOutputStream(
                        path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            addContent(document, content);
            document.write(output);
        }
    }

    @Override
    public void append(Path path, String content) throws IOException {
        if (!Files.exists(path)) {
            overwrite(path, content);
            return;
        }
        XWPFDocument document;
        try (InputStream input = Files.newInputStream(path)) {
            document = new XWPFDocument(input);
        }
        try (document;
                OutputStream output = Files.newOutputStream(
                        path, StandardOpenOption.TRUNCATE_EXISTING)) {
            addContent(document, content);
            document.write(output);
        }
    }

    private static void addContent(XWPFDocument document, String content) {
        String[] lines = content.split("\\R", -1);
        for (String line : lines) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText(line);
        }
    }
}
