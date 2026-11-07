# Playwright UI Tests

迁移自 `yak-ops-ui/tests/e2e/README.md`，保留断言与隔离约定；场景文件按本仓库产品建立，不沿用来源项目的业务场景。

Playwright checks browser interaction, rendering, validation, navigation, and failure states. Deterministic compilation, query execution, RLS isolation, and audit persistence belong to backend JUnit tests.

| Layer | Technology | When |
| --- | --- | --- |
| Unit and frontend contracts | JUnit / TypeScript | Every change |
| Backend integration and E2E | JUnit + PostgreSQL | Related change and release |
| UI automation | Playwright | Release; manual as needed |
| Deployment smoke | Docker Compose | Release |

The suite keeps stable business scenarios instead of adding a new script for every feature change. Ontos scenarios cover conversational query, immutable manifest inspection, approval decisions, and audit log pagination. Browser-only API fixtures stay deterministic.

```bash
python3 -m pip install playwright==1.57.0
python3 -m playwright install chromium
# Serve the built ontos-ui on localhost:5173 first.
python3 ontos-ui/tests/e2e/run.py --suite mocked --base-url http://127.0.0.1:5173
```

Reports must record the test suite, version, exact commit SHA, relevant traces, and pass/fail state. A skipped or failed Playwright suite does not pass release tests.

Tests must be isolated, use role/label locators, controlled fixtures, condition-based waits, and bounded timeouts. No arbitrary sleep, real query execution waits in the browser, implicit mutable shared state, or retry solely to hide flakes.
