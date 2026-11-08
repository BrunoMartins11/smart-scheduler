import { LogIn, LogOut } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router'
import { RulesPage } from './RulesPage'
import { TeamPage } from './TeamPage'
import { t, type MessageKey } from './i18n/messages'
import { btnPrimary, btnSecondary, card, focusRing, muted, alertText } from './ui'

const navItems = [
  { to: '/escala', label: t('nav.schedule') },
  { to: '/equipa', label: t('nav.team') },
  { to: '/turnos', label: t('nav.shifts') },
  { to: '/regras', label: t('nav.rules') },
]

const navLink = ({ isActive }: { isActive: boolean }) =>
  `px-2 py-1 rounded-md ${focusRing} ${isActive ? 'bg-cyan-50 text-cyan-800 font-bold' : 'text-slate-600 hover:text-slate-900 hover:bg-slate-100'}`

function ComingSoon({ line }: { line: MessageKey }) {
  return (
    <section className={`${card} p-6`}>
      <h1 className="text-xl font-bold">{t('soon.heading')}</h1>
      <p className={muted}>{t(line)}</p>
    </section>
  )
}

type Me = { id: string; email: string; displayName: string; isCompanyAdmin: boolean }
type Session =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'signedIn'; me: Me }
  | { status: 'error' }

export function AppShell() {
  const [session, setSession] = useState<Session>({ status: 'loading' })
  // Keeps ?team= when switching pages.
  const { search } = useLocation()

  useEffect(() => {
    const controller = new AbortController()
    fetch('/api/me', { credentials: 'same-origin', signal: controller.signal })
      .then((res) => {
        if (res.status === 401) return setSession({ status: 'anonymous' })
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.json().then((me: Me) => setSession({ status: 'signedIn', me }))
      })
      .catch(() => {
        if (!controller.signal.aborted) setSession({ status: 'error' })
      })
    return () => controller.abort()
  }, [])

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b border-slate-200 bg-white">
        <div className="max-w-6xl mx-auto px-4 py-3 flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-wrap items-center gap-6">
            <span className="text-lg font-bold">{t('app.name')}</span>
            <nav aria-label={t('nav.label')}>
              <ul className="flex flex-wrap gap-2">
                {navItems.map((item) => (
                  <li key={item.to}>
                    <NavLink className={navLink} to={item.to + search}>
                      {item.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </nav>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            {session.status === 'signedIn' && (
              <>
                <span className={muted}>{session.me.displayName}</span>
                <form method="post" action="/auth/logout">
                  <button type="submit" className={btnSecondary}>
                    <LogOut className="size-4" aria-hidden="true" />
                    {t('auth.logout')}
                  </button>
                </form>
              </>
            )}
            {session.status === 'anonymous' && (
              <a className={btnPrimary} href="/auth/login">
                <LogIn className="size-4" aria-hidden="true" />
                {t('auth.login')}
              </a>
            )}
            {session.status === 'error' && (
              <p role="alert" className={alertText}>
                {t('auth.error')}
              </p>
            )}
          </div>
        </div>
      </header>

      {session.status === 'signedIn' && (
        <main className="max-w-6xl mx-auto px-4 py-6">
          <Routes>
            <Route path="/" element={<Navigate to="/escala" replace />} />
            <Route path="/escala" element={<ComingSoon line="soon.schedule" />} />
            <Route
              path="/equipa"
              element={<TeamPage meId={session.me.id} isAdmin={session.me.isCompanyAdmin} />}
            />
            <Route path="/turnos" element={<ComingSoon line="soon.shifts" />} />
            <Route path="/regras" element={<RulesPage />} />
            <Route path="*" element={<></>} />
          </Routes>
        </main>
      )}
    </div>
  )
}
