package com.ontos.module.datasource.core;

import java.util.Map;

import com.ontos.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 凭据加密器：AES-GCM 往返、错 key 拒绝、构造 fail-fast、缺 key fail-closed。 */
class DatasourceSecretCipherTest {

    /** 32 字节测试 key（Base64）：bytes 32..63。 */
    private static final String KEY_A = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";
    private static final String KEY_B = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1+fic=";

    @Test
    void encryptDecryptRoundTrip() {
        DatasourceSecretCipher cipher = new DatasourceSecretCipher(KEY_A);
        Map<String, String> secrets = Map.of("password", "s3cr3t!", "token", "abc");
        String packed = cipher.encrypt(secrets);
        assertEquals(secrets, cipher.decrypt(packed));
    }

    @Test
    void sameKeyDifferentIvProducesDifferentCiphertext() {
        DatasourceSecretCipher cipher = new DatasourceSecretCipher(KEY_A);
        String first = cipher.encrypt(Map.of("password", "x"));
        String second = cipher.encrypt(Map.of("password", "x"));
        assertNotEquals(first, second);
    }

    @Test
    void decryptWithWrongKeyFailsClosed() {
        String packed = new DatasourceSecretCipher(KEY_A).encrypt(Map.of("password", "x"));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> new DatasourceSecretCipher(KEY_B).decrypt(packed));
        assertEquals(1_006_000_110, exception.getCode());
    }

    @Test
    void invalidKeyLengthFailsFastOnStartup() {
        assertThrows(IllegalStateException.class,
                () -> new DatasourceSecretCipher("AQIDBA=="));
        assertThrows(IllegalStateException.class,
                () -> new DatasourceSecretCipher("not-base64!!!"));
    }

    @Test
    void missingKeyFailsClosedOnEncryptAndDecrypt() {
        DatasourceSecretCipher cipher = new DatasourceSecretCipher(null);
        ServiceException encryptFailure = assertThrows(ServiceException.class,
                () -> cipher.encrypt(Map.of("password", "x")));
        assertEquals(1_006_000_109, encryptFailure.getCode());
        ServiceException decryptFailure = assertThrows(ServiceException.class,
                () -> cipher.decrypt("AAAA"));
        assertEquals(1_006_000_109, decryptFailure.getCode());
    }

    @Test
    void tamperedCiphertextFailsClosed() {
        DatasourceSecretCipher cipher = new DatasourceSecretCipher(KEY_A);
        String packed = cipher.encrypt(Map.of("password", "x"));
        byte[] raw = java.util.Base64.getDecoder().decode(packed);
        raw[raw.length - 1] ^= 0x01;
        String tampered = java.util.Base64.getEncoder().encodeToString(raw);
        ServiceException exception = assertThrows(ServiceException.class, () -> cipher.decrypt(tampered));
        assertEquals(1_006_000_110, exception.getCode());
        assertTrue(exception.getMessage().contains("key 不匹配"));
    }
}
