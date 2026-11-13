import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  Alert,
  Badge,
  Button,
  PageHeader,
  SectionCard,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Spinner,
} from "@ontos/ui";

import {
  browseDataSourceCatalog,
  dataSourceQueryKeys,
  getDataSourceCatalog,
  getDataSourceColumns,
  testDataSourceConnection,
} from "@/service/catalog";
import { failureMessage } from "@/utils/error-message";

/**
 * 数据源（B2 Phase 1，只读）：连接信息、已注册插件与真实库目录。
 * 本期不做建连、改配置、多源注册——这些属 Phase 2；插件不生成、不执行 SQL。
 */
export function DataSourcesPage() {
  const [schema, setSchema] = useState<string>("");
  const [selectedTable, setSelectedTable] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<string | null>(null);

  const catalogQuery = useQuery({
    queryKey: dataSourceQueryKeys.catalog,
    queryFn: ({ signal }) => getDataSourceCatalog(signal),
  });
  const browseQuery = useQuery({
    queryKey: dataSourceQueryKeys.browse(schema),
    queryFn: ({ signal }) => browseDataSourceCatalog(schema || undefined, signal),
  });
  const browse = browseQuery.data;
  const columnsQuery = useQuery({
    queryKey: dataSourceQueryKeys.columns(browse?.schema ?? "", selectedTable ?? ""),
    queryFn: ({ signal }) =>
      getDataSourceColumns(browse?.schema ?? "", selectedTable ?? "", signal),
    enabled: Boolean(selectedTable) && Boolean(browse?.schema),
  });

  const testMutation = useMutation({
    mutationFn: testDataSourceConnection,
    onSuccess: () => setTestResult("ok"),
    onError: (error) => setTestResult(failureMessage(error)),
  });

  const pickSchema = (value: string) => {
    setSchema(value === "__default__" ? "" : value);
    setSelectedTable(null);
  };

  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="数据源"
        description="当前查询库、已注册插件与真实库目录；本期只读，多源注册在 Phase 2 接入"
        extra={
          <Button
            size="small"
            onClick={() => void catalogQuery.refetch()}
            loading={catalogQuery.isFetching}
          >
            刷新
          </Button>
        }
      />
      {catalogQuery.isError && <Alert className="mt-4">{failureMessage(catalogQuery.error)}</Alert>}
      {catalogQuery.isPending && <Spinner className="mt-6" />}
      {catalogQuery.data && (
        <div className="mt-4 space-y-4">
          <div className="grid gap-4 lg:grid-cols-3">
            <SectionCard
              title="连接信息"
              extra={
                <Button
                  size="small"
                  loading={testMutation.isPending}
                  onClick={() => {
                    setTestResult(null);
                    testMutation.mutate();
                  }}
                >
                  测试连接
                </Button>
              }
            >
              {testResult && (
                <Alert className="mb-3">
                  {testResult === "ok" ? "连通性测试通过（超时 10 秒内）" : testResult}
                </Alert>
              )}
              <dl className="space-y-2 text-sm">
                <div className="flex justify-between gap-3">
                  <dt className="text-[var(--muted-foreground)]">名称</dt>
                  <dd>{catalogQuery.data.name}</dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt className="text-[var(--muted-foreground)]">类型</dt>
                  <dd>PostgreSQL（单库单方言）</dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt className="text-[var(--muted-foreground)]">默认 Schema</dt>
                  <dd className="font-mono text-xs">{catalogQuery.data.schema}</dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt className="text-[var(--muted-foreground)]">访问模式</dt>
                  <dd>{catalogQuery.data.readOnly ? "只读（RLS + 只读账号）" : "可写"}</dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt className="text-[var(--muted-foreground)]">生效清单</dt>
                  <dd className="font-mono text-xs">{catalogQuery.data.manifestId ?? "未加载"}</dd>
                </div>
              </dl>
            </SectionCard>
            <SectionCard title="JDBC URL" className="lg:col-span-2">
              <code className="block break-all rounded-[var(--radius)] bg-[var(--accent)] px-3 py-2 font-mono text-xs">
                {catalogQuery.data.jdbcUrl || "未配置"}
              </code>
              <p className="mt-2 text-xs text-[var(--muted-foreground)]">
                账号口令已在服务端脱敏；SQL 由 Ontos 编译并经只读通道执行，插件不参与执行。
              </p>
            </SectionCard>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard title={`已注册插件（${catalogQuery.data.plugins.length}）`}>
              <div className="space-y-2">
                {catalogQuery.data.plugins.map((plugin) => (
                  <div
                    key={plugin.type}
                    className="flex items-center justify-between rounded-[var(--radius)] border border-[var(--border)] px-3 py-2 text-sm"
                  >
                    <div>
                      <div className="font-medium">{plugin.displayName}</div>
                      <div className="font-mono text-xs text-[var(--muted-foreground)]">
                        {plugin.type}
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-1">
                      {plugin.capabilities.map((capability) => (
                        <Badge key={capability}>{capability}</Badge>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </SectionCard>
            <SectionCard title={`清单引用的物理表（${catalogQuery.data.manifestTables.length}）`}>
              {catalogQuery.data.manifestTables.length === 0 ? (
                <p className="text-sm text-[var(--muted-foreground)]">
                  当前清单没有引用任何物理表。
                </p>
              ) : (
                <div className="flex flex-wrap gap-2">
                  {catalogQuery.data.manifestTables.map((table) => (
                    <span
                      key={table.name}
                      title={table.propertyIds.join("、")}
                      className="rounded border border-[var(--border)] px-2.5 py-1 text-sm"
                    >
                      {table.name}
                      <span className="ml-1 text-xs text-[var(--muted-foreground)]">
                        ×{table.propertyCount}
                      </span>
                    </span>
                  ))}
                </div>
              )}
              <p className="mt-2 text-xs text-[var(--muted-foreground)]">
                来自清单「column」反推，仅覆盖语义模型已引用的表，用于与真实目录对照。
              </p>
            </SectionCard>
          </div>

          <SectionCard title="真实库目录">
            {browseQuery.isError && <Alert>{failureMessage(browseQuery.error)}</Alert>}
            {browseQuery.isPending && <Spinner className="my-2" />}
            {browse && (
              <div className="space-y-3">
                <div className="flex items-center gap-2">
                  <span className="text-xs text-[var(--muted-foreground)]">Schema</span>
                  <Select size="small" value={schema || "__default__"} onValueChange={pickSchema}>
                    <SelectTrigger variant="outlined" aria-label="选择 Schema" className="w-56">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="__default__">默认（{browse.schema}）</SelectItem>
                      {browse.schemas.map((item) => (
                        <SelectItem key={item} value={item}>
                          {item}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <span className="text-xs text-[var(--muted-foreground)]">
                    {browse.tables.length} 张表 / 视图；点行查看列
                  </span>
                </div>
                <div className="overflow-x-auto rounded-[var(--radius)] border border-[var(--border)]">
                  <table className="w-full text-sm">
                    <thead className="text-left text-xs text-[var(--muted-foreground)]">
                      <tr className="border-b border-[var(--border)]">
                        <th className="px-3 py-2 font-medium">表名</th>
                        <th className="px-3 py-2 font-medium">类型</th>
                      </tr>
                    </thead>
                    <tbody>
                      {browse.tables.map((table) => (
                        <tr
                          key={table.name}
                          onClick={() => setSelectedTable(table.name)}
                          className={`cursor-pointer border-b border-[var(--border)] last:border-0 hover:bg-[var(--accent)] ${
                            selectedTable === table.name ? "bg-[var(--accent)]" : ""
                          }`}
                        >
                          <td className="px-3 py-2 font-mono text-xs">{table.name}</td>
                          <td className="px-3 py-2 text-[var(--muted-foreground)]">{table.type}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {selectedTable && (
                  <div>
                    <div className="mb-2 text-sm font-medium">
                      列：{selectedTable}
                      <span className="ml-2 font-mono text-xs text-[var(--muted-foreground)]">
                        schema = {browse.schema}
                      </span>
                    </div>
                    {columnsQuery.isPending && <Spinner className="my-2" />}
                    {columnsQuery.isError && <Alert>{failureMessage(columnsQuery.error)}</Alert>}
                    {columnsQuery.data && (
                      <div className="overflow-x-auto rounded-[var(--radius)] border border-[var(--border)]">
                        <table className="w-full text-sm">
                          <thead className="text-left text-xs text-[var(--muted-foreground)]">
                            <tr className="border-b border-[var(--border)]">
                              <th className="px-3 py-2 font-medium">列名</th>
                              <th className="px-3 py-2 font-medium">类型</th>
                              <th className="px-3 py-2 font-medium">可空</th>
                            </tr>
                          </thead>
                          <tbody>
                            {columnsQuery.data.map((column) => (
                              <tr
                                key={column.name}
                                className="border-b border-[var(--border)] last:border-0"
                              >
                                <td className="px-3 py-2 font-mono text-xs">{column.name}</td>
                                <td className="px-3 py-2 text-[var(--muted-foreground)]">
                                  {column.dataType}
                                </td>
                                <td className="px-3 py-2 text-[var(--muted-foreground)]">
                                  {column.nullable ? "是" : "否"}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </SectionCard>

          <SectionCard title="边界说明">
            <ul className="list-disc space-y-1 pl-5 text-sm text-[var(--muted-foreground)]">
              <li>目录来自数据源插件的 information_schema 读取，已过滤系统 schema；元数据只读。</li>
              <li>
                插件不生成、不执行 SQL；业务查询仍由 Ontos 编译并经只读通道执行，RLS
                与审计不受影响。
              </li>
              <li>
                接入第二个数据源需要把资产绑定升级为 datasetId + column（Phase 2），本期不开放。
              </li>
            </ul>
          </SectionCard>
        </div>
      )}
    </div>
  );
}
