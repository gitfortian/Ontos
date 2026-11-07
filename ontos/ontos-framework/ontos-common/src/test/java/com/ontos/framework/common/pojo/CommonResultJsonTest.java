package com.ontos.framework.common.pojo;

import com.ontos.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonResultJsonTest {

    @Test
    void shouldOnlySerializeThreeFields() {
        String json = JsonUtils.toJsonString(CommonResult.success("ok"));
        assertTrue(json.contains("\"code\":0"), json);
        assertTrue(json.contains("\"data\":\"ok\""), json);
        assertFalse(json.contains("success"), "@JsonIgnore 必须阻止派生属性进入 JSON: " + json);
        assertFalse(json.contains("checkedData"), "getCheckedData 不得被序列化: " + json);
    }

    @Test
    void shouldRoundTrip() {
        CommonResult<String> result = CommonResult.success("payload");
        CommonResult<?> parsed = JsonUtils.parseObject(JsonUtils.toJsonString(result), CommonResult.class);
        assertEquals(0, parsed.getCode());
        assertTrue(CommonResult.isSuccess(parsed.getCode()));
    }

    @Test
    void shouldFormatErrorCodeMessage() {
        CommonResult<Void> result = CommonResult.error(1001, "参数 {} 非法");
        assertEquals(Integer.valueOf(1001), result.getCode());
        assertEquals("参数 {} 非法", result.getMsg());
        assertTrue(result.isError());
    }
}
