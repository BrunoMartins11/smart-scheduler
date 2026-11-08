import { Check, Loader2 } from 'lucide-react'
import { useEffect, useState, type ChangeEvent, type FormEvent, type InputHTMLAttributes, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { ApiError, api } from './api'
import { t, type MessageKey } from './i18n/messages'
import {
  alertText,
  btnPrimary,
  btnSecondary,
  card,
  input,
  inputGrouped,
  label,
  muted,
  pillSlate,
  suffix,
} from './ui'

type Team = { id: string; name: string }
type Rules = {
  minRestBetweenShiftsMin: number
  maxConsecutiveWorkMin: number
  maxAvgWeeklyWorkMin: number
  avgReferenceWeeks: number
  minWeeklyRestMin: number
  maxConsecutiveNights: number | null
  restAfterNightRunMin: number | null
}
// Inputs hold strings; durations are hours here and minutes on the wire.
type Form = {
  minRest: string
  maxWork: string
  maxAvg: string
  refWeeks: string
  minWeekly: string
  maxNights: string
  restAfter: string
}

const toForm = (r: Rules): Form => ({
  minRest: String(r.minRestBetweenShiftsMin / 60),
  maxWork: String(r.maxConsecutiveWorkMin / 60),
  maxAvg: String(r.maxAvgWeeklyWorkMin / 60),
  refWeeks: String(r.avgReferenceWeeks),
  minWeekly: String(r.minWeeklyRestMin / 60),
  maxNights: r.maxConsecutiveNights == null ? '' : String(r.maxConsecutiveNights),
  restAfter: r.restAfterNightRunMin == null ? '' : String(r.restAfterNightRunMin / 60),
})

const toRules = (f: Form): Rules => ({
  minRestBetweenShiftsMin: Number(f.minRest) * 60,
  maxConsecutiveWorkMin: Number(f.maxWork) * 60,
  maxAvgWeeklyWorkMin: Number(f.maxAvg) * 60,
  avgReferenceWeeks: Number(f.refWeeks),
  minWeeklyRestMin: Number(f.minWeekly) * 60,
  maxConsecutiveNights: f.maxNights === '' ? null : Number(f.maxNights),
  restAfterNightRunMin: f.restAfter === '' ? null : Number(f.restAfter) * 60,
})

// 404 means the caller cannot manage the team, so the generic message would mislead.
const errorFor = (err: unknown, fallback: MessageKey) =>
  t(err instanceof ApiError && err.status === 404 ? 'rules.forbidden' : fallback)

const spinner = <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />

function Section({ title, desc, children }: { title: MessageKey; desc: MessageKey; children: ReactNode }) {
  return (
    <section className={`${card} p-4 md:p-6 space-y-4`}>
      <div>
        <h2 className="text-lg font-bold">{t(title)}</h2>
        <p className={muted}>{t(desc)}</p>
      </div>
      {children}
    </section>
  )
}

function Field({
  id,
  text,
  hint,
  unit,
  optional,
  ...props
}: {
  id: string
  text: string
  hint?: string
  unit: MessageKey
  optional?: boolean
} & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor={id} className={label}>
          {text}
        </label>
        {optional && <span className={pillSlate}>{t('rules.optional')}</span>}
      </div>
      <div className="inline-flex items-stretch self-start">
        <input
          id={id}
          type="number"
          className={`${inputGrouped} w-32`}
          aria-describedby={hint ? `${id}-hint` : undefined}
          {...props}
        />
        <span className={suffix} aria-hidden="true">
          {t(unit)}
        </span>
      </div>
      {hint && (
        <p id={`${id}-hint`} className={`text-sm ${muted}`}>
          {hint}
        </p>
      )}
    </div>
  )
}

export function RulesPage() {
  const [params, setParams] = useSearchParams()
  const [teams, setTeams] = useState<Team[] | null>(null)
  const [form, setForm] = useState<Form | null>(null)
  // The last rules loaded from the server; the form is dirty when it differs.
  const [loaded, setLoaded] = useState<Form | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [copyError, setCopyError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [reload, setReload] = useState(0)
  const [pending, setPending] = useState<'save' | 'copy' | null>(null)
  const busy = pending !== null
  const [copyFrom, setCopyFrom] = useState('')

  const teamId = teams?.find((team) => team.id === params.get('team'))?.id ?? teams?.[0]?.id ?? ''
  const current = teams?.find((team) => team.id === teamId)
  const others = teams?.filter((team) => team.id !== teamId) ?? []
  const from = others.find((team) => team.id === copyFrom) ?? others[0]
  const dirty = form !== null && JSON.stringify(form) !== JSON.stringify(loaded)

  useEffect(() => {
    let ignore = false
    api<Team[]>('/api/teams')
      .then((list) => !ignore && setTeams(list))
      .catch((err) => !ignore && setError(errorFor(err, 'rules.error')))
    return () => {
      ignore = true
    }
  }, [])

  useEffect(() => {
    if (!teamId) return
    let ignore = false
    api<Rules>(`/api/teams/${teamId}/rules`)
      .then((r) => {
        if (ignore) return
        const f = toForm(r)
        setForm(f)
        setLoaded(f)
      })
      .catch((err) => !ignore && setError(errorFor(err, 'rules.error')))
    return () => {
      ignore = true
    }
  }, [teamId, reload])

  function selectTeam(id: string) {
    setError(null)
    setSaveError(null)
    setCopyError(null)
    setSaved(false)
    setForm(null)
    setParams({ team: id })
  }

  const set = (key: keyof Form) => (e: ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value
    setForm((f) => f && { ...f, [key]: value })
  }

  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (busy || !form) return
    setPending('save')
    setSaveError(null)
    setSaved(false)
    try {
      await api(`/api/teams/${teamId}/rules`, { method: 'PUT', body: toRules(form) })
      setSaved(true)
      setReload((n) => n + 1)
    } catch (err) {
      setSaveError(errorFor(err, 'rules.save.error'))
    } finally {
      setPending(null)
    }
  }

  async function copy() {
    if (busy || !from || !current) return
    if (!window.confirm(t('rules.copy.confirm').replace('{to}', current.name).replace('{from}', from.name))) return
    setPending('copy')
    setCopyError(null)
    setSaved(false)
    try {
      await api(`/api/teams/${teamId}/rules/copy`, { method: 'POST', body: { fromTeamId: from.id } })
      setReload((n) => n + 1)
    } catch (err) {
      setCopyError(errorFor(err, 'rules.copy.error'))
    } finally {
      setPending(null)
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-4">
        <h1 className="text-xl font-bold">{t('rules.heading')}</h1>
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
          {form && (
            <form onSubmit={save} className="space-y-6">
              <Section title="rules.section.rest" desc="rules.section.rest.desc">
                <div className="grid gap-4 md:grid-cols-2">
                  <Field id="rule-min-rest" text={t('rules.minRest')} hint={t('rules.hint.minRest')} unit="rules.unit.h" required min={0.5} step={0.5} disabled={busy} value={form.minRest} onChange={set('minRest')} />
                  <Field id="rule-min-weekly" text={t('rules.minWeekly')} unit="rules.unit.h" required min={0.5} step={0.5} disabled={busy} value={form.minWeekly} onChange={set('minWeekly')} />
                </div>
              </Section>

              <Section title="rules.section.limits" desc="rules.section.limits.desc">
                <div className="grid gap-4 md:grid-cols-3">
                  <Field id="rule-max-work" text={t('rules.maxWork')} hint={t('rules.hint.maxWork')} unit="rules.unit.h" required min={0.5} step={0.5} disabled={busy} value={form.maxWork} onChange={set('maxWork')} />
                  <Field id="rule-max-avg" text={t('rules.maxAvg')} hint={t('rules.hint.maxAvg')} unit="rules.unit.h" required min={0.5} step={0.5} disabled={busy} value={form.maxAvg} onChange={set('maxAvg')} />
                  <Field id="rule-ref-weeks" text={t('rules.refWeeks')} hint={t('rules.hint.refWeeks')} unit="rules.unit.weeks" required min={1} max={52} step={1} disabled={busy} value={form.refWeeks} onChange={set('refWeeks')} />
                </div>
              </Section>

              <Section title="rules.section.nights" desc="rules.section.nights.desc">
                <div className="grid gap-4 md:grid-cols-2">
                  <Field id="rule-max-nights" text={t('rules.maxNights')} hint={t('rules.hint.maxNights')} optional unit="rules.unit.nights" min={0} step={1} disabled={busy} value={form.maxNights} onChange={set('maxNights')} />
                  <Field id="rule-rest-after" text={t('rules.restAfter')} hint={t('rules.notSet')} optional unit="rules.unit.h" min={0.5} step={0.5} disabled={busy} value={form.restAfter} onChange={set('restAfter')} />
                </div>
              </Section>

              <div className="sticky bottom-0 z-10 flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 bg-white py-3">
                {dirty && (
                  <p className={`flex items-center gap-2 text-sm ${muted}`}>
                    <span className="size-2 rounded-full bg-amber-500" aria-hidden="true" />
                    {t('rules.unsaved')}
                  </p>
                )}
                <div className="ml-auto flex flex-wrap items-center gap-3">
                  {saved && (
                    <p role="status" className="flex items-center gap-2 font-bold text-green-800">
                      <Check className="size-4" aria-hidden="true" />
                      {t('rules.saved')}
                    </p>
                  )}
                  {saveError && (
                    <p role="alert" className={alertText}>
                      {saveError}
                    </p>
                  )}
                  <button type="submit" className={btnPrimary} disabled={busy || !dirty}>
                    {pending === 'save' && spinner}
                    {t('rules.save')}
                  </button>
                </div>
              </div>
            </form>
          )}

          {others.length > 0 && (
            <section className={`${card} p-4 md:p-6 space-y-4`}>
              <div>
                <h2 className="text-lg font-bold">{t('rules.copy.section')}</h2>
                <p className={muted}>{t('rules.copy.desc')}</p>
              </div>
              <div className="flex flex-wrap items-end gap-3">
                <div className="flex flex-col gap-1">
                  <label htmlFor="copy-from" className={label}>
                    {t('rules.copy.from')}
                  </label>
                  <select id="copy-from" className={input} value={from?.id} onChange={(e) => setCopyFrom(e.target.value)}>
                    {others.map((team) => (
                      <option key={team.id} value={team.id}>
                        {team.name}
                      </option>
                    ))}
                  </select>
                </div>
                <button type="button" className={btnSecondary} disabled={busy} onClick={copy}>
                  {pending === 'copy' && spinner}
                  {t('rules.copy.submit')}
                </button>
              </div>
              {copyError && (
                <p role="alert" className={alertText}>
                  {copyError}
                </p>
              )}
            </section>
          )}
        </>
      )}
    </div>
  )
}
