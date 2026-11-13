package com.ontos.module.catalog.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.ontos.module.ontology.service.publish.PublishService;

/**
 * 测试桩发布服务：记录请求、可编排 alreadyExists / 校验拒绝，其余路径返回成功回执。
 * 真实校验逻辑由 PublishServiceImpl 的既有测试覆盖，这里只测合成服务的编排行为。
 */
public class StubPublishService implements PublishService {

    private final List<PublishRequest> requests = new ArrayList<>();
    private Predicate<String> alreadyExistsFor = id -> false;
    private List<String> violations = List.of();

    public List<PublishRequest> requests() {
        return List.copyOf(requests);
    }

    /** 命中谓词的 manifestId 返回 alreadyExists=true（模拟 ID 占用，驱动递增重试）。 */
    public void alreadyExistsFor(Predicate<String> predicate) {
        this.alreadyExistsFor = predicate;
    }

    /** 让下一次校验以这些 violations 拒绝。 */
    public void rejectWith(String... violations) {
        this.violations = List.of(violations);
    }

    @Override
    public PublishReportVO publish(PublishRequest request) {
        requests.add(request);
        if (alreadyExistsFor.test(request.manifestId())) {
            return new PublishReportVO(request.manifestId(), false, List.of(), true, null,
                    Map.of(), List.of());
        }
        if (!violations.isEmpty()) {
            return new PublishReportVO(request.manifestId(), false, violations, false, null,
                    Map.of(), List.of());
        }
        return new PublishReportVO(request.manifestId(), true, List.of(), false,
                "hash-" + request.manifestId(), Map.of("concepts", 1), List.of());
    }

    @Override
    public List<Map<String, Object>> listPublishRecords(int limit) {
        return List.of();
    }
}
