package com.ontos.module.query.core.scenario;

import com.ontos.framework.web.core.context.RequestContext;

/**
 * DSL {@code scenarioId} 服务端裁决端口（M6/W2/T2.4，ADR-0017 D1）。
 *
 * <p>直连查询 API 的请求 DTO 白名单已含 {@code scenarioId}（显式选择场景的唯一入口）；
 * 本端口由 session 模块实现（瀑布宿主持有会话状态与授权链路），server 装配注入。
 * query 模块只依赖接口，不反向依赖 session（ArchUnit 边界）。
 *
 * <p>裁决语义（D1 四步，M6 全量深度审核 D-1 修复）：① 提交 scenarioId → ② 授权 + 已发布校验
 * （失败即拒，不静默回退）→ ③ 分析与口径校验（模式白名单 / scope 交集 / frozen_calibers）
 * → ④ **全部校验（含 DSL、策略、时间解析）通过后才由调用方提交会话状态**。
 * {@link #adjudicate} 是纯前置校验、不写会话状态；状态提交由 {@link #commitExplicitRoute} 在
 * prepare 全链成功后执行——保证「失败即拒、目标状态不变」，避免越界/非法请求把会话污染成
 * in_scenario 后影响后续自由问数的授权裁剪。
 */
public interface ScenarioRouteGuard {

    /**
     * @param context             请求上下文（授权裁剪按 tenant+roles+userId）
     * @param scenarioId          DSL 直传的场景 ID；空/null = 自由问数语义，直接放行（不改会话状态）
     * @param mode                DSL 分析模式（{@code null} 允许——LOOKUP 等模式由下游校验补齐后再断言）
     * @param timeRangeField      DSL 显式时间绑定字段（{@code timeRange.field}；无显式绑定为 {@code null}）
     * @param referencedAssetIds  DSL 引用的语义资产 ID（metric/dimension/filter 字段/属性/实体）；
     *                            显式带场景时做 scope 交集——**引用场景外资产一律拒绝**（T24-5：
     *                            场景是业务域切片，不是入口标签；授权之外再收一层场景边界）
     * @throws com.ontos.framework.common.exception.ServiceException 未发布 / 未授权 /
     *         模式越界 / 口径冲突 / 引用越界 / 状态迁移非法（业务码，fail-closed 不回退）
     */
    void adjudicate(RequestContext context, String scenarioId, String mode, String timeRangeField,
                    java.util.Collection<String> referencedAssetIds);

    /**
     * 提交显式场景路由状态（D1 第 ④ 步，M6 全量深度审核 D-1 修复）：
     * 仅在 {@link #adjudicate} 与 prepare 全链（manifest / 时间解析 / DSL 校验 / 策略）都成功后调用，
     * 显式进入/切换场景落会话状态。scenarioId 为空 = 自由问数语义，不写状态。
     *
     * <p>默认空实现：无场景实现的装配（单测 no-op 桩）按「不迁移状态」处理。
     */
    default void commitExplicitRoute(RequestContext context, String scenarioId) {
        // no-op
    }

    /**
     * 当前会话的场景 ID（in_scenario 态返回场景 ID；free / pending_confirm / 无状态返回 null）。
     * T3.2 编译期校验第 2 条（allowed_scenario_ids 语义）消费；default null 兜底——
     * 无场景实现的装配（单测 no-op 桩）按「不在任何场景内」处理，fail-closed。
     */
    default String currentScenarioId(RequestContext context) {
        return null;
    }

    /**
     * 最近一次场景路由决策的审计快照（V15 三列）。query 模块自有类型——session 侧实现把
     * 会话状态投影到这里，避免 query 反向依赖 session（ArchUnit 边界）。
     *
     * <p>{@code recommendationConfidence} 按 M6 规格决策 4「确定性口径、不引入 LLM 置信度
     * 数值」恒为 null；保留字段是为了让审计写入端与 DTO/表结构同形，不得填造数值。
     */
    record DecisionAudit(String entrySource, Boolean confirmByUser,
                         java.math.BigDecimal recommendationConfidence) {
    }

    /**
     * 读会话中最近一次路由决策（决策期信息）。default null 兜底：无场景实现的装配按
     * 「无决策信息」处理，审计列留空而不猜。
     */
    default DecisionAudit lastDecision(RequestContext context) {
        return null;
    }
}
