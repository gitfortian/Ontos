import request, {
  type ApiResponse,
  type BusinessErrorMode,
  type IdentityHeaderMode,
} from "@/service/http/request";

export type HttpRequestOptions = RequestInit & {
  businessErrorMode?: BusinessErrorMode;
  skipErrorHandler?: boolean;
  identityHeader?: IdentityHeaderMode;
};

const withEnvelopeBusinessErrors = (options?: HttpRequestOptions): HttpRequestOptions => ({
  ...options,
  businessErrorMode: options?.businessErrorMode ?? "resolve",
});

const withRejectedBusinessErrors = (options?: HttpRequestOptions): HttpRequestOptions => ({
  ...options,
  businessErrorMode: "reject",
});

class HttpUtils {
  static envelope<T>(response: ApiResponse<T>): ApiResponse<T> {
    return response;
  }

  static unwrap<T>(response: ApiResponse<T>): T {
    return response.data;
  }

  static post<T>(
    url: string,
    body?: unknown,
    options?: HttpRequestOptions,
  ): Promise<ApiResponse<T>> {
    return request<ApiResponse<T>>(url, {
      method: "POST",
      data: body,
      ...withEnvelopeBusinessErrors(options),
    });
  }

  static postForm<T>(
    url: string,
    formData: FormData,
    options?: HttpRequestOptions,
  ): Promise<ApiResponse<T>> {
    return request<ApiResponse<T>>(url, {
      method: "POST",
      data: formData,
      ...withEnvelopeBusinessErrors(options),
    });
  }

  static get<T>(url: string, options?: HttpRequestOptions): Promise<ApiResponse<T>> {
    return request<ApiResponse<T>>(url, {
      method: "GET",
      ...withEnvelopeBusinessErrors(options),
    });
  }

  static getData<T>(url: string, options?: HttpRequestOptions): Promise<T> {
    return HttpUtils.get<T>(url, withRejectedBusinessErrors(options)).then(HttpUtils.unwrap);
  }

  static postData<T>(url: string, body?: unknown, options?: HttpRequestOptions): Promise<T> {
    return HttpUtils.post<T>(url, body, withRejectedBusinessErrors(options)).then(HttpUtils.unwrap);
  }

  /**
   * POST 并附带响应头元信息（当前只有 X-Request-Id）。
   * 服务端按该头生成请求 ID，/plan/{requestId} 回放与审计查询都依赖它，
   * 业务错误同样 reject（与 postData 一致），调用方拿 BizError 自行分支。
   */
  static postWithMeta<T>(
    url: string,
    body?: unknown,
    options?: HttpRequestOptions,
  ): Promise<{ data: T; requestId: string | null }> {
    return request<{ data: ApiResponse<T>; response: Response }>(url, {
      method: "POST",
      data: body,
      getResponse: true,
      ...withRejectedBusinessErrors(options),
    }).then(({ data, response }) => ({
      data: HttpUtils.unwrap(data),
      requestId: response.headers.get("X-Request-Id"),
    }));
  }

  static put<T>(
    url: string,
    body?: unknown,
    options?: HttpRequestOptions,
  ): Promise<ApiResponse<T>> {
    return request<ApiResponse<T>>(url, {
      method: "PUT",
      data: body,
      ...withEnvelopeBusinessErrors(options),
    });
  }

  static putData<T>(url: string, body?: unknown, options?: HttpRequestOptions): Promise<T> {
    return HttpUtils.put<T>(url, body, withRejectedBusinessErrors(options)).then(HttpUtils.unwrap);
  }

  static delete<T>(
    url: string,
    data?: unknown,
    options?: HttpRequestOptions,
  ): Promise<ApiResponse<T>> {
    return request<ApiResponse<T>>(url, {
      method: "DELETE",
      data,
      ...withEnvelopeBusinessErrors(options),
    });
  }

  static deleteData<T>(url: string, data?: unknown, options?: HttpRequestOptions): Promise<T> {
    return HttpUtils.delete<T>(url, data, withRejectedBusinessErrors(options)).then(
      HttpUtils.unwrap,
    );
  }

  static download(url: string): Promise<Blob> {
    return request<Blob>(url, {
      method: "GET",
      responseType: "blob",
    });
  }
}

export default HttpUtils;
export type { ApiResponse };
