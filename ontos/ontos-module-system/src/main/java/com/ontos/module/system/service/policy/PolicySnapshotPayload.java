package com.ontos.module.system.service.policy;

import java.util.List;
import java.util.Set;

import com.ontos.framework.common.util.json.JsonUtils;

/**
 * 策略快照 payload 模型（M4/W1）。
 *
 * <p>三段结构：roles（角色定义）/ assignments（用户 → 角色，userId 支持 "*" 通配）
 * / assets（资产授权矩阵，effect=allow + 角色交集才放行，未登记即拒绝）。
 * sensitivityClass 是**策略级**分级（pii / existence / label / internal / public），
 * 与语义清单的 sensitive / existenceSensitive 字段分工：清单管资产语义，快照管授权语义。
 */
public record PolicySnapshotPayload(
        String policyVersion,
        List<Role> roles,
        List<Assignment> assignments,
        List<Asset> assets) {

    public record Role(String roleId, String name, String description) {
    }

    public record Assignment(String userId, String roleId) {
    }

    public record Asset(
            String assetType,
            String assetId,
            String effect,
            Set<String> roles,
            String sensitivityClass) {
    }

    public static PolicySnapshotPayload parse(String json) {
        return JsonUtils.parseObject(json, PolicySnapshotPayload.class);
    }
}
