package com.ontos.framework.common.exception;

import com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants;
import lombok.Data;

/**
 * 错误码对象。
 *
 * <p>全局错误码占用 [0, 999]，参见 {@link GlobalErrorCodeConstants}；
 * 业务错误码占用 [1 000 000 000, +∞)，各模块在 {@code enums/ErrorCodeConstants} 中声明。
 *
 * <p>来源：yudao-boot-mini {@code cn.iocoder.yudao.framework.common.exception.ErrorCode}，
 * 按本项目包名调整，结构与用法保持一致。
 */
@Data
public class ErrorCode {

    /**
     * 错误码
     */
    private final Integer code;
    /**
     * 错误提示
     */
    private final String msg;

    public ErrorCode(Integer code, String message) {
        this.code = code;
        this.msg = message;
    }

}
