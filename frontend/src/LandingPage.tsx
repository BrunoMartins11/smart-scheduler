import { CalendarCheck, CalendarDays, Check, ChevronDown, Clock, ClipboardList, Lock, LogIn, Moon, Scale, Stethoscope, type LucideIcon } from 'lucide-react'
import { t, type MessageKey } from './i18n/messages'
import { focusRing, focusRingDark, pillGreen } from './ui'
import { Stars } from './PageLayout'

// Signed-out page. Night-shift hero with a decorative backdrop; mobile-first, lg: is the desktop layout.
// Deterministic backdrop geometry (same 1440x640 canvas as the mock). No Math.random: markup must be stable.
const ROTA_CELLS = Array.from({ length: 60 }, (_, i) => ({ r: Math.floor(i / 10), c: i % 10 })).filter(
  ({ r, c }) => (r * 3 + c) % 7 !== 0,
)

function HeroBackdrop() {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      className="pointer-events-none absolute inset-0 size-full"
      viewBox="0 0 1440 640"
      preserveAspectRatio="xMidYMid slice"
    >
      <g className="hidden lg:inline" fill="#312e81" opacity=".55">
        {ROTA_CELLS.map(({ r, c }) => (
          <rect
            key={`${r}-${c}`}
            x={700 + c * 44}
            y={56 + r * 36}
            width="36"
            height="28"
            rx="6"
            fill={(r * 5 + c * 2) % 9 === 0 ? '#3730a3' : undefined}
          />
        ))}
      </g>
      <path d="M 660 600 A 420 420 0 0 1 1500 600" fill="none" stroke="#a5b4fc" strokeWidth="1.5" strokeDasharray="4 8" opacity=".35" />
      <circle cx="660" cy="600" r="14" fill="#fcd34d" />
      <circle cx="1500" cy="600" r="14" fill="#e0e7ff" />
      <circle cx="1506" cy="596" r="12" fill="#1e1b4b" />
      <text x="660" y="634" textAnchor="middle" fill="#a5b4fc" fontSize="12">08:00</text>
      <text x="1500" y="634" textAnchor="middle" fill="#a5b4fc" fontSize="12">20:00</text>
      <Stars />
    </svg>
  )
}

// Preview chips: D = day, N = night, '.' = off. Same pattern as the mock.
const PREVIEW_PATTERN = 'DDNN..D' + 'D.DNN..'
const chipClass = { D: 'bg-stone-100 text-stone-800', N: 'bg-indigo-100 text-indigo-900', '.': 'bg-slate-100' } as const

const RULES: { Icon: LucideIcon; title: MessageKey; body: MessageKey }[] = [
  { Icon: Clock, title: 'landing.rules.rest.title', body: 'landing.rules.rest.body' },
  { Icon: Moon, title: 'landing.rules.nights.title', body: 'landing.rules.nights.body' },
  { Icon: Scale, title: 'landing.rules.fair.title', body: 'landing.rules.fair.body' },
  { Icon: CalendarDays, title: 'landing.rules.holidays.title', body: 'landing.rules.holidays.body' },
]

const STEPS: { title: MessageKey; body: MessageKey }[] = [
  { title: 'landing.step1.title', body: 'landing.step1.body' },
  { title: 'landing.step2.title', body: 'landing.step2.body' },
  { title: 'landing.step3.title', body: 'landing.step3.body' },
]

const ROLES: { Icon: LucideIcon; title: MessageKey; items: MessageKey[] }[] = [
  { Icon: Stethoscope, title: 'landing.doctors.title', items: ['landing.doctors.1', 'landing.doctors.2', 'landing.doctors.3'] },
  { Icon: ClipboardList, title: 'landing.managers.title', items: ['landing.managers.1', 'landing.managers.2', 'landing.managers.3'] },
]

const FAQS: { q: MessageKey; a: MessageKey }[] = [
  { q: 'landing.faq.1.q', a: 'landing.faq.1.a' },
  { q: 'landing.faq.2.q', a: 'landing.faq.2.a' },
  { q: 'landing.faq.3.q', a: 'landing.faq.3.a' },
]

export function LandingPage() {
  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <section className="relative overflow-hidden bg-indigo-950 text-white">
        <HeroBackdrop />
        <div className="relative mx-auto max-w-6xl px-4">
          <header className="flex items-center justify-between py-5">
            <div className="flex items-center gap-2.5 text-lg font-bold">
              <span className="grid size-6 place-items-center rounded-md bg-cyan-700">
                <CalendarCheck className="size-4" aria-hidden="true" />
              </span>
              {t('app.name')}
            </div>
            <a
              href="/auth/login"
              className={`hidden min-h-11 items-center rounded-md border border-white/30 px-4 font-bold hover:bg-white/10 lg:inline-flex ${focusRingDark}`}
            >
              {t('auth.login')}
            </a>
          </header>

          <div className="grid gap-12 pt-6 pb-24 lg:grid-cols-[1.1fr_0.9fr] lg:items-center lg:gap-16 lg:py-20">
            <div className="flex flex-col items-start">
              <span className="inline-block rounded-full bg-cyan-300/10 px-3 py-1 text-xs font-bold text-cyan-300">
                {t('landing.eyebrow')}
              </span>
              <h1 className="mt-4 text-3xl leading-tight font-bold lg:mt-5 lg:text-5xl">{t('landing.title')}</h1>
              <p className="mt-4 text-base leading-6 text-indigo-200 lg:mt-5 lg:text-lg lg:leading-7">{t('landing.subtitle')}</p>
              <a
                href="/auth/login"
                className={`mt-8 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-md bg-cyan-700 px-6 text-base font-bold text-white transition-colors hover:bg-cyan-800 lg:w-auto ${focusRingDark}`}
              >
                <LogIn className="size-4" aria-hidden="true" />
                {t('landing.cta')}
              </a>
              <p className="mt-4 flex items-start gap-2 text-sm leading-5 text-indigo-300">
                <Lock className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
                {t('landing.invite')}
              </p>
            </div>

            <div aria-hidden="true" className="hidden space-y-3 rounded-xl bg-white p-5 text-slate-900 shadow-2xl shadow-indigo-950/40 lg:block">
              <div className="flex items-center justify-between">
                <span className="text-base font-bold">{t('landing.preview.month')}</span>
                <span className={pillGreen}>{t('landing.preview.published')}</span>
              </div>
              <div className="grid grid-cols-7 gap-1.5 text-center text-xs text-slate-600">
                {t('landing.preview.weekdays')
                  .split(', ')
                  .map((letter, i) => (
                    <span key={i}>{letter}</span>
                  ))}
              </div>
              <div className="grid grid-cols-7 gap-1.5">
                {[...PREVIEW_PATTERN].map((kind, i) => (
                  <span
                    key={i}
                    className={`mx-auto flex h-8 w-9 items-center justify-center rounded-md text-xs font-bold ${chipClass[kind as keyof typeof chipClass]}`}
                  >
                    {kind === '.' ? '' : kind}
                  </span>
                ))}
              </div>
              <div className="space-y-2 border-t border-slate-200 pt-3">
                <p className="text-xs text-slate-600">{t('landing.preview.share')}</p>
                <div className="relative h-1.5 rounded-full bg-slate-200">
                  <div className="absolute inset-y-0 left-0 w-[47%] rounded-full bg-cyan-700" />
                  <div className="absolute -top-0.5 left-1/2 h-3 w-0.5 bg-slate-900" />
                </div>
                <p className="text-xs font-bold text-green-700">{t('landing.preview.fair')}</p>
              </div>
            </div>
          </div>
        </div>
      </section>

      <main>
        <section className="border-b border-slate-200 bg-white">
          <div className="mx-auto max-w-6xl px-4 py-6">
            <p className="text-sm font-bold">{t('landing.rules.intro')}</p>
            <ul className="mt-4 grid grid-cols-2 gap-x-4 gap-y-5 lg:grid-cols-4 lg:gap-6">
              {RULES.map(({ Icon, title, body }) => (
                <li key={title} className="flex items-start gap-3">
                  <Icon className="mt-0.5 size-5 shrink-0 text-cyan-700" aria-hidden="true" />
                  <div className="flex flex-col">
                    <span className="text-sm font-bold">{t(title)}</span>
                    <span className="text-xs text-slate-600">{t(body)}</span>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        </section>

        <section className="mx-auto max-w-6xl px-4 pt-14 pb-14">
          <h2 className="text-2xl leading-9 font-bold lg:text-3xl">{t('landing.how.title')}</h2>
          <p className="mt-2 text-base text-slate-600">{t('landing.how.sub')}</p>
          <ol className="mt-8 grid gap-4 lg:grid-cols-3 lg:gap-6">
            {STEPS.map(({ title, body }, i) => (
              <li key={title} className="flex flex-col gap-2 rounded-lg border border-slate-200 bg-white p-5">
                <span aria-hidden="true" className="grid size-8 place-items-center rounded-full bg-cyan-50 font-bold text-cyan-800">
                  {i + 1}
                </span>
                <h3 className="mt-1 text-base leading-6 font-bold">{t(title)}</h3>
                <p className="text-slate-600">{t(body)}</p>
              </li>
            ))}
          </ol>

          <div className="mt-6 grid gap-4 lg:grid-cols-2 lg:gap-6">
            {ROLES.map(({ Icon, title, items }) => (
              <div key={title} className="rounded-lg border border-slate-200 bg-white p-6">
                <span className="grid size-10 place-items-center rounded-lg bg-cyan-50 text-cyan-700">
                  <Icon className="size-5" aria-hidden="true" />
                </span>
                <h3 className="mt-4 text-lg font-bold">{t(title)}</h3>
                <ul className="mt-4 space-y-3">
                  {items.map((item) => (
                    <li key={item} className="flex items-start gap-2 text-base leading-6">
                      <Check className="mt-1 size-4 shrink-0 text-cyan-700" aria-hidden="true" />
                      {t(item)}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </section>

        <section className="border-t border-slate-200 bg-white">
          <div className="mx-auto max-w-3xl px-4 py-14">
            <h2 className="text-2xl leading-9 font-bold lg:text-3xl">{t('landing.faq.title')}</h2>
            <div className="mt-6">
              {FAQS.map(({ q, a }, i) => (
                <details key={q} open={i === 0} className="group border-b border-slate-200 py-1">
                  <summary
                    className={`flex min-h-11 cursor-pointer list-none items-center justify-between gap-4 rounded-md font-bold [&::-webkit-details-marker]:hidden ${focusRing}`}
                  >
                    <span>{t(q)}</span>
                    <ChevronDown className="size-5 shrink-0 text-slate-600 motion-safe:transition-transform group-open:rotate-180" aria-hidden="true" />
                  </summary>
                  <p className="mb-3 text-slate-600">{t(a)}</p>
                </details>
              ))}
            </div>
            <a
              href="/auth/login"
              className={`mt-10 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-md bg-cyan-700 px-6 text-base font-bold text-white transition-colors hover:bg-cyan-800 lg:w-auto ${focusRing}`}
            >
              <LogIn className="size-4" aria-hidden="true" />
              {t('landing.cta')}
            </a>
          </div>
        </section>
      </main>

      <footer className="bg-slate-100">
        <div className="mx-auto max-w-6xl px-4 py-6 text-sm text-slate-600">
          <span className="font-bold">{t('app.name')}</span>
        </div>
      </footer>
    </div>
  )
}
