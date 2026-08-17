package com.github.agentos.tool.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ToolPackageArchitectureTest {

    private static final JavaClasses TOOL_CLASSES = new ClassFileImporter()
            .importPackages("com.github.agentos.tool");

    @Test
    void apiDoesNotDependOnRuntimeOrBuiltins() {
        noClasses().that().resideInAPackage("..tool.api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..tool.runtime..", "..tool.builtin..")
                .check(TOOL_CLASSES);
    }

    @Test
    void runtimeDoesNotDependOnBuiltins() {
        noClasses().that().resideInAPackage("..tool.runtime..")
                .should().dependOnClassesThat().resideInAPackage("..tool.builtin..")
                .check(TOOL_CLASSES);
    }

    @Test
    void builtinsDoNotDependOnRuntime() {
        noClasses().that().resideInAPackage("..tool.builtin..")
                .should().dependOnClassesThat().resideInAPackage("..tool.runtime..")
                .check(TOOL_CLASSES);
    }

    @Test
    void fileAdapterPackagesHaveNoCycles() {
        slices().matching("com.github.agentos.tool.builtin.file.(*)..")
                .should().beFreeOfCycles()
                .check(TOOL_CLASSES);
    }

    @Test
    void sharedFileSupportStaysWithFileTools() {
        classes().that().haveSimpleName("FileToolSupport")
                .should().resideInAPackage("..tool.builtin.file")
                .check(TOOL_CLASSES);
    }
}
