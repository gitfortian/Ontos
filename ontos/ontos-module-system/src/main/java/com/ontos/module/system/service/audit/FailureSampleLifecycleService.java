package com.ontos.module.system.service.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ontos.module.system.dal.dataobject.EvaluationFailureSampleDO;
import com.ontos.module.system.dal.mysql.EvaluationFailureSampleLifecycleMapper;
import com.ontos.module.system.dal.mysql.EvaluationFailureSampleMapper;
import com.ontos.module.system.enums.FailureCauseCategory;
import com.ontos.module.system.enums.FailureSampleStatus;
import org.springframework.stereotype.Service;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.system.enums.ErrorCodeConstants.FAILURE_SAMPLE_CATEGORY_INVALID;
import static com.ontos.module.system.enums.ErrorCodeConstants.FAILURE_SAMPLE_INVALID;
import static com.ontos.module.system.enums.ErrorCodeConstants.FAILURE_SAMPLE_REGRESSION_INVALID;
import static com.ontos.module.system.enums.ErrorCodeConstants.FAILURE_SAMPLE_TRANSITION_INVALID;

/**
 * 失败样本生命周期（M7/W4/T4.1–T4.3，D-22）：状态流转、根因分类、退回回归集。
 *
 * <p>核心约束：
 * <ul>
 *   <li>V17 样本表只追加，不改写；当前态由状态事件最新一条派生（无事件 = OPEN）；</li>
 *   <li>迁移表封闭（{@link FailureSampleStatus#canTransitionTo}），非法迁移 fail-closed；</li>
 *   <li>{@code REGRESSED} 只能由回流通道写入，普通状态流转拒绝；</li>
 *   <li>回流目标只允许 {@code dev} / {@code validation}——<b>禁止 test</b>（test 集只能升版）。</li>
 * </ul>
 */
@Service
public class FailureSampleLifecycleService {

    /** 允许的回归集（test 集冻结，只能升版，禁止直接写入）。 */
    static final Set<String> REGRESSION_TARGETS = Set.of("dev", "validation");

    private final EvaluationFailureSampleMapper sampleMapper;
    private final EvaluationFailureSampleLifecycleMapper lifecycleMapper;

    public FailureSampleLifecycleService(EvaluationFailureSampleMapper sampleMapper,
                                         EvaluationFailureSampleLifecycleMapper lifecycleMapper) {
        this.sampleMapper = sampleMapper;
        this.lifecycleMapper = lifecycleMapper;
    }

    /** 状态流转（REGRESSED 目标被拒绝——必须走 {@link #returnToRegression}）。 */
    public Map<String, Object> transition(String tenantId, long sampleId, String toStatus,
                                          String actor, String note) {
        requireSample(tenantId, sampleId);
        FailureSampleStatus target = FailureSampleStatus.fromCode(toStatus);
        if (target == null) {
            throw exception(FAILURE_SAMPLE_TRANSITION_INVALID, "未知状态: " + toStatus);
        }
        if (target == FailureSampleStatus.REGRESSED) {
            throw exception(FAILURE_SAMPLE_TRANSITION_INVALID,
                    "regressed 只能通过「退回回归集」通道写入");
        }
        FailureSampleStatus current = currentStatus(tenantId, sampleId);
        if (!current.canTransitionTo(target)) {
            throw exception(FAILURE_SAMPLE_TRANSITION_INVALID,
                    current.code() + " -> " + target.code());
        }
        lifecycleMapper.insertStatusEvent(tenantId, sampleId, current.code(), target.code(),
                actor, note);
        // 直接返回目标态：不依赖「刚写入的事件已可见」的读一致性（写后读在只追加事件表上无必要）
        return statusView(sampleId, target, lifecycleMapper.selectLatestCategory(tenantId, sampleId));
    }

    /** 根因分类（闭词表；只追加分类事件，当前分类由最新事件派生）。 */
    public Map<String, Object> classify(String tenantId, long sampleId, String categoryCode,
                                        String actor, String note) {
        requireSample(tenantId, sampleId);
        FailureCauseCategory category = FailureCauseCategory.fromCode(categoryCode);
        if (category == null) {
            throw exception(FAILURE_SAMPLE_CATEGORY_INVALID, "未知分类: " + categoryCode);
        }
        lifecycleMapper.insertClassification(tenantId, sampleId, category.code(), actor, note);
        return statusView(sampleId, currentStatus(tenantId, sampleId), category.code());
    }

    /**
     * 退回回归集：要求当前态 FIXED；目标 ∈ {dev, validation}；写 FIXED→REGRESSED 事件，
     * 生成 regressionSampleId 供 runner/脚本落题集（本服务不直接改写 test 集文件）。
     */
    public Map<String, Object> returnToRegression(String tenantId, long sampleId,
                                                   String targetSet, String actor, String note) {
        requireSample(tenantId, sampleId);
        String target = targetSet == null ? "" : targetSet.trim().toLowerCase();
        if (!REGRESSION_TARGETS.contains(target)) {
            throw exception(FAILURE_SAMPLE_REGRESSION_INVALID,
                    "只允许 dev / validation（test 集冻结，只能升版）: " + targetSet);
        }
        FailureSampleStatus current = currentStatus(tenantId, sampleId);
        if (current != FailureSampleStatus.FIXED) {
            throw exception(FAILURE_SAMPLE_TRANSITION_INVALID,
                    "只有 fixed 状态可退回回归集，当前: " + current.code());
        }
        String regressionSampleId = "reg-" + sampleId + "-" + System.currentTimeMillis();
        String eventNote = "targetSet=" + target + "; regressionSampleId=" + regressionSampleId
                + (note == null || note.isBlank() ? "" : "; " + note.trim());
        lifecycleMapper.insertStatusEvent(tenantId, sampleId, current.code(),
                FailureSampleStatus.REGRESSED.code(), actor, eventNote);
        Map<String, Object> view = statusView(sampleId, FailureSampleStatus.REGRESSED,
                lifecycleMapper.selectLatestCategory(tenantId, sampleId));
        view.put("regressionSampleId", regressionSampleId);
        view.put("targetSet", target);
        return view;
    }

    /** 样本详情：基础字段 + 当前状态 + 最新分类 + 状态/分类事件历史。 */
    public Map<String, Object> detail(String tenantId, long sampleId, int eventLimit) {
        EvaluationFailureSampleDO sample = requireSample(tenantId, sampleId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", sample.getId());
        view.put("setId", sample.getSetId());
        view.put("questionId", sample.getQuestionId());
        view.put("runId", sample.getRunId());
        view.put("scenarioEntryMode", sample.getScenarioEntryMode());
        view.put("queryShape", sample.getQueryShape());
        view.put("turnType", sample.getTurnType());
        view.put("isComposite", sample.getIsComposite());
        view.put("riskType", sample.getRiskType());
        view.put("expectedTerminal", sample.getExpectedTerminal());
        view.put("actualTerminal", sample.getActualTerminal());
        view.put("errorCode", sample.getErrorCode());
        view.put("reason", sample.getReason());
        view.put("createdAt", sample.getCreatedAt());
        view.put("status", currentStatus(tenantId, sampleId).code());
        view.put("category", lifecycleMapper.selectLatestCategory(tenantId, sampleId));
        view.put("statusEvents", lifecycleMapper.selectStatusEvents(tenantId, sampleId, eventLimit));
        view.put("classifications", lifecycleMapper.selectClassifications(tenantId, sampleId,
                eventLimit));
        return view;
    }

    private Map<String, Object> statusView(long sampleId, FailureSampleStatus status,
                                           String category) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("sampleId", sampleId);
        view.put("status", status.code());
        view.put("category", category);
        return view;
    }

    /** 无事件 = OPEN（样本登记即进入待分诊，不额外写初始事件）。 */
    private FailureSampleStatus currentStatus(String tenantId, long sampleId) {
        String latest = lifecycleMapper.selectLatestStatus(tenantId, sampleId);
        FailureSampleStatus status = FailureSampleStatus.fromCode(latest);
        return status == null ? FailureSampleStatus.OPEN : status;
    }

    /** 存在性 + 租户校验：跨租户 ID 与不存在同形拒绝（不泄露存在性）。 */
    private EvaluationFailureSampleDO requireSample(String tenantId, long sampleId) {
        EvaluationFailureSampleDO sample = sampleMapper.selectById(tenantId, sampleId);
        if (sample == null) {
            throw exception(FAILURE_SAMPLE_INVALID, "样本不存在或不属于当前租户: " + sampleId);
        }
        return sample;
    }
}
