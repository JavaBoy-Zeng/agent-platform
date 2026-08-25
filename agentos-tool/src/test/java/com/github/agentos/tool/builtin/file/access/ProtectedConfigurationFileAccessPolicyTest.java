package com.github.agentos.tool.builtin.file.access;

import com.github.agentos.tool.api.ToolFailureType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtectedConfigurationFileAccessPolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void protectsCommonDeploymentConfigurationNamesAndSuffixes() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
        ProtectedConfigurationFileAccessPolicy policy = policy(root);

        for (String name : new String[]{
                ".env", ".env.production", ".envrc", "Caddyfile", "Dockerfile",
                "Caddyfile.backup-20260825", "application.yml.bak", "settings.json~",
                "application.yml", "application.yaml", "server.properties", "settings.toml",
                "nginx.conf", "service.ini", "manifest.json", "launch.plist", "tls.pem",
                "tls.key", "certificate.crt", "keystore.p12", "settings.xml"}) {
            Path file = Files.writeString(root.resolve(name), "protected");

            assertThat(policy.authorizeDiscovery(file)).isEmpty();
            assertSecurityDeniedWithoutPath(() -> policy.authorizeRead(file), name);
            assertSecurityDeniedWithoutPath(() -> policy.authorizeWrite(file), name);
        }
    }

    @Test
    void allowsOrdinaryFilesAndBlocksAliasesToProtectedFiles() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
        Path notes = Files.writeString(root.resolve("notes.txt"), "safe");
        Path caddyfile = Files.writeString(root.resolve("Caddyfile"), "protected");
        Path alias = root.resolve("notes-alias.txt");
        Files.createSymbolicLink(alias, caddyfile);
        ProtectedConfigurationFileAccessPolicy policy = policy(root);

        assertThat(policy.authorizeRead(notes)).isEqualTo(notes.toRealPath());
        assertThat(policy.authorizeDiscovery(notes)).contains(notes.toRealPath());
        assertThat(policy.authorizeDiscovery(alias)).isEmpty();
        assertSecurityDeniedWithoutPath(() -> policy.authorizeRead(alias), "Caddyfile");
    }

    private static ProtectedConfigurationFileAccessPolicy policy(Path root) {
        return new ProtectedConfigurationFileAccessPolicy(new RootedFileAccessPolicy(root));
    }

    private static void assertSecurityDeniedWithoutPath(Runnable action, String protectedName) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(FileAccessException.class, exception -> {
                    assertThat(exception.failureType()).isEqualTo(ToolFailureType.SECURITY_DENIED);
                    assertThat(exception.getMessage())
                            .contains("protected deployment configuration")
                            .doesNotContain(protectedName);
                });
    }
}
