package com.github.agentos.tool.builtin.file.access;

import com.github.agentos.tool.api.ToolFailureType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RootedFileAccessPolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesRelativeAndAbsolutePathsInsideRoot() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
        Path file = Files.writeString(root.resolve("inside.txt"), "inside");
        RootedFileAccessPolicy policy = new RootedFileAccessPolicy(root);

        assertThat(policy.allowedRoot()).contains(root.toRealPath());
        assertThat(policy.authorizeRead(Path.of("inside.txt"))).isEqualTo(file.toRealPath());
        assertThat(policy.authorizeRead(file)).isEqualTo(file.toRealPath());
        assertThat(policy.authorizeWrite(Path.of("new/child.txt")))
                .isEqualTo(root.toRealPath().resolve("new/child.txt"));
    }

    @Test
    void rejectsTraversalAndAbsolutePathOutsideRoot() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.txt"), "outside");
        RootedFileAccessPolicy policy = new RootedFileAccessPolicy(root);

        assertSecurityDenied(() -> policy.authorizeRead(Path.of("../outside.txt")));
        assertSecurityDenied(() -> policy.authorizeRead(outside));
        assertSecurityDenied(() -> policy.authorizeWrite(Path.of("../created.txt")));
    }

    @Test
    void rejectsExistingAndFutureTargetsThroughSymlink() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = root.resolve("escape");
        Files.createSymbolicLink(link, outside);
        RootedFileAccessPolicy policy = new RootedFileAccessPolicy(root);

        assertSecurityDenied(() -> policy.authorizeRead(link.resolve(secret.getFileName())));
        assertSecurityDenied(() -> policy.authorizeWrite(link.resolve("new.txt")));
    }

    private static void assertSecurityDenied(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(FileAccessException.class, exception ->
                        assertThat(exception.failureType())
                                .isEqualTo(ToolFailureType.SECURITY_DENIED));
    }
}
