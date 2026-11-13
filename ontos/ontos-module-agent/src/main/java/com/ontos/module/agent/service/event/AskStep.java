package com.ontos.module.agent.service.event;

/**
 * ask 单轮编排的过程步骤（M5 过程可见性扩展）。
 *
 * <p>步骤是**链路事实**，不是前端推测的进度：每个 stepKey 对应 {@code AskOrchestrationService}
 * 里一段真实执行逻辑，status 只有 running / done / skipped / failed 四态。
 * 文案（{@link #title()}）由服务端下发，前端不拼装——避免前端自造业务话术。
 *
 * <p><b>不是线性流程</b>：模型修复轮、澄清回跑、HITL 放行重放都会让同一步骤在一个 run 内
 * 出现多次，也可能整段跳过。前端按事件 {@code seq} 顺序逐条追加，**不做步骤补齐、不显示
 * 总体进度或「第 N / 共 M 步」**——进度条会把循环与回退画成假象。
 * {@link #ordinal()} 只用于同 seq 下的稳定排序，不代表进度，更不能用来算完成度。
 */
public enum AskStep {

    /** 模型（或确定性降级）把话术解析成结构化提案。 */
    UNDERSTAND("understand", "理解问题"),
    /** 授权范围内检索命中的指标 / 维度 / 场景候选。 */
    RETRIEVE("retrieve", "匹配已发布资产"),
    /** 场景与查询模板路由（未命中即跳过，不是失败）。 */
    ROUTE("route", "场景与查询模板"),
    /** 装配 Query DSL（指标 + 维度 + 过滤 + 时间窗）。 */
    COMPOSE("compose", "构造查询"),
    /** 输出契约与来源完整性校验。 */
    VALIDATE("validate", "校验"),
    /** 编译并执行只读查询（工具调用）。 */
    EXECUTE("execute", "编译并执行"),
    /** 审计落库（与查询同一事务，执行成功即已记录）。 */
    AUDIT("audit", "记录审计"),
    /** 装配结构化终态。 */
    ASSEMBLE("assemble", "汇总结果");

    private final String stepKey;
    private final String title;

    AskStep(String stepKey, String title) {
        this.stepKey = stepKey;
        this.title = title;
    }

    public String stepKey() {
        return stepKey;
    }

    public String title() {
        return title;
    }

    /** 步骤状态（封闭四态；前端不得自行扩展「进行中」等别名）。 */
    public enum Status {
        RUNNING("running"),
        DONE("done"),
        SKIPPED("skipped"),
        FAILED("failed");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
