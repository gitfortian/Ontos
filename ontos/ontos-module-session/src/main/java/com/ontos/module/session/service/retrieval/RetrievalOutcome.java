package com.ontos.module.session.service.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 检索瀑布的单轮产出。
 *
 * <p>分层语义（「模糊候选进 DSL=0」硬断言的数据基础）：
 * <ul>
 *   <li>{@code dslReady}：L1/L2 命中（fuzzy=false），允许作为本轮 DSL 组装的实体输入；</li>
 *   <li>{@code fuzzyOnly}：仅 L3 命中的候选（fuzzy=true），**只能**出现在澄清候选里，
 *       任何路径不得进入 DSL；</li>
 *   <li>{@code prevTurn}：本轮无显式提及、由会话投影（resolved_entities）给出的指代候选——
 *       已按授权与清单存在性复核，失效投影不会出现在这里（失效走 {@code projectionStale}）。</li>
 * </ul>
 *
 * @param dslReady         可直接进 DSL 的候选（L1/L2，已授权）
 * @param fuzzyOnly        仅模糊层命中（不得进 DSL，仅澄清候选）
 * @param prevTurn         指代消解候选（来自会话投影，已复核有效）
 * @param clarifications   概念歧义澄清（四要素；空 = 本轮无需澄清）
 * @param projectionStale  会话投影失效（资产被降权/清单变化）——需要向用户澄清或重新发现
 * @param contextHash      候选集内容 SHA-256（清单 contentHash + 授权集合 + 词表），供
 *                         Proposal 来源对账（候选集外 ID → InjectionCheckpoint）
 * @param scenario         场景路由上下文（M6/W2/T2.4：in_scenario 时的场景 ID/入口来源/
 *                         allowed_analysis_modes/frozen_calibers；free/pending 为 null）
 */
public record RetrievalOutcome(List<RetrievedCandidate> dslReady,
                               List<RetrievedCandidate> fuzzyOnly,
                               List<RetrievedCandidate> prevTurn,
                               List<ClarificationPayload> clarifications,
                               boolean projectionStale,
                               String contextHash,
                               ScenarioContext scenario,
                               List<VqrCandidate> vqrCandidates) {

    /** 空结果（无候选/无澄清/无场景/无 VQR）。 */
    public static RetrievalOutcome empty() {
        return new RetrievalOutcome(List.of(), List.of(), List.of(), List.of(), false, "",
                null, List.of());
    }

    /** 旧签名兼容构造器（场景上下文缺省 = 无场景语义）。 */
    public RetrievalOutcome(List<RetrievedCandidate> dslReady,
                            List<RetrievedCandidate> fuzzyOnly,
                            List<RetrievedCandidate> prevTurn,
                            List<ClarificationPayload> clarifications,
                            boolean projectionStale,
                            String contextHash) {
        this(dslReady, fuzzyOnly, prevTurn, clarifications, projectionStale, contextHash, null, List.of());
    }

    /** 兼容构造器（VQR 候选缺省 = 瀑布未启用第 7 步或未命中）。 */
    public RetrievalOutcome(List<RetrievedCandidate> dslReady,
                            List<RetrievedCandidate> fuzzyOnly,
                            List<RetrievedCandidate> prevTurn,
                            List<ClarificationPayload> clarifications,
                            boolean projectionStale,
                            String contextHash,
                            ScenarioContext scenario) {
        this(dslReady, fuzzyOnly, prevTurn, clarifications, projectionStale, contextHash,
                scenario, List.of());
    }

    public RetrievalOutcome {
        dslReady = dslReady == null ? List.of() : List.copyOf(dslReady);
        fuzzyOnly = fuzzyOnly == null ? List.of() : List.copyOf(fuzzyOnly);
        prevTurn = prevTurn == null ? List.of() : List.copyOf(prevTurn);
        clarifications = clarifications == null ? List.of() : List.copyOf(clarifications);
    }

    /** Top-5 候选视图（PoC #5 口径）：dslReady + fuzzyOnly 合并排序后的前 5。 */
    public List<RetrievedCandidate> top5() {
        List<RetrievedCandidate> merged = new java.util.ArrayList<>(dslReady);
        merged.addAll(fuzzyOnly);
        java.util.Collections.sort(merged);
        return merged.stream().limit(5).toList();
    }

    /** 是否完全无候选（连模糊/指代都没有）——编排层据此走能力发现或拒答。 */
    public boolean isEmpty() {
        return dslReady.isEmpty() && fuzzyOnly.isEmpty() && prevTurn.isEmpty();
    }

    /**
     * VQR 命中候选（M6/W3/T3.5，瀑布第 7 步）：questionPattern 与话术确定性匹配的
     * 参数化模板；候选带**已提取的参数**，编排提案引用 vqrId（候选集外引用被完整性闸门拦截）。
     */
    public record VqrCandidate(String vqrId,
                               String name,
                               Map<String, String> params,
                               String matchedPattern) {
        public VqrCandidate {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** 场景路由上下文（M6/W2/T2.4）。 */
    public record ScenarioContext(String scenarioId,
                                  String entrySource,
                                  List<String> allowedModes,
                                  Map<String, String> calibers) {
        public ScenarioContext {
            allowedModes = allowedModes == null ? List.of() : List.copyOf(allowedModes);
            calibers = calibers == null ? Map.of() : Map.copyOf(calibers);
        }
    }

}
