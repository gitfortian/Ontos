/**
 * PoC #5「中文与模糊召回」提及样本集生成器（M0 / W3 / T3.3，判据级交付）。
 *
 * 判据（技术选型 v1.1 §7 #5）：冻结 ≥300 条提及样本（同义词 / 口语 / 拼音 / 编辑距离各占一定比例）；
 * 精确 + pg_trgm 组合 Top-5 召回率 ≥95%；模糊候选进入 DSL 次数为 0。
 *
 * M0 边界：只冻结题目（utterance / expectedAssetId / category / shouldReachDsl），
 * 召回率与 DSL 零污染断言待 M5（检索层未实现）。本生成器只负责把人工策划的种子表
 * 确定性展开为冻结的 JSON 集合；种子本身即题目的事实来源，与产物一同入库可追溯。
 *
 * 运行：node scripts/poc/gen-poc05-mentions.mjs
 * 产物：docs/06-verification/evaluation/中文提及样本集-v1.json
 */
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repo = resolve(fileURLToPath(new URL("..", import.meta.url)), "..");
const outPath = resolve(repo, "docs/06-verification/evaluation/中文提及样本集-v1.json");

const TARGETS = { synonym: 120, colloquial: 90, pinyin: 45, typo: 45 }; // 合计 300

/**
 * 种子表：资产 → 分类 → 提及变体。ambiguous=true 表示口径/指代歧义，
 * 期望作为澄清候选而不是直接进入 DSL（shouldReachDsl=false）。
 * 资产 ID 引用 ADR-0004 首发资产规划，M1 清单发布后校准。
 */
const SEEDS = {
  "metric.gmv": {
    synonym: ["成交金额", "销售额", "交易额", "成交额", "销售金额", "总销售额"],
    colloquial: [
      { t: "卖了多少钱" }, { t: "做了多少业绩" }, { t: "总共卖了多少钱" }, { t: "卖了多少" },
      { t: "看看销售额", ambiguous: true }, { t: "营收怎么样", ambiguous: true },
    ],
    pinyin: ["cheng jiao e", "xiao shou e", "jiao yi e", "gmv"],
    typo: ["销锈额", "成交金颖", "营叶额", "销售颛"],
  },
  "metric.order_count": {
    synonym: ["订单数", "订单量", "订单数量", "下单量"],
    colloquial: [{ t: "接了多少单" }, { t: "出了多少单" }, { t: "单量如何" }, { t: "一共几单" }],
    pinyin: ["ding dan shu", "dan liang", "ding dan liang"],
    typo: ["订丹数", "订単数", "订单娄"],
  },
  "metric.distinct_customers": {
    synonym: ["下单客户数", "购买客户数", "成交客户数"],
    colloquial: [{ t: "多少个客户买过" }, { t: "有多少客户下单" }, { t: "几个客户买过东西" }],
    pinyin: ["ke hu shu", "xia dan ke hu shu"],
    typo: ["客户娄", "购麦客户数"],
  },
  "metric.avg_order_value": {
    synonym: ["客单价", "平均订单金额", "单均金额"],
    colloquial: [{ t: "一单平均多少钱" }, { t: "平均每单多少钱" }, { t: "平均一单多少" }],
    pinyin: ["ke dan jia"],
    typo: ["客单介", "客旦价"],
  },
  "metric.refund_amount": {
    synonym: ["退款金额", "退货金额", "退款额"],
    colloquial: [{ t: "退了多少钱" }, { t: "退货退了多少" }, { t: "退了多少款" }],
    pinyin: ["tui kuan jin e"],
    typo: ["退棺金额", "退贷金额"],
  },
  "metric.high_value_customer": {
    synonym: ["高价值客户数", "高价值客户"],
    colloquial: [{ t: "有多少大客户" }, { t: "VIP 客户有多少" }, { t: "大客户有几个" }],
    pinyin: ["gao jia zhi ke hu"],
    typo: ["高价质客户", "高价值客护"],
  },
  "dim.region": {
    synonym: ["大区", "区域", "地区"],
    colloquial: [{ t: "分区域" }, { t: "按地区" }],
    pinyin: ["qu yu", "da qu"],
    typo: ["区城"],
  },
  "dim.channel": {
    synonym: ["渠道", "通路", "销售渠道"],
    colloquial: [{ t: "按渠道分" }, { t: "从哪个渠道卖的多" }],
    pinyin: ["qu dao"],
    typo: ["渠遒", "果道"],
  },
  "dim.product_category": {
    synonym: ["品类", "商品类目", "类目", "商品分类"],
    colloquial: [{ t: "按品类看" }, { t: "什么类目" }],
    pinyin: ["pin lei"],
    typo: ["品美", "类木"],
  },
  "dim.order_date": {
    synonym: ["下单日期", "订单日期"],
    colloquial: [{ t: "什么时候下的单" }],
    pinyin: ["xia dan ri qi"],
    typo: ["下単日期"],
  },
  "dim.customer_tier": {
    synonym: ["客户等级", "会员等级", "客户分层"],
    colloquial: [{ t: "客户是什么级别" }],
    pinyin: ["ke hu deng ji"],
    typo: ["客户登级"],
  },
  "entity.customer": {
    synonym: ["客户", "顾客"],
    colloquial: [{ t: "这个客户什么情况" }],
    pinyin: ["ke hu"],
    typo: ["客护", "顾容"],
  },
  "attr.customer.email": {
    synonym: ["客户邮箱", "邮箱地址"],
    colloquial: [{ t: "他邮箱多少", ambiguous: true }],
    pinyin: ["you xiang"],
    typo: ["邮葙", "油箱地址"],
  },
  "entity.order": {
    synonym: ["订单"],
    colloquial: [{ t: "这单什么情况", ambiguous: true }],
    pinyin: ["ding dan"],
    typo: ["订丹"],
  },
  "attr.order.order_no": {
    synonym: ["订单号", "订单编号"],
    colloquial: [{ t: "单号是多少" }],
    pinyin: ["ding dan hao"],
    typo: ["订单好", "订单编好"],
  },
  "entity.product": {
    synonym: ["商品", "产品"],
    colloquial: [{ t: "这个商品卖得怎样" }],
    pinyin: ["shang pin"],
    typo: ["商皮", "产片"],
  },
};

/** 修饰模式按「资产类型 × 变体形态」区分，保证展开后的 utterance 自然。 */
const PATTERNS = {
  metric: ["{x}", "帮我查一下{x}", "查一下{x}", "看一下{x}", "麻烦看下{x}", "上个月{x}", "今年{x}", "华东地区的{x}", "近 7 天的{x}", "各渠道的{x}"],
  dim: ["{x}", "帮我查一下{x}", "按{x}看", "各{x}", "分{x}", "看一下{x}"],
  entity: ["{x}", "帮我查一下{x}", "查一下{x}", "看一下{x}"],
  sentence: ["{x}", "上个月{x}", "今年{x}", "这个月{x}", "总共{x}"],
  plain: ["{x}"],
  pinyin: ["{x}", "帮我查一下{x}", "查一下{x}", "上个月{x}"],
  typo: ["{x}", "帮我查一下{x}", "查一下{x}", "上个月{x}"],
};

function kindOf(asset) {
  if (asset.startsWith("metric.")) return "metric";
  if (asset.startsWith("dim.")) return "dim";
  return "entity";
}

const ASSETS = Object.keys(SEEDS);
const usedUtterances = new Set(); // 全局去重：同一句话只允许出现一次，无论分类

function expand(category) {
  // 每个资产先出裸种子，再按 (种子 × 修饰) 确定性扩展；轮转保证资产分布均匀。
  const items = [];
  const pools = ASSETS.map((asset) => ({
    asset,
    variants: SEEDS[asset][category].map((v) => (typeof v === "string" ? { t: v } : v)),
  }));
  let poolIndex = 0;
  let variantIndex = {};
  let modifierIndex = {};
  let staleRound = 0;
  while (items.length < TARGETS[category]) {
    if (staleRound > pools.length * 4) {
      throw new Error(`分类 ${category} 无法在去重约束下填满 ${TARGETS[category]} 条，请扩充种子表`);
    }
    const pool = pools[poolIndex % pools.length];
    poolIndex += 1;
    const vIdx = (variantIndex[pool.asset] ?? 0) % pool.variants.length;
    const variant = pool.variants[vIdx];
    // 口语变体本身是完整句子：指标句可加时间/总量前缀，实体与维度句保持原样；
    // 拼音与错字限制在自然搭配内
    const patternKey = category === "colloquial"
      ? (kindOf(pool.asset) === "metric" ? "sentence" : "plain")
      : category === "pinyin" ? "pinyin" : category === "typo" ? "typo" : kindOf(pool.asset);
    const patterns = PATTERNS[patternKey];
    const mIdx = modifierIndex[pool.asset] ?? 0;
    const pattern = patterns[mIdx % patterns.length];
    modifierIndex[pool.asset] = mIdx + 1;
    if (mIdx >= patterns.length) {
      variantIndex[pool.asset] = vIdx + 1; // 一轮修饰用尽后才推进种子
    }
    const utterance = pattern.replace("{x}", variant.t);
    if (usedUtterances.has(utterance)) {
      staleRound += 1;
      continue;
    }
    staleRound = 0;
    usedUtterances.add(utterance);
    items.push({
      utterance,
      expectedAssetId: pool.asset,
      category,
      shouldReachDsl: !variant.ambiguous,
    });
  }
  return items;
}

const cases = [];
for (const category of ["synonym", "colloquial", "pinyin", "typo"]) {
  cases.push(...expand(category));
}
cases.sort((a, b) => a.category.localeCompare(b.category) || a.utterance.localeCompare(b.utterance, "zh"));
cases.forEach((c, i) => {
  c.id = `m${String(i + 1).padStart(3, "0")}`;
});

const counts = {};
for (const c of cases) counts[c.category] = (counts[c.category] ?? 0) + 1;
const json = {
  setId: "poc05-mentions",
  version: "v1",
  frozenAt: "2026-11-07",
  criteriaRef: "技术选型 v1.1 §7 #5（召回率与 DSL 零污染判据一律引用上游，不在此复制）",
  note: "M0 / T3.3 交付：仅冻结提及样本。检索层未实现，Top-5 召回率与「模糊候选进入 DSL 次数为 0」断言待 M5 回填。资产 ID 引用 ADR-0004 首发资产规划，M1 清单发布后校准。shouldReachDsl=false 的条目期望作为澄清候选，不直接进入 DSL。生成器 scripts/poc/gen-poc05-mentions.mjs 与本文件同版本。",
  total: cases.length,
  proportions: counts,
  cases,
};
writeFileSync(outPath, JSON.stringify(json, null, 2));
console.log(`生成 ${cases.length} 条：${JSON.stringify(counts)}`);
console.log(`shouldReachDsl=false（澄清候选）：${cases.filter((c) => !c.shouldReachDsl).length}`);
console.log(`产物：${outPath}`);
