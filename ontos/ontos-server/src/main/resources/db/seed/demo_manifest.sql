-- 本地/沙箱演示清单（payload v2）。【已退役为 PoC 夹具（M1/T3.4）】：首发清单发布后，
-- 本清单与 sales_order 表仅用于 PoC 探针（PoC-01/02）与本地调试；生产清单由发布流水线生成
-- （scripts/publish.mjs → ontos-assets/）。
-- 哈希按 PostgreSQL jsonb 的文本表示计算，与 ManifestService 读取 payload::text 后计算的 SHA-256 一致。
-- v2 变更：资产补齐业务化必填字段（summary/aliases，业务化命名规格）；新增 rule / copyTemplate /
--          timeExpressionPattern / scenario 段；manifest_id 升版（清单不可变，禁止原地覆盖旧版本）。
-- 注意：table/column 为演示库 sales_order 的物理列；首发清单发布后本清单退役为 PoC 夹具。

CREATE EXTENSION IF NOT EXISTS pgcrypto;

WITH payload AS (
    SELECT $json$
    {
      "semantic": {
        "semanticVersion": "2026.11.7-demo",
        "metrics": [
          {
            "id": "metric.gmv",
            "name": "GMV",
            "summary": "成交金额：一段时间内全部订单的金额合计",
            "aliases": ["成交金额", "成交额", "销售额"],
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
            "summary": "下了多少笔订单，按客户去重",
            "aliases": ["单量", "下单数"],
            "table": "sales_order",
            "column": "id",
            "aggregation": "COUNT_DISTINCT",
            "allowedModes": ["QUERY"],
            "allowedDimensions": ["dim.region", "dim.channel"],
            "sensitive": false
          }
        ],
        "dimensions": [
          {"id": "dim.region", "name": "区域", "summary": "订单发货所属的大区", "aliases": ["大区", "地区"],
           "table": "sales_order", "column": "region", "sensitive": false},
          {"id": "dim.channel", "name": "渠道", "summary": "订单来源的销售渠道", "aliases": ["通路"],
           "table": "sales_order", "column": "channel", "sensitive": false},
          {"id": "dim.order_date", "name": "下单日期", "summary": "订单创建的日期", "aliases": ["订单日期"],
           "table": "sales_order", "column": "order_date", "sensitive": false}
        ]
      },
      "ontology": {
        "ontologyVersion": "2026.11.7-demo",
        "concepts": [
          {"id": "concept.order", "name": "订单", "summary": "客户下的每一次购买", "aliases": ["单"],
           "identityProperty": "property.order.order_no", "domain": "trade"},
          {"id": "concept.region", "name": "区域", "summary": "订单发货所属的地区", "aliases": ["地区"],
           "identityProperty": "property.region.code", "domain": "trade"}
        ],
        "properties": [
          {"id": "property.order.order_no", "name": "订单编号", "summary": "一笔订单的唯一编号",
           "aliases": ["单号"], "conceptId": "concept.order", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "sales_order.id"},
          {"id": "property.order.region", "name": "所属区域", "summary": "订单发货的大区",
           "aliases": ["发货区域"], "conceptId": "concept.order", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "sales_order.region"},
          {"id": "property.order.channel", "name": "下单渠道", "summary": "订单来源渠道",
           "aliases": ["来源渠道"], "conceptId": "concept.order", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "sales_order.channel"},
          {"id": "property.order.order_date", "name": "下单日期", "summary": "订单创建日期",
           "aliases": ["订单日期"], "conceptId": "concept.order", "valueType": "date",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": ["date_fmt"], "column": "sales_order.order_date"},
          {"id": "property.order.amount", "name": "订单金额", "summary": "这笔订单的金额",
           "aliases": ["金额"], "conceptId": "concept.order", "valueType": "decimal",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "sales_order.amount"},
          {"id": "property.region.code", "name": "区域编码", "summary": "区域的标识编码",
           "aliases": ["区域码"], "conceptId": "concept.region", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "sales_order.region"}
        ],
        "relations": [
          {"id": "rel.order_region", "name": "属于", "summary": "订单发货所属的区域",
           "aliases": ["属于区域", "发货到"], "from": "concept.order", "to": "concept.region",
           "cardinality": "N:1", "reverseLabel": "包含订单",
           "fromColumn": "sales_order.region", "toColumn": "sales_order.region",
           "queryable": true, "goldenPath": true}
        ],
        "events": [],
        "enums": [],
        "constraints": [],
        "synonyms": [
          {"term": "卖了多少钱", "assetId": "metric.gmv"},
          {"term": "出了多少单", "assetId": "metric.order_count"}
        ]
      },
      "rules": [
        {"id": "rule.high_value_customer", "name": "高价值客户", "summary": "累计消费金额满 5 万的客户",
         "scope": "global",
         "expression": {"metricId": "metric.gmv", "operator": "GTE", "threshold": 50000},
         "status": "published", "reviewer": "seed"}
      ],
      "copyTemplates": [
        {"templateId": "template.reason.OUT_OF_MANIFEST", "locale": "zh-CN",
         "text": "您问的内容不在当前可查范围内。可以换个说法，或先看看有哪些指标可用。"}
      ],
      "timeExpressionPatterns": [
        {"patternId": "tep.last_n_days", "pattern": "近 N 天", "granularity": "DAY",
         "offsetUnit": "DAY", "offsetAmount": 1}
      ],
      "scenarios": [
        {"id": "scenario.sales", "name": "销售分析", "summary": "围绕交易域的日常销售分析",
         "entryMode": "recommend_or_confirm",
         "ontologyScope": ["concept.order"], "semanticScope": ["metric.gmv", "metric.order_count"],
         "allowedAnalysisModes": ["query"]}
      ]
    }
    $json$::jsonb AS p
)
INSERT INTO published_snapshot (manifest_id, content_hash, ontology_version, semantic_version, policy_version,
                                rule_version, time_parser_version, copy_template_version, payload, created_by)
SELECT '2026.11.07-demo',
       encode(digest(p::text, 'sha256'), 'hex'),
       '2026.11.7-demo',
       '2026.11.7-demo',
       '2026.11.7-demo',
       '2026.11.7-demo',
       '2026.11.7-demo',
       '2026.11.7-demo',
       p,
       'seed'
FROM payload
ON CONFLICT (manifest_id) DO NOTHING;
