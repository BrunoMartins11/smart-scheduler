import { CalendarCheck, LogOut } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router'
import { AvailabilityPage } from './AvailabilityPage'
import { LandingPage } from './LandingPage'
import { PeriodsPage } from './PeriodsPage'
import { RulesPage } from './RulesPage'
import { ShiftsPage } from './ShiftsPage'
import { TeamPage } from './TeamPage'
import { t } from './i18n/messages'
import { alertText, focusRingDark, muted } from './ui'

// The nav is shown to company admins and team managers only; everyone else goes straight to availability.
const navItems = [
  { to: '/escala', label: t('nav.schedule') },
  { to: '/equipa', label: t('nav.team') },
  { to: '/turnos', label: t('nav.shifts') },
  { to: '/regras', label: t('nav.rules') },
  { to: '/disponibilidade', label: t('nav.availability') },
]

const navLink = ({ isActive }: { isActive: boolean }) =>
  `min-h-11 px-3 inline-flex items-center rounded-md ${focusRingDark} ${isActive ? 'bg-white/10 text-white font-bold' : 'text-indigo-200 hover:bg-white/10 hover:text-white'}`

type Me = { id: string; email: string; displayName: string; isCompanyAdmin: boolean; managedTeamIds: string[] }
type Session =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'signedIn'; me: Me }
  | { status: 'error' }

export function AppShell() {
  const [session, setSession] = useState<Session>({ status: 'loading' })
  // Keeps ?team= when switching pages.
  const { search } = useLocation()
  const isManager = session.status === 'signedIn' && (session.me.isCompanyAdmin || session.me.managedTeamIds.length > 0)

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

  // Signed-out visitors get the landing page, which has its own header.
  if (session.status === 'anonymous') return <LandingPage />

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="bg-indigo-950 text-white">
        <div className="max-w-6xl mx-auto px-4 py-3 flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-wrap items-center gap-6">
            <div className="flex items-center gap-2.5 text-lg font-bold">
              <span className="grid size-6 place-items-center rounded-md bg-cyan-700">
                <CalendarCheck className="size-4" aria-hidden="true" />
              </span>
              {t('app.name')}
            </div>
            {isManager && (
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
            )}
          </div>

          <div className="flex flex-wrap items-center gap-3">
            {session.status === 'signedIn' && (
              <>
                <span className="hidden text-indigo-200 sm:inline">{session.me.displayName}</span>
                <form method="post" action="/auth/logout">
                  {/* Below sm the label is hidden, so the icon button keeps its name through aria-label. */}
                  <button
                    type="submit"
                    aria-label={t('auth.logout')}
                    className={`h-11 w-11 sm:w-auto sm:px-4 inline-flex items-center justify-center gap-2 rounded-md border border-white/30 font-bold text-white hover:bg-white/10 transition-colors ${focusRingDark}`}
                  >
                    <LogOut className="size-4" aria-hidden="true" />
                    <span className="hidden sm:inline">{t('auth.logout')}</span>
                  </button>
                </form>
              </>
            )}
          </div>
        </div>
      </header>

      <main>
        {session.status === 'loading' && (
          <div className="max-w-6xl mx-auto px-4 py-6">
            <p className={muted}>{t('app.loading')}</p>
          </div>
        )}
        {session.status === 'error' && (
          <div className="max-w-6xl mx-auto px-4 py-6">
            <p role="alert" className={alertText}>
              {t('auth.error')}
            </p>
          </div>
        )}
        {session.status === 'signedIn' && (
          <Routes>
            <Route path="/" element={<Navigate to={isManager ? '/escala' : '/disponibilidade'} replace />} />
            <Route path="/escala" element={<PeriodsPage />} />
            <Route
              path="/equipa"
              element={<TeamPage meId={session.me.id} isAdmin={session.me.isCompanyAdmin} />}
            />
            <Route path="/turnos" element={<ShiftsPage />} />
            <Route path="/regras" element={<RulesPage />} />
            <Route path="/disponibilidade" element={<AvailabilityPage userId={session.me.id} />} />
            <Route path="*" element={<></>} />
          </Routes>
        )}
      </main>
    </div>
  )
}
