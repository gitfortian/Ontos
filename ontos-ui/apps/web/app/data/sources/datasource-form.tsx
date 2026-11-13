import { useMemo, useState } from "react";
import { Alert, Button, Input } from "@ontos/ui";

import { testDataSourceConfigValues } from "@/service/catalog";
import type {
  DataSourceConfigFieldVO,
  DataSourceConfigVO,
  DataSourcePluginVO,
} from "@/service/catalog/types";
import { failureMessage } from "@/utils/error-message";

export interface DataSourceFormProps {
  plugins: DataSourcePluginVO[];
  /** 编辑时预填；null = 注册。 */
  initial: DataSourceConfigVO | null;
  busy: boolean;
  onSave: (payload: {
    name: string;
    type: string;
    config: Record<string, string>;
    secrets?: Record<string, string>;
  }) => Promise<void>;
  onCancel: () => void;
}

const SECRET_MASK = "••••";

/**
 * 注册/编辑数据源动态表单（B2 Phase 2 前半）：字段由插件 descriptor 的 configFields
 * 驱动，secret 字段用密码输入且编辑时留空 = 保留原凭据；「测试连接」直接测表单值（不落库）。
 */
export function DataSourceForm({ plugins, initial, busy, onSave, onCancel }: DataSourceFormProps) {
  const [name, setName] = useState(initial?.name ?? "");
  const [type, setType] = useState(initial?.type ?? "");
  const [values, setValues] = useState<Record<string, string>>(() => {
    const seeded: Record<string, string> = {};
    if (initial) {
      for (const [key, value] of Object.entries(initial.config)) {
        // secret 掩码不回填：留空 = 保留原凭据
        if (value !== SECRET_MASK) seeded[key] = value;
      }
    }
    return seeded;
  });
  const [secrets, setSecrets] = useState<Record<string, string>>({});
  const [testResult, setTestResult] = useState<"ok" | string | null>(null);
  const [testing, setTesting] = useState(false);

  const fields: DataSourceConfigFieldVO[] = useMemo(() => {
    const plugin = plugins.find((item) => item.type === type);
    return plugin?.configFields ?? [];
  }, [plugins, type]);

  const nonSecretValues = useMemo(() => {
    const secretKeys = new Set(fields.filter((field) => field.secret).map((field) => field.key));
    return {
      ...values,
      ...Object.fromEntries(Object.entries(secrets).filter(([key]) => secretKeys.has(key))),
    };
  }, [fields, values, secrets]);

  const setValue = (key: string, value: string) => {
    const field = fields.find((item) => item.key === key);
    if (field?.secret) {
      setSecrets((prev) => ({ ...prev, [key]: value }));
    } else {
      setValues((prev) => ({ ...prev, [key]: value }));
    }
  };

  const handleTest = async () => {
    setTestResult(null);
    setTesting(true);
    try {
      await testDataSourceConfigValues(type, nonSecretValues);
      setTestResult("ok");
    } catch (error) {
      setTestResult(failureMessage(error));
    } finally {
      setTesting(false);
    }
  };

  const requiredMissing = fields.some(
    (field) => field.required && !(nonSecretValues[field.key] ?? "").trim(),
  );

  return (
    <div className="space-y-3 text-sm">
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block">
          <span className="text-xs text-[var(--muted-foreground)]">名称</span>
          <Input
            size="small"
            variant="outlined"
            value={name}
            placeholder="如：数仓 PG"
            onChange={(event) => setName(event.target.value)}
          />
        </label>
        <label className="block">
          <span className="text-xs text-[var(--muted-foreground)]">
            类型{initial ? "（注册后不可改）" : ""}
          </span>
          <select
            className="h-8 w-full rounded border border-[var(--border)] bg-[var(--background)] px-2 text-xs"
            value={type}
            disabled={Boolean(initial)}
            aria-label="插件类型"
            onChange={(event) => {
              setType(event.target.value);
              setTestResult(null);
            }}
          >
            <option value="">选择插件类型</option>
            {plugins.map((plugin) => (
              <option key={plugin.type} value={plugin.type}>
                {plugin.displayName}（{plugin.type}）
              </option>
            ))}
          </select>
        </label>
      </div>

      {type === "" ? (
        <p className="text-xs text-[var(--muted-foreground)]">
          先选插件类型，字段按插件声明动态生成。
        </p>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {fields.map((field) => (
            <label key={field.key} className="block">
              <span className="text-xs text-[var(--muted-foreground)]">
                {field.label}
                {field.required && <span className="ml-0.5 text-[var(--destructive)]">*</span>}
                {field.secret && (
                  <span className="ml-1 text-[var(--muted-foreground)]">（加密存储）</span>
                )}
              </span>
              <Input
                size="small"
                variant="outlined"
                type={field.secret ? "password" : "text"}
                value={field.secret ? (secrets[field.key] ?? "") : (values[field.key] ?? "")}
                placeholder={
                  initial?.hasSecret && field.secret
                    ? "留空 = 保留原凭据"
                    : (field.placeholder ?? "")
                }
                onChange={(event) => setValue(field.key, event.target.value)}
              />
            </label>
          ))}
        </div>
      )}

      {testResult && (
        <Alert>{testResult === "ok" ? "连通性测试通过（超时 10 秒内）" : testResult}</Alert>
      )}

      <div className="flex items-center gap-2 pt-1">
        <Button
          size="small"
          variant="primary"
          loading={busy}
          disabled={type === "" || requiredMissing || !name.trim()}
          onClick={() =>
            void onSave({
              name: name.trim(),
              type,
              config: Object.fromEntries(
                Object.entries(values).filter(([, value]) => value.trim() !== ""),
              ),
              ...(Object.keys(secrets).length > 0 ? { secrets } : {}),
            })
          }
        >
          {initial ? "保存修改（先测通再落库）" : "注册（先测通再落库）"}
        </Button>
        <Button size="small" disabled={testing || type === ""} onClick={() => void handleTest()}>
          测试连接
        </Button>
        <Button size="small" variant="secondary" onClick={onCancel}>
          取消
        </Button>
      </div>
      <p className="text-xs text-[var(--muted-foreground)]">
        保存时服务端会再次强制测试；凭据（密码）以 AES-256-GCM 密文落库，master key
        经环境变量注入，永不进日志。
      </p>
    </div>
  );
}
