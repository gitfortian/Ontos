import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
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
  toast,
} from "@ontos/ui";

import {
  browseDataSourceByTarget,
  createDataSourceConfig,
  dataSourceQueryKeys,
  deleteDataSourceConfig,
  getDataSourceCatalog,
  getDataSourceColumnsByTarget,
  listDataSourceConfigs,
  testDataSourceConfig,
  testDataSourceConnection,
  updateDataSourceConfig,
} from "@/service/catalog";
import type { DataSourceConfigVO } from "@/service/catalog/types";
import { failureMessage } from "@/utils/error-message";

import { DataSourceForm } from "./datasource-form";

type FormState = { mode: "create" } | { mode: "edit"; config: DataSourceConfigVO } | null;

/** 目标选择器的固定项：配置文件的当前查询库。 */
const CURRENT_TARGET = "current";

/**
 * 数据源（B2）：当前查询库 + 注册数据源（Phase 2 前半）+ 真实库目录。
 * 注册走 /configs CRUD（凭据 AES-GCM 加密落库，ADR-0025）；目录浏览按目标切换；
 * 列行可「建信息项」直接预填本体建模表单（B1 P2-C 前半）。
 */
export function DataSourcesPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [target, setTarget] = useState<string>(CURRENT_TARGET);
  const [schema, setSchema] = useState<string>("");
  const [selectedTable, setSelectedTable] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>(null);

  const catalogQuery = useQuery({
    queryKey: dataSourceQueryKeys.catalog,
    queryFn: ({ signal }) => getDataSourceCatalog(signal),
  });
  const configsQuery = useQuery({
    queryKey: dataSourceQueryKeys.configs,
    queryFn: ({ signal }) => listDataSourceConfigs(signal),
  });
  const configs = configsQuery.data ?? [];

  const browseQuery = useQuery({
    queryKey: dataSourceQueryKeys.browse(target, schema),
    queryFn: ({ signal }) => browseDataSourceByTarget(target, schema || undefined, signal),
  });
  const browse = browseQuery.data;
  const columnsQuery = useQuery({
    queryKey: dataSourceQueryKeys.columns(target, browse?.schema ?? "", selectedTable ?? ""),
    queryFn: ({ signal }) =>
      getDataSourceColumnsByTarget(target, browse?.schema ?? "", selectedTable ?? "", signal),
    enabled: Boolean(selectedTable) && Boolean(browse?.schema),
  });

  const invalidateConfigs = async () => {
    await queryClient.invalidateQueries({ queryKey: dataSourceQueryKeys.configs });
    await queryClient.invalidateQueries({ queryKey: dataSourceQueryKeys.catalog });
  };

  const testMutation = useMutation({
    mutationFn: testDataSourceConnection,
    onSuccess: () => setTestResult("ok"),
    onError: (error) => setTestResult(failureMessage(error)),
  });

  const saveMutation = useMutation({
    mutationFn: async (payload: {
      name: string;
      type: string;
      config: Record<string, string>;
      secrets?: Record<string, string>;
    }) => {
      if (form?.mode === "edit") {
        return updateDataSourceConfig(form.config.id, payload);
      }
      return createDataSourceConfig(payload);
    },
    onSuccess: async (saved) => {
      const mode = form?.mode;
      setForm(null);
      await invalidateConfigs();
      toast.success(mode === "edit" ? `已更新：${saved.name}` : `已注册：${saved.name}`);
    },
    onError: (error) => toast.error(failureMessage(error)),
  });

  const deleteMutation = useMutation({
    mutationFn: deleteDataSourceConfig,
    onSuccess: async () => {
      if (target !== CURRENT_TARGET) {
        // 被删目标可能是当前浏览对象：回退到当前查询库
        setTarget(CURRENT_TARGET);
        setSchema("");
        setSelectedTable(null);
      }
      await invalidateConfigs();
      toast.success("已删除");
    },
    onError: (error) => toast.error(failureMessage(error)),
  });

  const testConfigMutation = useMutation({
    mutationFn: testDataSourceConfig,
    onSuccess: () => toast.success("连通性测试通过"),
    onError: (error) => toast.error(failureMessage(error)),
  });

  const targetLabel = (value: string) =>
    value === CURRENT_TARGET
      ? "当前查询库（配置文件）"
      : (configs.find((config) => config.id === value)?.name ?? value);

  const pickSchema = (value: string) => {
    setSchema(value === "__default__" ? "" : value);
    setSelectedTable(null);
  };

  return (
    <div className="px-6 py-5 max-md:px-4">
      <PageHeader
        title="数据源"
        description="当前查询库、注册数据源与真实库目录；执行链仍走当前查询库，注册源用于目录浏览与建模取列"
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
              title="连接信息（当前查询库）"
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

          <SectionCard
            title={`已注册数据源（${configs.length}）`}
            extra={
              <Button size="small" variant="primary" onClick={() => setForm({ mode: "create" })}>
                注册数据源
              </Button>
            }
          >
            {form && (
              <div className="mb-3 rounded-[var(--radius)] border border-[var(--border)] p-3">
                <div className="mb-2 text-sm font-medium">
                  {form.mode === "create" ? "注册数据源" : `编辑：${form.config.name}`}
                </div>
                <DataSourceForm
                  plugins={catalogQuery.data.plugins}
                  initial={form.mode === "edit" ? form.config : null}
                  busy={saveMutation.isPending}
                  onCancel={() => setForm(null)}
                  onSave={async (payload) => {
                    await saveMutation.mutateAsync(payload);
                  }}
                />
              </div>
            )}
            {configsQuery.isPending && <Spinner className="my-2" />}
            {configsQuery.isError && <Alert>{failureMessage(configsQuery.error)}</Alert>}
            {configs.length === 0 && !form && !configsQuery.isPending && (
              <p className="text-sm text-[var(--muted-foreground)]">
                还没有注册数据源。注册后可在下方目录浏览按源切换，并从列一键建信息项草稿；业务查询仍走当前查询库。
              </p>
            )}
            {configs.length > 0 && (
              <div className="overflow-x-auto rounded-[var(--radius)] border border-[var(--border)]">
                <table className="w-full text-sm">
                  <thead className="text-left text-xs text-[var(--muted-foreground)]">
                    <tr className="border-b border-[var(--border)]">
                      <th className="px-3 py-2 font-medium">名称</th>
                      <th className="px-3 py-2 font-medium">类型</th>
                      <th className="px-3 py-2 font-medium">配置摘要</th>
                      <th className="px-3 py-2 font-medium">凭据</th>
                      <th className="px-3 py-2 font-medium">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {configs.map((config) => (
                      <tr
                        key={config.id}
                        className={`border-b border-[var(--border)] last:border-0 ${
                          target === config.id ? "bg-[var(--accent)]" : ""
                        }`}
                      >
                        <td className="px-3 py-2 font-medium">{config.name}</td>
                        <td className="px-3 py-2 font-mono text-xs">{config.type}</td>
                        <td className="px-3 py-2 text-xs text-[var(--muted-foreground)]">
                          {Object.entries(config.config)
                            .filter(([key]) => key !== "password")
                            .map(([key, value]) => `${key}=${value}`)
                            .join("　") || "—"}
                        </td>
                        <td className="px-3 py-2">
                          <Badge>{config.hasSecret ? "已加密存储" : "无凭据"}</Badge>
                        </td>
                        <td className="px-3 py-2">
                          <div className="flex flex-wrap gap-1.5">
                            <Button
                              size="small"
                              disabled={target === config.id}
                              onClick={() => {
                                setTarget(config.id);
                                setSchema("");
                                setSelectedTable(null);
                              }}
                            >
                              {target === config.id ? "浏览中" : "浏览目录"}
                            </Button>
                            <Button
                              size="small"
                              loading={testConfigMutation.isPending}
                              onClick={() => testConfigMutation.mutate(config.id)}
                            >
                              测试
                            </Button>
                            <Button size="small" onClick={() => setForm({ mode: "edit", config })}>
                              编辑
                            </Button>
                            <Button
                              size="small"
                              variant="secondary"
                              loading={deleteMutation.isPending}
                              onClick={() => deleteMutation.mutate(config.id)}
                            >
                              删除
                            </Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <p className="mt-2 text-xs text-[var(--muted-foreground)]">
              凭据按插件声明加密落库（AES-256-GCM，master key 环境注入）；编辑时凭据留空 =
              保留原值；注册/编辑服务端强制测试通过。
            </p>
          </SectionCard>

          <SectionCard title="真实库目录">
            {browseQuery.isError && <Alert>{failureMessage(browseQuery.error)}</Alert>}
            {browseQuery.isPending && <Spinner className="my-2" />}
            {browse && (
              <div className="space-y-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs text-[var(--muted-foreground)]">数据源</span>
                  <Select
                    size="small"
                    value={target}
                    onValueChange={(value: string) => {
                      setTarget(value);
                      setSchema("");
                      setSelectedTable(null);
                    }}
                  >
                    <SelectTrigger variant="outlined" aria-label="选择数据源" className="w-56">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value={CURRENT_TARGET}>当前查询库（配置文件）</SelectItem>
                      {configs.map((config) => (
                        <SelectItem key={config.id} value={config.id}>
                          {config.name}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
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
                    {targetLabel(target)} · {browse.tables.length} 张表 / 视图；点行查看列
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
                              <th className="px-3 py-2 font-medium">建模</th>
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
                                <td className="px-3 py-2">
                                  <Button
                                    size="small"
                                    onClick={() =>
                                      navigate(
                                        `/ontology/modeling?tab=properties&create=1&column=${encodeURIComponent(
                                          `${selectedTable}.${column.name}`,
                                        )}&dataType=${encodeURIComponent(column.dataType)}`,
                                      )
                                    }
                                  >
                                    建信息项
                                  </Button>
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

          <SectionCard title="边界说明">
            <ul className="list-disc space-y-1 pl-5 text-sm text-[var(--muted-foreground)]">
              <li>目录来自数据源插件的 information_schema 读取，已过滤系统 schema；元数据只读。</li>
              <li>
                插件不生成、不执行 SQL；业务查询仍由 Ontos 编译并经只读通道执行，RLS
                与审计不受影响。
              </li>
              <li>
                注册源当前只用于「目录浏览 + 建模取列」；按源执行查询需要资产绑定升级为 datasetId +
                column（Phase 2 后半），本期不开放。
              </li>
            </ul>
          </SectionCard>
        </div>
      )}
    </div>
  );
}
