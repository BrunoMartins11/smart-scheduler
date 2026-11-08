import { Check, Minus, Moon, UserCog } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { ApiError, api } from './api'
import { dateText, dayLabel, deadlineHint, parseDay, rangeText } from './dates'
import { t, type MessageKey } from './i18n/messages'
import { alertText, availBase, availIf, availNone, availYes, brushBase, btnWide, muted } from './ui'

type Kind = 'AVAILABLE' | 'IF_NEEDED'
type Slot = {
  id: string
  date: string
  templateName: string
  startTime: string
  endTime: string
  isNight: boolean
  kind: Kind | null
  enteredByManager: boolean
}
type Availability = {
  editable: boolean
  period: { startDate: string; endDate: string; status: string; inputDeadline: string | null }
  slots: Slot[]
}
type Mark = { slotId: string; kind: Kind | null }

const stateWord: Record<Kind | 'NONE', MessageKey> = {
  AVAILABLE: 'availability.state.AVAILABLE',
  IF_NEEDED: 'availability.state.IF_NEEDED',
  NONE: 'availability.state.NONE',
}
const stateClass: Record<Kind | 'NONE', string> = { AVAILABLE: availYes, IF_NEEDED: availIf, NONE: availNone }

// Brings the targets to the brush's kind, or clears them when they all have it already. Only changed slots are sent.
const plan = (targets: Slot[], brush: Kind): Mark[] => {
  const kind = targets.every((s) => s.kind === brush) ? null : brush
  return targets.filter((s) => s.kind !== kind).map((s) => ({ slotId: s.id, kind }))
}

// This component never scrolls itself. The page scrolls the window, the Escala dialog scrolls its body,
// and the sticky bar sticks against whichever of those is the scroll container.
// forManager: the manager is entering availability, so the "pelo gestor" legend is left out.
export function AvailabilityCalendar({ teamId, periodId, userId, forManager = false }: { teamId: string; periodId: string; userId: string; forManager?: boolean }) {
  const [data, setData] = useState<Availability | null>(null)
  const [slots, setSlots] = useState<Slot[]>([])
  const [brush, setBrush] = useState<Kind>('AVAILABLE')
  const [loadError, setLoadError] = useState<string | null>(null)
  const [alert, setAlert] = useState<string | null>(null)
  const [pending, setPending] = useState(0)
  const [saved, setSaved] = useState(false)
  const [reload, setReload] = useState(0)
  // Slots with a request in flight ignore taps; a ref, so two taps in one render still see it.
  const inFlight = useRef(new Set<string>())
  const url = `/api/teams/${teamId}/periods/${periodId}/members/${userId}/availability`

  useEffect(() => {
    let ignore = false
    api<Availability>(url)
      .then((d) => {
        if (ignore) return
        setData(d)
        setSlots(d.slots)
      })
      .catch((err) => {
        if (!ignore) setLoadError(t(err instanceof ApiError && err.status === 404 ? 'availability.forbidden' : 'availability.error'))
      })
    return () => {
      ignore = true
    }
  }, [url, reload])

  // "Guardado" goes away after two seconds; the timer is cleared if the page leaves first.
  useEffect(() => {
    if (!saved) return
    const id = setTimeout(() => setSaved(false), 2000)
    return () => clearTimeout(id)
  }, [saved])

  async function mark(marks: Mark[]) {
    const todo = marks.filter((m) => !inFlight.current.has(m.slotId))
    if (!data?.editable || todo.length === 0) return
    const before = slots.filter((s) => todo.some((m) => m.slotId === s.id))
    todo.forEach((m) => inFlight.current.add(m.slotId))
    setPending((n) => n + 1)
    setAlert(null)
    setSaved(false)
    setSlots((list) =>
      list.map((s) => {
        const m = todo.find((x) => x.slotId === s.id)
        return m ? { ...s, kind: m.kind, enteredByManager: false } : s
      }),
    )
    try {
      await api(url, { method: 'PATCH', body: { marks: todo } })
      setSaved(true)
    } catch (err) {
      setSlots((list) => list.map((s) => before.find((b) => b.id === s.id) ?? s))
      if (err instanceof ApiError && err.status === 409) {
        setAlert(t('availability.changed'))
        setReload((n) => n + 1)
      } else {
        setAlert(t('availability.save.error'))
      }
    } finally {
      todo.forEach((m) => inFlight.current.delete(m.slotId))
      setPending((n) => n - 1)
    }
  }

  if (loadError) return <p role="alert" className={alertText}>{loadError}</p>
  if (!data) return <p className={muted}>{t('team.loading')}</p>

  const editable = data.editable
  const deadline = data.period.inputDeadline
  const nights = slots.filter((s) => s.isNight)
  const allNights = nights.length > 0 && nights.every((s) => s.kind === brush)
  const count = (k: Kind) => slots.filter((s) => s.kind === k).length
  const days = [...new Set(slots.map((s) => s.date))]
  const status = pending > 0 ? t('availability.saving') : saved ? t('availability.saved') : ''
  const hint = deadline ? deadlineHint(deadline) : null

  return (
    <section className="mx-auto w-full max-w-md space-y-4 pb-2">
      <header className="space-y-1">
        {/* On the member page the period range and the deadline sit in the page band, so only the manager's dialog shows them here. */}
        {forManager && <p className="font-bold">{rangeText(data.period)}</p>}
        {!editable && <p className={muted}>{t(data.period.status === 'PUBLISHED' ? 'availability.published' : 'availability.closed')}</p>}
        {forManager && editable && hint && (
          <p className={hint.soon ? 'font-bold text-amber-900' : muted}>{t('availability.deadline').replace('{deadline}', hint.text)}</p>
        )}
        {!forManager && slots.some((s) => s.enteredByManager) && <p className={muted}>{t('availability.managerLegend')}</p>}
      </header>

      {/* About 110 px: instruction, brush, and one line for the summary and the save status. */}
      <div className={editable ? 'sticky top-0 z-10 space-y-1 border-b border-slate-200 bg-white py-2' : 'space-y-1 py-2'}>
        {editable && <p className={`text-sm ${muted}`}>{t('availability.instructions')}</p>}
        {editable && (
          <div role="group" aria-label={t('availability.brush')} className="grid grid-cols-2 gap-2">
            {(['AVAILABLE', 'IF_NEEDED'] as const).map((k) => (
              <button
                key={k}
                type="button"
                aria-pressed={brush === k}
                onClick={() => setBrush(k)}
                className={`${brushBase} ${brush === k ? stateClass[k] : availNone}`}
              >
                {t(k === 'AVAILABLE' ? 'availability.brush.available' : 'availability.brush.ifNeeded')}
              </button>
            ))}
          </div>
        )}
        <p className={`text-sm ${muted}`}>
          <span>{t('availability.summary').replace('{a}', String(count('AVAILABLE'))).replace('{b}', String(count('IF_NEEDED')))}</span>
          {editable && (
            <span role="status" className="font-bold text-cyan-800">
              {status ? ` · ${status}` : ''}
            </span>
          )}
        </p>
        {alert && <p role="alert" className={alertText}>{alert}</p>}
      </div>

      {editable && (
        <button type="button" className={`${btnWide} w-full`} disabled={nights.length === 0} onClick={() => mark(plan(nights, brush))}>
          <Moon className="size-4" aria-hidden="true" />
          {allNights ? t('availability.nightsClear') : t('availability.nightsAll')}
        </button>
      )}

      {days.map((date) => {
        const day = slots.filter((s) => s.date === date)
        const weekend = [0, 6].includes(parseDay(date).getDay())
        const clearsDay = day.every((s) => s.kind === brush)
        return (
          <div key={date} className={`rounded-lg border border-slate-200 p-3 space-y-2 ${weekend ? 'bg-slate-50' : 'bg-white'}`}>
            <div className="flex items-center justify-between gap-2">
              <h3 className="font-bold">{dayLabel(date)}</h3>
              {editable && (
                <button
                  type="button"
                  className={`${btnWide} h-11 px-3`}
                  aria-label={`${t(clearsDay ? 'availability.dayClear' : 'availability.dayAll')} ${dayLabel(date)}`}
                  onClick={() => mark(plan(day, brush))}
                >
                  {t(clearsDay ? 'availability.dayClear' : 'availability.dayAll')}
                </button>
              )}
            </div>
            <div className="grid grid-cols-2 gap-2">
              {day.map((s) => {
                const state = s.kind ?? 'NONE'
                const label =
                  t('availability.slot').replace('{template}', s.templateName).replace('{date}', dateText(s.date)).replace('{state}', t(stateWord[state])) +
                  (s.enteredByManager ? t('availability.managerAria') : '')
                const body = (
                  <>
                    <span className="flex items-center gap-1.5">
                      {s.kind === 'AVAILABLE' && <Check className="size-3.5" aria-hidden="true" />}
                      {s.kind === 'IF_NEEDED' && <Minus className="size-3.5" aria-hidden="true" />}
                      <span className="text-base">{s.templateName}</span>
                      {s.isNight && <Moon className="size-3.5" aria-hidden="true" />}
                    </span>
                    <span className="text-sm tabular-nums font-normal">{`${s.startTime}–${s.endTime}`}</span>
                    {s.enteredByManager && (
                      <span className="flex items-center gap-1 font-normal">
                        <UserCog className="size-3.5" aria-hidden="true" />
                        {t('availability.manager')}
                      </span>
                    )}
                    {!editable && <span className="font-bold">{t(stateWord[state])}</span>}
                  </>
                )
                // Read-only slots are plain text: no buttons, so the state word carries the meaning at full contrast.
                return editable ? (
                  <button key={s.id} type="button" aria-label={label} onClick={() => mark(plan([s], brush))} className={`${availBase} ${stateClass[state]}`}>
                    {body}
                  </button>
                ) : (
                  <div key={s.id} role="group" aria-label={label} className={`${availBase} ${stateClass[state]}`}>
                    {body}
                  </div>
                )
              })}
            </div>
          </div>
        )
      })}
    </section>
  )
}
