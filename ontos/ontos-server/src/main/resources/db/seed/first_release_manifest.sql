-- 首发交易域清单（M1/T2.3，对照 ADR-0004 资产下限与 v1.4 §5.6 七条关系）。
-- 内容与 ontos-assets/ 下的 YAML 资产同源（YAML 为 Git 真源；发布流水线 T3.x 落地前，
-- 本 seed 是其落库桥接，二者内容必须保持一致，发布管道接管后本文件退役为样例）。
-- 在 System DB（ontos）执行；清单不可变，manifest_id 只增不改。

CREATE EXTENSION IF NOT EXISTS pgcrypto;

WITH payload AS (
    SELECT $json$
    {
      "semantic": {
        "semanticVersion": "2026.11.7",
        "metrics": [
          {
            "id": "metric.gmv",
            "name": "GMV",
            "summary": "成交金额：已支付订单的金额合计",
            "aliases": ["成交金额", "成交额", "销售额", "流水", "卖了多少"],
            "table": "orders",
            "column": "amount",
            "aggregation": "SUM",
            "allowedModes": ["QUERY", "LOOKUP"],
            "allowedDimensions": ["dim.region", "dim.channel", "dim.order_date"],
            "sensitive": false
          },
          {
            "id": "metric.gmv_created",
            "name": "GMV（下单口径）",
            "summary": "下单口径的成交金额：含未支付订单，与支付口径对比用",
            "aliases": ["下单金额"],
            "table": "orders",
            "column": "amount",
            "aggregation": "SUM",
            "allowedModes": ["QUERY"],
            "allowedDimensions": ["dim.region", "dim.channel", "dim.order_date"],
            "sensitive": false
          },
          {
            "id": "metric.order_count",
            "name": "订单数",
            "summary": "下了多少笔订单",
            "aliases": ["单量", "下单数", "订单量"],
            "table": "orders",
            "column": "id",
            "aggregation": "COUNT_DISTINCT",
            "allowedModes": ["QUERY"],
            "allowedDimensions": ["dim.region", "dim.channel", "dim.order_date"],
            "sensitive": false
          },
          {
            "id": "metric.distinct_customers",
            "name": "下单客户数",
            "summary": "有多少个客户下过单，按客户去重",
            "aliases": ["成交客户数", "购买客户数", "有多少个客户买过东西", "多少客户买过"],
            "table": "orders",
            "column": "customer_id",
            "aggregation": "COUNT_DISTINCT",
            "allowedModes": ["QUERY"],
            "allowedDimensions": ["dim.region", "dim.channel"],
            "sensitive": false
          },
          {
            "id": "metric.avg_order_value",
            "name": "客单价",
            "summary": "平均每笔订单的金额",
            "aliases": ["平均订单金额", "单均金额"],
            "table": "orders",
            "column": "amount",
            "aggregation": "AVG",
            "allowedModes": ["QUERY"],
            "allowedDimensions": ["dim.region", "dim.channel"],
            "sensitive": false
          },
          {
            "id": "metric.refund_amount",
            "name": "退款金额",
            "summary": "客户退回的金额合计",
            "aliases": ["退货金额", "退款额"],
            "table": "orders",
            "column": "refund_amount",
            "aggregation": "SUM",
            "allowedModes": ["QUERY"],
            "allowedDimensions": ["dim.region", "dim.channel"],
            "sensitive": false
          },
          {
            "id": "metric.high_value_customer",
            "name": "高价值客户",
            "summary": "按判定标准认定的重点客户：累计消费满 5 万",
            "aliases": ["大客户", "VIP客户"],
            "ruleId": "rule.high_value_customer",
            "allowedModes": ["LOOKUP"],
            "allowedDimensions": [],
            "sensitive": false
          }
        ],
        "dimensions": [
          {"id": "dim.region", "name": "区域", "summary": "订单发货所属的大区", "aliases": ["大区", "地区"],
           "table": "orders", "column": "region", "sensitive": false},
          {"id": "dim.channel", "name": "渠道", "summary": "订单来源的销售渠道", "aliases": ["通路"],
           "table": "orders", "column": "channel", "sensitive": false},
          {"id": "dim.order_date", "name": "下单日期", "summary": "订单创建的日期", "aliases": ["订单日期"],
           "table": "orders", "column": "order_date", "sensitive": false},
          {"id": "dim.customer_tier", "name": "客户等级", "summary": "客户的会员级别", "aliases": ["会员等级"],
           "table": "customer", "column": "tier", "sensitive": false},
          {"id": "dim.product_category", "name": "品类", "summary": "商品所属的分类", "aliases": ["商品类目", "类目"],
           "table": "product", "column": "category", "sensitive": false}
        ]
      },
      "ontology": {
        "ontologyVersion": "2026.11.7",
        "concepts": [
          {"id": "concept.customer", "name": "客户", "summary": "和我们做买卖的人——下过单或登记过信息的都算",
           "aliases": ["顾客", "买家"], "identityProperty": "property.customer.customer_id", "domain": "customer"},
          {"id": "concept.order", "name": "订单", "summary": "客户下的每一次购买",
           "aliases": ["单"], "identityProperty": "property.order.order_no", "domain": "trade"},
          {"id": "concept.order_item", "name": "订单明细", "summary": "订单里买的每一种商品及数量",
           "aliases": ["明细行"], "identityProperty": "property.order_item.item_id", "domain": "trade"},
          {"id": "concept.product", "name": "商品", "summary": "我们卖的东西",
           "aliases": ["产品"], "identityProperty": "property.product.product_id", "domain": "product"},
          {"id": "concept.channel", "name": "渠道", "summary": "订单来源的销售通路",
           "aliases": ["通路"], "identityProperty": "property.channel.channel_id", "domain": "trade"},
          {"id": "concept.event", "name": "业务事件", "summary": "客户或订单上发生的动作记录",
           "aliases": ["动作"], "identityProperty": "property.event.event_id", "domain": "trade"}
        ],
        "properties": [
          {"id": "property.customer.customer_id", "name": "客户编号", "summary": "客户的唯一编号",
           "aliases": ["客户ID"], "conceptId": "concept.customer", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "customer.id"},
          {"id": "property.customer.email", "name": "邮箱", "summary": "客户的联系邮箱",
           "aliases": ["电子邮件", "邮箱地址"], "conceptId": "concept.customer", "valueType": "string",
           "sensitivity": "PII", "lookupProjectable": true, "filterable": false,
           "existenceSensitive": true, "displayHints": ["mask"], "column": "customer.email"},
          {"id": "property.customer.tier", "name": "客户等级", "summary": "客户的会员级别",
           "aliases": ["会员等级"], "conceptId": "concept.customer", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": ["enum_label"], "column": "customer.tier"},
          {"id": "property.order.order_no", "name": "订单编号", "summary": "一笔订单的唯一编号",
           "aliases": ["单号"], "conceptId": "concept.order", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "orders.id"},
          {"id": "property.order.order_date", "name": "下单日期", "summary": "订单创建日期",
           "aliases": ["订单日期"], "conceptId": "concept.order", "valueType": "date",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": ["date_fmt"], "column": "orders.order_date"},
          {"id": "property.order.amount", "name": "订单金额", "summary": "这笔订单的金额",
           "aliases": ["金额"], "conceptId": "concept.order", "valueType": "decimal",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "orders.amount"},
          {"id": "property.order_item.item_id", "name": "明细编号", "summary": "一行明细的唯一编号",
           "aliases": ["明细ID"], "conceptId": "concept.order_item", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": false, "filterable": false,
           "existenceSensitive": false, "displayHints": [], "column": "order_item.id"},
          {"id": "property.order_item.quantity", "name": "购买数量", "summary": "这一行买了几件",
           "aliases": ["数量"], "conceptId": "concept.order_item", "valueType": "integer",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "order_item.quantity"},
          {"id": "property.product.product_id", "name": "商品编号", "summary": "商品的唯一编号",
           "aliases": ["商品ID"], "conceptId": "concept.product", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "product.id"},
          {"id": "property.product.category", "name": "品类", "summary": "商品所属的分类",
           "aliases": ["类目"], "conceptId": "concept.product", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "product.category"},
          {"id": "property.channel.channel_id", "name": "渠道编号", "summary": "渠道的唯一编号",
           "aliases": ["渠道ID"], "conceptId": "concept.channel", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": true, "filterable": true,
           "existenceSensitive": false, "displayHints": [], "column": "channel.id"},
          {"id": "property.event.event_id", "name": "事件编号", "summary": "一条事件记录的唯一编号",
           "aliases": ["事件ID"], "conceptId": "concept.event", "valueType": "string",
           "sensitivity": "INTERNAL", "lookupProjectable": false, "filterable": false,
           "existenceSensitive": false, "displayHints": [], "column": "event.id"}
        ],
        "relations": [
          {"id": "ontology.Customer.placesOrder", "name": "下了", "summary": "客户下的订单",
           "aliases": ["购买", "下单"], "from": "concept.customer", "to": "concept.order",
           "cardinality": "1:N", "reverseLabel": "被下单",
           "fromColumn": "customer.id", "toColumn": "orders.customer_id",
           "queryable": true, "goldenPath": true},
          {"id": "ontology.Order.containsItem", "name": "包含", "summary": "订单里的每一行明细",
           "aliases": ["含有"], "from": "concept.order", "to": "concept.order_item",
           "cardinality": "1:N", "reverseLabel": "属于订单",
           "fromColumn": "orders.id", "toColumn": "order_item.order_id",
           "queryable": true, "goldenPath": true},
          {"id": "ontology.OrderItem.refersProduct", "name": "买了", "summary": "明细行指向的商品",
           "aliases": ["购买的商品"], "from": "concept.order_item", "to": "concept.product",
           "cardinality": "N:1", "reverseLabel": "被购买",
           "fromColumn": "order_item.product_id", "toColumn": "product.id",
           "queryable": true, "goldenPath": true},
          {"id": "ontology.Customer.performsEvent", "name": "产生了", "summary": "客户身上的业务事件（阶段一元数据，阶段三开放）",
           "aliases": ["发生"], "from": "concept.customer", "to": "concept.event",
           "cardinality": "1:N", "reverseLabel": "由客户产生",
           "fromColumn": "customer.id", "toColumn": "event.customer_id",
           "queryable": false, "goldenPath": false},
          {"id": "ontology.Order.recordsEvent", "name": "记录了", "summary": "订单上的业务事件（阶段一元数据，阶段三开放）",
           "aliases": ["关联事件"], "from": "concept.order", "to": "concept.event",
           "cardinality": "1:N", "reverseLabel": "发生于订单",
           "fromColumn": "orders.id", "toColumn": "event.order_id",
           "queryable": false, "goldenPath": false},
          {"id": "ontology.Event.attributedToChannel", "name": "归因于", "summary": "事件归因的渠道（阶段一元数据，阶段三开放）",
           "aliases": ["来自渠道"], "from": "concept.event", "to": "concept.channel",
           "cardinality": "N:1", "reverseLabel": "归因事件",
           "fromColumn": "event.id", "toColumn": "channel.id",
           "queryable": false, "goldenPath": false},
          {"id": "ontology.Order.attributedToChannel", "name": "经由", "summary": "订单经由哪个渠道售出",
           "aliases": ["销售渠道"], "from": "concept.order", "to": "concept.channel",
           "cardinality": "N:1", "reverseLabel": "售出订单",
           "fromColumn": "orders.channel_id", "toColumn": "channel.id",
           "queryable": true, "goldenPath": true}
        ],
        "events": [
          {"id": "event.order_placed", "name": "下单事件", "summary": "客户提交一笔订单",
           "aliases": ["提交订单"], "participantClass": "concept.customer",
           "timeProperty": "property.order.order_date", "eventType": "ORDER_PLACED",
           "dedupKey": "order_id:ORDER_PLACED"},
          {"id": "event.refund", "name": "退款事件", "summary": "客户退回订单或商品",
           "aliases": ["退货"], "participantClass": "concept.customer",
           "timeProperty": "property.order.order_date", "eventType": "REFUND",
           "dedupKey": "order_id:REFUND"}
        ],
        "enums": [
          {"id": "enum.order_status", "name": "订单状态",
           "values": [
             {"value": "CREATED", "label": "已创建"},
             {"value": "PAID", "label": "已支付"},
             {"value": "CANCELLED", "label": "已取消"}
           ]}
        ],
        "constraints": [],
        "synonyms": [
          {"term": "卖了多少", "assetId": "metric.gmv"},
          {"term": "出了多少单", "assetId": "metric.order_count"}
        ]
      },
      "rules": [
        {"id": "rule.high_value_customer", "name": "高价值客户", "summary": "累计消费金额满 5 万的客户",
         "scope": "global",
         "expression": {"metricId": "metric.gmv", "operator": "GTE", "threshold": 50000},
         "status": "published", "reviewer": "m1-seed"}
      ],
      "copyTemplates": [
        {"templateId": "template.reason.OUT_OF_MANIFEST", "locale": "zh-CN",
         "text": "您问的内容不在当前可查范围内。可以换个说法，或先看看有哪些指标可用。"},
        {"templateId": "template.reason.NO_RULE", "locale": "zh-CN",
         "text": "这类判断还没有已审核的判定标准，暂时无法回答。"},
        {"templateId": "template.reason.OUT_OF_SCOPE", "locale": "zh-CN",
         "text": "这个问题超出了问数的能力范围。"}
      ],
      "timeExpressionPatterns": [
        {"patternId": "tep.last_n_days", "pattern": "近 N 天", "granularity": "DAY",
         "offsetUnit": "DAY", "offsetAmount": 1},
        {"patternId": "tep.last_month", "pattern": "上个月", "granularity": "MONTH",
         "offsetUnit": "MONTH", "offsetAmount": 1},
        {"patternId": "tep.this_month", "pattern": "这个月", "granularity": "MONTH",
         "offsetUnit": "MONTH", "offsetAmount": 0}
      ],
      "scenarios": [
        {"id": "scenario.sales", "name": "销售分析", "summary": "围绕交易域的日常销售分析",
         "entryMode": "recommend_or_confirm",
         "ontologyScope": ["concept.order", "concept.customer", "concept.product"],
         "semanticScope": ["metric.gmv", "metric.order_count", "metric.avg_order_value",
                            "metric.distinct_customers", "metric.refund_amount"],
         "allowedAnalysisModes": ["query"]}
      ]
    }
    $json$::jsonb AS p
)
INSERT INTO published_snapshot (manifest_id, content_hash, ontology_version, semantic_version, policy_version,
                                rule_version, time_parser_version, copy_template_version, payload, created_by)
SELECT '2026.11.07-first-r2',
       encode(digest(p::text, 'sha256'), 'hex'),
       '2026.11.7',
       '2026.11.7',
       '2026.11.7',
       '2026.11.7',
       '2026.11.7',
       '2026.11.7',
       p,
       'm1-seed'
FROM payload
ON CONFLICT (manifest_id) DO NOTHING;
