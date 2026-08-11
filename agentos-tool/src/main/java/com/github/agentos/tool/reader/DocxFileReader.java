package com.github.agentos.tool.reader;

import com.github.agentos.tool.FileReader;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public final class DocxFileReader implements FileReader {


    @Override
    public boolean supports(Path path) {

        return path.toString().endsWith(".docx");
    }


    @Override
    public String read(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            XWPFDocument document = new XWPFDocument(is);
            return document.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}