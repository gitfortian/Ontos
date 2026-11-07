package com.ontos.framework.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.ontos.framework.agent.config.AgentProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 意图提案修复中间件：在推理轮输出下发后校验输出契约，不合法时注入修正反馈并重入推理。
 *
 * <p>基于现役 middleware 扩展点（{@link MiddlewareBase#onReasoning}），不用已标记删除的 legacy hook。
 * 行为设计：
 * <ul>
 *   <li>合法输出：逐事件透传，零干预——PoC-10 的流式时序（打字机）判据不受影响；</li>
 *   <li>不合法且未超修复次数：追加一轮真实推理（输入携带违规清单与修正指引，同样流式透传）；
 *       下发 {@code CUSTOM(intent_repair)} 事件供前端与探针识别「最后一条完整 JSON 才是权威提案」；</li>
 *   <li>超限：合成确定性拒答 JSON 文本块并下发 {@code CUSTOM(intent_repair_exhausted)}（fail-closed），
 *       不让脏输出成为最终提案。</li>
 * </ul>
 *
 * <p>修复轮对下游是新增文本块（新 replyId），客户端契约：**每次 run 的最后一条完整 JSON 为权威提案**。
 * 判据与阈值引用 ADR-0006；本类只做协议层修复，候选集语义白名单由 {@link IntentProposalContract} 注入。
 */
@Slf4j
public class IntentProposalRepairMiddleware implements MiddlewareBase {

    public static final String REPAIR_EVENT = "intent_repair";
    public static final String REPAIR_EXHAUSTED_EVENT = "intent_repair_exhausted";

    private final IntentProposalContract contract;
    private final int maxRepairs;

    public IntentProposalRepairMiddleware(IntentProposalContract contract, AgentProperties properties) {
        this.contract = contract;
        this.maxRepairs = Math.max(0, properties.getOutputMaxRepairs());
    }

    @Override
    public java.util.Set<ExtensionPoint> activePoints() {
        return java.util.EnumSet.of(ExtensionPoint.ON_REASONING);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context,
                                        ReasoningInput input,
                                        java.util.function.Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            AtomicInteger attempts = new AtomicInteger();
            return reasoningRound(input, next, attempts);
        });
    }

    /** 单轮透传 + 流末判定；不合法时在 defer 里递归开启修复轮。无文本增量的轮次（如工具调用）不校验。 */
    private Flux<AgentEvent> reasoningRound(ReasoningInput input,
                                            java.util.function.Function<ReasoningInput, Flux<AgentEvent>> next,
                                            AtomicInteger attempts) {
        StringBuilder text = new StringBuilder();
        return next.apply(input)
                .concatMap(event -> {
                    if (event instanceof TextBlockDeltaEvent delta && delta.getDelta() != null) {
                        text.append(delta.getDelta());
                    }
                    return Flux.just(event);
                })
                .concatWith(Flux.defer(() -> {
                    if (text.isEmpty()) {
                        // 本轮没有任何文本增量：工具调用等中间态，不是提案轮，跳过契约校验
                        return Flux.empty();
                    }
                    return verdict(input, next, attempts, text.toString());
                }));
    }

    private Flux<AgentEvent> verdict(ReasoningInput input,
                                     java.util.function.Function<ReasoningInput, Flux<AgentEvent>> next,
                                     AtomicInteger attempts, String text) {
        IntentProposalContract.ValidationResult result = contract.validate(text);
        if (result.valid()) {
            return Flux.empty();
        }
        int attempt = attempts.incrementAndGet();
        if (attempt > maxRepairs) {
            log.warn("[verdict][意图提案 {} 次未过契约，合成确定性拒答（fail-closed）] violations={}",
                    attempt, result.violations());
            return Flux.<AgentEvent>just(new CustomEvent(REPAIR_EXHAUSTED_EVENT,
                            Map.of("attempts", attempt, "violations", result.violations().stream()
                                    .map(IntentProposalContract.Violation::toString).toList())))
                    .concatWith(rejectBlock());
        }
        log.info("[verdict][意图提案未过契约，注入修正反馈进入修复轮（第 {}/{} 次）] violations={}",
                attempt, maxRepairs, result.violations());
        String feedback = IntentProposalContract.renderRepairFeedback(result.violations());
        ReasoningInput repairInput = new ReasoningInput(
                appendMessage(input.messages(), feedback), input.tools(), input.options());
        return Flux.<AgentEvent>just(new CustomEvent(REPAIR_EVENT,
                        Map.of("attempt", attempt, "violations", result.violations().stream()
                                .map(IntentProposalContract.Violation::toString).toList())))
                .concatWith(reasoningRound(repairInput, next, attempts));
    }

    private static List<Msg> appendMessage(List<Msg> messages, String feedback) {
        List<Msg> appended = new ArrayList<>(messages);
        appended.add(Msg.builder()
                .name("ontos-contract")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(feedback).build())
                .build());
        return appended;
    }

    /** 确定性拒答文本块：新 replyId（客户端契约=最后一条完整 JSON 为权威提案）。 */
    private static Flux<AgentEvent> rejectBlock() {
        String replyId = "repair-" + UUID.randomUUID();
        String blockId = replyId + "-0";
        String reject = IntentProposalContract.rejectFallback();
        return Flux.just(
                new TextBlockStartEvent(replyId, blockId),
                new TextBlockDeltaEvent(replyId, blockId, reject),
                new TextBlockEndEvent(replyId, blockId));
    }

}
