package com.ontos.framework.common.util.json;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 工具类。
 *
 * <p>来源：yudao-boot-mini {@code cn.iocoder.yudao.framework.common.util.json.JsonUtils}。
 * <b>唯一改动</b>：Jackson 2 的 {@code com.fasterxml.jackson.databind} 迁移到 Jackson 3 的
 * {@code tools.jackson.databind}（Spring Boot 4 默认 JSON 栈），方法签名与调用方保持一致。
 *
 * <p>应用启动时由 {@code JsonUtilsInitializer} 注入 Spring 管理的 ObjectMapper，
 * 保证与 MVC 序列化使用同一套配置。
 */
@Slf4j
public class JsonUtils {

    @Getter
    private static ObjectMapper objectMapper = buildObjectMapper();

    private static ObjectMapper buildObjectMapper() {
        return JsonMapper.builder()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * 使用 Spring 创建的 ObjectMapper Bean 替换默认实例。
     */
    public static void init(ObjectMapper objectMapper) {
        JsonUtils.objectMapper = objectMapper;
    }

    @SneakyThrows
    public static String toJsonString(Object object) {
        return objectMapper.writeValueAsString(object);
    }

    @SneakyThrows
    public static byte[] toJsonByte(Object object) {
        return objectMapper.writeValueAsBytes(object);
    }

    public static <T> T parseObject(String text, Class<T> clazz) {
        if (StrUtil.isEmpty(text)) {
            return null;
        }
        try {
            return objectMapper.readValue(text, clazz);
        } catch (JacksonException e) {
            log.error("json parse err,json:{}", text, e);
            throw new IllegalArgumentException(e);
        }
    }

    public static <T> T parseObject(String text, Type type) {
        if (StrUtil.isEmpty(text)) {
            return null;
        }
        try {
            return objectMapper.readValue(text, objectMapper.getTypeFactory().constructType(type));
        } catch (JacksonException e) {
            log.error("json parse err,json:{}", text, e);
            throw new IllegalArgumentException(e);
        }
    }

    public static <T> T parseObject(byte[] bytes, Class<T> clazz) {
        if (ArrayUtil.isEmpty(bytes)) {
            return null;
        }
        try {
            return objectMapper.readValue(bytes, clazz);
        } catch (JacksonException e) {
            log.error("json parse err", e);
            throw new IllegalArgumentException(e);
        }
    }

    public static <T> T parseObject(String text, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(text, typeReference);
        } catch (JacksonException e) {
            log.error("json parse err,json:{}", text, e);
            throw new IllegalArgumentException(e);
        }
    }

    public static <T> T parseObjectQuietly(String text, Class<T> clazz) {
        if (StrUtil.isEmpty(text)) {
            return null;
        }
        try {
            return objectMapper.readValue(text, clazz);
        } catch (JacksonException e) {
            return null;
        }
    }

    public static Map<String, Object> parseMap(String text) {
        if (StrUtil.isEmpty(text)) {
            return null;
        }
        try {
            return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            return null;
        }
    }

    public static <T> List<T> parseArray(String text, Class<T> clazz) {
        if (StrUtil.isEmpty(text)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(text, objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (JacksonException e) {
            log.error("json parse err,json:{}", text, e);
            throw new IllegalArgumentException(e);
        }
    }

    public static JsonNode parseTree(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JacksonException e) {
            log.error("json parse err,json:{}", text, e);
            throw new IllegalArgumentException(e);
        }
    }

    public static String getText(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    public static <T> T convertObject(Object obj, Class<T> clazz) {
        if (obj == null) {
            return null;
        }
        if (clazz.isInstance(obj)) {
            return clazz.cast(obj);
        }
        return objectMapper.convertValue(obj, clazz);
    }

}
