package com.ontos.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 多模块依赖方向守护。
 *
 * <p>允许方向：server → module / framework；module-query → module-ontology / module-system；
 * 所有模块 → framework。framework 不得反向依赖任何业务模块，业务模块之间不得出现环形依赖。
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
                    .resideInAnyPackage("com.ontos.module.query..", "com.ontos.module.ontology..")
                    .because("system 提供策略与审计，不得反向依赖业务域");

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
