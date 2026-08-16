package com.github.agentos.memory;

import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 使用单个 SQLite 数据库文件持久化 L0-L3 记忆和流水线任务。 */
public final class SqliteMemoryStore extends JdbcMemoryStore {

    private final Path databaseFile;

    /**
     * 创建数据库文件并自动执行内置迁移。
     *
     * @param databaseFile SQLite 数据库文件
     */
    public SqliteMemoryStore(Path databaseFile) {
        this(prepare(databaseFile), true);
    }

    private SqliteMemoryStore(Path normalized, boolean ignored) {
        super(dataSource(normalized));
        this.databaseFile = normalized;
    }

    /**
     * 返回 SQLite 数据库文件的绝对规范化路径。
     *
     * @return 当前存储使用的数据库文件
     */
    public Path databaseFile() {
        return databaseFile;
    }

    private static Path prepare(Path databaseFile) {
        Objects.requireNonNull(databaseFile, "databaseFile must not be null");
        Path normalized = databaseFile.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new IllegalArgumentException("databaseFile must have a parent directory");
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new MemoryAdapterException("failed to create SQLite database directory", exception);
        }
        return normalized;
    }

    private static SQLiteDataSource dataSource(Path databaseFile) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databaseFile);
        return dataSource;
    }
}
