-- Ontos System DB：demo 租户追加 scenario.sales.auto 授权（M7/W1/T1.2，E-6）
--
-- 背景：v18 清单发布 scenario.sales.auto（entryMode=auto_if_confident），
--       但 V8/V10/V16 种子快照的 assets 只含 scenario.sales——新场景无授权时
--       hintMatches 会过滤掉它（授权裁剪语义），auto 入口永远不触发。
--       场景授权走「发布新 policy_version」通道（快照内容不可变，active 可翻转）。
-- 边界：只扩 demo 开发租户；生产租户由运营发布快照时逐租户绑定（同 V8/V16 口径）。
-- 注意：迁移链在全新库执行时 V16 已派生 seed-2026.11.10-v2，本迁移基于它继续派生。

-- 1. 翻转旧种子 active=false（触发器只拦内容列，active 翻转放行）
UPDATE policy_snapshot SET active = false
 WHERE tenant_id = 'demo' AND policy_version = 'seed-2026.11.10-v2';

-- 2. 派生新快照：assets 追加 scenario.sales.auto 授权，policyVersion 同步更新
INSERT INTO policy_snapshot (tenant_id, policy_version, payload, active, created_by)
SELECT 'demo', 'seed-2026.11.11-v1',
       jsonb_set(
           jsonb_set(payload, '{assets}',
                     payload->'assets' || '[{"assetType":"scenario","assetId":"scenario.sales.auto","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal","note":"M7/W1/T1.2：auto_if_confident 销售场景入口授权（v18 清单；唯一命中自动进入，scope 与 scenario.sales 一致）"}]'::jsonb),
           '{policyVersion}', '"seed-2026.11.11-v1"'::jsonb),
       TRUE, 'm7-w1-t12'
FROM policy_snapshot
 WHERE tenant_id = 'demo' AND policy_version = 'seed-2026.11.10-v2';
