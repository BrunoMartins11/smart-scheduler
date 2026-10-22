import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppShell } from './AppShell'

const NAV_LABELS = ['Escala', 'Equipa', 'Turnos', 'Regras']

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
    render(<AppShell />)

    const nav = screen.getByRole('navigation', { name: 'Principal' })
    for (const label of NAV_LABELS) expect(nav.textContent).toContain(label)

    expect(await screen.findByText('Ana Silva')).toBeTruthy()
    const logout = screen.getByRole('button', { name: /Sair/ })
    expect(logout.closest('form')?.getAttribute('action')).toBe('/auth/logout')
    expect(screen.queryByRole('link', { name: /Entrar/ })).toBeNull()
  })

  it('shows the login link when not signed in', async () => {
    stubMe(new Response(null, { status: 401 }))
    render(<AppShell />)

    const nav = screen.getByRole('navigation', { name: 'Principal' })
    for (const label of NAV_LABELS) expect(nav.textContent).toContain(label)

    const login = await screen.findByRole('link', { name: /Entrar/ })
    expect(login.getAttribute('href')).toBe('/auth/login')
  })
})
