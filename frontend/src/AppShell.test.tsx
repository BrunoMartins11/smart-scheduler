import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppShell } from './AppShell'

const NAV_LABELS = ['Escala', 'Equipa', 'Turnos', 'Regras']

function LocationProbe() {
  return <p data-testid="location">{useLocation().pathname}</p>
}

function stubMe(response: Response) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('AppShell', () => {
  it('shows the user name and a logout form when signed in', async () => {
    stubMe(
      new Response(JSON.stringify({ id: '1', email: 'ana@example.pt', displayName: 'Ana Silva' }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    )
    render(
      <MemoryRouter>
        <AppShell />
      </MemoryRouter>,
    )

    const nav = screen.getByRole('navigation', { name: 'Principal' })
    for (const label of NAV_LABELS) expect(nav.textContent).toContain(label)

    expect(await screen.findByText('Ana Silva')).toBeTruthy()
    const logout = screen.getByRole('button', { name: /Sair/ })
    expect(logout.closest('form')?.getAttribute('action')).toBe('/auth/logout')
    expect(screen.queryByRole('link', { name: /Entrar/ })).toBeNull()
  })

  it('shows the login link when not signed in', async () => {
    stubMe(new Response(null, { status: 401 }))
    render(
      <MemoryRouter>
        <AppShell />
      </MemoryRouter>,
    )

    const nav = screen.getByRole('navigation', { name: 'Principal' })
    for (const label of NAV_LABELS) expect(nav.textContent).toContain(label)

    const login = await screen.findByRole('link', { name: /Entrar/ })
    expect(login.getAttribute('href')).toBe('/auth/login')
  })

  it('redirects the root path to the schedule once signed in', async () => {
    stubMe(new Response(JSON.stringify({ id: '1', email: 'ana@example.pt', displayName: 'Ana Silva', isCompanyAdmin: false }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }))
    render(
      <MemoryRouter initialEntries={['/']}>
        <AppShell />
        <LocationProbe />
      </MemoryRouter>,
    )

    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/escala'))
  })

  it('renders no page for the team route when signed out', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
    vi.stubGlobal('fetch', fetchMock)
    render(
      <MemoryRouter initialEntries={['/equipa']}>
        <AppShell />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('link', { name: /Entrar/ })).toBeTruthy()
    expect(screen.queryByRole('heading', { name: 'Membros da equipa' })).toBeNull()
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/me'])
  })
})
