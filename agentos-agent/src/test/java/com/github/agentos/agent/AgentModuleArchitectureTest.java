package com.github.agentos.agent;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * agentos-agent 模块分层守护（按概念分包：接口、实现与同类协作类同包）。
 *
 * <p>依赖只能自上而下：</p>
 * <pre>
 *   根包（核心抽象 Agent/AgentExecutionResult）
 *     ← registry / finalize / loop / routing / strategy
 *   registry ← routing        finalize ← loop / strategy
 * </pre>
 * <p>routing 与 strategy 互不依赖，也不得依赖 loop；loop 是可执行实现的叶子包。</p>
 */
@AnalyzeClasses(packages = "com.github.agentos.agent", importOptions = ImportOption.DoNotIncludeTests.class)
class AgentModuleArchitectureTest {

    /** 根包核心抽象必须自包含：不得依赖任何子包。 */
    @ArchTest
    static final ArchRule rootMustNotDependOnSubpackages = noClasses()
            .that().resideInAPackage("com.github.agentos.agent")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "..agent.registry..", "..agent.finalize..",
                    "..agent.loop..", "..agent.routing..", "..agent.strategy..");

    /** 注册表包是底层设施：不得依赖机制包或实现包。 */
    @ArchTest
    static final ArchRule registryMustNotDependOnMechanisms = noClasses()
            .that().resideInAPackage("..agent.registry..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..agent.finalize..", "..agent.loop..",
                    "..agent.routing..", "..agent.strategy..");

    /** 收口器包是底层设施：不得依赖注册、机制或实现包。 */
    @ArchTest
    static final ArchRule finalizeMustNotDependOnOtherPackages = noClasses()
            .that().resideInAPackage("..agent.finalize..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..agent.registry..", "..agent.loop..",
                    "..agent.routing..", "..agent.strategy..");

    /** 可执行 Agent 是叶子：不得依赖注册表或机制包（允许依赖 finalize）。 */
    @ArchTest
    static final ArchRule loopMustNotDependOnRegistryOrMechanisms = noClasses()
            .that().resideInAPackage("..agent.loop..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..agent.registry..", "..agent.routing..", "..agent.strategy..");

    /** 路由层只依据抽象决策：不得依赖收口器、可执行实现或策略。 */
    @ArchTest
    static final ArchRule routingMustNotDependOnImplOrStrategy = noClasses()
            .that().resideInAPackage("..agent.routing..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..agent.finalize..", "..agent.loop..", "..agent.strategy..");

    /** 策略层不得依赖路由或可执行实现（允许依赖 finalize）。 */
    @ArchTest
    static final ArchRule strategyMustNotDependOnRoutingOrLoop = noClasses()
            .that().resideInAPackage("..agent.strategy..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..agent.routing..", "..agent.loop..");
}
