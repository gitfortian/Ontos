package com.ontos.framework.web.core.handler;

import java.util.List;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import com.ontos.framework.common.exception.ServiceException;
import com.ontos.framework.common.pojo.CommonResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;
import static com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR;
import static com.ontos.framework.common.exception.enums.GlobalErrorCodeConstants.METHOD_NOT_ALLOWED;

/**
 * 全局异常处理器，将 Exception 翻译成 CommonResult + 对应的异常编号。
 *
 * <p>来源：yudao-boot-mini {@code cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler}。
 * 已移除 yudao 内部基础设施依赖（错误日志 API、Security、WebFrameworkUtils），保留异常映射与日志风格。
 */
@RestControllerAdvice
@Order(0)
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public CommonResult<?> missingServletRequestParameterExceptionHandler(MissingServletRequestParameterException ex) {
        log.warn("[missingServletRequestParameterExceptionHandler]", ex);
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数缺失:%s", ex.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public CommonResult<?> methodArgumentTypeMismatchExceptionHandler(MethodArgumentTypeMismatchException ex) {
        log.warn("[methodArgumentTypeMismatchExceptionHandler]", ex);
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数类型错误:%s", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResult<?> methodArgumentNotValidExceptionHandler(MethodArgumentNotValidException ex) {
        log.warn("[methodArgumentNotValidExceptionHandler]", ex);
        String errorMessage = null;
        FieldError fieldError = ex.getBindingResult().getFieldError();
        if (fieldError == null) {
            List<ObjectError> allErrors = ex.getBindingResult().getAllErrors();
            if (CollUtil.isNotEmpty(allErrors)) {
                errorMessage = allErrors.get(0).getDefaultMessage();
            }
        } else {
            errorMessage = fieldError.getDefaultMessage();
        }
        if (StrUtil.isEmpty(errorMessage)) {
            return CommonResult.error(BAD_REQUEST);
        }
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数不正确:%s", errorMessage));
    }

    @ExceptionHandler(BindException.class)
    public CommonResult<?> bindExceptionHandler(BindException ex) {
        log.warn("[handleBindException]", ex);
        FieldError fieldError = ex.getFieldError();
        assert fieldError != null;
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数不正确:%s", fieldError.getDefaultMessage()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public CommonResult<?> constraintViolationExceptionHandler(ConstraintViolationException ex) {
        log.warn("[constraintViolationExceptionHandler]", ex);
        ConstraintViolation<?> constraintViolation = ex.getConstraintViolations().iterator().next();
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求参数不正确:%s", constraintViolation.getMessage()));
    }

    @ExceptionHandler(ValidationException.class)
    public CommonResult<?> validationException(ValidationException ex) {
        log.warn("[validationException]", ex);
        return CommonResult.error(BAD_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public CommonResult<?> httpMessageNotReadableExceptionHandler(HttpMessageNotReadableException ex) {
        log.warn("[httpMessageNotReadableExceptionHandler]", ex);
        if (StrUtil.startWith(ex.getMessage(), "Required request body is missing")) {
            return CommonResult.error(BAD_REQUEST.getCode(), "请求参数类型错误: request body 缺失");
        }
        return CommonResult.error(BAD_REQUEST.getCode(), "请求参数格式错误");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public CommonResult<?> httpRequestMethodNotSupportedExceptionHandler(HttpRequestMethodNotSupportedException ex) {
        log.warn("[httpRequestMethodNotSupportedExceptionHandler]", ex);
        return CommonResult.error(METHOD_NOT_ALLOWED.getCode(), String.format("请求方法不正确:%s", ex.getMessage()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public CommonResult<?> httpMediaTypeNotSupportedExceptionHandler(HttpMediaTypeNotSupportedException ex) {
        log.warn("[httpMediaTypeNotSupportedExceptionHandler]", ex);
        return CommonResult.error(BAD_REQUEST.getCode(), String.format("请求类型不正确:%s", ex.getMessage()));
    }

    @ExceptionHandler(value = ServiceException.class)
    public CommonResult<?> serviceExceptionHandler(ServiceException ex) {
        StackTraceElement[] stackTraces = ex.getStackTrace();
        for (StackTraceElement stackTrace : stackTraces) {
            if (!stackTrace.getClassName().equals(ServiceException.class.getName())) {
                log.warn("[serviceExceptionHandler]\n\t{}", stackTrace);
                break;
            }
        }
        if (CommonResult.isSuccess(ex.getCode())) {
            return CommonResult.error(INTERNAL_SERVER_ERROR);
        }
        return CommonResult.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(value = Exception.class)
    public CommonResult<?> defaultExceptionHandler(HttpServletRequest req, Throwable ex) {
        log.error("[defaultExceptionHandler][uri({})]", req.getRequestURI(), ex);
        return CommonResult.error(INTERNAL_SERVER_ERROR.getCode(),
                StrUtil.blankToDefault(ExceptionUtil.getRootCauseMessage(ex), INTERNAL_SERVER_ERROR.getMsg()));
    }

}
