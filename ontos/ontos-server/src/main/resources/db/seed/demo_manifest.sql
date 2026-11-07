-- 本地/沙箱演示清单。不要在生产执行；生产清单由 Git → CI → 发布流水线生成。
-- 哈希按 PostgreSQL jsonb 的文本表示计算，与 ManifestService 读取 payload::text 后计算的 SHA-256 一致。

CREATE EXTENSION IF NOT EXISTS pgcrypto;

WITH payload AS (
    SELECT $json$
    {
      "semantic": {
        "semanticVersion": "2026.11.1-demo",
        "metrics": [
          {
            "id": "metric.gmv",
            "name": "GMV",
            "table": "sales_order",
            "column": "amount",
            "aggregation": "SUM",
            "allowedModes": ["QUERY", "LOOKUP"],
            "allowedDimensions": ["dim.region", "dim.channel", "dim.order_date"],
            "sensitive": false
          },
          {
            "id": "metric.order_count",
            "name": "订单数",
            "table": "sales_order",
            "column": "id",
            "aggregation": "COUNT_DISTINCT",
            "allowedModes": ["QUERY"],
            "allowedDimensions": ["dim.region", "dim.channel"],
            "sensitive": false
          }
        ],
        "dimensions": [
          {"id": "dim.region", "name": "区域", "table": "sales_order", "column": "region", "sensitive": false},
          {"id": "dim.channel", "name": "渠道", "table": "sales_order", "column": "channel", "sensitive": false},
          {"id": "dim.order_date", "name": "下单日期", "table": "sales_order", "column": "order_date", "sensitive": false}
        ]
      },
      "ontology": {
        "ontologyVersion": "2026.11.1-demo",
        "concepts": [
          {"id": "concept.order", "name": "订单", "aliases": ["单", "订单"]},
          {"id": "concept.region", "name": "区域", "aliases": ["地区"]}
        ],
        "relations": [
          {"id": "rel.order_region", "name": "订单所属区域", "from": "concept.order", "to": "concept.region", "cardinality": "N:1"}
        ]
      }
    }
    $json$::jsonb AS p
)
INSERT INTO published_snapshot (manifest_id, content_hash, ontology_version, semantic_version, policy_version,
                                rule_version, time_parser_version, copy_template_version, payload, created_by)
SELECT '2026.11.06-demo',
       encode(digest(p::text, 'sha256'), 'hex'),
       '2026.11.1-demo',
       '2026.11.1-demo',
       '2026.11.1-demo',
       '2026.11.1-demo',
       '2026.11.1-demo',
       '2026.11.1-demo',
       p,
       'seed'
FROM payload
ON CONFLICT (manifest_id) DO NOTHING;
