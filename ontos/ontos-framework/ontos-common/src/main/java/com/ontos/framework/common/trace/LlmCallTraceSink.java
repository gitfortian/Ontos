package com.ontos.framework.common.trace;

/**
 * 模型调用追踪落点（M7/W5/T5.1）。
 *
 * <p>实现由业务模块提供（当前是 system 的 {@code LlmCallTraceService}），框架只依赖本接口——
 * starter 不得反向依赖业务模块（ArchUnit {@code framework_must_not_depend_on_business_modules}）。
 *
 * <p>实现必须自行吞掉持久化异常：**观测写失败不得影响业务链路**。
 */
public interface LlmCallTraceSink {

    void record(LlmCallRecord record);
}
