type Init = Omit<RequestInit, 'body' | 'headers'> & { body?: unknown }

export async function api<T>(path: string, init: Init = {}): Promise<T> {
  const { body, ...rest } = init
  const res = await fetch(path, {
    credentials: 'same-origin',
    ...rest,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}
