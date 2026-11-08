import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RulesPage } from './RulesPage'

const teams = [
  { id: 't1', name: 'Urgências' },
  { id: 't2', name: 'Cardiologia' },
]
const rules = {
  minRestBetweenShiftsMin: 720,
  maxConsecutiveWorkMin: 2880,
  maxAvgWeeklyWorkMin: 2400,
  avgReferenceWeeks: 4,
  minWeeklyRestMin: 2160,
  maxConsecutiveNights: null,
  restAfterNightRunMin: null,
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function renderPage(url = '/regras') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <RulesPage />
    </MemoryRouter>,
  )
}

// Answers the teams list and the rules GET; every other request goes to onWrite.
function stubApi(onWrite: (url: string, init?: RequestInit) => Promise<Response> = async () => new Response(null, { status: 204 })) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/teams') return json(teams)
    if (!init?.method) return json(rules)
    return onWrite(url, init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('RulesPage', () => {
  it('loads the rules and shows durations in hours', async () => {
    stubApi()
    renderPage()

    expect(((await screen.findByLabelText('Descanso mínimo entre turnos')) as HTMLInputElement).value).toBe('12')
    expect((screen.getByLabelText('Trabalho consecutivo máximo') as HTMLInputElement).value).toBe('48')
    expect((screen.getByLabelText('Máximo de noites seguidas') as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText('Descanso após noites seguidas') as HTMLInputElement).value).toBe('')
  })

  it('saves the whole rule set in minutes and sends empty night fields as null', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.change(await screen.findByLabelText('Descanso mínimo entre turnos'), { target: { value: '11.5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    expect((await screen.findByRole('status')).textContent).toBe('Regras guardadas.')
    const puts = fetchMock.mock.calls.filter(([, i]) => i?.method === 'PUT')
    expect(puts).toHaveLength(1)
    expect(puts[0][0]).toBe('/api/teams/t1/rules')
    expect(JSON.parse(puts[0][1]!.body as string)).toEqual({ ...rules, minRestBetweenShiftsMin: 690 })
  })

  it('saves 0 as the night limit, not null', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.change(await screen.findByLabelText('Máximo de noites seguidas'), { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Guardar' }))

    await screen.findByRole('status')
    const puts = fetchMock.mock.calls.filter(([, i]) => i?.method === 'PUT')
    expect(puts).toHaveLength(1)
    expect(puts[0][1]!.body as string).toContain('"maxConsecutiveNights":0')
  })

  it('keeps Guardar disabled until a field changes', async () => {
    stubApi()
    renderPage()

    const save = await screen.findByRole('button', { name: 'Guardar' })
    expect((save as HTMLButtonElement).disabled).toBe(true)
    expect(screen.queryByText('Alterações por guardar')).toBeNull()

    fireEvent.change(screen.getByLabelText('Descanso mínimo entre turnos'), { target: { value: '11' } })
    expect((save as HTMLButtonElement).disabled).toBe(false)
    expect(screen.getByText('Alterações por guardar')).toBeTruthy()
  })

  it('sends one PUT when Guardar is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(async () => {
      await gate
      return new Response(null, { status: 204 })
    })
    renderPage()

    fireEvent.change(await screen.findByLabelText('Descanso mínimo entre turnos'), { target: { value: '11' } })
    const save = screen.getByRole('button', { name: 'Guardar' })
    fireEvent.click(save)
    fireEvent.click(save)
    expect((save as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(screen.getByRole('status')).toBeTruthy())
    expect(fetchMock.mock.calls.filter(([, i]) => i?.method === 'PUT')).toHaveLength(1)
  })

  it('does nothing when the copy is cancelled', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const fetchMock = stubApi()
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: "Substituir regras" }))

    expect(confirm).toHaveBeenCalledWith('Substituir todas as regras de Urgências pelas de Cardiologia?')
    expect(fetchMock.mock.calls.map(([u, i]) => [u, i?.method ?? 'GET'])).toEqual([
      ['/api/teams', 'GET'],
      ['/api/teams/t1/rules', 'GET'],
    ])
  })

  it('copies from the other team when confirmed, then reloads the rules', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const fetchMock = stubApi()
    renderPage()

    await screen.findByLabelText('Descanso mínimo entre turnos')
    fireEvent.click(screen.getByRole('button', { name: "Substituir regras" }))

    await waitFor(() => expect(fetchMock.mock.calls.map(([u, i]) => [u, i?.method ?? 'GET'])).toEqual([
      ['/api/teams', 'GET'],
      ['/api/teams/t1/rules', 'GET'],
      ['/api/teams/t1/rules/copy', 'POST'],
      ['/api/teams/t1/rules', 'GET'],
    ]))
    expect(JSON.parse(fetchMock.mock.calls[2][1]!.body as string)).toEqual({ fromTeamId: 't2' })
  })

  it('sends one POST when Copiar is confirmed twice quickly', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(async () => {
      await gate
      return new Response(null, { status: 204 })
    })
    renderPage()

    await screen.findByLabelText('Descanso mínimo entre turnos')
    const copy = screen.getByRole('button', { name: "Substituir regras" })
    fireEvent.click(copy)
    fireEvent.click(copy)
    expect((copy as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect((copy as HTMLButtonElement).disabled).toBe(false))
    expect(fetchMock.mock.calls.filter(([, i]) => i?.method === 'POST')).toHaveLength(1)
  })

  it('selects the team named in ?team=', async () => {
    const fetchMock = stubApi()
    renderPage('/regras?team=t2')

    expect(await screen.findByLabelText('Descanso mínimo entre turnos')).toBeTruthy()
    expect((screen.getByLabelText('Escolher equipa') as HTMLSelectElement).value).toBe('t2')
    expect(fetchMock.mock.calls.map(([u]) => u)).toContain('/api/teams/t2/rules')
  })

  it('says only managed teams can be seen when the rules return 404', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => (url === '/api/teams' ? json(teams) : json({}, 404))))
    renderPage()

    expect((await screen.findByRole('alert')).textContent).toBe('Só pode ver as regras das equipas que gere.')
  })

  it('hides the copy section when there is no other team', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => (url === '/api/teams' ? json([teams[0]]) : json(rules))))
    renderPage()

    await screen.findByLabelText('Descanso mínimo entre turnos')
    expect(screen.queryByRole('button', { name: "Substituir regras" })).toBeNull()
  })

  it('asks before switching team with unsaved edits and keeps team and values when cancelled', async () => {
    const fetchMock = stubApi()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPage()

    fireEvent.change(await screen.findByLabelText('Descanso mínimo entre turnos'), { target: { value: '11' } })
    fireEvent.change(screen.getByLabelText('Escolher equipa'), { target: { value: 't2' } })

    expect(confirm).toHaveBeenCalledWith('Tem alterações por guardar nestas regras. Descartar?')
    expect((screen.getByLabelText('Escolher equipa') as HTMLSelectElement).value).toBe('t1')
    expect((screen.getByLabelText('Descanso mínimo entre turnos') as HTMLInputElement).value).toBe('11')
    expect(fetchMock.mock.calls.map(([u]) => u)).not.toContain('/api/teams/t2/rules')
  })
})
