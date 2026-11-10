package com.ontos.module.query.core.scenario;

import com.ontos.framework.web.core.context.RequestContext;

/**
 * DSL {@code scenarioId} 服务端裁决端口（M6/W2/T2.4，ADR-0017 D1）。
 *
 * <p>直连查询 API 的请求 DTO 白名单已含 {@code scenarioId}（显式选择场景的唯一入口）；
 * 本端口由 session 模块实现（瀑布宿主持有会话状态与授权链路），server 装配注入。
 * query 模块只依赖接口，不反向依赖 session（ArchUnit 边界）。
 *
 * <p>裁决语义（D1 四步）：① 提交 scenarioId → ② 授权 + 已发布校验（失败即拒，
 * 不静默回退）→ ③ 通过后作为 manual 路由输入落会话状态（显式进入/切换）
 * → ④ 校验通过即视为路由结果与 DSL 一致；另校验分析模式在场景
 * {@code allowed_analysis_modes} 内、时间绑定与 {@code frozen_calibers} 无冲突。
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
     * 当前会话的场景 ID（in_scenario 态返回场景 ID；free / pending_confirm / 无状态返回 null）。
     * T3.2 编译期校验第 2 条（allowed_scenario_ids 语义）消费；default null 兜底——
     * 无场景实现的装配（单测 no-op 桩）按「不在任何场景内」处理，fail-closed。
     */
    default String currentScenarioId(RequestContext context) {
        return null;
    }
}
