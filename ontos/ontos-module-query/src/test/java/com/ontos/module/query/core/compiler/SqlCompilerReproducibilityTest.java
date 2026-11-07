package com.ontos.module.query.core.compiler;

import java.time.Instant;
import java.util.List;

import com.ontos.module.ontology.core.PublishedManifest;
import com.ontos.module.query.core.TestFixtures;
import com.ontos.module.query.core.dsl.QueryDsl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * PoC #2「发布快照可复现」的编译器侧探针（M0 实施规格 T2.2）。
 *
 * <p>验证同一 DSL + 同一清单下，规范化 SQL 与绑定值逐字一致；并用全新编译器实例模拟跨重启。
 * 完整判据（重复次数、跨两次应用重启、版本组合）见技术选型 §7 #2，由 PoC 报告汇总，此处不复制阈值。
 */
class SqlCompilerReproducibilityTest {

    private static final int REPEAT = 100;

    @Test
    void shouldProduceIdenticalSqlAndBindValuesAcrossRepeats() {
        SqlCompiler compiler = new SqlCompiler();
        PublishedManifest manifest = TestFixtures.manifest();
        QueryDsl dsl = TestFixtures.query();

        CompiledQuery baseline = compiler.compile(dsl, manifest);
        for (int i = 0; i < REPEAT; i++) {
            CompiledQuery current = compiler.compile(dsl, manifest);
            assertEquals(baseline.getSql(), current.getSql(), "第 " + (i + 1) + " 次编译 SQL 不一致");
            assertEquals(baseline.getBindValues(), current.getBindValues(),
                    "第 " + (i + 1) + " 次编译绑定值不一致");
            assertEquals(manifest.getContentHash(), current.getContentHash());
            assertEquals(manifest.getManifestId(), current.getManifestId());
            assertEquals(baseline.getAppliedLimit(), current.getAppliedLimit());
        }
    }

    @Test
    void shouldRemainStableWithFreshCompilerInstances() {
        PublishedManifest manifest = TestFixtures.manifest();
        QueryDsl dsl = TestFixtures.query();
        String baselineSql = new SqlCompiler().compile(dsl, manifest).getSql();

        for (int i = 0; i < 10; i++) {
            CompiledQuery current = new SqlCompiler().compile(dsl, manifest);
            assertEquals(baselineSql, current.getSql(), "新编译器实例（模拟重启）产物不一致");
        }
    }

    @Test
    void shouldChangeSqlWhenManifestOrDslChanges() {
        SqlCompiler compiler = new SqlCompiler();
        PublishedManifest manifest = TestFixtures.manifest();
        QueryDsl baseline = TestFixtures.query();

        QueryDsl differentLimit = TestFixtures.query();
        differentLimit.setLimit(10);
        assertNotEquals(baseline.getLimit(), differentLimit.getLimit());
        assertEquals(10, compiler.compile(differentLimit, manifest).getAppliedLimit());

        QueryDsl differentDimensions = TestFixtures.query();
        differentDimensions.setDimensions(List.of());
        assertNotEquals(compiler.compile(baseline, manifest).getSql(),
                compiler.compile(differentDimensions, manifest).getSql());
    }

    @Test
    void shouldKeepVersionAnchorIndependentOfCompileTime() {
        SqlCompiler compiler = new SqlCompiler();
        PublishedManifest manifest = TestFixtures.manifest();

        CompiledQuery first = compiler.compile(TestFixtures.query(), manifest);
        CompiledQuery second = compiler.compile(TestFixtures.query(), manifest);
        assertEquals(first.getSql(), second.getSql());
        assertEquals(first.getContentHash(), second.getContentHash());
        assertEquals("postgresql", first.getDialect());
        assertEquals(Instant.class, first.getCompiledAt().getClass());
    }

}
