package com.ontos.module.datasource.core;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.ontos.framework.common.util.json.JsonUtils;

import static com.ontos.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_MASTER_KEY_MISSING;
import static com.ontos.module.datasource.enums.ErrorCodeConstants.DATASOURCE_SECRET_CIPHER_FAILED;

/**
 * 数据源凭据加密器（B2 Phase 2 / ADR-0025）：AES-256-GCM。
 *
 * <p>安全约束（不可协商）：
 * <ul>
 *   <li>master key 经 {@code ontos.datasource.master-key}（环境变量注入）提供，Base64 编码的
 *   32 字节 AES 密钥；**永不落库、不进代码**；local profile 的开发 key 仅覆盖本地开发库凭据，
 *   生产必须显式注入随机 key；</li>
 *   <li>key 未配置时 encrypt/decrypt 一律抛 {@code 1_006_000_109}——fail-closed，不降级明文；</li>
 *   <li>密文格式 {@code Base64(iv || ciphertext)}，IV 每次加密随机 12 字节（GCM 标准）；</li>
 *   <li>本类不感知插件与存储，纯加解密；secret 字段的剥离/重组由 service 按 descriptor 声明完成。</li>
 * </ul>
 */
public final class DatasourceSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int KEY_BYTES = 32;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /** @param base64Key 环境注入的 Base64 32 字节密钥；null/blank = 未配置（encrypt/decrypt 将 fail-closed）。 */
    public DatasourceSecretCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "ontos.datasource.master-key 不是合法 Base64，拒绝启动（fail-fast）", ex);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("ontos.datasource.master-key 必须是 32 字节（Base64），当前 "
                    + raw.length + " 字节，拒绝启动（fail-fast）");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 凭据字段集 → Base64(iv||ciphertext)。key 未配置时 fail-closed。 */
    public String encrypt(Map<String, String> secrets) {
        requireKey();
        if (secrets == null || secrets.isEmpty()) {
            throw exception(DATASOURCE_SECRET_CIPHER_FAILED, "凭据字段为空，不应加密");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = JsonUtils.toJsonString(secrets).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] cipherText = cipher.doFinal(plain);
            byte[] packed = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherText, 0, packed, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (java.security.GeneralSecurityException ex) {
            throw exception(DATASOURCE_SECRET_CIPHER_FAILED, ex.getMessage());
        }
    }

    /** Base64(iv||ciphertext) → 凭据字段集。key 未配置或密文非法时 fail-closed。 */
    public Map<String, String> decrypt(String packedBase64) {
        requireKey();
        if (packedBase64 == null || packedBase64.isBlank()) {
            throw exception(DATASOURCE_SECRET_CIPHER_FAILED, "密文为空");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(packedBase64.trim());
            if (packed.length <= IV_BYTES) {
                throw exception(DATASOURCE_SECRET_CIPHER_FAILED, "密文长度不足");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(packed, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(packed, IV_BYTES, packed.length - IV_BYTES);
            Map<String, String> secrets = new LinkedHashMap<>();
            Map<String, Object> raw = JsonUtils.parseMap(
                    new String(plain, java.nio.charset.StandardCharsets.UTF_8));
            raw.forEach((k, v) -> secrets.put(k, String.valueOf(v)));
            return secrets;
        } catch (java.security.GeneralSecurityException ex) {
            throw exception(DATASOURCE_SECRET_CIPHER_FAILED, "解密失败（key 不匹配或密文损坏）");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw exception(DATASOURCE_MASTER_KEY_MISSING);
        }
    }
}
