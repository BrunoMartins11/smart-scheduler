type Init = Omit<RequestInit, 'body' | 'headers'> & { body?: unknown }

export class ApiError extends Error {
  status: number
  constructor(status: number) {
    super(`HTTP ${status}`)
    this.status = status
  }
}

export async function api<T>(path: string, init: Init = {}): Promise<T> {
  const { body, ...rest } = init
  const res = await fetch(path, {
    credentials: 'same-origin',
    ...rest,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) throw new ApiError(res.status)
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}
