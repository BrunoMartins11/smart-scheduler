import { LogIn, LogOut } from 'lucide-react'
import { useEffect, useState } from 'react'
import { t } from './i18n/messages'

const btnPrimary =
  'h-10 px-4 inline-flex items-center rounded-md bg-cyan-700 text-white font-bold hover:bg-cyan-800 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-700'
const btnSecondary =
  'h-10 px-4 inline-flex items-center rounded-md border border-slate-300 bg-white font-bold hover:bg-slate-50 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-700'
const navLink =
  'px-2 py-1 rounded-md text-slate-600 hover:text-slate-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-700'

// shortcut: plain hash links with no pages behind them, add react-router when the four routes get real content
const navItems = [
  { href: '#escala', label: t('nav.schedule') },
  { href: '#equipa', label: t('nav.team') },
  { href: '#turnos', label: t('nav.shifts') },
  { href: '#regras', label: t('nav.rules') },
]

type Me = { id: string; email: string; displayName: string }
type Session =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'signedIn'; me: Me }
  | { status: 'error' }

export function AppShell() {
  const [session, setSession] = useState<Session>({ status: 'loading' })

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
      <header className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 bg-white px-4 py-3">
        <div className="flex flex-wrap items-center gap-6">
          <span className="text-lg font-bold">{t('app.name')}</span>
          <nav aria-label={t('nav.label')}>
            <ul className="flex flex-wrap gap-2">
              {navItems.map((item) => (
                <li key={item.href}>
                  <a className={navLink} href={item.href}>
                    {item.label}
                  </a>
                </li>
              ))}
            </ul>
          </nav>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          {session.status === 'signedIn' && (
            <>
              <span className="text-slate-600">{session.me.displayName}</span>
              <form method="post" action="/auth/logout">
                <button type="submit" className={btnSecondary}>
                  <LogOut className="size-4 mr-2" aria-hidden="true" />
                  {t('auth.logout')}
                </button>
              </form>
            </>
          )}
          {session.status === 'anonymous' && (
            <a className={btnPrimary} href="/auth/login">
              <LogIn className="size-4 mr-2" aria-hidden="true" />
              {t('auth.login')}
            </a>
          )}
          {session.status === 'error' && (
            <p role="alert" className="text-red-700">
              {t('auth.error')}
            </p>
          )}
        </div>
      </header>

      <main className="p-4" />
    </div>
  )
}
