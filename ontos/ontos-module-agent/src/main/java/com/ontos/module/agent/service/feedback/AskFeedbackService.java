package com.ontos.module.agent.service.feedback;

import com.ontos.module.agent.dal.mysql.AskFeedbackMapper;
import org.springframework.stereotype.Service;

/**
 * ask 反馈服务（W3 结果看板 👍👎）。
 *
 * <p>fail-closed：rating / reason_code 都是封闭枚举，越界值直接拒绝（不静默归一）；
 * runId 与 requestId 至少要有其一——无法归属的反馈不收（脏数据比缺数据更害人）。
 */
@Service
public class AskFeedbackService {

    /** rating 封闭枚举。 */
    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    /** DOWN 细分原因码（前端固定四项，码值随本类冻结）。 */
    public static final String REASON_WRONG_DATA = "WRONG_DATA";
    public static final String REASON_WRONG_CALIBER = "WRONG_CALIBER";
    public static final String REASON_NOT_ANSWERED = "NOT_ANSWERED";
    public static final String REASON_OTHER = "OTHER";

    private final AskFeedbackMapper mapper;

    public AskFeedbackService(AskFeedbackMapper mapper) {
        this.mapper = mapper;
    }

    /** 提交反馈；参数非法抛 IllegalArgumentException（由全局异常层转 400）。 */
    public long submit(Request request) {
        if (!UP.equals(request.rating()) && !DOWN.equals(request.rating())) {
            throw new IllegalArgumentException("rating 仅支持 UP/DOWN");
        }
        boolean hasTarget = (request.runId() != null && !request.runId().isBlank())
                || (request.requestId() != null && !request.requestId().isBlank());
        if (!hasTarget) {
            throw new IllegalArgumentException("runId 与 requestId 至少提供其一");
        }
        String reasonCode = blankToNull(request.reasonCode());
        if (reasonCode != null && !isKnownReason(reasonCode)) {
            throw new IllegalArgumentException("未知的 reasonCode: " + reasonCode);
        }
        if (UP.equals(request.rating()) && reasonCode != null && !reasonCode.isBlank()) {
            throw new IllegalArgumentException("UP 反馈不携带 reasonCode");
        }
        String comment = request.comment();
        if (comment != null && comment.length() > 500) {
            throw new IllegalArgumentException("comment 超长（上限 500 字）");
        }
        return mapper.insert(request.tenantId(), request.userId(), request.sessionId(),
                blankToNull(request.runId()), blankToNull(request.requestId()),
                request.rating(), blankToNull(reasonCode), blankToNull(comment));
    }

    private static boolean isKnownReason(String reasonCode) {
        return REASON_WRONG_DATA.equals(reasonCode) || REASON_WRONG_CALIBER.equals(reasonCode)
                || REASON_NOT_ANSWERED.equals(reasonCode) || REASON_OTHER.equals(reasonCode);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 反馈请求（归属三件套由编排上下文填充，不由前端声明）。 */
    public record Request(String tenantId, String userId, String sessionId,
                          String runId, String requestId,
                          String rating, String reasonCode, String comment) {
    }
}
