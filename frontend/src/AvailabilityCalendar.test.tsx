import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AvailabilityCalendar } from './AvailabilityCalendar'

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

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('AvailabilityCalendar', () => {
  it('announces each slot state, tags manager entries and summarises the marks', async () => {
    stubApi()
    renderCalendar()

    const manager = await screen.findByRole('button', { name: 'Madrugada 07/11/2026: Se necessário, introduzido pelo gestor' })
    expect(within(manager).getByText('Pelo gestor')).toBeTruthy()
    expect(screen.getByText('Pelo gestor: marcas introduzidas pelo seu gestor.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Manhã 07/11/2026: Disponível' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Manhã 08/11/2026: Não disponível' })).toBeTruthy()
    expect(screen.getByText('1 disponível · 1 se necessário. Turnos sem marca contam como indisponível.')).toBeTruthy()
  })

  it('switches the brush with aria-pressed', async () => {
    stubApi()
    renderCalendar()

    const available = await screen.findByRole('button', { name: 'Disponível' })
    const ifNeeded = screen.getByRole('button', { name: 'Se necessário' })
    expect(available.getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(ifNeeded)
    expect(ifNeeded.getAttribute('aria-pressed')).toBe('true')
    expect(available.getAttribute('aria-pressed')).toBe('false')
  })

  it('sends one PATCH with the brush mark for a tapped slot and shows it at once', async () => {
    const fetchMock = stubApi()
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'Manhã 08/11/2026: Não disponível' }))

    expect(await screen.findByRole('button', { name: 'Manhã 08/11/2026: Disponível' })).toBeTruthy()
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(writes(fetchMock)[0][0]).toBe(URL)
    expect(bodyOf(writes(fetchMock)[0])).toEqual({ marks: [{ slotId: 's3', kind: 'AVAILABLE' }] })
  })

  it('clears a slot already holding the brush kind with a null mark', async () => {
    const fetchMock = stubApi()
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'Manhã 07/11/2026: Disponível' }))

    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(bodyOf(writes(fetchMock)[0])).toEqual({ marks: [{ slotId: 's1', kind: null }] })
    expect(await screen.findByRole('button', { name: 'Manhã 07/11/2026: Não disponível' })).toBeTruthy()
  })

  it('sends one PATCH when the same slot is tapped twice quickly', async () => {
    const { release, onWrite } = gated()
    const fetchMock = stubApi(availability(), onWrite)
    renderCalendar()

    const slot = await screen.findByRole('button', { name: 'Manhã 08/11/2026: Não disponível' })
    fireEvent.click(slot)
    fireEvent.click(slot)

    release()
    await screen.findByText(/Guardado/)
    expect(writes(fetchMock)).toHaveLength(1)
  })

  it('sends one PATCH per slot when two different slots are tapped quickly', async () => {
    const { release, onWrite } = gated()
    const fetchMock = stubApi(availability(), onWrite)
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'Manhã 08/11/2026: Não disponível' }))
    fireEvent.click(screen.getByRole('button', { name: 'Madrugada 08/11/2026: Não disponível' }))

    release()
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(2))
    expect(bodyOf(writes(fetchMock)[1])).toEqual({ marks: [{ slotId: 's4', kind: 'AVAILABLE' }] })
  })

  it('reverts the slot and shows an alert when the save fails', async () => {
    stubApi(availability(), async () => new Response(null, { status: 500 }))
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'Manhã 08/11/2026: Não disponível' }))

    expect((await screen.findByRole('alert')).textContent).toBe('Não foi possível guardar. Tente novamente.')
    expect(await screen.findByRole('button', { name: 'Manhã 08/11/2026: Não disponível' })).toBeTruthy()
  })

  it('says the period changed and refetches when the save returns 409', async () => {
    const fetchMock = stubApi(availability(), async () => new Response(null, { status: 409 }))
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'Manhã 08/11/2026: Não disponível' }))

    expect((await screen.findByRole('alert')).textContent).toBe('O período mudou entretanto.')
    await waitFor(() => expect(gets(fetchMock)).toHaveLength(2))
  })

  it('applies the brush to a whole day with one PATCH of the changed slots', async () => {
    const fetchMock = stubApi()
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'Se necessário' }))
    fireEvent.click(screen.getByRole('button', { name: /^Dia e noite .*07\/11$/ }))

    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(bodyOf(writes(fetchMock)[0])).toEqual({ marks: [{ slotId: 's1', kind: 'IF_NEEDED' }] })
  })

  it('clears a whole day when every slot already has the brush kind', async () => {
    const fetchMock = stubApi(availability({ slots: [slots[0], { ...slots[1], kind: 'AVAILABLE', enteredByManager: false }] }))
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: /^Limpar dia .*07\/11$/ }))

    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(bodyOf(writes(fetchMock)[0])).toEqual({ marks: [{ slotId: 's1', kind: null }, { slotId: 's2', kind: null }] })
  })

  it('sends one PATCH when Dia todo is clicked twice quickly', async () => {
    const { release, onWrite } = gated()
    const fetchMock = stubApi(availability(), onWrite)
    renderCalendar()

    const day = await screen.findByRole('button', { name: /^Dia e noite .*08\/11$/ })
    fireEvent.click(day)
    fireEvent.click(day)

    release()
    await screen.findByText(/Guardado/)
    expect(writes(fetchMock)).toHaveLength(1)
  })

  it('applies the brush to every night with one PATCH', async () => {
    const fetchMock = stubApi()
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'Todas as noites' }))

    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(bodyOf(writes(fetchMock)[0])).toEqual({
      marks: [
        { slotId: 's2', kind: 'AVAILABLE' },
        { slotId: 's4', kind: 'AVAILABLE' },
      ],
    })
  })

  it('clears every night when all of them already have the brush kind', async () => {
    const fetchMock = stubApi(
      availability({
        slots: [slots[0], { ...slots[1], kind: 'AVAILABLE', enteredByManager: false }, slots[2], { ...slots[3], kind: 'AVAILABLE' }],
      }),
    )
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'Limpar noites' }))

    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(bodyOf(writes(fetchMock)[0])).toEqual({
      marks: [
        { slotId: 's2', kind: null },
        { slotId: 's4', kind: null },
      ],
    })
  })

  it('sends one PATCH when Todas as noites is clicked twice quickly', async () => {
    const { release, onWrite } = gated()
    const fetchMock = stubApi(availability(), onWrite)
    renderCalendar()

    const nights = await screen.findByRole('button', { name: 'Todas as noites' })
    fireEvent.click(nights)
    fireEvent.click(nights)

    release()
    await screen.findByText(/Guardado/)
    expect(writes(fetchMock)).toHaveLength(1)
  })

  it('renders a closed period read-only: no buttons, and the state word on each slot', async () => {
    stubApi(availability({ editable: false, period: { startDate: '2026-11-07', endDate: '2026-11-08', status: 'CLOSED', inputDeadline: null } }))
    renderCalendar()

    expect(await screen.findByText('Período fechado. Já não é possível alterar esta disponibilidade.')).toBeTruthy()
    expect(screen.queryAllByRole('button')).toHaveLength(0)
    expect(screen.getByRole('group', { name: 'Manhã 07/11/2026: Disponível' })).toBeTruthy()
    expect(screen.getByRole('group', { name: 'Manhã 08/11/2026: Não disponível' })).toBeTruthy()
    expect(screen.getAllByText('Não disponível').length).toBeGreaterThan(0)
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

      fireEvent.click(screen.getByRole('button', { name: 'Manhã 08/11/2026: Não disponível' }))
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
