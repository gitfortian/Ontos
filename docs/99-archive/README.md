# 99 · 归档

**放**：被新版本替代或已废弃的文档。

**不放**：当前有效文档。

**索引 / 约定**：

- 归档文件头块状态改为「已归档」，并注明**被谁替代**、**原路径**。
- 归档路径**镜像分类而非编号目录**：`docs/06-verification/poc/x.md` → `docs/99-archive/verification/x.md`。
  原因是 `check-docs.mjs` 禁止一级目录下出现 `\d{2}-` 编号子目录（层级上限 3 层），
  归档若照搬 `99-archive/06-verification/poc/` 会同时违反层级与编号两条规则。
  映射关系：`01-decisions`→`decisions`、`02-solutions`→`solutions`、`03-plans`→`plans`、
  `04-specs`→`specs`、`05-progress`→`progress`、`06-verification`→`verification`、`07-references`→`references`。
- 归档文件不进入 `docs/README.md` 总索引；由**当前有效版本**在文末「历史版本」处链接，保证链条不断。
- ADR 只标记「已取代」，不进本目录（决策不可删除）。

**当前归档**

| 文档 | 原路径 | 替代者 |
|---|---|---|
| [M0 硬阻断结论 v1](verification/M0-硬阻断结论-v1.md) | `06-verification/poc/M0-硬阻断结论-v1.md` | [v2](../06-verification/poc/M0-硬阻断结论-v2.md) |

本目录遵循 `../README.md`（文档规范与总索引）：统一头块、生命周期、单一事实源与相对链接规则。
