import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AvailabilityPage } from './AvailabilityPage'

const closed = { teamId: 't1', teamName: 'Urgências', periodId: 'pA', startDate: '2026-10-03', endDate: '2026-10-04', status: 'CLOSED', inputDeadline: null, submitted: true }
const open = { teamId: 't2', teamName: 'Cardiologia', periodId: 'pB', startDate: '2026-11-07', endDate: '2026-11-08', status: 'OPEN', inputDeadline: '2099-11-05T12:00:00.000Z', submitted: false }

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } })

// Answers the period list and an empty availability per period; records every URL asked for.
function stubApi(entries: unknown[]) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url === '/api/me/availability-periods') return json(entries)
    return json({ editable: true, period: { startDate: '2026-11-07', endDate: '2026-11-08', status: 'OPEN', inputDeadline: null }, slots: [] })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const renderPage = (url = '/disponibilidade') =>
  render(
    <MemoryRouter initialEntries={[url]}>
      <AvailabilityPage userId="u1" />
    </MemoryRouter>,
  )

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('AvailabilityPage', () => {
  it('opens the first open period by default and marks it in the picker', async () => {
    const fetchMock = stubApi([closed, open])
    renderPage()

    expect((await screen.findByRole('button', { name: /Cardiologia/ })).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: /Urgências/ }).getAttribute('aria-pressed')).toBe('false')
    await waitFor(() => expect(fetchMock.mock.calls.map(([u]) => u)).toContain('/api/teams/t2/periods/pB/members/u1/availability'))
  })

  it('opens the period named in ?period=', async () => {
    const fetchMock = stubApi([closed, open])
    renderPage('/disponibilidade?period=pA')

    expect((await screen.findByRole('button', { name: /Urgências/ })).getAttribute('aria-pressed')).toBe('true')
    expect(fetchMock.mock.calls.map(([u]) => u)).toContain('/api/teams/t1/periods/pA/members/u1/availability')
  })

  it('says there are no periods when none is open to the member, under the page heading', async () => {
    stubApi([])
    renderPage()

    expect(await screen.findByText('Ainda não há períodos abertos para si.')).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1, name: 'Disponibilidade' })).toBeTruthy()
  })

  it('keeps the page heading when the periods fail to load', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 500 })))
    renderPage()

    expect((await screen.findByRole('alert')).textContent).toBe('Não foi possível carregar a disponibilidade. Tente novamente mais tarde.')
    expect(screen.getByRole('heading', { level: 1, name: 'Disponibilidade' })).toBeTruthy()
  })

  it('shows the deadline of the open period in the page band', async () => {
    stubApi([open])
    renderPage()

    expect(await screen.findByText(/Prazo de entrega: .*em \d+ dias/)).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1, name: '07/11/2026 – 08/11/2026' }).closest('.bg-indigo-950')).not.toBeNull()
  })

  it('hides the period picker when the member has only one period', async () => {
    stubApi([open])
    renderPage()

    expect(await screen.findByRole('heading', { name: '07/11/2026 – 08/11/2026' })).toBeTruthy()
    expect(screen.queryByRole('group', { name: 'Escolher período' })).toBeNull()
  })
})
