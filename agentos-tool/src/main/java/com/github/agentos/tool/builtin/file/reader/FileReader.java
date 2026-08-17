package com.github.agentos.tool.builtin.file.reader;

import java.nio.file.Path;

public interface FileReader {


    boolean supports(Path path);


    String read(Path path);

}
