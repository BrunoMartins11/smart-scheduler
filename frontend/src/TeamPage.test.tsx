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
    expect((within(table).getByRole('combobox', { name: 'Função Ana Silva' }) as HTMLSelectElement).value).toBe('MANAGER')
    expect((within(table).getByRole('combobox', { name: 'Função Rui Costa' }) as HTMLSelectElement).value).toBe('DOCTOR')
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

  it('posts a new invite from the add row and collapses it after saving', async () => {
    const list = members()
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'POST') return json({ userId: 'u3' }, 201)
      return json(list)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Adicionar membro' }))
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Joana Lopes' } })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'joana@example.pt' } })
    fireEvent.change(screen.getByLabelText('FTE (%)'), { target: { value: '60' } })
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(fetchMock.mock.calls.map(([u, i]) => [u, i?.method ?? 'GET'])).toEqual([
      ['/api/teams', 'GET'],
      ['/api/teams/t1/members', 'GET'],
      ['/api/teams/t1/invites', 'POST'],
      ['/api/teams/t1/members', 'GET'],
    ]))
    const [url, init] = fetchMock.mock.calls[2]
    expect(url).toBe('/api/teams/t1/invites')
    expect(JSON.parse(init!.body as string)).toEqual({
      email: 'joana@example.pt',
      displayName: 'Joana Lopes',
      role: 'MEMBER',
      ftePercent: 60,
      worksShifts: true,
    })
    expect(await screen.findByRole('button', { name: 'Adicionar membro' })).toBeTruthy()
    expect(screen.queryByLabelText('Nome')).toBeNull()
  })

  it('cancel collapses the add row without sending a request', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'POST') return json({}, 201)
      return json(members())
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Adicionar membro' }))
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Joana Lopes' } })
    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByLabelText('Nome')).toBeNull()
    expect(screen.getByRole('button', { name: 'Adicionar membro' })).toBeTruthy()
    expect(fetchMock.mock.calls.filter(([, i]) => i?.method === 'POST')).toHaveLength(0)
  })

  it('shows the saved FTE after the refetch', async () => {
    const list = members()
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'PATCH') {
        list[0].ftePercent = 50
        return new Response(null, { status: 204 })
      }
      return json(list)
    }))
    render(<TeamPage />)

    const input = await screen.findByRole('spinbutton', { name: 'FTE (%) Ana Silva' })
    fireEvent.change(input, { target: { value: '50' } })
    fireEvent.blur(input)

    await waitFor(() => expect((screen.getByRole('spinbutton', { name: 'FTE (%) Ana Silva' }) as HTMLInputElement).value).toBe('50'))
  })

  it('unticking works-shifts sends one PATCH and refetches the members', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'PATCH') return new Response(null, { status: 204 })
      return json(members())
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    const table = await screen.findByRole('table')
    fireEvent.click(within(table).getByRole('checkbox', { name: 'Faz turnos Ana Silva' }))

    await waitFor(() => expect(fetchMock.mock.calls.map(([u, i]) => [u, i?.method ?? 'GET'])).toEqual([
      ['/api/teams', 'GET'],
      ['/api/teams/t1/members', 'GET'],
      ['/api/teams/t1/members/u1', 'PATCH'],
      ['/api/teams/t1/members', 'GET'],
    ]))
    const [, init] = fetchMock.mock.calls[2]
    expect(JSON.parse(init!.body as string)).toEqual({ worksShifts: false })
  })

  it('saves a changed FTE on blur', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'PATCH') return new Response(null, { status: 204 })
      return json(members())
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    const input = await screen.findByRole('spinbutton', { name: 'FTE (%) Ana Silva' })
    fireEvent.change(input, { target: { value: '50' } })
    fireEvent.blur(input)

    await waitFor(() => expect(fetchMock.mock.calls.filter(([, i]) => i?.method === 'PATCH')).toHaveLength(1))
    const [url, init] = fetchMock.mock.calls.find(([, i]) => i?.method === 'PATCH')!
    expect(url).toBe('/api/teams/t1/members/u1')
    expect(JSON.parse(init!.body as string)).toEqual({ ftePercent: 50 })
  })

  it('resets an invalid FTE and sends nothing on blur', async () => {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'PATCH') return new Response(null, { status: 204 })
      return json(members())
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    const input = await screen.findByRole('spinbutton', { name: 'FTE (%) Ana Silva' }) as HTMLInputElement
    fireEvent.change(input, { target: { value: '0' } })
    fireEvent.blur(input)

    expect(input.value).toBe('100')
    expect(fetchMock.mock.calls.filter(([, i]) => i?.method === 'PATCH')).toHaveLength(0)
  })

  it('changing the role sends a PATCH with the new role', async () => {
    const list = members()
    list[0].role = 'MEMBER'
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'PATCH') return new Response(null, { status: 204 })
      return json(list)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    const select = await screen.findByRole('combobox', { name: 'Função Ana Silva' })
    fireEvent.change(select, { target: { value: 'MANAGER' } })

    await waitFor(() => expect(fetchMock.mock.calls.filter(([, i]) => i?.method === 'PATCH')).toHaveLength(1))
    const [url, init] = fetchMock.mock.calls.find(([, i]) => i?.method === 'PATCH')!
    expect(url).toBe('/api/teams/t1/members/u1')
    expect(JSON.parse(init!.body as string)).toEqual({ role: 'MANAGER' })
  })

  it('keeps the add row open and shows the email-in-use alert on a 409', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'POST') return json({}, 409)
      return json(members())
    }))
    render(<TeamPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Adicionar membro' }))
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Joana Lopes' } })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'ana@example.pt' } })
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect((await screen.findByRole('alert')).textContent).toBe('Este email já está em uso nesta empresa.')
    expect((screen.getByLabelText('Nome') as HTMLInputElement).value).toBe('Joana Lopes')
  })

  it('shows an alert when loading fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({}, 500)))
    render(<TeamPage />)

    expect((await screen.findByRole('alert')).textContent).toContain('Não foi possível carregar')
    expect(screen.queryByRole('table')).toBeNull()
  })

  it('creates the first team from the header and selects it', async () => {
    let created = false
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams' && init?.method === 'POST') {
        created = true
        return json({ id: 't2' }, 201)
      }
      if (url === '/api/teams') return json(created ? [{ id: 't2', name: 'Cardiologia' }] : [])
      return json([])
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Nova equipa' }))
    fireEvent.change(screen.getByLabelText('Nome da equipa'), { target: { value: 'Cardiologia' } })
    fireEvent.click(screen.getByRole('button', { name: 'Criar' }))

    await waitFor(() => expect((screen.getByLabelText('Escolher equipa') as HTMLSelectElement).value).toBe('t2'))
    expect(screen.queryByLabelText('Nome da equipa')).toBeNull()
    const posts = fetchMock.mock.calls.filter(([, i]) => i?.method === 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0][0]).toBe('/api/teams')
    expect(JSON.parse(posts[0][1]!.body as string)).toEqual({ name: 'Cardiologia' })
    expect(fetchMock.mock.calls.map(([u, i]) => [u, i?.method ?? 'GET'])).toEqual([
      ['/api/teams', 'GET'],
      ['/api/teams', 'POST'],
      ['/api/teams', 'GET'],
      ['/api/teams/t2/members', 'GET'],
    ])
  })

  it('keeps the team form open and shows the alert when creating fails with 404', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams' && init?.method === 'POST') return json({}, 404)
      return json([])
    }))
    render(<TeamPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Nova equipa' }))
    fireEvent.change(screen.getByLabelText('Nome da equipa'), { target: { value: 'Cardiologia' } })
    fireEvent.click(screen.getByRole('button', { name: 'Criar' }))

    expect((await screen.findByRole('alert')).textContent).toBe(
      'Não foi possível criar a equipa. Só um administrador da empresa pode criar equipas.',
    )
    expect((screen.getByLabelText('Nome da equipa') as HTMLInputElement).value).toBe('Cardiologia')
  })

  it('hides the toggle on the signed-in user row only', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url === '/api/teams') return json(teams)
      return json(members())
    }))
    render(<TeamPage meId="u1" />)

    const table = await screen.findByRole('table')
    const rows = within(table).getAllByRole('row')
    expect(within(rows[1]).queryByRole('button')).toBeNull()
    expect(within(rows[2]).getByRole('button', { name: 'Reativar Rui Costa' })).toBeTruthy()
  })

  it('sends one POST when the create button is clicked twice quickly', async () => {
    let created = false
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams' && init?.method === 'POST') {
        await gate
        created = true
        return json({ id: 't2' }, 201)
      }
      if (url === '/api/teams') return json(created ? [{ id: 't2', name: 'Cardiologia' }] : [])
      return json([])
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Nova equipa' }))
    fireEvent.change(screen.getByLabelText('Nome da equipa'), { target: { value: 'Cardiologia' } })
    const create = screen.getByRole('button', { name: 'Criar' })
    fireEvent.click(create)
    fireEvent.click(create)
    expect((create as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect((screen.getByLabelText('Escolher equipa') as HTMLSelectElement).value).toBe('t2'))
    expect(fetchMock.mock.calls.filter(([, i]) => i?.method === 'POST')).toHaveLength(1)
  })

  it('sends one POST when the add-row save button is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'POST') {
        await gate
        return json({ userId: 'u3' }, 201)
      }
      return json(members())
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Adicionar membro' }))
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Joana Lopes' } })
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'joana@example.pt' } })
    const save = screen.getByRole('button', { name: 'Guardar' })
    fireEvent.click(save)
    fireEvent.click(save)
    expect((save as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(screen.queryByLabelText('Nome')).toBeNull())
    expect(fetchMock.mock.calls.filter(([, i]) => i?.method === 'POST').map(([u]) => u)).toEqual(['/api/teams/t1/invites'])
  })

  it('sends one PATCH when the works-shifts box is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/teams') return json(teams)
      if (init?.method === 'PATCH') {
        await gate
        return new Response(null, { status: 204 })
      }
      return json(members())
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<TeamPage />)

    const table = await screen.findByRole('table')
    const checkbox = within(table).getByRole('checkbox', { name: 'Faz turnos Ana Silva' })
    fireEvent.click(checkbox)
    fireEvent.click(checkbox)
    expect((checkbox as HTMLInputElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(fetchMock.mock.calls).toHaveLength(4))
    expect(fetchMock.mock.calls.filter(([, i]) => i?.method === 'PATCH')).toHaveLength(1)
  })
})
