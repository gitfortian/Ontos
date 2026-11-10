package com.ontos.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 多模块依赖方向守护。
 *
 * <p>允许方向：server → module / framework；module-catalog → module-ontology / framework（管理面，
 * 不承载运行时）；module-query → module-ontology / module-system；module-session → module-query /
 * module-ontology / module-system；module-agent（W3）→ module-session 等；
 * 所有模块 → framework。framework 不得反向依赖任何业务模块，业务模块之间不得出现环形依赖。
 * 运行时只读已发布清单的 scenarios/skills/vqrs 段，因此 query/system/session/agent 不得依赖 catalog。
 */
@AnalyzeClasses(packages = "com.ontos")
class ArchitectureTest {

    @ArchTest
    static final ArchRule framework_must_not_depend_on_business_modules =
            noClasses().that().resideInAPackage("com.ontos.framework..")
                    .should().dependOnClassesThat().resideInAPackage("com.ontos.module..")
                    .because("framework 是底层能力，反向依赖会形成环");

    @ArchTest
    static final ArchRule ontology_must_not_depend_on_other_modules =
            noClasses().that().resideInAPackage("com.ontos.module.ontology..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.ontos.module.query..", "com.ontos.module.system..")
                    .because("ontology 是被 query 消费的底层资产域");

    @ArchTest
    static final ArchRule system_must_not_depend_on_other_modules =
            noClasses().that().resideInAPackage("com.ontos.module.system..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.ontos.module.query..", "com.ontos.module.ontology..",
                            "com.ontos.module.session..")
                    .because("system 提供策略与审计，不得反向依赖业务域");

    @ArchTest
    static final ArchRule session_must_not_depend_on_agent =
            noClasses().that().resideInAPackage("com.ontos.module.session..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.ontos.module.agent..")
                    .because("编排（agent）依赖会话（session），反向会成环；session 允许依赖 query/ontology/system");

    @ArchTest
    static final ArchRule ontology_must_not_depend_on_session =
            noClasses().that().resideInAPackage("com.ontos.module.ontology..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.ontos.module.session..")
                    .because("ontology 是底层资产域，不感知会话");

    @ArchTest
    static final ArchRule catalog_must_not_depend_on_runtime_modules =
            noClasses().that().resideInAPackage("com.ontos.module.catalog..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.ontos.module.query..", "com.ontos.module.system..",
                            "com.ontos.module.session..", "com.ontos.module.agent..")
                    .because("catalog 是管理面资产域；运行时只读已发布清单，"
                            + "反向依赖会让草稿/生命周期表混入查询链路（M6 规格 §3.0 G-1）");

    @ArchTest
    static final ArchRule only_server_may_depend_on_catalog =
            noClasses().that().resideInAnyPackage("com.ontos.module.ontology..",
                            "com.ontos.module.query..", "com.ontos.module.system..",
                            "com.ontos.module.session..", "com.ontos.module.agent..")
                    .should().dependOnClassesThat().resideInAPackage("com.ontos.module.catalog..")
                    .because("除 server 装配层外，任何运行时模块都不得依赖 catalog；"
                            + "Scenario/Skill/VQR 的运行时来源只能是 PublishedManifest");

    @ArchTest
    static final ArchRule controllers_must_not_touch_dal =
            noClasses().that().resideInAPackage("..controller..")
                    .should().dependOnClassesThat().resideInAPackage("..dal..")
                    .because("控制器只能经 service 访问数据层");

    @ArchTest
    static final ArchRule core_must_not_depend_on_controllers =
            noClasses().that().resideInAPackage("..core..")
                    .should().dependOnClassesThat().resideInAPackage("..controller..")
                    .because("确定性编译内核不得感知 Web 层");
}
