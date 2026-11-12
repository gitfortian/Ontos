import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { fieldProps, firstError } from "./FormBinding.ts";

/** @ontos/ui 表单绑定适配（ADR-0024）的直接单测：纯函数、无 DOM。 */
describe("firstError", () => {
  it("undefined 与空数组返回 undefined", () => {
    assert.equal(firstError(undefined), undefined);
    assert.equal(firstError([]), undefined);
  });

  it("字符串错误直取", () => {
    assert.equal(firstError(["指标名称必填"]), "指标名称必填");
  });

  it("Standard Schema issue（含 message 的对象）取 message", () => {
    assert.equal(firstError([{ message: "指标 ID 必填", path: ["id"] }]), "指标 ID 必填");
  });

  it("非 message 对象返回 undefined", () => {
    assert.equal(firstError([{ code: "custom" }]), undefined);
    assert.equal(firstError([42]), undefined);
  });

  it("取第一条而非最后一条", () => {
    assert.equal(firstError(["第一条", "第二条"]), "第一条");
  });
});

describe("fieldProps", () => {
  it("映射 value / onChange / onBlur / error 四项", () => {
    let changed;
    let blurred = false;
    const field = {
      state: { value: "v1", meta: { errors: [{ message: "必填" }] } },
      handleChange: (value) => {
        changed = value;
      },
      handleBlur: () => {
        blurred = true;
      },
    };

    const props = fieldProps(field);
    assert.equal(props.value, "v1");
    assert.equal(props.error, "必填");

    props.onChange("v2");
    assert.equal(changed, "v2");
    assert.equal(props.value, "v1"); // handleChange 前 state 不变，映射本身无缓存

    props.onBlur();
    assert.equal(blurred, true);
  });

  it("值类型为字面量联合时原样透传（TanStack 按 schema 推导）", () => {
    let received;
    const field = {
      state: { value: "SUM", meta: { errors: [] } },
      handleChange: (value) => {
        received = value;
      },
      handleBlur: () => {},
    };

    const props = fieldProps(field);
    props.onChange("COUNT");
    assert.equal(received, "COUNT");
    assert.equal(props.error, undefined); // 空 errors 数组 → 无错误
  });
});
