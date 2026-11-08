import { Check, Loader2, Plus, UserRoundCheck, UserRoundX, X } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { ApiError, api } from './api'
import { messages, t, type MessageKey } from './i18n/messages'
import {
  alertText,
  btnIcon,
  btnIconDanger,
  btnIconPrimary,
  btnPrimary,
  btnRow,
  btnSecondary,
  card,
  checkbox,
  input,
  inputGrouped,
  label,
  muted,
  pillCyan,
  pillGreen,
  pillSlate,
  suffix,
} from './ui'

const th = 'px-4 py-3 text-xs font-bold uppercase tracking-wide text-slate-600'
const spinner = <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />

type Team = { id: string; name: string }
type Member = {
  userId: string
  email: string
  displayName: string
  role: string
  ftePercent: number
  worksShifts: boolean
  active: boolean
}

// Unknown roles fall back to the raw backend value.
const roleLabel = (role: string) => (`role.${role}` in messages ? t(`role.${role}` as MessageKey) : role)

export function TeamPage({ meId, isAdmin }: { meId?: string; isAdmin?: boolean }) {
  const [teams, setTeams] = useState<Team[] | null>(null)
  const [members, setMembers] = useState<Member[] | null>(null)
  const [error, setError] = useState(false)
  const [reload, setReload] = useState(0)
  // Which action is running: 'add', 'create', `toggle:<userId>` or a userId for an inline edit.
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const busy = busyKey !== null
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [role, setRole] = useState('MEMBER')
  const [fte, setFte] = useState('100')
  const [worksShifts, setWorksShifts] = useState(true)
  const [addError, setAddError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [creating, setCreating] = useState(false)
  const [teamName, setTeamName] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const [params, setParams] = useSearchParams()
  // Selected team comes from ?team=; unknown or missing falls back to the first team.
  const teamId = teams?.find((team) => team.id === params.get('team'))?.id ?? teams?.[0]?.id ?? ''

  useEffect(() => {
    let ignore = false
    api<Team[]>('/api/teams')
      .then((list) => {
        if (ignore) return
        setTeams(list)
      })
      .catch(() => !ignore && setError(true))
    return () => {
      ignore = true
    }
  }, [])

  useEffect(() => {
    if (!teamId) return
    let ignore = false
    api<Member[]>(`/api/teams/${teamId}/members`)
      .then((list) => !ignore && setMembers(list))
      .catch(() => !ignore && setError(true))
    return () => {
      ignore = true
    }
  }, [teamId, reload])

  async function toggle(m: Member) {
    if (busy) return
    setBusyKey(`toggle:${m.userId}`)
    setError(false)
    try {
      await api(`/api/teams/${teamId}/members/${m.userId}`, m.active
        ? { method: 'DELETE' }
        : { method: 'PATCH', body: { active: true } })
      setReload((n) => n + 1)
    } catch {
      setError(true)
    } finally {
      setBusyKey(null)
    }
  }

  // Always refetch: the FTE inputs remount on the new key, so they show stored values again.
  async function patchMember(m: Member, body: Record<string, unknown>) {
    if (busy) return
    setBusyKey(m.userId)
    setError(false)
    try {
      await api(`/api/teams/${teamId}/members/${m.userId}`, { method: 'PATCH', body })
    } catch {
      setError(true)
    } finally {
      setBusyKey(null)
      setReload((n) => n + 1)
    }
  }

  function resetAdd() {
    setAdding(false)
    setName('')
    setEmail('')
    setRole('MEMBER')
    setFte('100')
    setWorksShifts(true)
    setAddError(null)
  }

  async function addMember(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (busy) return
    setBusyKey('add')
    setAddError(null)
    try {
      await api(`/api/teams/${teamId}/invites`, {
        method: 'POST',
        body: { email, displayName: name, role, ftePercent: Number(fte), worksShifts },
      })
      resetAdd()
      setReload((n) => n + 1)
    } catch (err) {
      setAddError(err instanceof ApiError && err.status === 409 ? t('team.add.conflict') : t('team.add.error'))
    } finally {
      setBusyKey(null)
    }
  }

  function resetCreate() {
    setCreating(false)
    setTeamName('')
    setCreateError(null)
  }

  // Refetches the team list and selects the new team by the id the POST returned.
  async function createTeam(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (busy) return
    setBusyKey('create')
    setCreateError(null)
    let id = ''
    try {
      id = (await api<{ id: string }>('/api/teams', { method: 'POST', body: { name: teamName } })).id
    } catch {
      setCreateError(t('team.create.error'))
    }
    if (id) {
      resetCreate()
      try {
        setTeams(await api<Team[]>('/api/teams'))
        setMembers(null)
        setParams({ team: id })
      } catch {
        setError(true)
      }
    }
    setBusyKey(null)
  }

  const activeCount = members?.filter((m) => m.active).length ?? 0

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex flex-wrap items-center gap-4">
          <h1 className="text-xl font-bold">{t('team.heading')}</h1>
          {teams && teams.length > 0 && (
            <div className="flex items-center gap-2">
              <label htmlFor="team-select" className="text-sm font-bold text-slate-600">
                {t('team.pick')}
              </label>
              <select
                id="team-select"
                className={input}
                value={teamId}
                onChange={(e) => {
                  setError(false)
                  setMembers(null)
                  setParams({ team: e.target.value })
                }}
              >
                {teams.map((team) => (
                  <option key={team.id} value={team.id}>
                    {team.name}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
        {isAdmin && (
          <button type="button" className={btnSecondary} disabled={busy} onClick={() => setCreating(!creating)}>
            <Plus className="size-4" aria-hidden="true" />
            {t('team.create.open')}
          </button>
        )}
      </div>

      {isAdmin && creating && (
        <form onSubmit={createTeam} className={`${card} p-4 flex flex-wrap items-end gap-3`}>
          <label className="flex flex-col gap-1">
            <span className={label}>{t('team.create.name')}</span>
            <input className={input} required value={teamName} onChange={(e) => setTeamName(e.target.value)} />
          </label>
          <button type="submit" className={btnPrimary} disabled={busy}>
            {busyKey === 'create' && spinner}
            {t('team.create.save')}
          </button>
          <button type="button" className={btnSecondary} disabled={busy} onClick={resetCreate}>
            {t('team.create.cancel')}
          </button>
        </form>
      )}
      {createError && (
        <p role="alert" className={alertText}>
          {createError}
        </p>
      )}

      {error && (
        <p role="alert" className={alertText}>
          {t('team.error')}
        </p>
      )}

      {teams === null && !error && <p className={muted}>{t('team.loading')}</p>}

      {teams?.length === 0 && <p className={muted}>{t('team.noTeams')}</p>}

      {teams && teams.length > 0 && (
        <>
          {members === null && !error && <p className={muted}>{t('team.loading')}</p>}

          {members?.length === 0 && <p className={muted}>{t('team.empty')}</p>}

          {members && (
            <div className={card}>
              <div className="px-4 py-3 border-b border-slate-200 text-sm text-slate-600">
                {t('team.summary').replace('{total}', String(members.length)).replace('{active}', String(activeCount))}
              </div>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[720px] text-left">
                  <thead className="bg-slate-50 border-b border-slate-200">
                    <tr>
                      <th scope="col" className={th}>{t('team.col.member')}</th>
                      <th scope="col" className={th}>{t('team.col.role')}</th>
                      <th scope="col" className={th}>{t('team.col.fte')}</th>
                      <th scope="col" className={th}>{t('team.col.shifts')}</th>
                      <th scope="col" className={th}>{t('team.col.status')}</th>
                      <th scope="col" className={`${th} text-right`}>{t('team.col.actions')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {members.map((m) => {
                      const actionLabel = `${m.active ? t('team.deactivate') : t('team.reactivate')} ${m.displayName}`
                      return (
                        <tr
                          key={m.userId}
                          className={`border-b border-slate-100 last:border-0 hover:bg-slate-50 ${m.active ? '' : 'text-slate-600'}`}
                        >
                          <th scope="row" className="px-4 py-3 font-normal text-left">
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="font-bold">{m.displayName}</span>
                              {m.userId === meId && <span className={pillCyan}>{t('team.badge.you')}</span>}
                            </div>
                            <div className="text-sm text-slate-600">{m.email}</div>
                          </th>
                          <td className="px-4 py-3">
                            <select
                              className={input}
                              aria-label={`${t('team.col.role')} ${m.displayName}`}
                              value={m.role}
                              disabled={busy || !m.active || (!isAdmin && m.role === 'MANAGER')}
                              onChange={(e) => patchMember(m, { role: e.target.value })}
                            >
                              <option value="MEMBER">{t('role.MEMBER')}</option>
                              <option value="MANAGER" disabled={!isAdmin && m.role !== 'MANAGER'}>{t('role.MANAGER')}</option>
                              {m.role !== 'MEMBER' && m.role !== 'MANAGER' && <option value={m.role}>{roleLabel(m.role)}</option>}
                            </select>
                          </td>
                          <td className="px-4 py-3">
                            <div className="inline-flex items-stretch">
                              <input
                                key={`${reload}-${m.ftePercent}`}
                                type="number"
                                min={1}
                                max={100}
                                className={`${inputGrouped} w-20`}
                                aria-label={`${t('team.col.fte')} ${m.displayName}`}
                                defaultValue={m.ftePercent}
                                disabled={busy || !m.active}
                                onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
                                onBlur={(e) => {
                                  const n = Number(e.currentTarget.value)
                                  if (Number.isInteger(n) && n >= 1 && n <= 100 && n !== m.ftePercent) patchMember(m, { ftePercent: n })
                                  else e.currentTarget.value = String(m.ftePercent)
                                }}
                              />
                              <span className={suffix} aria-hidden="true">%</span>
                            </div>
                          </td>
                          <td className="px-4 py-3">
                            <input
                              type="checkbox"
                              className={checkbox}
                              aria-label={`${t('team.col.shifts')} ${m.displayName}`}
                              checked={m.worksShifts}
                              disabled={busy || !m.active}
                              onChange={(e) => patchMember(m, { worksShifts: e.target.checked })}
                            />
                          </td>
                          <td className="px-4 py-3">
                            <span className={m.active ? pillGreen : pillSlate}>
                              {m.active ? t('team.active') : t('team.inactive')}
                            </span>
                          </td>
                          <td className="px-4 py-3 text-right">
                            {m.userId !== meId && (
                              <button
                                type="button"
                                className={m.active ? btnIconDanger : btnIcon}
                                aria-label={actionLabel}
                                title={actionLabel}
                                disabled={busy}
                                onClick={() => toggle(m)}
                              >
                                {busyKey === `toggle:${m.userId}` ? spinner : m.active ? (
                                  <UserRoundX className="size-4" aria-hidden="true" />
                                ) : (
                                  <UserRoundCheck className="size-4" aria-hidden="true" />
                                )}
                              </button>
                            )}
                          </td>
                        </tr>
                      )
                    })}

                    {adding ? (
                      <>
                        <tr className="border-b border-slate-100 bg-cyan-50/50">
                          <td className="px-4 py-3">
                            <div className="flex flex-col gap-2">
                              <input form="add-member" className={input} aria-label={t('team.add.name')} required value={name} onChange={(e) => setName(e.target.value)} />
                              <input form="add-member" type="email" className={input} aria-label={t('team.add.email')} required value={email} onChange={(e) => setEmail(e.target.value)} />
                            </div>
                          </td>
                          <td className="px-4 py-3">
                            <select form="add-member" className={input} aria-label={t('team.add.role')} value={role} onChange={(e) => setRole(e.target.value)}>
                              <option value="MEMBER">{t('role.MEMBER')}</option>
                              <option value="MANAGER" disabled={!isAdmin}>{t('role.MANAGER')}</option>
                            </select>
                          </td>
                          <td className="px-4 py-3">
                            <div className="inline-flex items-stretch">
                              <input form="add-member" type="number" min={1} max={100} required className={`${inputGrouped} w-20`} aria-label={t('team.add.fte')} value={fte} onChange={(e) => setFte(e.target.value)} />
                              <span className={suffix} aria-hidden="true">%</span>
                            </div>
                          </td>
                          <td className="px-4 py-3">
                            <input form="add-member" type="checkbox" className={checkbox} aria-label={t('team.add.shifts')} checked={worksShifts} onChange={(e) => setWorksShifts(e.target.checked)} />
                          </td>
                          <td className="px-4 py-3" />
                          <td className="px-4 py-3">
                            <div className="flex justify-end gap-2">
                              <button type="submit" form="add-member" className={btnIconPrimary} aria-label={t('team.add.save')} title={t('team.add.save')} disabled={busy}>
                                {busyKey === 'add' ? spinner : <Check className="size-4" aria-hidden="true" />}
                              </button>
                              <button type="button" className={btnIcon} aria-label={t('team.add.cancel')} title={t('team.add.cancel')} disabled={busy} onClick={resetAdd}>
                                <X className="size-4" aria-hidden="true" />
                              </button>
                            </div>
                          </td>
                        </tr>
                        {addError && (
                          <tr className="border-b border-slate-100 bg-cyan-50/50">
                            <td colSpan={6} className="px-4 pb-3">
                              <p role="alert" className={alertText}>
                                {addError}
                              </p>
                            </td>
                          </tr>
                        )}
                      </>
                    ) : (
                      <tr>
                        <td colSpan={6} className="p-0 border-t border-dashed border-slate-300">
                          <button type="button" className={btnRow} disabled={busy} onClick={() => setAdding(true)}>
                            <Plus className="size-4" aria-hidden="true" />
                            {t('team.add.submit')}
                          </button>
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          <form id="add-member" onSubmit={addMember} />
        </>
      )}
    </div>
  )
}
