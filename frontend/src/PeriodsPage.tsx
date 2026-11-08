import { CalendarRange, ChevronDown, ClipboardCheck, LayoutGrid, Loader2, Moon, Pencil, Plus, Users, X } from 'lucide-react'
import { Fragment, useEffect, useRef, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { ApiError, api } from './api'
import { t, type MessageKey } from './i18n/messages'
import {
  alertText,
  btnCell,
  btnDangerText,
  btnIcon,
  btnPrimary,
  btnSecondary,
  card,
  cardRaised,
  cellChanged,
  cellText,
  focusRing,
  input,
  muted,
  pillGreen,
  pillSlate,
  periodStatusPill,
  th,
} from './ui'
import { TeamPicker, useTeams } from './teams'
import { dateText, dayLabel, deadlineText, parseDay, rangeText, toLocalInput } from './dates'
import { AvailabilityCalendar } from './AvailabilityCalendar'
import { EmptyState, PageLayout, SectionHeading } from './PageLayout'

const spinner = <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />

type Status = 'DRAFT' | 'OPEN' | 'CLOSED' | 'PUBLISHED'
type Period = { id: string; startDate: string; endDate: string; status: Status; inputDeadline: string | null }
// defaultMinStaff/defaultMaxStaff are the template's coverage; absent means the slot is not overridden.
type Slot = {
  id: string
  date: string
  templateId: string
  templateName: string
  startTime: string
  endTime: string
  isNight: boolean
  minStaff: number
  maxStaff: number
  defaultMinStaff?: number
  defaultMaxStaff?: number
}
// Asked for when a period opens (DRAFT, required) or reopens (CLOSED, the deadline may stay empty).
type DeadlineEdit = { id: string; value: string; required: boolean; error: string | null }
// The one grid cell in edit mode, with its inline error.
type Cell = { id: string; error: string | null }
type Submission = { userId: string; displayName: string; status: 'NOT_SUBMITTED' | 'MEMBER' | 'MANAGER' }
const submissionView: Record<Submission['status'], { label: MessageKey; pill: string }> = {
  NOT_SUBMITTED: { label: 'periods.submission.NOT_SUBMITTED', pill: pillSlate },
  MEMBER: { label: 'periods.submission.MEMBER', pill: pillGreen },
  MANAGER: { label: 'periods.submission.MANAGER', pill: pillSlate },
}

const statusView: Record<Status, MessageKey> = {
  DRAFT: 'periods.status.DRAFT',
  OPEN: 'periods.status.OPEN',
  CLOSED: 'periods.status.CLOSED',
  PUBLISHED: 'periods.status.PUBLISHED',
}
const blankPeriod = { startDate: '', endDate: '' }

// A deadline is past once its instant has gone; only open and closed periods show that.
const pastDeadline = (p: Period) => (p.status === 'OPEN' || p.status === 'CLOSED') && !!p.inputDeadline && new Date(p.inputDeadline) < new Date()
// A slot is overridden when its coverage differs from the template it was generated with.
const overridden = (s: Slot) =>
  (s.defaultMinStaff !== undefined && s.minStaff !== s.defaultMinStaff) || (s.defaultMaxStaff !== undefined && s.maxStaff !== s.defaultMaxStaff)

// 404 means the caller cannot manage the team, so the generic message would mislead.
const errorFor = (err: unknown, fallback: MessageKey) =>
  t(err instanceof ApiError && err.status === 404 ? 'periods.forbidden' : fallback)
// 409 means the period changed under the user, so the list is refetched and says so.
const actionError = (err: unknown, fallback: MessageKey) =>
  err instanceof ApiError && err.status === 409 ? t('periods.changed') : errorFor(err, fallback)

export function PeriodsPage() {
  const { teams, teamId, selectTeam, loadError } = useTeams()
  const [params, setParams] = useSearchParams()
  const [periods, setPeriods] = useState<Period[] | null>(null)
  // Tagged with its period, so a slow reply for another period never shows.
  const [slots, setSlots] = useState<{ periodId: string; list: Slot[] } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const failure = error ?? (loadError !== null ? errorFor(loadError, 'periods.error') : null)
  const [reload, setReload] = useState(0)
  // 'create', or `status:<id>` / `delete:<id>` / `slot:<id>` for the row or cell being saved.
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const busy = busyKey !== null
  const slotSaving = useRef(false)
  const [cell, setCell] = useState<Cell | null>(null)
  const [submissions, setSubmissions] = useState<{ periodId: string; list: Submission[] } | null>(null)
  // The member whose availability a manager is entering, one at a time.
  const [onBehalf, setOnBehalf] = useState<Submission | null>(null)
  const dialogRef = useRef<HTMLDialogElement>(null)
  const memberReturn = useRef<string | null>(null)
  // Set when Esc or Enter closes a cell, so its button gets focus back once it is rendered.
  const returnFocus = useRef<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [newPeriod, setNewPeriod] = useState(blankPeriod)
  const [createError, setCreateError] = useState<string | null>(null)
  const [deadlineEdit, setDeadlineEdit] = useState<DeadlineEdit | null>(null)

  const selectedId = periods?.find((p) => p.id === params.get('period'))?.id ?? null
  const selected = periods?.find((p) => p.id === selectedId)
  const published = selected?.status === 'PUBLISHED'
  const selectedSlots = slots?.periodId === selectedId ? slots.list : null
  const base = `/api/teams/${teamId}/periods`

  useEffect(() => {
    if (!teamId) return
    let ignore = false
    api<Period[]>(`/api/teams/${teamId}/periods`)
      .then((list) => !ignore && setPeriods(list))
      .catch((err) => !ignore && setError(errorFor(err, 'periods.error')))
    return () => {
      ignore = true
    }
  }, [teamId, reload])

  useEffect(() => {
    if (!teamId || !selectedId) return
    let ignore = false
    api<Slot[]>(`/api/teams/${teamId}/periods/${selectedId}/slots`)
      .then((list) => !ignore && setSlots({ periodId: selectedId, list }))
      .catch((err) => !ignore && setError(errorFor(err, 'periods.error')))
    return () => {
      ignore = true
    }
  }, [teamId, selectedId, reload])

  const showsSubmissions = selected !== undefined && selected.status !== 'DRAFT'
  useEffect(() => {
    if (!teamId || !selectedId || !showsSubmissions) return
    let ignore = false
    api<Submission[]>(`/api/teams/${teamId}/periods/${selectedId}/submissions`)
      .then((list) => !ignore && setSubmissions({ periodId: selectedId, list }))
      .catch((err) => !ignore && setError(errorFor(err, 'periods.error')))
    return () => {
      ignore = true
    }
  }, [teamId, selectedId, showsSubmissions, reload])

  // Opens the dialog for a member, and closes it (and returns focus) when the member is cleared.
  useEffect(() => {
    const d = dialogRef.current
    if (!d) return
    if (onBehalf && !d.open) d.showModal()
    if (!onBehalf && d.open) d.close()
    if (!onBehalf && memberReturn.current) {
      document.getElementById(memberReturn.current)?.focus()
      memberReturn.current = null
    }
  }, [onBehalf])

  useEffect(() => {
    if (cell === null && returnFocus.current) {
      document.getElementById(returnFocus.current)?.focus()
      returnFocus.current = null
    }
  }, [cell])

  // Switching team drops the period, so the grid and the open forms go with it.
  function switchTeam(id: string) {
    setError(null)
    setPeriods(null)
    setSlots(null)
    setDeadlineEdit(null)
    setCell(null)
    setOnBehalf(null)
    closeCreate()
    selectTeam(id)
  }

  function selectPeriod(id: string) {
    setCell(null)
    setOnBehalf(null)
    setParams({ team: teamId, period: id })
  }

  // Closing (X, Esc, backdrop) refetches the table and hands focus back to the row that opened it.
  function closeOnBehalf() {
    if (onBehalf) memberReturn.current = `member-${onBehalf.userId}`
    setOnBehalf(null)
    setReload((n) => n + 1)
  }

  function closeCreate() {
    setCreating(false)
    setNewPeriod(blankPeriod)
    setCreateError(null)
  }

  async function create(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (busy) return
    if (newPeriod.endDate < newPeriod.startDate) {
      setCreateError(t('periods.form.endBeforeStart'))
      return
    }
    setBusyKey('create')
    setCreateError(null)
    setError(null)
    try {
      await api(base, { method: 'POST', body: newPeriod })
      closeCreate()
      setReload((n) => n + 1)
    } catch (err) {
      setCreateError(err instanceof ApiError && err.status === 409 ? t('periods.create.overlap') : errorFor(err, 'periods.create.error'))
    } finally {
      setBusyKey(null)
    }
  }

  // One period request at a time. The list is refetched after every attempt, so a 409 shows what is there now.
  async function act(key: string, request: () => Promise<unknown>, done?: () => void) {
    if (busy) return
    setBusyKey(key)
    setError(null)
    try {
      await request()
      done?.()
    } catch (err) {
      setError(actionError(err, 'periods.action.error'))
    } finally {
      setBusyKey(null)
      setReload((n) => n + 1)
    }
  }

  function close(p: Period) {
    if (busy || !window.confirm(t('periods.close.confirm'))) return
    return act(`status:${p.id}`, () => api(`${base}/${p.id}/status`, { method: 'POST', body: { status: 'CLOSED' } }))
  }

  function remove(p: Period) {
    if (busy || !window.confirm(t('periods.delete.confirm'))) return
    return act(`delete:${p.id}`, () => api(`${base}/${p.id}`, { method: 'DELETE' }), () => {
      if (p.id === selectedId) setParams({ team: teamId })
    })
  }

  function confirmDeadline(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!deadlineEdit) return
    const { id, value, required } = deadlineEdit
    const fail = (message: MessageKey) => setDeadlineEdit({ ...deadlineEdit, error: t(message) })
    if (required && !value) return fail('periods.deadline.required')
    if (required && value < toLocalInput(new Date().toISOString())) return fail('periods.deadline.future')
    const body = { status: 'OPEN', ...(value ? { inputDeadline: new Date(value).toISOString() } : {}) }
    return act(`status:${id}`, () => api(`${base}/${id}/status`, { method: 'POST', body }), () => setDeadlineEdit(null))
  }

  // Saves one cell. Enter (refocus) hands focus back to the cell button; a blur leaves focus where the user went.
  async function saveSlot(s: Slot, minText: string, maxText: string, refocus: boolean) {
    if (slotSaving.current) return
    const finish = () => {
      if (refocus) returnFocus.current = `slot-${s.id}`
      setCell((c) => (c?.id === s.id ? null : c))
    }
    if (minText === String(s.minStaff) && maxText === String(s.maxStaff)) return finish()
    const min = Number(minText)
    const max = Number(maxText)
    if (!/^\d+$/.test(minText) || !/^\d+$/.test(maxText) || max < 1 || min > max) {
      setCell((c) => (c?.id === s.id ? { id: s.id, error: t('periods.slots.invalid') } : c))
      return
    }
    slotSaving.current = true
    setBusyKey(`slot:${s.id}`)
    setCell((c) => (c?.id === s.id ? { id: s.id, error: null } : c))
    try {
      await api(`${base}/${selectedId}/slots/${s.id}`, { method: 'PATCH', body: { minStaff: min, maxStaff: max } })
      finish()
    } catch (err) {
      const message = actionError(err, 'periods.slots.error')
      setCell((c) => (c?.id === s.id ? { id: s.id, error: message } : c))
    } finally {
      slotSaving.current = false
      setBusyKey(null)
      setReload((n) => n + 1)
    }
  }

  const columns = [...new Map<string, Slot>((selectedSlots ?? []).map((s) => [s.templateId, s])).values()]
  const days = [...new Set((selectedSlots ?? []).map((s) => s.date))]
  const byCell = new Map<string, Slot>((selectedSlots ?? []).map((s) => [`${s.date}|${s.templateId}`, s]))
  // Members still to answer come first, then by name.
  const sortedSubmissions = [...(submissions?.list ?? [])].sort(
    (a, b) => Number(b.status === 'NOT_SUBMITTED') - Number(a.status === 'NOT_SUBMITTED') || a.displayName.localeCompare(b.displayName, 'pt-PT'),
  )
  const awaiting = sortedSubmissions.filter((m) => m.status === 'NOT_SUBMITTED').length
  const nowLocal = toLocalInput(new Date().toISOString())

  return (
    <PageLayout title={t('periods.heading')} description={t('periods.description')} controls={<TeamPicker teams={teams} teamId={teamId} onSelect={switchTeam} />}>
      <div className="space-y-6">

        {failure && (
          <p role="alert" className={alertText}>
            {failure}
          </p>
        )}

        {teams === null && !failure && <p className={muted}>{t('team.loading')}</p>}

        {teams?.length === 0 && (
          <div className={cardRaised}>
            <EmptyState icon={Users} text={t('team.noTeams')} />
          </div>
        )}

        {teams && teams.length > 0 && (
          <>
            {/* The periods list is the page's first card, so it overlaps the band. */}
            <div className={cardRaised}>
              <div className="p-4 border-b border-slate-200">
                <SectionHeading
                  icon={CalendarRange}
                  actions={
                    creating ? null : (
                      <button type="button" className={btnSecondary} disabled={busy} onClick={() => setCreating(true)}>
                        <Plus className="size-4" aria-hidden="true" />
                        {t('periods.new')}
                      </button>
                    )
                  }
                >
                  {t('periods.list.heading')}
                </SectionHeading>
              </div>
              {creating && (
                <form onSubmit={create} className="flex flex-wrap items-end gap-3 p-4 border-b border-slate-200">
                  <label className="flex flex-col gap-1 text-sm font-bold">
                    {t('periods.form.start')}
                    <input type="date" required className={input} value={newPeriod.startDate} onChange={(e) => setNewPeriod({ ...newPeriod, startDate: e.target.value })} />
                  </label>
                  <label className="flex flex-col gap-1 text-sm font-bold">
                    {t('periods.form.end')}
                    <input type="date" required className={input} value={newPeriod.endDate} onChange={(e) => setNewPeriod({ ...newPeriod, endDate: e.target.value })} />
                  </label>
                  <button type="submit" className={btnPrimary} disabled={busy}>
                    {busyKey === 'create' ? spinner : null}
                    {t('periods.form.submit')}
                  </button>
                  <button type="button" className={btnSecondary} disabled={busy} onClick={closeCreate}>
                    {t('periods.form.cancel')}
                  </button>
                  {createError && (
                    <p role="alert" className={`${alertText} w-full`}>
                      {createError}
                    </p>
                  )}
                </form>
              )}
              {periods === null && !failure && <p className={`p-4 ${muted}`}>{t('team.loading')}</p>}
              {periods?.length === 0 && <EmptyState icon={CalendarRange} text={t('periods.empty')} />}
              {periods && periods.length > 0 && (
                <div className="overflow-x-auto">
                    <table className="w-full min-w-[640px] text-left">
                      <thead className="bg-slate-50 border-b border-slate-200">
                        <tr>
                          <th scope="col" className={th}>{t('periods.col.dates')}</th>
                          <th scope="col" className={th}>{t('periods.col.status')}</th>
                          <th scope="col" className={th}>{t('periods.col.deadline')}</th>
                          <th scope="col" className={`${th} text-right`}>{t('periods.col.actions')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {periods.map((p) => {
                          const range = rangeText(p)
                          return (
                            <Fragment key={p.id}>
                              <tr className={`border-b border-slate-100 last:border-0 hover:bg-slate-50 ${p.id === selectedId ? 'bg-cyan-50' : ''}`}>
                                <th scope="row" className="px-4 py-3 font-bold tabular-nums">
                                  {range}
                                </th>
                                <td className="px-4 py-3">
                                  <span className={periodStatusPill[p.status]}>{t(statusView[p.status])}</span>
                                </td>
                                <td className="px-4 py-3">
                                  <div className="flex flex-wrap items-center gap-2 tabular-nums">
                                    <span>{p.inputDeadline ? deadlineText(p.inputDeadline) : '—'}</span>
                                    {pastDeadline(p) && <span className={pillSlate}>{t('periods.deadline.past')}</span>}
                                  </div>
                                </td>
                                <td className="px-4 py-3">
                                  <div className="flex flex-wrap justify-end gap-2">
                                    {p.status === 'DRAFT' && (
                                      <button
                                        type="button"
                                        className={btnPrimary}
                                        aria-label={`${t('periods.open')} ${range}`}
                                        disabled={busy}
                                        onClick={() => setDeadlineEdit({ id: p.id, value: '', required: true, error: null })}
                                      >
                                        {t('periods.open')}
                                      </button>
                                    )}
                                    {p.status === 'OPEN' && (
                                      <button type="button" className={btnSecondary} aria-label={`${t('periods.close')} ${range}`} disabled={busy} onClick={() => close(p)}>
                                        {busyKey === `status:${p.id}` && spinner}
                                        {t('periods.close')}
                                      </button>
                                    )}
                                    {p.status === 'CLOSED' && (
                                      <button
                                        type="button"
                                        className={btnSecondary}
                                        aria-label={`${t('periods.reopen')} ${range}`}
                                        disabled={busy}
                                        onClick={() => setDeadlineEdit({ id: p.id, value: p.inputDeadline ? toLocalInput(p.inputDeadline) : '', required: false, error: null })}
                                      >
                                        {t('periods.reopen')}
                                      </button>
                                    )}
                                    <button
                                      type="button"
                                      className={btnSecondary}
                                      aria-label={`${t('periods.view')} ${range}`}
                                      disabled={busy}
                                      onClick={() => selectPeriod(p.id)}
                                    >
                                      {t('periods.view')}
                                    </button>
                                    {p.status === 'DRAFT' && (
                                      <button type="button" className={btnDangerText} aria-label={`${t('periods.delete')} ${range}`} disabled={busy} onClick={() => remove(p)}>
                                        {busyKey === `delete:${p.id}` && spinner}
                                        {t('periods.delete')}
                                      </button>
                                    )}
                                  </div>
                                </td>
                              </tr>
                              {deadlineEdit?.id === p.id && (
                                <tr className="border-b border-slate-100 bg-cyan-50/50">
                                  <td colSpan={4} className="px-4 py-3">
                                    <form onSubmit={confirmDeadline} noValidate className="flex flex-wrap items-end gap-3">
                                      <label className="flex flex-col gap-1 text-sm font-bold">
                                        {t('periods.deadline.label')}
                                        <input
                                          type="datetime-local"
                                          min={deadlineEdit.required ? nowLocal : undefined}
                                          className={input}
                                          value={deadlineEdit.value}
                                          onChange={(e) => setDeadlineEdit({ ...deadlineEdit, value: e.target.value, error: null })}
                                        />
                                      </label>
                                      <button type="submit" className={btnPrimary} disabled={busy}>
                                        {busyKey === `status:${p.id}` ? spinner : null}
                                        {t(deadlineEdit.required ? 'periods.deadline.openConfirm' : 'periods.deadline.reopenConfirm')}
                                      </button>
                                      <button type="button" className={btnSecondary} disabled={busy} onClick={() => setDeadlineEdit(null)}>
                                        {t('periods.deadline.cancel')}
                                      </button>
                                      {deadlineEdit.error && (
                                        <p role="alert" className={`${alertText} w-full`}>
                                          {deadlineEdit.error}
                                        </p>
                                      )}
                                    </form>
                                  </td>
                                </tr>
                              )}
                            </Fragment>
                          )
                        })}

                      </tbody>
                    </table>
                </div>
              )}
            </div>

            {selectedId && (
              // Collapsible: starts open, and the key remounts it (open again) when the period changes.
              <details key={selectedId} open className="group space-y-3">
                <summary className={`flex cursor-pointer list-none flex-wrap items-center gap-3 rounded-md [&::-webkit-details-marker]:hidden ${focusRing}`}>
                  <SectionHeading icon={LayoutGrid}>{t('periods.slots.heading')}</SectionHeading>
                  {selected && (
                    <span className="flex flex-wrap items-center gap-2 text-sm">
                      <span className={muted}>{t('periods.slots.viewing').replace('{range}', rangeText(selected))}</span>
                      <span className={periodStatusPill[selected.status]}>{t(statusView[selected.status])}</span>
                    </span>
                  )}
                  <ChevronDown className="size-5 transition-transform group-open:rotate-180" aria-hidden="true" />
                </summary>
                {published && <p className={muted}>{t('periods.slots.locked')}</p>}
                <p className={`text-sm ${muted}`}>{t('periods.slots.legend')}</p>
                {selectedSlots === null && !failure && <p className={muted}>{t('team.loading')}</p>}
                {selectedSlots?.length === 0 && (
                  <div className={card}>
                    <EmptyState icon={LayoutGrid} text={t('periods.slots.empty')} />
                  </div>
                )}

                {selectedSlots && selectedSlots.length > 0 && (
                  <div className={card}>
                    <div className="overflow-x-auto">
                      <table className="w-full min-w-[640px] text-left">
                        <thead className="bg-slate-50 border-b border-slate-200">
                          <tr>
                            <th scope="col" className={th}>{t('periods.slots.day')}</th>
                            {columns.map((c) => (
                              <th key={c.templateId} scope="col" className={th}>
                                <span className="block text-sm text-slate-900">{c.templateName}</span>
                                <span className="inline-flex items-center gap-1 tabular-nums font-normal">
                                  {`${c.startTime}–${c.endTime}`}
                                  {c.isNight && (
                                    <>
                                      <Moon className="size-3.5" aria-hidden="true" />
                                      <span className="sr-only">{t('shifts.night')}</span>
                                    </>
                                  )}
                                </span>
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {days.map((date) => {
                            const weekend = [0, 6].includes(parseDay(date).getDay())
                            return (
                              <tr key={date} className={`border-b border-slate-100 last:border-0 ${weekend ? 'bg-slate-50' : ''}`}>
                                <th scope="row" className="px-4 py-3 font-bold tabular-nums">
                                  {dayLabel(date)}
                                </th>
                                {columns.map((c) => {
                                  const s = byCell.get(`${date}|${c.templateId}`)
                                  if (!s) return <td key={c.templateId} className="px-4 py-2"><span className={muted}>—</span></td>
                                  const name = `${c.templateName} ${dateText(date)}`
                                  const changed = overridden(s)
                                  const range = `${s.minStaff}–${s.maxStaff}`
                                  const label =
                                    t('periods.slots.cell').replace('{name}', name).replace('{min}', String(s.minStaff)).replace('{max}', String(s.maxStaff)) +
                                    (changed ? `, ${t('periods.slots.overridden').replace('{min}', String(s.defaultMinStaff)).replace('{max}', String(s.defaultMaxStaff))}` : '')
                                  const text = (
                                    <>
                                      {changed && <Pencil className="size-3.5" aria-hidden="true" />}
                                      {range}
                                    </>
                                  )
                                  return (
                                    <td key={c.templateId} className="px-4 py-2">
                                      {published ? (
                                        <span aria-label={label} className={`${cellText} ${changed ? cellChanged : ''}`}>
                                          {text}
                                        </span>
                                      ) : cell?.id === s.id ? (
                                        <div
                                          className="flex flex-wrap items-end gap-2"
                                          onBlur={(e) => {
                                            // Leaving one input for the other stays in the cell; saving waits for the cell.
                                            if (e.currentTarget.contains(e.relatedTarget as Node | null)) return
                                            const [min, max] = Array.from(e.currentTarget.querySelectorAll('input'), (i) => i.value)
                                            saveSlot(s, min, max, false)
                                          }}
                                          onKeyDown={(e) => {
                                            if (e.key === 'Escape') {
                                              returnFocus.current = `slot-${s.id}`
                                              setCell(null)
                                            }
                                            if (e.key === 'Enter') {
                                              e.preventDefault()
                                              const [min, max] = Array.from(e.currentTarget.querySelectorAll('input'), (i) => i.value)
                                              saveSlot(s, min, max, true)
                                            }
                                          }}
                                        >
                                          <label className="flex flex-col gap-1 text-xs font-bold">
                                            {t('periods.slots.minShort')}
                                            <input
                                              autoFocus
                                              type="number"
                                              min={0}
                                              className={`${input} w-20`}
                                              aria-label={`${t('periods.slots.min')} ${name}`}
                                              defaultValue={s.minStaff}
                                              disabled={busy}
                                            />
                                          </label>
                                          <label className="flex flex-col gap-1 text-xs font-bold">
                                            {t('periods.slots.maxShort')}
                                            <input
                                              type="number"
                                              min={1}
                                              className={`${input} w-20`}
                                              aria-label={`${t('periods.slots.max')} ${name}`}
                                              defaultValue={s.maxStaff}
                                              disabled={busy}
                                            />
                                          </label>
                                          {cell.error && (
                                            <p role="alert" className={`${alertText} w-full text-sm`}>
                                              {cell.error}
                                            </p>
                                          )}
                                        </div>
                                      ) : (
                                        <button
                                          type="button"
                                          id={`slot-${s.id}`}
                                          aria-label={label}
                                          disabled={busy}
                                          className={`${btnCell} ${changed ? cellChanged : ''}`}
                                          onClick={() => setCell({ id: s.id, error: null })}
                                        >
                                          {text}
                                        </button>
                                      )}
                                    </td>
                                  )
                                })}
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>
                  </div>
                )}
              </details>
            )}

            {selectedId && showsSubmissions && (
              <section className="space-y-3">
                <SectionHeading icon={ClipboardCheck}>{t('periods.submissions.heading')}</SectionHeading>
                {submissions?.periodId === selectedId && (
                  <p className={awaiting > 0 ? 'font-bold text-amber-900' : muted}>
                    {awaiting > 0
                      ? t('periods.submissions.summary').replace('{n}', String(awaiting)).replace('{total}', String(sortedSubmissions.length))
                      : t('periods.submissions.allDone')}
                  </p>
                )}
                {submissions?.periodId === selectedId && (
                  <div className={card}>
                    <div className="overflow-x-auto">
                      <table className="w-full min-w-[480px] text-left">
                        <thead className="bg-slate-50 border-b border-slate-200">
                          <tr>
                            <th scope="col" className={th}>{t('periods.submissions.col.member')}</th>
                            <th scope="col" className={th}>{t('periods.col.status')}</th>
                            <th scope="col" className={`${th} text-right`}>{t('periods.col.actions')}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {sortedSubmissions.map((m) => (
                            <tr key={m.userId} className="border-b border-slate-100 last:border-0">
                              <th scope="row" className="px-4 py-3 font-bold">
                                {m.displayName}
                              </th>
                              <td className="px-4 py-3">
                                <span className={submissionView[m.status].pill}>{t(submissionView[m.status].label)}</span>
                              </td>
                              <td className="px-4 py-3 text-right">
                                <button type="button" id={`member-${m.userId}`} className={btnSecondary} disabled={busy} onClick={() => setOnBehalf(m)}>
                                  {t(m.status === 'NOT_SUBMITTED' ? 'periods.submissions.enter' : 'periods.submissions.edit')}
                                </button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                )}
                {/* Native dialog: showModal gives the focus trap, Esc and the backdrop. Content is only mounted while a member is open. */}
                <dialog
                  ref={dialogRef}
                  aria-labelledby="member-dialog-title"
                  onClose={() => onBehalf && closeOnBehalf()}
                  onClick={(e) => e.target === e.currentTarget && onBehalf && closeOnBehalf()}
                  className="m-0 h-full w-full max-w-none max-h-none rounded-none bg-white p-0 text-slate-900 backdrop:bg-slate-900/50 sm:m-auto sm:h-auto sm:max-h-[90vh] sm:max-w-2xl sm:rounded-lg"
                >
                  {onBehalf && (
                    <div className="flex h-full flex-col sm:max-h-[90vh]">
                      <div className="flex shrink-0 items-start justify-between gap-4 border-b border-slate-200 p-4">
                        <div>
                          <h2 id="member-dialog-title" className="text-lg font-bold">
                            {t('periods.submissions.dialog').replace('{name}', onBehalf.displayName)}
                          </h2>
                          <p className={`text-sm ${muted}`}>{t('availability.onBehalfHint')}</p>
                        </div>
                        <button type="button" className={btnIcon} aria-label={t('periods.submissions.close')} title={t('periods.submissions.close')} onClick={closeOnBehalf}>
                          <X className="size-4" aria-hidden="true" />
                        </button>
                      </div>
                      <div className="min-h-0 flex-1 overflow-y-auto p-4">
                        <AvailabilityCalendar key={onBehalf.userId} teamId={teamId} periodId={selectedId} userId={onBehalf.userId} forManager />
                      </div>
                    </div>
                  )}
                </dialog>
              </section>
            )}
          </>
        )}
      </div>
    </PageLayout>
  )
}
