import { Check, Loader2, Moon, Pencil, Plus, Power, PowerOff, Sun, X } from 'lucide-react'
import { Fragment, useEffect, useState, type ChangeEvent, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { ApiError, api } from './api'
import { t, type MessageKey } from './i18n/messages'
import {
  alertText,
  btnIcon,
  btnIconDanger,
  btnIconPrimary,
  btnRow,
  card,
  checkbox,
  input,
  muted,
  pillAmber,
  pillGreen,
  pillIndigo,
  pillSlate,
} from './ui'

const th = 'px-4 py-3 text-xs font-bold uppercase tracking-wide text-slate-600'
const spinner = <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />

type Team = { id: string; name: string }
type Shift = {
  id: string
  name: string
  startTime: string
  endTime: string
  isNight: boolean
  minStaff: number
  maxStaff: number
  active: boolean
}
// Inputs hold strings; the body is built from them on save.
type Draft = {
  name: string
  startTime: string
  endTime: string
  isNight: boolean
  minStaff: string
  maxStaff: string
}
// id null is the add row; initial is what the row opened with, to detect unsaved changes.
type Edit = { id: string | null; draft: Draft; initial: Draft }

const blank: Draft = { name: '', startTime: '08:00', endTime: '20:00', isNight: false, minStaff: '1', maxStaff: '1' }
const toDraft = (s: Shift): Draft => ({
  name: s.name,
  startTime: s.startTime,
  endTime: s.endTime,
  isNight: s.isNight,
  minStaff: String(s.minStaff),
  maxStaff: String(s.maxStaff),
})
const toBody = (d: Draft) => ({
  name: d.name,
  startTime: d.startTime,
  endTime: d.endTime,
  isNight: d.isNight,
  minStaff: Number(d.minStaff),
  maxStaff: Number(d.maxStaff),
})

const toMin = (hhmm: string) => {
  const [h, m] = hhmm.split(':').map(Number)
  return h * 60 + m
}
// Equal start and end is a full 24 h; a later end than start wraps past midnight.
const minutesOf = (s: Shift) => ((toMin(s.endTime) - toMin(s.startTime) + 1440) % 1440) || 1440
const nextDay = (s: Shift) => toMin(s.endTime) <= toMin(s.startTime)
// Whole or half hours in practice; one decimal keeps other values readable.
const hoursText = (s: Shift) => String(Math.round(minutesOf(s) / 6) / 10).replace('.', ',')

// 404 means the caller cannot manage the team, so the generic message would mislead.
const errorFor = (err: unknown, fallback: MessageKey) =>
  t(err instanceof ApiError && err.status === 404 ? 'shifts.forbidden' : fallback)

export function ShiftsPage() {
  const [params, setParams] = useSearchParams()
  const [teams, setTeams] = useState<Team[] | null>(null)
  const [shifts, setShifts] = useState<Shift[] | null>(null)
  const [edit, setEdit] = useState<Edit | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editError, setEditError] = useState<string | null>(null)
  const [reload, setReload] = useState(0)
  // 'save', or `toggle:<id>` for the row whose active flag is changing.
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const busy = busyKey !== null

  const teamId = teams?.find((team) => team.id === params.get('team'))?.id ?? teams?.[0]?.id ?? ''

  useEffect(() => {
    let ignore = false
    api<Team[]>('/api/teams')
      .then((list) => !ignore && setTeams(list))
      .catch((err) => !ignore && setError(errorFor(err, 'shifts.error')))
    return () => {
      ignore = true
    }
  }, [])

  useEffect(() => {
    if (!teamId) return
    let ignore = false
    api<Shift[]>(`/api/teams/${teamId}/shift-templates`)
      .then((list) => !ignore && setShifts(list))
      .catch((err) => !ignore && setError(errorFor(err, 'shifts.error')))
    return () => {
      ignore = true
    }
  }, [teamId, reload])

  function selectTeam(id: string) {
    setError(null)
    setEdit(null)
    setEditError(null)
    setShifts(null)
    setParams({ team: id })
  }

  function open(next: Edit) {
    const dirty = edit !== null && JSON.stringify(edit.draft) !== JSON.stringify(edit.initial)
    if (dirty && !window.confirm(t('shifts.discardConfirm'))) return
    setEdit(next)
    setEditError(null)
  }

  function cancel() {
    setEdit(null)
    setEditError(null)
  }

  const setDraft = (patch: Partial<Draft>) => setEdit((e) => e && { ...e, draft: { ...e.draft, ...patch } })
  const field = (key: keyof Draft) => (e: ChangeEvent<HTMLInputElement>) => setDraft({ [key]: e.target.value })

  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (busy || !edit) return
    if (Number(edit.draft.maxStaff) < Number(edit.draft.minStaff)) {
      setEditError(t('shifts.form.maxBelowMin'))
      return
    }
    setBusyKey('save')
    setEditError(null)
    try {
      const path = `/api/teams/${teamId}/shift-templates`
      const body = toBody(edit.draft)
      // A PUT can return a new id (a template used by a schedule is replaced), so the list is refetched.
      await (edit.id ? api(`${path}/${edit.id}`, { method: 'PUT', body }) : api(path, { method: 'POST', body }))
      setEdit(null)
      setReload((n) => n + 1)
    } catch {
      setEditError(t('shifts.save.error'))
    } finally {
      setBusyKey(null)
    }
  }

  async function toggle(s: Shift) {
    if (busy) return
    setBusyKey(`toggle:${s.id}`)
    setError(null)
    try {
      const url = `/api/teams/${teamId}/shift-templates/${s.id}`
      await api(url, s.active ? { method: 'DELETE' } : { method: 'PATCH', body: { active: true } })
      setReload((n) => n + 1)
    } catch {
      setError(t('shifts.toggle.error'))
    } finally {
      setBusyKey(null)
    }
  }

  const draftRows = edit && (
    <>
      <tr className="border-b border-slate-100 bg-cyan-50/50">
        <th scope="row" className="px-4 py-3 font-normal">
          <input form="shift-form" className={input} aria-label={t('shifts.form.name')} required value={edit.draft.name} onChange={field('name')} />
        </th>
        <td className="px-4 py-3">
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-2">
              <span className="text-sm">{t('shifts.form.start')}</span>
              <input form="shift-form" type="time" className={input} required value={edit.draft.startTime} onChange={field('startTime')} />
            </label>
            <label className="flex items-center gap-2">
              <span className="text-sm">{t('shifts.form.end')}</span>
              <input form="shift-form" type="time" className={input} required value={edit.draft.endTime} onChange={field('endTime')} />
            </label>
          </div>
        </td>
        <td className="px-4 py-3" />
        <td className="px-4 py-3">
          <label className="inline-flex items-center gap-2 font-bold">
            <input form="shift-form" type="checkbox" className={checkbox} checked={edit.draft.isNight} onChange={(e) => setDraft({ isNight: e.target.checked })} />
            {t('shifts.night')}
          </label>
        </td>
        <td className="px-4 py-3">
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-2">
              <span className="text-sm">{t('shifts.form.min')}</span>
              <input form="shift-form" type="number" min={0} required className={`${input} w-20`} value={edit.draft.minStaff} onChange={field('minStaff')} />
            </label>
            <label className="flex items-center gap-2">
              <span className="text-sm">{t('shifts.form.max')}</span>
              <input form="shift-form" type="number" min={1} required className={`${input} w-20`} value={edit.draft.maxStaff} onChange={field('maxStaff')} />
            </label>
          </div>
        </td>
        <td className="px-4 py-3" />
        <td className="px-4 py-3">
          <div className="flex justify-end gap-2">
            <button type="submit" form="shift-form" className={btnIconPrimary} aria-label={t('shifts.form.save')} title={t('shifts.form.save')} disabled={busy}>
              {busyKey === 'save' ? spinner : <Check className="size-4" aria-hidden="true" />}
            </button>
            <button type="button" className={btnIcon} aria-label={t('shifts.form.cancel')} title={t('shifts.form.cancel')} disabled={busy} onClick={cancel}>
              <X className="size-4" aria-hidden="true" />
            </button>
          </div>
        </td>
      </tr>
      {editError && (
        <tr className="border-b border-slate-100 bg-cyan-50/50">
          <td colSpan={7} className="px-4 pb-3">
            <p role="alert" className={alertText}>
              {editError}
            </p>
          </td>
        </tr>
      )}
    </>
  )

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-4">
        <h1 className="text-xl font-bold">{t('shifts.heading')}</h1>
        {teams && teams.length > 0 && (
          <div className="flex items-center gap-2">
            <label htmlFor="team-select" className="text-sm font-bold text-slate-600">
              {t('team.pick')}
            </label>
            <select id="team-select" className={input} value={teamId} onChange={(e) => selectTeam(e.target.value)}>
              {teams.map((team) => (
                <option key={team.id} value={team.id}>
                  {team.name}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>

      {error && (
        <p role="alert" className={alertText}>
          {error}
        </p>
      )}

      {teams === null && !error && <p className={muted}>{t('team.loading')}</p>}

      {teams?.length === 0 && <p className={muted}>{t('team.noTeams')}</p>}

      {teams && teams.length > 0 && (
        <>
          {shifts === null && !error && <p className={muted}>{t('team.loading')}</p>}

          {shifts && (
            <>
              <div className={card}>
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[960px] text-left">
                    <thead className="bg-slate-50 border-b border-slate-200">
                      <tr>
                        <th scope="col" className={th}>{t('shifts.col.name')}</th>
                        <th scope="col" className={th}>{t('shifts.col.hours')}</th>
                        <th scope="col" className={th}>{t('shifts.col.duration')}</th>
                        <th scope="col" className={th}>{t('shifts.col.type')}</th>
                        <th scope="col" className={th}>{t('shifts.col.coverage')}</th>
                        <th scope="col" className={th}>{t('shifts.col.status')}</th>
                        <th scope="col" className={`${th} text-right`}>{t('shifts.col.actions')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {shifts.map((s) =>
                        edit?.id === s.id ? (
                          <Fragment key={s.id}>{draftRows}</Fragment>
                        ) : (
                          <tr
                            key={s.id}
                            className={`border-b border-slate-100 last:border-0 hover:bg-slate-50 ${s.active ? '' : 'text-slate-600'}`}
                          >
                            <th scope="row" className="px-4 py-3 font-bold">
                              {s.name}
                            </th>
                            <td className="px-4 py-3">
                              <div className="flex flex-wrap items-center gap-2 tabular-nums">
                                <span>{`${s.startTime} – ${s.endTime}`}</span>
                                {nextDay(s) && <span className={pillSlate}>{t('shifts.nextDay')}</span>}
                              </div>
                            </td>
                            <td className="px-4 py-3">{t('shifts.duration').replace('{hours}', hoursText(s))}</td>
                            <td className="px-4 py-3">
                              <span className={s.isNight ? pillIndigo : pillAmber}>
                                {s.isNight ? <Moon className="size-3.5" aria-hidden="true" /> : <Sun className="size-3.5" aria-hidden="true" />}
                                {t(s.isNight ? 'shifts.night' : 'shifts.day')}
                              </span>
                            </td>
                            <td className="px-4 py-3 tabular-nums">
                              {t('shifts.coverage').replace('{min}', String(s.minStaff)).replace('{max}', String(s.maxStaff))}
                            </td>
                            <td className="px-4 py-3">
                              <span className={s.active ? pillGreen : pillSlate}>{s.active ? t('shifts.active') : t('shifts.inactive')}</span>
                            </td>
                            <td className="px-4 py-3">
                              <div className="flex justify-end gap-2">
                                <button
                                  type="button"
                                  className={btnIcon}
                                  aria-label={`${t('shifts.edit')} ${s.name}`}
                                  title={`${t('shifts.edit')} ${s.name}`}
                                  disabled={busy}
                                  onClick={() => open({ id: s.id, draft: toDraft(s), initial: toDraft(s) })}
                                >
                                  <Pencil className="size-4" aria-hidden="true" />
                                </button>
                                <button
                                  type="button"
                                  className={s.active ? btnIconDanger : btnIcon}
                                  aria-label={`${s.active ? t('shifts.deactivate') : t('shifts.reactivate')} ${s.name}`}
                                  title={`${s.active ? t('shifts.deactivate') : t('shifts.reactivate')} ${s.name}`}
                                  disabled={busy}
                                  onClick={() => toggle(s)}
                                >
                                  {busyKey === `toggle:${s.id}` ? spinner : s.active ? (
                                    <PowerOff className="size-4" aria-hidden="true" />
                                  ) : (
                                    <Power className="size-4" aria-hidden="true" />
                                  )}
                                </button>
                              </div>
                            </td>
                          </tr>
                        ),
                      )}

                      {shifts.length === 0 && (
                        <tr>
                          <td colSpan={7} className={`px-4 py-3 ${muted}`}>
                            {t('shifts.empty')}
                          </td>
                        </tr>
                      )}

                      {edit?.id === null ? (
                        draftRows
                      ) : (
                        <tr>
                          <td colSpan={7} className="p-0 border-t border-dashed border-slate-300">
                            <button type="button" className={btnRow} disabled={busy} onClick={() => open({ id: null, draft: blank, initial: blank })}>
                              <Plus className="size-4" aria-hidden="true" />
                              {t('shifts.add.submit')}
                            </button>
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
              <p className={`text-sm ${muted}`}>{t('shifts.note')}</p>
              <form id="shift-form" onSubmit={save} />
            </>
          )}
        </>
      )}
    </div>
  )
}
