import { Check, ChevronLeft, ChevronRight, Minus, Moon, UserCog } from 'lucide-react'
import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { ApiError, api } from './api'
import { dayLabel, deadlineHint, parseDay, rangeText, shiftDay } from './dates'
import { t, type MessageKey } from './i18n/messages'
import { alertText, availIf, availYes, btnOption, btnWide, cardRaised, chip, focusRing, muted } from './ui'

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
// The NONE chip has no border, so only its letter shows.
const chipLook: Record<Kind | 'NONE', string> = {
  AVAILABLE: `${availYes} font-bold`,
  IF_NEEDED: `${availIf} font-bold`,
  NONE: 'border-transparent font-normal text-slate-500',
}
const weekdays = ['seg', 'ter', 'qua', 'qui', 'sex', 'sáb', 'dom']
const noneOn = 'border-slate-500 bg-slate-100 text-slate-700'
const optionOff = 'border-slate-300 bg-white text-slate-900 hover:bg-slate-50'
// The three choices for one slot. kind null is "Não": the slot has no mark.
const options = [
  { kind: 'AVAILABLE', label: 'availability.state.AVAILABLE', on: availYes, Icon: Check },
  { kind: 'IF_NEEDED', label: 'availability.state.IF_NEEDED', on: availIf, Icon: Minus },
  { kind: null, label: 'availability.option.none', on: noneOn, Icon: null },
] as const
const navBtn = `size-11 inline-flex items-center justify-center rounded-md border border-slate-300 bg-white text-slate-900 hover:bg-slate-50 disabled:opacity-50 disabled:cursor-not-allowed ${focusRing}`
const steps: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }

const letter = (name: string) => name.charAt(0).toUpperCase()
const monthShort = (iso: string) => parseDay(iso).toLocaleDateString('pt-PT', { month: 'short' }).replace('.', '')
// Opens on the first day with turns and no marks yet, then the first day with turns, then the period start.
const firstDay = ({ period, slots }: Availability) => {
  const days = [...new Set(slots.map((s) => s.date))]
  const unmarked = days.find((day) => slots.filter((s) => s.date === day).every((s) => s.kind === null))
  return unmarked ?? days[0] ?? period.startDate
}

// forManager: the manager is entering availability, so the period range and the deadline go in the dialog header, and the "pelo gestor" legend is left out.
export function AvailabilityCalendar({ teamId, periodId, userId, forManager = false }: { teamId: string; periodId: string; userId: string; forManager?: boolean }) {
  const [data, setData] = useState<Availability | null>(null)
  const [slots, setSlots] = useState<Slot[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [alert, setAlert] = useState<string | null>(null)
  const [pending, setPending] = useState(0)
  const [saved, setSaved] = useState(false)
  const [reload, setReload] = useState(0)
  // Slots with a request in flight ignore taps; a ref, so two taps in one render still see it.
  const inFlight = useRef(new Set<string>())
  const gridRef = useRef<HTMLDivElement>(null)
  const url = `/api/teams/${teamId}/periods/${periodId}/members/${userId}/availability`

  useEffect(() => {
    let ignore = false
    api<Availability>(url)
      .then((d) => {
        if (ignore) return
        setData(d)
        setSlots(d.slots)
        // A reload (after a 409) keeps the day the person was on, if it is still in the period.
        setSelected((prev) => (prev && prev >= d.period.startDate && prev <= d.period.endDate ? prev : firstDay(d)))
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

  const { editable, period } = data
  const day = selected ?? period.startDate
  const slotsOn = (d: string) => slots.filter((s) => s.date === d)
  const daySlots = slotsOn(day)
  const allAvailable = daySlots.length > 0 && daySlots.every((s) => s.kind === 'AVAILABLE')
  const count = (k: Kind) => slots.filter((s) => s.kind === k).length
  const status = pending > 0 ? t('availability.saving') : saved ? t('availability.saved') : ''
  const hint = period.inputDeadline ? deadlineHint(period.inputDeadline) : null
  const inPeriod = (d: string) => d >= period.startDate && d <= period.endDate
  // The grid runs Monday to Sunday around the period; the cells outside it are empty spacers.
  const first = shiftDay(period.startDate, -((parseDay(period.startDate).getDay() + 6) % 7))
  const last = shiftDay(period.endDate, 6 - ((parseDay(period.endDate).getDay() + 6) % 7))
  const cells: string[] = []
  for (let d = first; d <= last; d = shiftDay(d, 1)) cells.push(d)
  const twoMonths = period.startDate.slice(0, 7) !== period.endDate.slice(0, 7)
  // The legend sample uses the team's own first template letter; "M" only when there are no slots.
  const legendLetter = slots[0] ? letter(slots[0].templateName) : 'M'
  const summary = t('availability.summary').replace('{a}', String(count('AVAILABLE'))).replace('{b}', String(count('IF_NEEDED')))
  const heading = `${parseDay(day).toLocaleDateString('pt-PT', { weekday: 'long' })}, ${day.slice(8)}/${day.slice(5, 7)}`
  const dayText = (d: string) => {
    const list = slotsOn(d).map((s) => t('availability.slot').replace('{template}', s.templateName).replace('{state}', t(stateWord[s.kind ?? 'NONE'])))
    return list.length ? `${dayLabel(d)}: ${list.join(', ')}` : dayLabel(d)
  }

  // Arrow keys move the selection a day (left, right) or a week (up, down), clamped to the period.
  const onDayKey = (e: KeyboardEvent<HTMLButtonElement>, from: string) => {
    const step = steps[e.key]
    if (!step) return
    e.preventDefault()
    const next = shiftDay(from, step)
    const clamped = next < period.startDate ? period.startDate : next > period.endDate ? period.endDate : next
    setSelected(clamped)
    gridRef.current?.querySelector<HTMLButtonElement>(`[data-day="${clamped}"]`)?.focus()
  }
  // Dia e noite marks every turn of the day, or clears them when they all already have it. Only changed slots are sent.
  const bulk = () => {
    const target: Kind | null = allAvailable ? null : 'AVAILABLE'
    mark(daySlots.filter((s) => s.kind !== target).map((s) => ({ slotId: s.id, kind: target })))
  }

  return (
    <section className="mx-auto w-full max-w-md space-y-4 pb-2">
      {/* On the member page the period range and the deadline sit in the page band, so only the manager's dialog shows them here. */}
      <header className="space-y-1">
        {forManager && <p className="font-bold">{rangeText(period)}</p>}
        {!editable && <p className={muted}>{t(period.status === 'PUBLISHED' ? 'availability.published' : 'availability.closed')}</p>}
        {forManager && editable && hint && (
          <p className={hint.soon ? 'font-bold text-amber-900' : muted}>{t('availability.deadline').replace('{deadline}', hint.text)}</p>
        )}
        {!forManager && slots.some((s) => s.enteredByManager) && <p className={muted}>{t('availability.managerLegend')}</p>}
      </header>

      <div className={forManager ? 'space-y-3' : `${cardRaised} space-y-3 p-4`}>
        <div className="flex flex-wrap items-baseline justify-between gap-1 text-sm text-slate-600">
          <p>{summary}</p>
          {editable && (
            <p role="status" className="font-bold text-cyan-800">
              {status}
            </p>
          )}
        </div>
        {alert && <p role="alert" className={alertText}>{alert}</p>}

        <div className="grid grid-cols-7 gap-1 text-center text-xs text-slate-600">
          {weekdays.map((w) => (
            <span key={w}>{w}</span>
          ))}
        </div>
        <div ref={gridRef} role="group" aria-label={t('availability.calendar')} className="grid grid-cols-7 gap-1">
          {cells.map((d) =>
            inPeriod(d) ? (
              <button
                key={d}
                type="button"
                data-day={d}
                aria-label={dayText(d)}
                aria-pressed={d === day}
                tabIndex={d === day ? 0 : -1}
                onClick={() => setSelected(d)}
                onKeyDown={(e) => onDayKey(e, d)}
                className={`flex min-h-13 flex-col items-center justify-center gap-0.5 rounded-md py-1 hover:bg-slate-50 ${focusRing}`}
              >
                <span className="flex items-center justify-center gap-1">
                  <span className={`flex size-7 items-center justify-center rounded-full text-[15px] leading-5 ${d === day ? 'bg-cyan-700 font-bold text-white' : ''}`}>
                    {Number(d.slice(8))}
                  </span>
                  {twoMonths && d.endsWith('-01') && <span className="text-xs text-slate-600">{monthShort(d)}</span>}
                </span>
                <span className="flex gap-0.5">
                  {slotsOn(d).map((s) => (
                    <span key={s.id} className={`${chip} ${chipLook[s.kind ?? 'NONE']}`}>
                      {letter(s.templateName)}
                    </span>
                  ))}
                </span>
              </button>
            ) : (
              <span key={d} aria-hidden="true" />
            ),
          )}
        </div>

        <div className="flex flex-wrap gap-x-4 gap-y-2 text-[13px] text-slate-600">
          {(['AVAILABLE', 'IF_NEEDED', 'NONE'] as const).map((k) => (
            <span key={k} className="inline-flex items-center gap-1.5">
              <span aria-hidden="true" className={`${chip} ${chipLook[k]}`}>
                {legendLetter}
              </span>
              {t(stateWord[k])}
            </span>
          ))}
        </div>

        <div className="space-y-3 border-t border-slate-200 pt-3">
          <div className="grid grid-cols-[44px_minmax(0,1fr)_44px] items-center gap-2">
            <button type="button" aria-label={t('availability.dayPrev')} disabled={day <= period.startDate} onClick={() => setSelected(shiftDay(day, -1))} className={navBtn}>
              <ChevronLeft className="size-[18px]" aria-hidden="true" />
            </button>
            <h3 className="text-center text-base font-bold">{heading}</h3>
            <button type="button" aria-label={t('availability.dayNext')} disabled={day >= period.endDate} onClick={() => setSelected(shiftDay(day, 1))} className={navBtn}>
              <ChevronRight className="size-[18px]" aria-hidden="true" />
            </button>
          </div>

          {daySlots.length === 0 && <p className={muted}>{t('availability.noSlots')}</p>}
          {daySlots.map((s) => {
            const state = s.kind ?? 'NONE'
            return (
              <div key={s.id} className="space-y-2">
                <div>
                  <p className="flex items-center gap-1.5 font-bold">
                    {s.templateName}
                    {s.isNight && <Moon className="size-3.5" aria-hidden="true" />}
                  </p>
                  <p className={`text-sm tabular-nums ${muted}`}>{`${s.startTime}–${s.endTime}`}</p>
                  {s.enteredByManager && (
                    <p className={`flex items-center gap-1 text-xs ${muted}`}>
                      <UserCog className="size-3.5" aria-hidden="true" />
                      {t('availability.manager')}
                    </p>
                  )}
                </div>
                {editable ? (
                  <div role="group" aria-label={t('availability.optionGroup').replace('{template}', s.templateName)} className="grid grid-cols-3 gap-2">
                    {options.map((o) => {
                      const on = s.kind === o.kind
                      const Icon = o.Icon
                      return (
                        <button
                          key={o.label}
                          type="button"
                          aria-pressed={on}
                          onClick={() => {
                            if (!on) mark([{ slotId: s.id, kind: o.kind }])
                          }}
                          className={`${btnOption} ${on ? o.on : optionOff}`}
                        >
                          {on && Icon && <Icon className="size-3.5" aria-hidden="true" />}
                          {t(o.label)}
                        </button>
                      )
                    })}
                  </div>
                ) : (
                  <p className="flex items-center gap-2">
                    <span aria-hidden="true" className={`${chip} ${chipLook[state]}`}>
                      {letter(s.templateName)}
                    </span>
                    <span className="font-bold">{t(stateWord[state])}</span>
                  </p>
                )}
              </div>
            )
          })}

          {editable && daySlots.length > 0 && (
            <button type="button" className={`${btnWide} w-full`} onClick={bulk}>
              {t(allAvailable ? 'availability.dayClear' : 'availability.dayAll')}
            </button>
          )}
        </div>
      </div>
    </section>
  )
}
