import {
  extractErrorMessage,
  extractUnknownErrorMessage,
  isApiResponse,
  isSuccessfulResponse,
  isUnauthenticatedResponse,
  type ApiResponse,
} from "@/service/http/response";
import { TENANT_HEADER_NAME, TENANT_STORAGE_KEY } from "@/constants/identity";
import { notifyOnce } from "@/utils/notification";

export type { ApiResponse } from "@/service/http/response";

export type BusinessErrorMode = "reject" | "resolve";
export type IdentityHeaderMode = "auto" | "omit";

export type RequestOptions = RequestInit & {
  data?: unknown;
  businessErrorMode?: BusinessErrorMode;
  skipErrorHandler?: boolean;
  responseType?: "blob";
  getResponse?: boolean;
  identityHeader?: IdentityHeaderMode;
};

export class BizError extends Error {
  constructor(
    message: string,
    readonly code?: number,
    readonly response?: ApiResponse,
  ) {
    super(message);
    this.name = "BizError";
  }
}

const HTTP_MESSAGES: Record<number, string> = {
  400: "请求参数有误",
  401: "当前请求未通过身份认证，请重新登录。",
  403: "当前用户已登录，但没有访问该资源的权限。",
  404: "资源不存在",
  405: "请求方式不支持",
  422: "数据校验未通过",
  500: "服务器发生错误，请检查服务器。",
  502: "网关错误",
  503: "服务不可用",
  504: "网关超时",
};

/** Identity comes from the trusted gateway; the UI never owns a login redirect. */
export const handleAuthenticationFailure = (
  reason = "当前请求未通过身份认证，请检查网关身份注入。",
) => {
  notifyOnce("authentication", {
    type: "warning",
    title: "身份未通过认证",
    description: reason,
    meta: "请检查网关配置",
  });
};

const handleBusinessError = (error: BizError, skipErrorHandler: boolean) => {
  if (skipErrorHandler) return;

  if (isUnauthenticatedResponse(error.response)) {
    handleAuthenticationFailure(error.message);
    return;
  }

  notifyOnce(`business:${error.code}:${error.message}`, {
    type: "error",
    title: "操作失败",
    description: error.message || "未知错误",
    meta: "请稍后重试",
  });
};

const parseResponse = async (response: Response) => {
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) return undefined;
  return response.json() as Promise<unknown>;
};

export default async function request<T>(url: string, options: RequestOptions = {}): Promise<T> {
  const {
    data,
    businessErrorMode = "reject",
    skipErrorHandler = false,
    responseType,
    getResponse,
    identityHeader = "auto",
    ...requestInit
  } = options;

  const headers = new Headers(requestInit.headers);
  if (identityHeader === "auto" && !headers.has(TENANT_HEADER_NAME)) {
    const tenantId = window.localStorage.getItem(TENANT_STORAGE_KEY);
    if (tenantId) headers.set(TENANT_HEADER_NAME, tenantId);
  }

  let body = requestInit.body;

  if (body === undefined && data !== undefined) {
    if (data instanceof FormData) {
      body = data;
    } else {
      if (!headers.has("Content-Type")) {
        headers.set("Content-Type", "application/json");
      }
      body = JSON.stringify(data);
    }
  }

  let response: Response;
  try {
    response = await fetch(url, {
      ...requestInit,
      headers,
      body,
      credentials: requestInit.credentials ?? "include",
    });
  } catch (error) {
    if (requestInit.signal?.aborted) throw error;
    if (!skipErrorHandler) {
      notifyOnce("network", {
        type: "warning",
        title: "网络异常",
        description: "当前无法连接到服务器，请检查网络或稍后再试。",
        meta: "连接中断",
      });
    }
    throw error;
  }

  if (responseType === "blob") {
    if (!response.ok) {
      throw new Error(HTTP_MESSAGES[response.status] ?? `HTTP ${response.status}`);
    }
    const blob = await response.blob();
    return (getResponse ? { data: blob, response } : blob) as T;
  }

  const payload = await parseResponse(response);

  if (!response.ok) {
    const message = extractUnknownErrorMessage(
      payload,
      HTTP_MESSAGES[response.status] ?? response.statusText ?? "请求失败",
    );

    if (response.status === 401) {
      if (!skipErrorHandler) handleAuthenticationFailure(message);
    } else if (!skipErrorHandler) {
      notifyOnce(`http:${response.status}:${url}:${message}`, {
        type: "error",
        title: HTTP_MESSAGES[response.status] ?? "请求失败",
        description: message,
        meta: `HTTP ${response.status}`,
      });
    }

    if (isApiResponse(payload)) {
      throw new BizError(message, payload.code, payload);
    }
    throw new Error(message);
  }

  if (isApiResponse(payload)) {
    if (isUnauthenticatedResponse(payload)) {
      const error = new BizError(
        extractErrorMessage(payload, "登录状态失效"),
        payload.code,
        payload,
      );
      handleBusinessError(error, skipErrorHandler);
      throw error;
    }

    if (!isSuccessfulResponse(payload)) {
      const error = new BizError(extractErrorMessage(payload), payload.code, payload);
      handleBusinessError(error, skipErrorHandler);
      if (businessErrorMode === "reject") throw error;
    }
  }

  // getResponse 与 blob 分支同语义：调用方需要响应头（如 X-Request-Id）时由它取，
  // fetch 依然只在这里发——service 层不直接持有 Response。
  if (getResponse) {
    return { data: payload, response } as T;
  }

  return payload as T;
}

export type StreamEvent = { type: string; [key: string]: unknown };

export type StreamOptions = {
  data?: unknown;
  signal?: AbortSignal;
  onEvent: (event: StreamEvent) => void;
};

/** AG-UI SSE transport. Lives here so fetch has exactly one owner in the product. */
export async function stream(url: string, { data, signal, onEvent }: StreamOptions): Promise<void> {
  const headers = new Headers();
  headers.set("Content-Type", "application/json");
  headers.set("Accept", "text/event-stream");

  const response = await fetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify(data),
    credentials: "include",
    signal,
  });

  if (!response.ok || !response.body) {
    const message = extractUnknownErrorMessage(
      await parseResponse(response),
      HTTP_MESSAGES[response.status] ?? `HTTP ${response.status}`,
    );
    throw new BizError(message, response.status);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() ?? "";
    for (const frame of frames) {
      const payload = frame
        .split(/\r?\n/)
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trimStart())
        .join("\n");
      if (!payload || payload === "[DONE]") continue;
      try {
        onEvent(JSON.parse(payload) as StreamEvent);
      } catch {
        continue;
      }
    }
  }
}
