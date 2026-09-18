import { z } from 'zod';

export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status = 0,
  ) {
    super(message);
  }
}
export async function responseError(response: Response): Promise<ApiError> {
  const value = await response.json().catch(() => null);
  return new ApiError(
    value?.code || 'HTTP_ERROR',
    value?.message ||
      (response.status >= 500
        ? '服务暂时不可用，请确认 Java 服务已启动。'
        : `请求未完成（${response.status}）`),
    response.status,
  );
}
export async function request<T>(path: string, schema: z.ZodType<T>, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...init,
      signal: init?.signal ?? AbortSignal.timeout(init?.body instanceof FormData ? 120_000 : 20_000),
      headers: {
        ...(init?.body && !(init.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
        ...init?.headers,
      },
    });
  } catch {
    throw new ApiError('NETWORK_ERROR', '无法连接服务，请检查网络与 Java 服务。');
  }
  if (!response.ok) throw await responseError(response);
  if (response.status === 204) return schema.parse(undefined);
  const envelope = z
    .object({ code: z.string(), message: z.string(), data: schema })
    .safeParse(await response.json());
  if (!envelope.success) throw new ApiError('INVALID_RESPONSE', '服务返回了无法识别的数据，请刷新后重试。');
  if (envelope.data.code !== 'SUCCESS') throw new ApiError(envelope.data.code, envelope.data.message);
  return envelope.data.data;
}
export const errorMessage = (error: unknown) => {
  if (error instanceof z.ZodError || error instanceof SyntaxError)
    return '服务响应格式异常，请刷新或查询本轮状态。';
  if (error instanceof TypeError) return '连接已中断，请检查网络后查询本轮状态。';
  return error instanceof Error ? error.message : '操作未完成，请稍后重试。';
};
