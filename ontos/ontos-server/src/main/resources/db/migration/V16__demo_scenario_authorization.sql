-- Ontos System DB：demo 租种子快照追加 Scenario 授权（M6/W2/T2.4，ADR-0004 核对 v2 配套）
--
-- 背景：v13 清单发布并激活了 scenario.sales（auto_if_confident + recommend 语义），
--       但 V8/V10 种子快照的 assets 只覆盖 ontology/semantic 资产——没有 scenario| 键，
--       按授权裁剪语义 demo 租户所有用户无场景授权：hint 不命中、直传一律拒绝。
--       场景授权走「发布新 policy_version」通道（快照内容不可变，active 可翻转——V10 触发器语义）。
-- 边界：本迁移只扩 demo 开发租户；生产租户由运营发布快照时逐租户绑定（同 V8 注释口径）。
-- 注意：迁移链在全新库执行时 probe/test 快照（M4 E2E 运行期插入）不存在——只基于 V8/V10 的种子行派生。

-- 1. 翻转旧种子 active=false（触发器只拦内容列，active 翻转放行）
UPDATE policy_snapshot SET active = false
 WHERE tenant_id = 'demo' AND policy_version = 'seed-2026.11.09-v2';

-- 2. 派生新快照：assets 追加 scenario.sales 授权（effect=allow，role.analyst），policyVersion 同步更新
INSERT INTO policy_snapshot (tenant_id, policy_version, payload, active, created_by)
SELECT 'demo', 'seed-2026.11.10-v2',
       jsonb_set(
           jsonb_set(payload, '{assets}',
                     payload->'assets' || '[{"assetType":"scenario","assetId":"scenario.sales","effect":"allow","roles":["role.analyst"],"sensitivityClass":"internal","note":"M6/W2/T2.4：首发销售分析场景入口授权（v13 清单 scenario.sales；入口路由 + 直传裁决共用）"}]'::jsonb),
           '{policyVersion}', '"seed-2026.11.10-v2"'::jsonb),
       TRUE, 'm6-w2-t24'
FROM policy_snapshot
 WHERE tenant_id = 'demo' AND policy_version = 'seed-2026.11.09-v2';
