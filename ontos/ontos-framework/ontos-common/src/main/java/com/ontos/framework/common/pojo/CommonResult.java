package com.ontos.framework.common.pojo;

import java.io.Serializable;
import java.util.Objects;

import cn.hutool.core.lang.Assert;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ontos.framework.common.exception.ErrorCode;
import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.ontos.framework.common.exception.util.ServiceExceptionUtil;
import lombok.Data;

/**
 * 通用返回。
 *
 * <p>来源：yudao-boot-mini {@code cn.iocoder.yudao.framework.common.pojo.CommonResult}。
 *
 * @param <T> 数据泛型
 */
@Data
public class CommonResult<T> implements Serializable {

    /**
     * 错误码
     *
     * @see ErrorCode#getCode()
     */
    private Integer code;
    /**
     * 错误提示，用户可阅读
     *
     * @see ErrorCode#getMsg()
     */
    private String msg;
    /**
     * 返回数据
     */
    private T data;

    /**
     * 将传入的 result 对象，转换成另外一个泛型结果的对象。
     */
    public static <T> CommonResult<T> error(CommonResult<?> result) {
        return error(result.getCode(), result.getMsg());
    }

    public static <T> CommonResult<T> error(Integer code, String message) {
        Assert.notEquals(GlobalErrorCodeConstants.SUCCESS.getCode(), code, "code 必须是错误的！");
        CommonResult<T> result = new CommonResult<>();
        result.code = code;
        result.msg = message;
        return result;
    }

    public static <T> CommonResult<T> error(ErrorCode errorCode, Object... params) {
        Assert.notEquals(GlobalErrorCodeConstants.SUCCESS.getCode(), errorCode.getCode(), "code 必须是错误的！");
        CommonResult<T> result = new CommonResult<>();
        result.code = errorCode.getCode();
        result.msg = ServiceExceptionUtil.doFormat(errorCode.getCode(), errorCode.getMsg(), params);
        return result;
    }

    public static <T> CommonResult<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMsg());
    }

    public static <T> CommonResult<T> success(T data) {
        CommonResult<T> result = new CommonResult<>();
        result.code = GlobalErrorCodeConstants.SUCCESS.getCode();
        result.data = data;
        result.msg = "";
        return result;
    }

    public static boolean isSuccess(Integer code) {
        return Objects.equals(code, GlobalErrorCodeConstants.SUCCESS.getCode());
    }

    @JsonIgnore
    public boolean isSuccess() {
        return isSuccess(code);
    }

    @JsonIgnore
    public boolean isError() {
        return !isSuccess();
    }

    // ========= 和 Exception 异常体系集成 =========

    public void checkError() throws ServiceException {
        if (isSuccess()) {
            return;
        }
        throw new ServiceException(code, msg);
    }

    @JsonIgnore
    public T getCheckedData() {
        checkError();
        return data;
    }

    public static <T> CommonResult<T> error(ServiceException serviceException) {
        return error(serviceException.getCode(), serviceException.getMessage());
    }

}
