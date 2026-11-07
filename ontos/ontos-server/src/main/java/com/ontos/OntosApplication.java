package com.ontos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Ontos —— 基于本体语义的确定性问数服务。
 *
 * <p>单体应用，按组件边界做包级模块化：identity / catalog / ontology / semantic /
 * dsl / compiler / executor / session / guard / approval / audit / agent。
 * 依赖方向见根目录 AGENTS.md，由 ArchitectureTest 守护。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OntosApplication {

    public static void main(String[] args) {
        SpringApplication.run(OntosApplication.class, args);
    }
}
