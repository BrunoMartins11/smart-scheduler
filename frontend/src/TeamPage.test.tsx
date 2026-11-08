import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TeamPage } from './TeamPage'

const teams = [{ id: 't1', name: 'Urgências' }]

function members() {
  return [
    { userId: 'u1', email: 'ana@example.pt', displayName: 'Ana Silva', role: 'MANAGER', ftePercent: 100, worksShifts: true, active: true },
    { userId: 'u2', email: 'rui@example.pt', displayName: 'Rui Costa', role: 'DOCTOR', ftePercent: 50, worksShifts: false, active: false },
  ]
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('TeamPage', () => {
  it('renders the members of the first team', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url === '/api/teams') return json(teams)
      return json(members())
    }))
    render(<TeamPage />)

    const table = await screen.findByRole('table')
    expect(within(table).getByRole('rowheader', { name: 'Ana Silva' })).toBeTruthy()
    expect(within(table).getByText('ana@example.pt')).toBeTruthy()
    expect(within(table).getByText('Inativo')).toBeTruthy()
    expect(within(table).getByText('Gestor')).toBeTruthy()
    expect(within(table).getByText('DOCTOR')).toBeTruthy()
    expect((screen.getByLabelText('Escolher equipa') as HTMLSelectElement).value).toBe('t1')
  })

  it('deactivates a member with DELETE and then refetches the list', async () => {
    const list = members()
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'DELETE') {
        list[0].active = false
        return new Response(null, { status: 204 })
      }
      return json(list)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Desativar Ana Silva' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Reativar Ana Silva' })).toBeTruthy())
    const calls = fetchMock.mock.calls.map(([url, init]) => [url, init?.method ?? 'GET'])
    expect(calls).toEqual([
      ['/api/teams', 'GET'],
      ['/api/teams/t1/members', 'GET'],
      ['/api/teams/t1/members/u1', 'DELETE'],
      ['/api/teams/t1/members', 'GET'],
    ])
  })

  it('sends one request when the action is clicked twice quickly', async () => {
    const list = members()
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'DELETE') {
        await gate
        list[0].active = false
        return new Response(null, { status: 204 })
      }
      return json(list)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    const button = await screen.findByRole('button', { name: 'Desativar Ana Silva' })
    fireEvent.click(button)
    fireEvent.click(button)
    expect((button as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reativar Ana Silva' })).toBeTruthy())
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(1)
  })

  it('shows an alert when loading fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({}, 500)))
    render(<TeamPage />)

    expect((await screen.findByRole('alert')).textContent).toContain('Não foi possível carregar')
    expect(screen.queryByRole('table')).toBeNull()
  })
})
