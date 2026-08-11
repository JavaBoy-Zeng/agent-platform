package com.github.agentos.tool;

import java.nio.file.Path;

public interface FileReader {


    boolean supports(Path path);


    String read(Path path);

}