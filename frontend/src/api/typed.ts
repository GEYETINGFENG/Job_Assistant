import request from "./request";
import { ApiError } from "./errors";
import type { paths } from "./schema";

/**
 * 基于 OpenAPI 生成类型的请求封装。
 * 路径、路径参数、query、header、请求体和返回值类型全部来自 openapi/openapi.json：
 * 后端改了契约并重新生成 schema.d.ts 后，调用处会在 `npm run typecheck` 时报错，而不是等到联调或上线。
 * 底层仍是同一个 axios 实例，统一错误处理和静默刷新照常生效。
 */

type HttpMethod = "get" | "post" | "put" | "patch" | "delete";

type Operation<P extends keyof paths, M extends HttpMethod> = NonNullable<paths[P][M]>;

/** 支持某个 HTTP 方法的全部路径。 */
export type PathsWithMethod<M extends HttpMethod> = {
  [P in keyof paths]: [NonNullable<paths[P][M]>] extends [never] ? never : P;
}[keyof paths];

// 生成的类型用 `path?: never` 表示“没有这类参数”，推断出来是 undefined，这里统一去掉 undefined。
type Parameters<O> = O extends { parameters: infer Params } ? Params : never;
type PathParams<O> = Exclude<Parameters<O> extends { path?: infer X } ? X : never, undefined>;
type QueryParams<O> = Exclude<Parameters<O> extends { query?: infer X } ? X : never, undefined>;
type HeaderParams<O> = Exclude<Parameters<O> extends { header?: infer X } ? X : never, undefined>;
type RequestBody<O> = Exclude<O extends { requestBody?: { content: infer C } } ? C[keyof C] : never, undefined>;

/** 2xx 响应体（统一响应 BaseResponse<T>）。 */
type SuccessBody<O> = O extends { responses: infer R }
  ? { [S in keyof R]: S extends 200 | 201 | 202 ? (R[S] extends { content: infer C } ? C[keyof C] : never) : never }[keyof R]
  : never;

/** BaseResponse<T> 里的 data。 */
export type ResponseData<O> = SuccessBody<O> extends { data?: infer D } ? D : never;

type RequestOptions<O> =
  ([PathParams<O>] extends [never] ? { path?: never } : { path: PathParams<O> }) &
  ([QueryParams<O>] extends [never] ? { query?: never } : { query?: QueryParams<O> }) &
  ([HeaderParams<O>] extends [never] ? { headers?: never } : { headers: HeaderParams<O> }) &
  ([RequestBody<O>] extends [never] ? { body?: never } : { body: RequestBody<O> }) &
  { signal?: AbortSignal };

/** 所有字段都可选时，options 参数本身也可以省略。 */
type OptionsArgs<O> = {} extends RequestOptions<O> ? [options?: RequestOptions<O>] : [options: RequestOptions<O>];

function fillPath(template: string, params?: Record<string, unknown>): string {
  return template.replace(/\{(\w+)\}/g, (_, name: string) => {
    const value = params?.[name];
    if (value === undefined || value === null) throw new Error(`Missing path parameter: ${name}`);
    return encodeURIComponent(String(value));
  });
}

/**
 * 调用接口并返回 BaseResponse 里的 data。
 * code != 0 的响应已经在拦截器里转换成 ApiError，这里拿到的一定是成功结果。
 */
export async function api<M extends HttpMethod, P extends PathsWithMethod<M>>(
  method: M,
  path: P,
  ...[options]: OptionsArgs<Operation<P, M>>
): Promise<ResponseData<Operation<P, M>>> {
  const opts = (options ?? {}) as {
    path?: Record<string, unknown>;
    query?: Record<string, unknown>;
    headers?: Record<string, string>;
    body?: unknown;
    signal?: AbortSignal;
  };

  const response = await request.request({
    method,
    url: fillPath(path, opts.path),
    params: opts.query,
    headers: opts.headers,
    data: opts.body,
    signal: opts.signal
  });

  const data = response.data?.data;
  if (data === undefined || data === null) {
    throw new ApiError({ kind: "http", status: response.status, detail: "Response has no data" });
  }
  return data as ResponseData<Operation<P, M>>;
}
