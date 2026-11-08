import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

// Deterministic star field (1440x300 canvas): no Math.random, so markup is stable. Shared by the landing hero and the app band.
const STARS = Array.from({ length: 40 }, (_, i) => ({ x: (i * 97) % 1440, y: (i * 53) % 300, r: 1 + (i % 3) * 0.3, o: 0.25 + (i % 4) * 0.15 }))

// Circles only, so it sits inside any svg that uses the 1440-wide viewBox. sparse keeps every third star.
export function Stars({ sparse = false }: { sparse?: boolean }) {
  return (
    <>
      {(sparse ? STARS.filter((_, i) => i % 3 === 0) : STARS).map(({ x, y, r, o }, i) => (
        <circle key={i} cx={x} cy={y} r={r} fill="#ffffff" opacity={o} />
      ))}
    </>
  )
}

export function PageLayout({ title, description, controls, children }: { title: string; description: string; controls?: ReactNode; children: ReactNode }) {
  return (
    <>
      <div className="relative overflow-hidden bg-indigo-950 text-white">
        <svg aria-hidden="true" focusable="false" className="pointer-events-none absolute inset-0 size-full" viewBox="0 0 1440 300" preserveAspectRatio="xMidYMid slice">
          <Stars sparse />
        </svg>
        <div className="relative mx-auto max-w-6xl px-4 pt-6 pb-20">
          <h1 className="text-2xl font-bold lg:text-3xl">{title}</h1>
          <p className="mt-2 text-indigo-200">{description}</p>
          {controls && <div className="mt-4 flex flex-wrap items-center gap-4">{controls}</div>}
        </div>
      </div>
      <div className="relative mx-auto max-w-6xl px-4 -mt-12 pb-10">{children}</div>
    </>
  )
}

export function SectionHeading({ icon: Icon, children, actions }: { icon: LucideIcon; children: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="flex items-center gap-3">
        <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-cyan-50 text-cyan-700">
          <Icon className="size-5" aria-hidden="true" />
        </span>
        <h2 className="text-lg font-bold">{children}</h2>
      </div>
      {actions}
    </div>
  )
}

export function EmptyState({ icon: Icon, text, action }: { icon: LucideIcon; text: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-3 px-4 py-8 text-center">
      <span className="grid size-9 place-items-center rounded-lg bg-cyan-50 text-cyan-700">
        <Icon className="size-5" aria-hidden="true" />
      </span>
      <p className="text-slate-600">{text}</p>
      {action}
    </div>
  )
}
