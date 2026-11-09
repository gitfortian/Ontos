package com.ontos.module.system.service.security;

import com.ontos.framework.web.core.context.RequestContext;

/**
 * 提示注入检查点（M4/W4，规格 §3.4 / E5-②：接口签名与审计 action 冻结，M5 编排调用）。
 *
 * <p><b>接口契约（冻结，M5 接入不得修改签名）</b>：
 *
 * <pre>{@code
 * void onSuspected(RequestContext context, String source, String sample, String detail);
 * }</pre>
 *
 * <ul>
 *   <li>{@code context}：认证上下文（身份只来自这里，调用方不得传入可被用户文本影响的身份）；</li>
 *   <li>{@code source}：检测来源，封闭枚举值——{@code CANDIDATE_OUT_OF_SET}（编排候选集外 ID）、
 *       {@code PROMPT_TEXT}（用户文本注入启发）、{@code TOOL_ARGS}（工具槽位注入启发）；</li>
 *   <li>{@code sample}：命中片段（截断到 200 字符，脱敏后入审计）；</li>
 *   <li>{@code detail}：机器可读判定细节（如候选集外 ID 值）。</li>
 * </ul>
 *
 * <p>桩实现 {@link AuditBackedInjectionCheckpoint} 只做审计（action=INJECTION_SUSPECTED）
 * 不做拦截——拦截动作（终止 run / 转人工）由 M5 编排根据编排语义决定；M4 只保证
 * 「可验证就绪」：桩在单测中被调用并产出审计记录，M5 接入无需修改本签名。
 */
public interface InjectionCheckpoint {

    String SOURCE_CANDIDATE_OUT_OF_SET = "CANDIDATE_OUT_OF_SET";
    String SOURCE_PROMPT_TEXT = "PROMPT_TEXT";
    String SOURCE_TOOL_ARGS = "TOOL_ARGS";

    String AUDIT_ACTION = "INJECTION_SUSPECTED";

    void onSuspected(RequestContext context, String source, String sample, String detail);
}
