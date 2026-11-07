package com.ontos.framework.common.exception.enums;

import com.ontos.framework.common.exception.ErrorCode;

/**
 * 全局错误码枚举：0-999 系统异常编码保留。
 *
 * <p>一般情况下使用 HTTP 响应状态码；成功固定为 0。
 *
 * <p>来源：yudao-boot-mini {@code cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants}。
 */
public interface GlobalErrorCodeConstants {

    ErrorCode SUCCESS = new ErrorCode(0, "成功");

    // ========== 客户端错误段 ==========

    ErrorCode BAD_REQUEST = new ErrorCode(400, "请求参数不正确");
    ErrorCode UNAUTHORIZED = new ErrorCode(401, "账号未登录");
    ErrorCode FORBIDDEN = new ErrorCode(403, "没有该操作权限");
    ErrorCode NOT_FOUND = new ErrorCode(404, "请求未找到");
    ErrorCode METHOD_NOT_ALLOWED = new ErrorCode(405, "请求方法不正确");
    ErrorCode LOCKED = new ErrorCode(423, "请求失败，请稍后重试");
    ErrorCode TOO_MANY_REQUESTS = new ErrorCode(429, "请求过于频繁，请稍后重试");

    // ========== 服务端错误段 ==========

    ErrorCode INTERNAL_SERVER_ERROR = new ErrorCode(500, "系统异常");
    ErrorCode NOT_IMPLEMENTED = new ErrorCode(501, "功能未实现/未开启");
    ErrorCode ERROR_CONFIGURATION = new ErrorCode(502, "错误的配置项");

    // ========== 自定义错误段 ==========

    ErrorCode REPEATED_REQUESTS = new ErrorCode(900, "重复请求，请稍后重试");
    ErrorCode UNKNOWN = new ErrorCode(999, "未知错误");

}
