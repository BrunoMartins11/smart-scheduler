import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PeriodsPage } from './PeriodsPage'

const teams = [
  { id: 't1', name: 'Urgências' },
  { id: 't2', name: 'Cardiologia' },
]
// Newest first, as the API returns them.
const draft = { id: 'p2', startDate: '2026-12-05', endDate: '2026-12-06', status: 'DRAFT', inputDeadline: null }
const open = { id: 'p1', startDate: '2026-11-07', endDate: '2026-11-08', status: 'OPEN', inputDeadline: '2026-11-05T12:00:00.000Z' }
const closed = { id: 'p3', startDate: '2026-10-03', endDate: '2026-10-04', status: 'CLOSED', inputDeadline: '2026-09-30T12:00:00.000Z' }
const published = { id: 'p4', startDate: '2026-09-05', endDate: '2026-09-06', status: 'PUBLISHED', inputDeadline: '2026-09-01T12:00:00.000Z' }
const periods = [draft, open, closed, published]

const manha = { templateId: 'tm1', templateName: 'Manhã', startTime: '08:00', endTime: '20:00', isNight: false }
const madrugada = { templateId: 'tm2', templateName: 'Madrugada', startTime: '20:00', endTime: '08:00', isNight: true }
// sl4 was edited from the template's 2–4, so it is overridden.
const slots = [
  { id: 'sl1', date: '2026-11-07', ...manha, minStaff: 1, maxStaff: 2, defaultMinStaff: 1, defaultMaxStaff: 2 },
  { id: 'sl2', date: '2026-11-07', ...madrugada, minStaff: 2, maxStaff: 3, defaultMinStaff: 2, defaultMaxStaff: 3 },
  { id: 'sl3', date: '2026-11-08', ...manha, minStaff: 1, maxStaff: 2, defaultMinStaff: 1, defaultMaxStaff: 2 },
  { id: 'sl4', date: '2026-11-08', ...madrugada, minStaff: 2, maxStaff: 3, defaultMinStaff: 2, defaultMaxStaff: 4 },
]

const openRange = '07/11/2026 – 08/11/2026'
const draftRange = '05/12/2026 – 06/12/2026'
const closedRange = '03/10/2026 – 04/10/2026'
const publishedRange = '05/09/2026 – 06/09/2026'

// jsdom has no showModal/close. This stands in for the browser: open attribute, close event, and Esc
// dispatching a cancelable 'cancel' before closing, as the spec does.
const escapes = new WeakMap<HTMLDialogElement, (e: KeyboardEvent) => void>()
HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
  this.setAttribute('open', '')
  const esc = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && this.dispatchEvent(new Event('cancel', { cancelable: true }))) this.close()
  }
  escapes.set(this, esc)
  this.addEventListener('keydown', esc)
}
HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
  this.removeAttribute('open')
  const esc = escapes.get(this)
  if (esc) this.removeEventListener('keydown', esc)
  this.dispatchEvent(new Event('close'))
}

const submissionList = [
  { userId: 'u1', displayName: 'Ana Silva', status: 'NOT_SUBMITTED' },
  { userId: 'u2', displayName: 'Bruno Costa', status: 'MEMBER' },
]
const memberAvailability = {
  editable: true,
  period: { startDate: '2026-11-07', endDate: '2026-11-08', status: 'OPEN', inputDeadline: null },
  slots: [{ id: 'sl9', date: '2026-11-07', templateName: 'Manhã', startTime: '08:00', endTime: '20:00', isNight: false, kind: null, enteredByManager: false }],
}
const MEMBER_URL = '/api/teams/t1/periods/p1/members/u1/availability'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function renderPage(url = '/escala?team=t1') {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <PeriodsPage />
      <SearchProbe />
    </MemoryRouter>,
  )
}

function SearchProbe() {
  return <p data-testid="search">{useLocation().search}</p>
}

// Answers the teams list and the period and slot GETs; every other request goes to onWrite.
function stubApi(
  list: unknown[] = periods,
  slotList: unknown[] = slots,
  onWrite: (url: string, init?: RequestInit) => Promise<Response> = async (_url, init) =>
    init?.method === 'POST' ? json({ id: 'p9' }, 201) : new Response(null, { status: 204 }),
) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/teams') return json(teams)
    if (init?.method) return onWrite(url, init)
    if (url.endsWith('/slots')) return json(slotList)
    if (url.endsWith('/submissions')) return json(submissionList)
    if (url.includes('/members/')) return json(memberAvailability)
    return json(list)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const writes = (fetchMock: ReturnType<typeof stubApi>, method: string) =>
  fetchMock.mock.calls.filter(([, i]) => i?.method === method)
const gets = (fetchMock: ReturnType<typeof stubApi>, url: string) =>
  fetchMock.mock.calls.filter(([u, i]) => u === url && !i?.method)

// The grid cell at rest, and the inputs once it is open.
const cellButton = (name: string) => screen.findByRole('button', { name })
const minInput = (name: string) => screen.findByRole('spinbutton', { name: `Mínimo ${name}` })
const SAT_MANHA = 'Manhã 07/11/2026'
const SAT_MANHA_LABEL = `${SAT_MANHA}: mínimo 1, máximo 2`

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('PeriodsPage', () => {
  it('lists each period with its status, dates and deadline, and flags a past deadline', async () => {
    stubApi()
    renderPage()

    const openRow = (await screen.findByRole('rowheader', { name: openRange })).closest('tr')!
    expect(within(openRow).getByText('Aberto')).toBeTruthy()
    expect(within(openRow).getByText(/05\/11\/2026/)).toBeTruthy()
    expect(within(openRow).getByText('Prazo passado')).toBeTruthy()

    const draftRow = screen.getByRole('rowheader', { name: draftRange }).closest('tr')!
    expect(within(draftRow).getByText('Rascunho')).toBeTruthy()
    expect(within(draftRow).getByText('—')).toBeTruthy()

    expect(within(screen.getByRole('rowheader', { name: closedRange }).closest('tr')!).getByText('Fechado')).toBeTruthy()
    expect(within(screen.getByRole('rowheader', { name: publishedRange }).closest('tr')!).getByText('Publicado')).toBeTruthy()
  })

  it('creates a period with one POST, then closes the form and refetches the list', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Novo período' }))
    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-12-20' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-12-26' } })
    fireEvent.click(screen.getByRole('button', { name: 'Criar período' }))

    await waitFor(() => expect(screen.queryByLabelText('Início')).toBeNull())
    const posts = writes(fetchMock, 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0][0]).toBe('/api/teams/t1/periods')
    expect(JSON.parse(posts[0][1]!.body as string)).toEqual({ startDate: '2026-12-20', endDate: '2026-12-26' })
    await waitFor(() => expect(gets(fetchMock, '/api/teams/t1/periods')).toHaveLength(2))
  })

  it('sends one POST when Criar período is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(periods, slots, async () => {
      await gate
      return json({ id: 'p9' }, 201)
    })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Novo período' }))
    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-12-20' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-12-26' } })
    const submit = screen.getByRole('button', { name: 'Criar período' })
    fireEvent.click(submit)
    fireEvent.click(submit)
    expect((submit as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(screen.queryByLabelText('Início')).toBeNull())
    expect(writes(fetchMock, 'POST')).toHaveLength(1)
  })

  it('blocks a period that ends before it starts without sending anything', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Novo período' }))
    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-12-20' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-12-10' } })
    fireEvent.click(screen.getByRole('button', { name: 'Criar período' }))

    expect((await screen.findByRole('alert')).textContent).toBe('O fim tem de ser igual ou posterior ao início.')
    expect(writes(fetchMock, 'POST')).toHaveLength(0)
  })

  it('says the period overlaps another one when the create returns 409', async () => {
    stubApi(periods, slots, async () => new Response(null, { status: 409 }))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Novo período' }))
    fireEvent.change(screen.getByLabelText('Início'), { target: { value: '2026-11-10' } })
    fireEvent.change(screen.getByLabelText('Fim'), { target: { value: '2026-11-12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Criar período' }))

    expect((await screen.findByRole('alert')).textContent).toBe('Este período sobrepõe-se a outro período da equipa.')
    expect(screen.getByLabelText('Início')).toBeTruthy()
  })

  it('opens a draft with one POST whose deadline is an ISO instant', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: `Abrir período ${draftRange}` }))
    fireEvent.change(screen.getByLabelText('Prazo de entrega'), { target: { value: '2099-12-01T18:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Abrir período' }))

    await waitFor(() => expect(writes(fetchMock, 'POST')).toHaveLength(1))
    expect(writes(fetchMock, 'POST')[0][0]).toBe('/api/teams/t1/periods/p2/status')
    expect(JSON.parse(writes(fetchMock, 'POST')[0][1]!.body as string)).toEqual({
      status: 'OPEN',
      inputDeadline: new Date('2099-12-01T18:00').toISOString(),
    })
  })

  it('blocks opening a draft with a deadline in the past', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: `Abrir período ${draftRange}` }))
    fireEvent.change(screen.getByLabelText('Prazo de entrega'), { target: { value: '2020-01-01T10:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Abrir período' }))

    expect((await screen.findByRole('alert')).textContent).toBe('O prazo tem de ser no futuro.')
    expect(writes(fetchMock, 'POST')).toHaveLength(0)
  })

  it('sends one POST when Abrir período is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(periods, slots, async () => {
      await gate
      return new Response(null, { status: 204 })
    })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: `Abrir período ${draftRange}` }))
    fireEvent.change(screen.getByLabelText('Prazo de entrega'), { target: { value: '2099-12-01T18:00' } })
    const confirm = screen.getByRole('button', { name: 'Abrir período' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    expect((confirm as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(gets(fetchMock, '/api/teams/t1/periods')).toHaveLength(2))
    expect(writes(fetchMock, 'POST')).toHaveLength(1)
  })

  it('does nothing when closing is cancelled in the confirm dialog', async () => {
    const fetchMock = stubApi()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: `Fechar período ${openRange}` }))

    expect(confirm).toHaveBeenCalledWith('Fechar o período? Os membros deixam de poder alterar a disponibilidade.')
    expect(writes(fetchMock, 'POST')).toHaveLength(0)
  })

  it('closes an open period with one POST after confirmation', async () => {
    const fetchMock = stubApi()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: `Fechar período ${openRange}` }))

    await waitFor(() => expect(writes(fetchMock, 'POST')).toHaveLength(1))
    expect(writes(fetchMock, 'POST')[0][0]).toBe('/api/teams/t1/periods/p1/status')
    expect(JSON.parse(writes(fetchMock, 'POST')[0][1]!.body as string)).toEqual({ status: 'CLOSED' })
  })

  it('sends one POST when Fechar is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(periods, slots, async () => {
      await gate
      return new Response(null, { status: 204 })
    })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()

    const close = await screen.findByRole('button', { name: `Fechar período ${openRange}` })
    fireEvent.click(close)
    fireEvent.click(close)
    expect((close as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(gets(fetchMock, '/api/teams/t1/periods')).toHaveLength(2))
    expect(writes(fetchMock, 'POST')).toHaveLength(1)
  })

  it('reopens a closed period with its deadline pre-filled and sends it as an ISO instant', async () => {
    const fetchMock = stubApi()
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: `Reabrir período ${closedRange}` }))
    const deadline = screen.getByLabelText('Prazo de entrega') as HTMLInputElement
    expect(deadline.value).toMatch(/^2026-09-3[01]T/)
    fireEvent.click(screen.getByRole('button', { name: 'Reabrir período' }))

    await waitFor(() => expect(writes(fetchMock, 'POST')).toHaveLength(1))
    expect(writes(fetchMock, 'POST')[0][0]).toBe('/api/teams/t1/periods/p3/status')
    expect(JSON.parse(writes(fetchMock, 'POST')[0][1]!.body as string)).toEqual({
      status: 'OPEN',
      inputDeadline: new Date(deadline.value).toISOString(),
    })
  })

  it('sends one POST when Reabrir período is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(periods, slots, async () => {
      await gate
      return new Response(null, { status: 204 })
    })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: `Reabrir período ${closedRange}` }))
    const confirm = screen.getByRole('button', { name: 'Reabrir período' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    expect((confirm as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(gets(fetchMock, '/api/teams/t1/periods')).toHaveLength(2))
    expect(writes(fetchMock, 'POST')).toHaveLength(1)
  })

  it('deletes a draft with one DELETE after confirmation and drops it from the URL', async () => {
    const fetchMock = stubApi()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage('/escala?team=t1&period=p2')

    fireEvent.click(await screen.findByRole('button', { name: `Apagar ${draftRange}` }))

    expect(confirm).toHaveBeenCalledWith('Apagar este período? Os turnos gerados também são apagados.')
    await waitFor(() => expect(writes(fetchMock, 'DELETE')).toHaveLength(1))
    expect(writes(fetchMock, 'DELETE')[0][0]).toBe('/api/teams/t1/periods/p2')
    await waitFor(() => expect(screen.getByTestId('search').textContent).toBe('?team=t1'))
  })

  it('sends one DELETE when Apagar is clicked twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(periods, slots, async () => {
      await gate
      return new Response(null, { status: 204 })
    })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()

    const remove = await screen.findByRole('button', { name: `Apagar ${draftRange}` })
    fireEvent.click(remove)
    fireEvent.click(remove)
    expect((remove as HTMLButtonElement).disabled).toBe(true)

    release()
    await waitFor(() => expect(gets(fetchMock, '/api/teams/t1/periods')).toHaveLength(2))
    expect(writes(fetchMock, 'DELETE')).toHaveLength(1)
  })

  it('says the period changed and refetches the list when a status change returns 409', async () => {
    const fetchMock = stubApi(periods, slots, async () => new Response(null, { status: 409 }))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: `Fechar período ${openRange}` }))

    expect((await screen.findByRole('alert')).textContent).toBe('O período mudou entretanto. A lista foi atualizada.')
    await waitFor(() => expect(gets(fetchMock, '/api/teams/t1/periods')).toHaveLength(2))
  })

  it('says only managed teams can be seen when the periods return 404', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => (url === '/api/teams' ? json(teams) : json({}, 404))))
    renderPage()

    expect((await screen.findByRole('alert')).textContent).toBe('Só pode ver os períodos das equipas que gere.')
  })

  it('shows the period in a text Ver turnos button and loads its grid, with a weekend row tinted', async () => {
    const fetchMock = stubApi()
    renderPage()

    const view = await screen.findByRole('button', { name: `Ver cobertura ${openRange}` })
    expect(view.textContent).toBe('Ver cobertura')
    fireEvent.click(view)

    expect(await screen.findByRole('heading', { name: 'Turnos do período' })).toBeTruthy()
    expect(screen.getByText(`A ver: ${openRange}`)).toBeTruthy()
    await waitFor(() => expect(fetchMock.mock.calls.map(([u]) => u)).toContain('/api/teams/t1/periods/p1/slots'))
    expect(screen.getByRole('columnheader', { name: /Manhã/ })).toBeTruthy()
    expect(screen.getByRole('columnheader', { name: /Madrugada/ })).toBeTruthy()
    const satRow = screen.getByRole('rowheader', { name: /07\/11$/ }).closest('tr')!
    expect(satRow.className).toContain('bg-slate-50')
    expect(screen.getByRole('rowheader', { name: /08\/11$/ })).toBeTruthy()
    expect(screen.getByRole('rowheader', { name: openRange }).closest('tr')!.className).toContain('bg-cyan-50')
  })

  it('shows each cell as min–max text and marks overridden cells with an icon and aria text', async () => {
    stubApi()
    renderPage('/escala?team=t1&period=p1')

    const plain = await cellButton(SAT_MANHA_LABEL)
    expect(plain.textContent).toBe('1–2')
    expect(plain.className).not.toContain('bg-amber-50')

    const changed = await cellButton('Madrugada 08/11/2026: mínimo 2, máximo 3, alterado (modelo: 2–4)')
    expect(changed.textContent).toBe('2–3')
    expect(changed.className).toContain('bg-amber-50')
    expect(changed.querySelector('svg')).toBeTruthy()
  })

  it('opens one cell with visible Mín. and Máx. labels, focuses Mín. and saves once on Enter', async () => {
    const fetchMock = stubApi()
    renderPage('/escala?team=t1&period=p1')

    fireEvent.click(await cellButton(SAT_MANHA_LABEL))
    const min = await minInput(SAT_MANHA)
    expect(screen.getByText('Mín.')).toBeTruthy()
    expect(screen.getByText('Máx.')).toBeTruthy()
    expect(document.activeElement).toBe(min)

    fireEvent.change(min, { target: { value: '2' } })
    fireEvent.keyDown(min, { key: 'Enter' })

    await waitFor(() => expect(writes(fetchMock, 'PATCH')).toHaveLength(1))
    expect(writes(fetchMock, 'PATCH')[0][0]).toBe('/api/teams/t1/periods/p1/slots/sl1')
    expect(JSON.parse(writes(fetchMock, 'PATCH')[0][1]!.body as string)).toEqual({ minStaff: 2, maxStaff: 2 })
    await waitFor(() => expect(screen.queryByRole('spinbutton')).toBeNull())
  })

  it('sends one PATCH when Enter is pressed twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(periods, slots, async () => {
      await gate
      return new Response(null, { status: 204 })
    })
    renderPage('/escala?team=t1&period=p1')

    fireEvent.click(await cellButton(SAT_MANHA_LABEL))
    const min = await minInput(SAT_MANHA)
    fireEvent.change(min, { target: { value: '2' } })
    fireEvent.keyDown(min, { key: 'Enter' })
    fireEvent.keyDown(min, { key: 'Enter' })

    release()
    await waitFor(() => expect(gets(fetchMock, '/api/teams/t1/periods/p1/slots')).toHaveLength(2))
    expect(writes(fetchMock, 'PATCH')).toHaveLength(1)
  })

  it('cancels with Esc without a request and returns focus to the cell button', async () => {
    const fetchMock = stubApi()
    renderPage('/escala?team=t1&period=p1')

    fireEvent.click(await cellButton(SAT_MANHA_LABEL))
    const min = await minInput(SAT_MANHA)
    fireEvent.change(min, { target: { value: '2' } })
    fireEvent.keyDown(min, { key: 'Escape' })

    const button = await cellButton(SAT_MANHA_LABEL)
    expect(document.activeElement).toBe(button)
    expect(writes(fetchMock, 'PATCH')).toHaveLength(0)
  })

  it('shows an inline alert and sends nothing when min is above max', async () => {
    const fetchMock = stubApi()
    renderPage('/escala?team=t1&period=p1')

    fireEvent.click(await cellButton(SAT_MANHA_LABEL))
    const min = await minInput(SAT_MANHA)
    fireEvent.change(min, { target: { value: '3' } })
    fireEvent.keyDown(min, { key: 'Enter' })

    expect((await screen.findByRole('alert')).textContent).toBe('O mínimo tem de ser 0 ou mais, o máximo 1 ou mais, e o mínimo não pode ser superior ao máximo.')
    expect(writes(fetchMock, 'PATCH')).toHaveLength(0)
    expect(await minInput(SAT_MANHA)).toBeTruthy()
  })

  it('keeps the cell open with an inline error when the save fails on the server', async () => {
    stubApi(periods, slots, async () => new Response(null, { status: 500 }))
    renderPage('/escala?team=t1&period=p1')

    fireEvent.click(await cellButton(SAT_MANHA_LABEL))
    const min = await minInput(SAT_MANHA)
    fireEvent.change(min, { target: { value: '2' } })
    fireEvent.keyDown(min, { key: 'Enter' })

    expect((await screen.findByRole('alert')).textContent).toBe('Não foi possível guardar o turno. Tente novamente.')
    expect(await minInput(SAT_MANHA)).toBeTruthy()
  })

  it('saves on blur only when the cell changed, and closes it either way', async () => {
    const fetchMock = stubApi()
    renderPage('/escala?team=t1&period=p1')

    fireEvent.click(await cellButton(SAT_MANHA_LABEL))
    fireEvent.blur(await minInput(SAT_MANHA))
    await cellButton(SAT_MANHA_LABEL)
    expect(writes(fetchMock, 'PATCH')).toHaveLength(0)

    fireEvent.click(await cellButton(SAT_MANHA_LABEL))
    const min = await minInput(SAT_MANHA)
    fireEvent.change(min, { target: { value: '2' } })
    fireEvent.blur(min)
    await waitFor(() => expect(writes(fetchMock, 'PATCH')).toHaveLength(1))
  })

  it('keeps only one cell open at a time', async () => {
    stubApi()
    renderPage('/escala?team=t1&period=p1')

    fireEvent.click(await cellButton(SAT_MANHA_LABEL))
    await minInput(SAT_MANHA)
    fireEvent.click(await cellButton('Madrugada 07/11/2026: mínimo 2, máximo 3'))

    expect(await minInput('Madrugada 07/11/2026')).toBeTruthy()
    expect(screen.getAllByRole('spinbutton')).toHaveLength(2)
    expect(await cellButton(SAT_MANHA_LABEL)).toBeTruthy()
  })

  it('shows plain text and an explanation, with no cell buttons, when published', async () => {
    stubApi()
    renderPage('/escala?team=t1&period=p4')

    await screen.findByText('Período publicado: os turnos já não podem ser alterados.')
    expect((await screen.findAllByText('1–2')).length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: /mínimo/ })).toBeNull()
    expect(screen.queryByRole('spinbutton')).toBeNull()
  })

  it('shows an empty line when the period has no slots', async () => {
    stubApi(periods, [])
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: `Ver cobertura ${draftRange}` }))

    expect(await screen.findByText(/Este período não tem turnos/)).toBeTruthy()
  })

  it('lists the submissions of an open period and opens the calendar to enter one of them', async () => {
    stubApi()
    renderPage('/escala?team=t1&period=p1')

    const row = (await screen.findByRole('rowheader', { name: 'Ana Silva' })).closest('tr')!
    expect(within(row).getByText('Não respondeu')).toBeTruthy()
    expect(within(screen.getByRole('rowheader', { name: 'Bruno Costa' }).closest('tr')!).getByText('Respondeu')).toBeTruthy()

    fireEvent.click(within(row).getByRole('button', { name: 'Preencher disponibilidade' }))
    expect(await screen.findByRole('heading', { name: 'Disponibilidade de Ana Silva' })).toBeTruthy()
  })

  it('opens the member in a dialog that loads their availability', async () => {
    const fetchMock = stubApi()
    renderPage('/escala?team=t1&period=p1')

    fireEvent.click(within((await screen.findByRole('rowheader', { name: 'Ana Silva' })).closest('tr')!).getByRole('button', { name: 'Preencher disponibilidade' }))

    const dialog = await screen.findByRole('dialog', { name: 'Disponibilidade de Ana Silva' })
    expect(dialog.hasAttribute('open')).toBe(true)
    expect(screen.getByText('Está a preencher em nome deste membro.')).toBeTruthy()
    await screen.findByRole('button', { name: /^Manhã 07\/11\/2026: Não disponível$/ })
    expect(fetchMock.mock.calls.map(([u]) => u)).toContain(MEMBER_URL)
  })

  it('closes with Esc, refetches the submissions and returns focus to the row button', async () => {
    const fetchMock = stubApi()
    renderPage('/escala?team=t1&period=p1')

    const opener = within((await screen.findByRole('rowheader', { name: 'Ana Silva' })).closest('tr')!).getByRole('button', { name: 'Preencher disponibilidade' })
    fireEvent.click(opener)
    const dialog = await screen.findByRole('dialog', { name: 'Disponibilidade de Ana Silva' })

    fireEvent.keyDown(dialog, { key: 'Escape' })

    await waitFor(() => expect(dialog.hasAttribute('open')).toBe(false))
    await waitFor(() => expect(gets(fetchMock, '/api/teams/t1/periods/p1/submissions')).toHaveLength(2))
    expect(document.activeElement).toBe(opener)
  })

  it('closes with the Fechar button and returns focus to the row button', async () => {
    stubApi()
    renderPage('/escala?team=t1&period=p1')

    const opener = within((await screen.findByRole('rowheader', { name: 'Bruno Costa' })).closest('tr')!).getByRole('button', { name: 'Ver / editar' })
    fireEvent.click(opener)
    const dialog = await screen.findByRole('dialog', { name: 'Disponibilidade de Bruno Costa' })

    fireEvent.click(screen.getByRole('button', { name: 'Fechar' }))

    await waitFor(() => expect(dialog.hasAttribute('open')).toBe(false))
    expect(document.activeElement).toBe(opener)
  })

  it('names the action by the submission status', async () => {
    stubApi()
    renderPage('/escala?team=t1&period=p1')

    expect(await screen.findByRole('button', { name: 'Preencher disponibilidade' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Ver / editar' })).toBeTruthy()
  })

  it('sends one PATCH when a slot in the dialog is tapped twice quickly', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    const fetchMock = stubApi(periods, slots, async () => {
      await gate
      return new Response(null, { status: 204 })
    })
    renderPage('/escala?team=t1&period=p1')

    fireEvent.click(within((await screen.findByRole('rowheader', { name: 'Ana Silva' })).closest('tr')!).getByRole('button', { name: 'Preencher disponibilidade' }))
    const slot = await screen.findByRole('button', { name: /^Manhã 07\/11\/2026: Não disponível$/ })
    fireEvent.click(slot)
    fireEvent.click(slot)

    release()
    await screen.findByText(/Guardado/)
    const patches = writes(fetchMock, 'PATCH')
    expect(patches).toHaveLength(1)
    expect(patches[0][0]).toBe(MEMBER_URL)
    expect(JSON.parse(patches[0][1]!.body as string)).toEqual({ marks: [{ slotId: 'sl9', kind: 'AVAILABLE' }] })
  })

  it('collapses the coverage grid from its summary while the submissions stay visible', async () => {
    stubApi()
    renderPage('/escala?team=t1&period=p1')

    const heading = await screen.findByRole('heading', { name: 'Turnos do período' })
    const details = heading.closest('details')!
    expect(details.open).toBe(true)

    fireEvent.click(heading.closest('summary')!)

    expect(details.open).toBe(false)
    expect(screen.getByRole('heading', { name: 'Disponibilidade' })).toBeTruthy()
    expect(await screen.findByText('Ana Silva')).toBeTruthy()
  })

  it('drops the period from the URL when the team changes', async () => {
    stubApi()
    renderPage('/escala?team=t1&period=p1')

    await cellButton(SAT_MANHA_LABEL)
    fireEvent.change(screen.getByLabelText('Escolher equipa'), { target: { value: 't2' } })

    await waitFor(() => expect(screen.getByTestId('search').textContent).toBe('?team=t2'))
  })
})
