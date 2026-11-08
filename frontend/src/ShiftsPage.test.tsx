import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ShiftsPage } from './ShiftsPage'

const teams = [
  { id: 't1', name: 'Urgências' },
  { id: 't2', name: 'Cardiologia' },
]
const night = { id: 's1', name: 'Madrugada', startTime: '20:00', endTime: '08:00', isNight: true, minStaff: 2, maxStaff: 3, active: true }
const day = { id: 's2', name: 'Manhã', startTime: '08:00', endTime: '20:00', isNight: false, minStaff: 1, maxStaff: 1, active: true }
const retired = { id: 's3', name: 'Antigo', startTime: '09:00', endTime: '17:30', isNight: false, minStaff: 0, maxStaff: 1, active: false }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function renderPage(url = '/turnos') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <ShiftsPage />
    </MemoryRouter>,
  )
}

// Answers the teams list and the shift list GET; every other request goes to onWrite.
function stubApi(
  shifts: unknown[] = [night, day, retired],
  onWrite: (url: string, init?: RequestInit) => Promise<Response> = async (_url, init) =>
    init?.method === 'DELETE' || init?.method === 'PATCH' ? new Response(null, { status: 204 }) : json({ id: 's9' }, 201),
) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/teams') return json(teams)
    if (!init?.method) return json(shifts)
    return onWrite(url, init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const writes = (fetchMock: ReturnType<typeof stubApi>, method: string) =>
  fetchMock.mock.calls.filter(([, i]) => i?.method === method)

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('ShiftsPage', () => {
  it('asks before discarding unsaved edits and keeps the row when cancelled', async () => {
    stubApi()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Editar Madrugada' }))
    fireEvent.change(screen.getByLabelText('Máximo'), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: 'Editar Manhã' }))

    expect(confirm).toHaveBeenCalledWith('Tem alterações por guardar neste turno. Descartar?')
    expect((screen.getByLabelText('Máximo') as HTMLInputElement).value).toBe('4')
    expect((screen.getByLabelText('Nome do turno') as HTMLInputElement).value).toBe('Madrugada')
  })

  it('opens the other row when the unsaved edits are discarded', async () => {
    stubApi()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Editar Madrugada' }))
    fireEvent.change(screen.getByLabelText('Máximo'), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: 'Editar Manhã' }))

    expect((screen.getByLabelText('Nome do turno') as HTMLInputElement).value).toBe('Manhã')
  })

  it('does not ask when the open row has no changes', async () => {
    stubApi()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Editar Madrugada' }))
    fireEvent.click(screen.getByRole('button', { name: 'Editar Manhã' }))

    expect(confirm).not.toHaveBeenCalled()
    expect((screen.getByLabelText('Nome do turno') as HTMLInputElement).value).toBe('Manhã')
  })

  it('shows the times, duration, type and +1 dia marker of each template', async () => {
    stubApi()
    renderPage()

    const nightRow = (await screen.findByText('Madrugada')).closest('tr')!
    expect(within(nightRow).getByText('20:00 – 08:00')).toBeTruthy()
    expect(within(nightRow).getByText('+1 dia')).toBeTruthy()
    expect(within(nightRow).getByText('12 h')).toBeTruthy()
    expect(within(nightRow).getByText('Noite')).toBeTruthy()
    expect(within(nightRow).getByText('mín 2 · máx 3')).toBeTruthy()

    const dayRow = screen.getByText('Manhã').closest('tr')!
    expect(within(dayRow).getByText('Dia')).toBeTruthy()
    expect(within(dayRow).queryByText('+1 dia')).toBeNull()

    const retiredRow = screen.getByText('Antigo').closest('tr')!
    expect(within(retiredRow).getByText('8,5 h')).toBeTruthy()
    expect(within(retiredRow).getByText('Inativo')).toBeTruthy()
  })

  it('adds a template with one POST, then refetches and collapses the row', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Adicionar turno' }))
    fireEvent.change(screen.getByLabelText('Nome do turno'), { target: { value: 'Tarde' } })
    fireEvent.change(screen.getByLabelText('Máximo'), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(screen.queryByLabelText('Nome do turno')).toBeNull())
    const posts = writes(fetchMock, 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0][0]).toBe('/api/teams/t1/shift-templates')
    expect(JSON.parse(posts[0][1]!.body as string)).toEqual({
      name: 'Tarde',
      startTime: '08:00',
      endTime: '20:00',
      isNight: false,
      minStaff: 1,
      maxStaff: 2,
    })
    expect(fetchMock.mock.calls.filter(([, i]) => !i?.method).map(([u]) => u)).toEqual([
      '/api/teams',
      '/api/teams/t1/shift-templates',
      '/api/teams/t1/shift-templates',
    ])
  })

  it('edits one row with one PUT of the full body, then refetches', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Editar Madrugada' }))
    fireEvent.change(screen.getByLabelText('Máximo'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(screen.queryByLabelText('Nome do turno')).toBeNull())
    const puts = writes(fetchMock, 'PUT')
    expect(puts).toHaveLength(1)
    expect(puts[0][0]).toBe('/api/teams/t1/shift-templates/s1')
    expect(JSON.parse(puts[0][1]!.body as string)).toEqual({
      name: 'Madrugada',
      startTime: '20:00',
      endTime: '08:00',
      isNight: true,
      minStaff: 2,
      maxStaff: 3,
    })
    expect(fetchMock.mock.calls.filter(([, i]) => !i?.method)).toHaveLength(3)
  })

  it('shows an inline message and sends nothing when max is below min', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Editar Manhã' }))
    fireEvent.change(screen.getByLabelText('Mínimo'), { target: { value: '3' } })
    fireEvent.change(screen.getByLabelText('Máximo'), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect((await screen.findByRole('alert')).textContent).toBe('O máximo não pode ser inferior ao mínimo.')
    expect(writes(fetchMock, 'PUT')).toHaveLength(0)
    expect(writes(fetchMock, 'POST')).toHaveLength(0)
  })

  it('deactivates with DELETE and reactivates with PATCH, refetching after each', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Desativar Manhã' }))
    await waitFor(() => expect(writes(fetchMock, 'DELETE')).toHaveLength(1))
    expect(writes(fetchMock, 'DELETE')[0][0]).toBe('/api/teams/t1/shift-templates/s2')

    fireEvent.click(await screen.findByRole('button', { name: 'Reativar Antigo' }))
    await waitFor(() => expect(writes(fetchMock, 'PATCH')).toHaveLength(1))
    expect(writes(fetchMock, 'PATCH')[0][0]).toBe('/api/teams/t1/shift-templates/s3')
    expect(JSON.parse(writes(fetchMock, 'PATCH')[0][1]!.body as string)).toEqual({ active: true })
    await waitFor(() => expect(fetchMock.mock.calls.filter(([, i]) => !i?.method)).toHaveLength(4))
  })

  it('sends one POST when Guardar on the add row is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(undefined, async () => {
      await gate
      return json({ id: 's9' }, 201)
    })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Adicionar turno' }))
    fireEvent.change(screen.getByLabelText('Nome do turno'), { target: { value: 'Tarde' } })
    const save = screen.getByRole('button', { name: 'Guardar' })
    fireEvent.click(save)
    fireEvent.click(save)
    expect((save as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(screen.queryByLabelText('Nome do turno')).toBeNull())
    expect(writes(fetchMock, 'POST')).toHaveLength(1)
  })

  it('sends one PUT when Guardar on the edit row is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(undefined, async () => {
      await gate
      return json({ id: 's1' })
    })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Editar Madrugada' }))
    const save = screen.getByRole('button', { name: 'Guardar' })
    fireEvent.click(save)
    fireEvent.click(save)
    expect((save as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(screen.queryByLabelText('Nome do turno')).toBeNull())
    expect(writes(fetchMock, 'PUT')).toHaveLength(1)
  })

  it('sends one DELETE when deactivate is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(undefined, async () => {
      await gate
      return new Response(null, { status: 204 })
    })
    renderPage()

    const deactivate = await screen.findByRole('button', { name: 'Desativar Manhã' })
    fireEvent.click(deactivate)
    fireEvent.click(deactivate)
    expect((deactivate as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(fetchMock.mock.calls.filter(([, i]) => !i?.method)).toHaveLength(3))
    expect(writes(fetchMock, 'DELETE')).toHaveLength(1)
  })

  it('says only managed teams can be seen when the shifts return 404', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => (url === '/api/teams' ? json(teams) : json({}, 404))))
    renderPage()

    expect((await screen.findByRole('alert')).textContent).toBe('Só pode ver os turnos das equipas que gere.')
  })

  it('shows an empty line and the add row when there are no templates', async () => {
    stubApi([])
    renderPage()

    expect(await screen.findByText('Esta equipa ainda não tem turnos.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Adicionar turno' })).toBeTruthy()
  })

  it('clears a failed toggle message once a later save succeeds', async () => {
    stubApi([night, day, retired], async (_url, init) => (init?.method === 'DELETE' ? json({}, 500) : json({ id: 's9' }, 201)))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Desativar Manhã' }))
    expect(await screen.findByRole('alert')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Adicionar turno' }))
    fireEvent.change(screen.getByLabelText('Nome do turno'), { target: { value: 'Tarde' } })
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await waitFor(() => expect(screen.queryByLabelText('Nome do turno')).toBeNull())
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('asks before switching team with unsaved edits and only switches when confirmed', async () => {
    const fetchMock = stubApi()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Editar Madrugada' }))
    fireEvent.change(screen.getByLabelText('Máximo'), { target: { value: '4' } })
    fireEvent.change(screen.getByLabelText('Escolher equipa'), { target: { value: 't2' } })

    expect(confirm).toHaveBeenCalledWith('Tem alterações por guardar neste turno. Descartar?')
    expect((screen.getByLabelText('Escolher equipa') as HTMLSelectElement).value).toBe('t1')
    expect((screen.getByLabelText('Nome do turno') as HTMLInputElement).value).toBe('Madrugada')

    fireEvent.change(screen.getByLabelText('Escolher equipa'), { target: { value: 't2' } })
    await waitFor(() => expect(fetchMock.mock.calls.map(([u]) => u)).toContain('/api/teams/t2/shift-templates'))
    expect((screen.getByLabelText('Escolher equipa') as HTMLSelectElement).value).toBe('t2')
    expect(screen.queryByLabelText('Nome do turno')).toBeNull()
  })
})
