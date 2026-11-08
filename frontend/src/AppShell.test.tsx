import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppShell } from './AppShell'
import { t } from './i18n/messages'

const MANAGER_LABELS = ['Escala', 'Equipa', 'Turnos', 'Regras']

function LocationProbe() {
  return <p data-testid="location">{useLocation().pathname}</p>
}

function stubMe(response: Response) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
}

const meResponse = (me: object) =>
  new Response(JSON.stringify({ id: '1', email: 'ana@example.pt', displayName: 'Ana Silva', ...me }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  })
const manager = { isCompanyAdmin: false, managedTeamIds: ['t1'] }
const member = { isCompanyAdmin: false, managedTeamIds: [] }

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('AppShell', () => {
  it('shows the user name, the manager items and a logout form when signed in', async () => {
    stubMe(meResponse(manager))
    render(
      <MemoryRouter>
        <AppShell />
      </MemoryRouter>,
    )

    expect(await screen.findByText('Ana Silva')).toBeTruthy()
    const nav = screen.getByRole('navigation', { name: 'Principal' })
    for (const label of [...MANAGER_LABELS, 'Disponibilidade']) expect(nav.textContent).toContain(label)

    const logout = screen.getByRole('button', { name: /Sair/ })
    expect(logout.closest('form')?.getAttribute('action')).toBe('/auth/logout')
    expect(screen.queryAllByRole('link', { name: /Entrar/ }).length).toBe(0)
  })

  it('shows the landing page with its sign-in CTA and FAQ when not signed in', async () => {
    stubMe(new Response(null, { status: 401 }))
    render(
      <MemoryRouter>
        <AppShell />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { level: 1, name: t('landing.title') })).toBeTruthy()
    expect(screen.queryByRole('navigation', { name: 'Principal' })).toBeNull()
    const ctas = screen.getAllByRole('link', { name: t('landing.cta') })
    expect(ctas.length).toBe(2)
    for (const cta of ctas) expect(cta.getAttribute('href')).toBe('/auth/login')
    expect(screen.getByRole('link', { name: 'Entrar' }).getAttribute('href')).toBe('/auth/login')

    const faqs = Array.from(document.querySelectorAll('details'))
    expect(faqs.length).toBe(3)
    expect(faqs.map((d) => d.open)).toEqual([true, false, false])
    fireEvent.click(faqs[1].querySelector('summary') as HTMLElement)
    expect(faqs[1].open).toBe(true)
  })

  it('hides the decorative backdrop and product preview from assistive tech', async () => {
    stubMe(new Response(null, { status: 401 }))
    render(
      <MemoryRouter>
        <AppShell />
      </MemoryRouter>,
    )

    await screen.findByRole('heading', { level: 1, name: t('landing.title') })
    expect(document.querySelector('section svg')?.getAttribute('aria-hidden')).toBe('true')
    expect(screen.getByText(t('landing.preview.published')).closest('[aria-hidden="true"]')).not.toBeNull()
  })

  it('hides the manager items from a plain member and redirects the root to availability', async () => {
    stubMe(meResponse(member))
    render(
      <MemoryRouter initialEntries={['/']}>
        <AppShell />
        <LocationProbe />
      </MemoryRouter>,
    )

    expect(await screen.findByText('Ana Silva')).toBeTruthy()
    expect(screen.queryByRole('navigation', { name: 'Principal' })).toBeNull()
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/disponibilidade'))
  })

  it('redirects the root path to the schedule for a team manager', async () => {
    stubMe(meResponse(manager))
    render(
      <MemoryRouter initialEntries={['/']}>
        <AppShell />
        <LocationProbe />
      </MemoryRouter>,
    )

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/escala'))
  })

  it('keeps the ?team= query on the nav links', async () => {
    stubMe(meResponse(manager))
    render(
      <MemoryRouter initialEntries={['/escala?team=t2']}>
        <AppShell />
      </MemoryRouter>,
    )

    expect((await screen.findByRole('link', { name: 'Equipa' })).getAttribute('href')).toBe('/equipa?team=t2')
    expect(screen.getByRole('link', { name: 'Regras' }).getAttribute('href')).toBe('/regras?team=t2')
  })

  it('marks the nav link for the current route with aria-current', async () => {
    stubMe(meResponse(manager))
    render(
      <MemoryRouter initialEntries={['/equipa']}>
        <AppShell />
      </MemoryRouter>,
    )

    expect((await screen.findByRole('link', { name: 'Equipa' })).getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('link', { name: 'Regras' }).getAttribute('aria-current')).toBeNull()
  })

  it('renders no page for the team route when signed out', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
    vi.stubGlobal('fetch', fetchMock)
    render(
      <MemoryRouter initialEntries={['/equipa']}>
        <AppShell />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { level: 1, name: t('landing.title') })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Membros da equipa' })).toBeNull()
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/me'])
  })

  it('renders the page title and description inside the indigo band, and an icon-only Sair with a name', async () => {
    stubMe(meResponse(manager))
    render(
      <MemoryRouter initialEntries={['/escala']}>
        <AppShell />
      </MemoryRouter>,
    )

    const title = await screen.findByRole('heading', { level: 1, name: t('periods.heading') })
    const band = title.closest('.bg-indigo-950') as HTMLElement
    expect(band).not.toBeNull()
    expect(within(band).getByText(t('periods.description'))).toBeTruthy()
    expect(screen.getByRole('button', { name: t('auth.logout') }).getAttribute('aria-label')).toBe(t('auth.logout'))
  })
})
