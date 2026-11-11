import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AvailabilityCalendar } from './AvailabilityCalendar'

// Period 07/11 (Sat) to 08/11 (Sun). 07/11 already has marks, so 08/11 is the day that opens.
const slots = [
  { id: 's1', date: '2026-11-07', templateName: 'Manhã', startTime: '08:00', endTime: '20:00', isNight: false, kind: 'AVAILABLE', enteredByManager: false },
  { id: 's2', date: '2026-11-07', templateName: 'Madrugada', startTime: '20:00', endTime: '08:00', isNight: true, kind: 'IF_NEEDED', enteredByManager: true },
  { id: 's3', date: '2026-11-08', templateName: 'Manhã', startTime: '08:00', endTime: '20:00', isNight: false, kind: null, enteredByManager: false },
  { id: 's4', date: '2026-11-08', templateName: 'Madrugada', startTime: '20:00', endTime: '08:00', isNight: true, kind: null, enteredByManager: false },
]
const availability = (overrides: object = {}) => ({
  editable: true,
  period: { startDate: '2026-11-07', endDate: '2026-11-08', status: 'OPEN', inputDeadline: '2099-11-05T12:00:00.000Z' },
  slots,
  ...overrides,
})

const URL = '/api/teams/t1/periods/p1/members/u1/availability'
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
const ok = async () => new Response(null, { status: 204 })

function stubApi(data: object = availability(), onWrite: (url: string, init?: RequestInit) => Promise<Response> = ok) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => (init?.method ? onWrite(url, init) : json(data)))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const writes = (fetchMock: ReturnType<typeof stubApi>) => fetchMock.mock.calls.filter(([, i]) => i?.method === 'PATCH')
const bodyOf = (call: unknown[]) => JSON.parse((call[1] as RequestInit).body as string)
const gets = (fetchMock: ReturnType<typeof stubApi>) => fetchMock.mock.calls.filter(([, i]) => !i?.method)

const renderCalendar = () => render(<AvailabilityCalendar teamId="t1" periodId="p1" userId="u1" />)

// Holds PATCH replies until release() so a test can tap again while a request is in flight.
function gated() {
  let release!: () => void
  const gate = new Promise<void>((resolve) => (release = resolve))
  return { release, onWrite: async () => (await gate, new Response(null, { status: 204 })) }
}

// The option buttons of one slot in the day panel.
const optionsOf = async (template: string) => within(await screen.findByRole('group', { name: `Marca para ${template}` }))
const dayButton = (pattern: RegExp) => screen.getByRole('button', { name: pattern })

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('AvailabilityCalendar', () => {
  it('renders the month grid with a chip per slot and opens on the first unmarked day', async () => {
    stubApi()
    renderCalendar()

    const grid = await screen.findByRole('group', { name: 'Calendário de disponibilidade' })
    expect(within(grid).getAllByRole('button')).toHaveLength(2)
    expect(within(grid).getByRole('button', { name: /07\/11: Manhã Disponível, Madrugada Se necessário$/ }).getAttribute('aria-pressed')).toBe('false')
    expect(within(grid).getByRole('button', { name: /08\/11: Manhã Não disponível, Madrugada Não disponível$/ }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByText('1 disponível · 1 se necessário. Turnos sem marca contam como indisponível.')).toBeTruthy()
  })

  it('shows the panel of the clicked day with its turns', async () => {
    stubApi()
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: /07\/11: Manhã Disponível/ }))

    expect(screen.getByRole('button', { name: /07\/11/ }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('heading', { level: 3, name: /07\/11$/ })).toBeTruthy()
    expect(screen.getByText('Pelo gestor')).toBeTruthy()
    expect(screen.getByText('Pelo gestor: marcas introduzidas pelo seu gestor.')).toBeTruthy()
  })

  it('sends one PATCH for Se necessário and shows the chip at once', async () => {
    const fetchMock = stubApi()
    renderCalendar()

    fireEvent.click((await optionsOf('Manhã')).getByRole('button', { name: 'Se necessário' }))

    expect(await screen.findByRole('button', { name: /08\/11: Manhã Se necessário, Madrugada Não disponível$/ })).toBeTruthy()
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(writes(fetchMock)[0][0]).toBe(URL)
    expect(bodyOf(writes(fetchMock)[0])).toEqual({ marks: [{ slotId: 's3', kind: 'IF_NEEDED' }] })
  })

  it('sends a null mark for Não', async () => {
    const fetchMock = stubApi()
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: /07\/11: Manhã Disponível/ }))
    fireEvent.click((await optionsOf('Manhã')).getByRole('button', { name: 'Não' }))

    expect(await screen.findByRole('button', { name: /07\/11: Manhã Não disponível, Madrugada Se necessário$/ })).toBeTruthy()
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(bodyOf(writes(fetchMock)[0])).toEqual({ marks: [{ slotId: 's1', kind: null }] })
  })

  it('does not send a mark for the option that is already set', async () => {
    const fetchMock = stubApi()
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: /07\/11: Manhã Disponível/ }))
    fireEvent.click((await optionsOf('Manhã')).getByRole('button', { name: 'Disponível' }))

    await act(async () => {
      await Promise.resolve()
    })
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it('applies Dia e noite to the changed slots only', async () => {
    const fetchMock = stubApi()
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: /07\/11: Manhã Disponível/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Dia e noite' }))

    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(bodyOf(writes(fetchMock)[0])).toEqual({ marks: [{ slotId: 's2', kind: 'AVAILABLE' }] })
    expect(await screen.findByRole('button', { name: 'Limpar dia' })).toBeTruthy()
  })

  it('clears the day with Limpar dia when every slot is already available', async () => {
    const fetchMock = stubApi(availability({ slots: [slots[0], { ...slots[1], kind: 'AVAILABLE', enteredByManager: false }] }))
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'Limpar dia' }))

    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(bodyOf(writes(fetchMock)[0])).toEqual({ marks: [{ slotId: 's1', kind: null }, { slotId: 's2', kind: null }] })
    expect(await screen.findByRole('button', { name: 'Dia e noite' })).toBeTruthy()
  })

  it('sends one PATCH when the same option is tapped twice quickly', async () => {
    const { release, onWrite } = gated()
    const fetchMock = stubApi(availability(), onWrite)
    renderCalendar()

    const option = (await optionsOf('Manhã')).getByRole('button', { name: 'Se necessário' })
    fireEvent.click(option)
    fireEvent.click(option)

    release()
    await screen.findByText(/Guardado/)
    expect(writes(fetchMock)).toHaveLength(1)
  })

  it('sends one PATCH when Dia e noite is tapped twice quickly', async () => {
    const { release, onWrite } = gated()
    const fetchMock = stubApi(availability(), onWrite)
    renderCalendar()

    const bulk = await screen.findByRole('button', { name: 'Dia e noite' })
    fireEvent.click(bulk)
    fireEvent.click(bulk)

    release()
    await screen.findByText(/Guardado/)
    expect(writes(fetchMock)).toHaveLength(1)
  })

  it('sends one PATCH per slot when two different slots are tapped quickly', async () => {
    const { release, onWrite } = gated()
    const fetchMock = stubApi(availability(), onWrite)
    renderCalendar()

    fireEvent.click((await optionsOf('Manhã')).getByRole('button', { name: 'Se necessário' }))
    fireEvent.click((await optionsOf('Madrugada')).getByRole('button', { name: 'Se necessário' }))

    release()
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(2))
    expect(bodyOf(writes(fetchMock)[1])).toEqual({ marks: [{ slotId: 's4', kind: 'IF_NEEDED' }] })
  })

  it('reverts the slot and shows an alert when the save fails', async () => {
    stubApi(availability(), async () => new Response(null, { status: 500 }))
    renderCalendar()

    fireEvent.click((await optionsOf('Manhã')).getByRole('button', { name: 'Se necessário' }))

    expect((await screen.findByRole('alert')).textContent).toBe('Não foi possível guardar. Tente novamente.')
    expect(await screen.findByRole('button', { name: /08\/11: Manhã Não disponível/ })).toBeTruthy()
  })

  it('says the period changed and refetches when the save returns 409', async () => {
    const fetchMock = stubApi(availability(), async () => new Response(null, { status: 409 }))
    renderCalendar()

    fireEvent.click((await optionsOf('Manhã')).getByRole('button', { name: 'Se necessário' }))

    expect((await screen.findByRole('alert')).textContent).toBe('O período mudou entretanto.')
    await waitFor(() => expect(gets(fetchMock)).toHaveLength(2))
  })

  it('moves the selection with the arrow keys and clamps it to the period', async () => {
    stubApi()
    renderCalendar()

    const day8 = await screen.findByRole('button', { name: /08\/11: Manhã Não disponível/ })
    fireEvent.keyDown(day8, { key: 'ArrowLeft' })

    const day7 = dayButton(/07\/11: Manhã/)
    expect(day7.getAttribute('aria-pressed')).toBe('true')
    expect(document.activeElement).toBe(day7)

    fireEvent.keyDown(day7, { key: 'ArrowDown' })
    expect(dayButton(/08\/11: Manhã/).getAttribute('aria-pressed')).toBe('true')

    fireEvent.keyDown(dayButton(/08\/11: Manhã/), { key: 'ArrowUp' })
    expect(dayButton(/07\/11: Manhã/).getAttribute('aria-pressed')).toBe('true')
  })

  it('disables the previous and next day buttons at the period edges', async () => {
    stubApi()
    renderCalendar()

    await screen.findByRole('button', { name: /08\/11: Manhã/ })
    const prev = screen.getByRole('button', { name: 'Dia anterior' }) as HTMLButtonElement
    const next = screen.getByRole('button', { name: 'Dia seguinte' }) as HTMLButtonElement
    expect(next.disabled).toBe(true)
    expect(prev.disabled).toBe(false)

    fireEvent.click(prev)

    expect(dayButton(/07\/11: Manhã/).getAttribute('aria-pressed')).toBe('true')
    expect(prev.disabled).toBe(true)
    expect(next.disabled).toBe(false)
  })

  it('shows Sem turnos neste dia. for a day without turns', async () => {
    stubApi(availability({ slots: [slots[0], slots[1]] }))
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: /08\/11$/ }))

    expect(screen.getByText('Sem turnos neste dia.')).toBeTruthy()
    expect(screen.getByRole('button', { name: /08\/11$/ }).getAttribute('aria-pressed')).toBe('true')
  })

  it('shows the short month on day 1 when the period spans two months', async () => {
    stubApi(availability({ period: { startDate: '2026-11-30', endDate: '2026-12-01', status: 'OPEN', inputDeadline: null }, slots: [] }))
    renderCalendar()

    expect(await screen.findByRole('button', { name: /01\/12$/ })).toBeTruthy()
    expect(screen.getByText('dez')).toBeTruthy()
  })

  it('renders a closed period read-only: the grid stays selectable, the panel has no option buttons', async () => {
    stubApi(availability({ editable: false, period: { startDate: '2026-11-07', endDate: '2026-11-08', status: 'CLOSED', inputDeadline: null } }))
    renderCalendar()

    expect(await screen.findByText('Período fechado. Já não é possível alterar esta disponibilidade.')).toBeTruthy()
    expect(within(screen.getByRole('group', { name: 'Calendário de disponibilidade' })).getAllByRole('button')).toHaveLength(2)
    expect(screen.queryByRole('group', { name: /Marca para/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Não' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Dia e noite' })).toBeNull()
    expect(screen.getAllByText('Não disponível').length).toBeGreaterThan(0)

    fireEvent.click(screen.getByRole('button', { name: /07\/11: Manhã/ }))
    expect(screen.getByText('Disponível', { selector: 'span.font-bold' })).toBeTruthy()
  })

  it('shows A guardar… while saving, Guardado after the save, and clears Guardado after two seconds', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const { release, onWrite } = gated()
      stubApi(availability(), onWrite)
      renderCalendar()
      await act(async () => {
        for (let i = 0; i < 10; i++) await Promise.resolve()
      })

      fireEvent.click(within(screen.getByRole('group', { name: 'Marca para Manhã' })).getByRole('button', { name: 'Se necessário' }))
      expect(screen.getByRole('status').textContent).toContain('A guardar…')

      await act(async () => {
        release()
        for (let i = 0; i < 10; i++) await Promise.resolve()
      })
      expect(screen.getByRole('status').textContent).toContain('Guardado')

      act(() => {
        vi.advanceTimersByTime(2000)
      })
      expect(screen.getByRole('status').textContent).toBe('')
    } finally {
      vi.useRealTimers()
    }
  })
})
