import { Check, UserRoundCheck, UserRoundPlus, UserRoundX, X } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api } from './api'
import { messages, t, type MessageKey } from './i18n/messages'

const btnIcon =
  'size-10 inline-flex items-center justify-center rounded-md border border-slate-300 bg-white hover:bg-slate-50 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-700'
const select =
  'h-10 px-3 rounded-md border border-slate-300 bg-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-700'

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

export function TeamPage({ meId }: { meId?: string }) {
  const [teams, setTeams] = useState<Team[] | null>(null)
  const [teamId, setTeamId] = useState('')
  const [members, setMembers] = useState<Member[] | null>(null)
  const [error, setError] = useState(false)
  const [reload, setReload] = useState(0)
  const [busy, setBusy] = useState(false)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [role, setRole] = useState('MEMBER')
  const [fte, setFte] = useState('100')
  const [worksShifts, setWorksShifts] = useState(true)
  const [addError, setAddError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)

  useEffect(() => {
    let ignore = false
    api<Team[]>('/api/teams')
      .then((list) => {
        if (ignore) return
        setTeams(list)
        setTeamId(list[0]?.id ?? '')
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
    setBusy(true)
    setError(false)
    try {
      await api(`/api/teams/${teamId}/members/${m.userId}`, m.active
        ? { method: 'DELETE' }
        : { method: 'PATCH', body: { active: true } })
      setReload((n) => n + 1)
    } catch {
      setError(true)
    } finally {
      setBusy(false)
    }
  }

  // Always refetch: the FTE inputs remount on the new key, so they show stored values again.
  async function patchMember(m: Member, body: Record<string, unknown>) {
    if (busy) return
    setBusy(true)
    setError(false)
    try {
      await api(`/api/teams/${teamId}/members/${m.userId}`, { method: 'PATCH', body })
    } catch {
      setError(true)
    } finally {
      setBusy(false)
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
    setBusy(true)
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
      setBusy(false)
    }
  }

  return (
    <main className="p-4 space-y-4">
      <h1 className="text-xl font-bold">{t('team.heading')}</h1>

      {error && (
        <p role="alert" className="text-red-700">
          {t('team.error')}
        </p>
      )}

      {teams === null && !error && <p className="text-slate-600">{t('team.loading')}</p>}

      {teams?.length === 0 && <p className="text-slate-600">{t('team.noTeams')}</p>}

      {teams && teams.length > 0 && (
        <>
          <div className="flex flex-wrap items-center gap-3">
            <label htmlFor="team-select" className="font-bold">
              {t('team.pick')}
            </label>
            <select
              id="team-select"
              className={select}
              value={teamId}
              onChange={(e) => {
                setError(false)
                setMembers(null)
                setTeamId(e.target.value)
              }}
            >
              {teams.map((team) => (
                <option key={team.id} value={team.id}>
                  {team.name}
                </option>
              ))}
            </select>
          </div>

          {members === null && !error && <p className="text-slate-600">{t('team.loading')}</p>}

          {members?.length === 0 && <p className="text-slate-600">{t('team.empty')}</p>}

          {members && (
            <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
              <table className="w-full text-left">
                <thead className="border-b border-slate-200 bg-slate-50">
                  <tr>
                    <th scope="col" className="px-3 py-2">{t('team.col.name')}</th>
                    <th scope="col" className="px-3 py-2">{t('team.col.email')}</th>
                    <th scope="col" className="px-3 py-2">{t('team.col.role')}</th>
                    <th scope="col" className="px-3 py-2">{t('team.col.fte')}</th>
                    <th scope="col" className="px-3 py-2">{t('team.col.shifts')}</th>
                    <th scope="col" className="px-3 py-2">{t('team.col.status')}</th>
                    <th scope="col" className="px-3 py-2">{t('team.col.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {members.map((m) => (
                    <tr key={m.userId} className="border-b border-slate-100 last:border-0">
                      <th scope="row" className="px-3 py-2 font-normal">{m.displayName}</th>
                      <td className="px-3 py-2">{m.email}</td>
                      <td className="px-3 py-2">
                        <select
                          className={select}
                          aria-label={`${t('team.col.role')} ${m.displayName}`}
                          value={m.role}
                          disabled={busy || !m.active}
                          onChange={(e) => patchMember(m, { role: e.target.value })}
                        >
                          <option value="MEMBER">{t('role.MEMBER')}</option>
                          <option value="MANAGER">{t('role.MANAGER')}</option>
                          {m.role !== 'MEMBER' && m.role !== 'MANAGER' && <option value={m.role}>{roleLabel(m.role)}</option>}
                        </select>
                      </td>
                      <td className="px-3 py-2">
                        <input
                          key={`${reload}-${m.ftePercent}`}
                          type="number"
                          min={1}
                          max={100}
                          className={`${select} w-24`}
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
                      </td>
                      <td className="px-3 py-2">
                        <input
                          type="checkbox"
                          aria-label={`${t('team.col.shifts')} ${m.displayName}`}
                          checked={m.worksShifts}
                          disabled={busy || !m.active}
                          onChange={(e) => patchMember(m, { worksShifts: e.target.checked })}
                        />
                      </td>
                      <td className="px-3 py-2">{m.active ? t('team.active') : t('team.inactive')}</td>
                      <td className="px-3 py-2">
                        {m.userId !== meId && (
                          <button
                            type="button"
                            className={btnIcon}
                            aria-label={`${m.active ? t('team.deactivate') : t('team.reactivate')} ${m.displayName}`}
                            disabled={busy}
                            onClick={() => toggle(m)}
                          >
                            {m.active ? (
                              <UserRoundX className="size-4" aria-hidden="true" />
                            ) : (
                              <UserRoundCheck className="size-4" aria-hidden="true" />
                            )}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                  {adding ? (
                    <tr className="border-b border-slate-100 last:border-0">
                      <td className="px-3 py-2">
                        <input form="add-member" className={select} aria-label={t('team.add.name')} required value={name} onChange={(e) => setName(e.target.value)} />
                      </td>
                      <td className="px-3 py-2">
                        <input form="add-member" type="email" className={select} aria-label={t('team.add.email')} required value={email} onChange={(e) => setEmail(e.target.value)} />
                      </td>
                      <td className="px-3 py-2">
                        <select form="add-member" className={select} aria-label={t('team.add.role')} value={role} onChange={(e) => setRole(e.target.value)}>
                          <option value="MEMBER">{t('role.MEMBER')}</option>
                          <option value="MANAGER">{t('role.MANAGER')}</option>
                        </select>
                      </td>
                      <td className="px-3 py-2">
                        <input form="add-member" type="number" min={1} max={100} required className={`${select} w-24`} aria-label={t('team.add.fte')} value={fte} onChange={(e) => setFte(e.target.value)} />
                      </td>
                      <td className="px-3 py-2">
                        <input form="add-member" type="checkbox" aria-label={t('team.add.shifts')} checked={worksShifts} onChange={(e) => setWorksShifts(e.target.checked)} />
                      </td>
                      <td className="px-3 py-2" />
                      <td className="px-3 py-2">
                        <div className="flex gap-2">
                          <button type="submit" form="add-member" className={btnIcon} aria-label={t('team.add.save')} disabled={busy}>
                            <Check className="size-4" aria-hidden="true" />
                          </button>
                          <button type="button" className={btnIcon} aria-label={t('team.add.cancel')} disabled={busy} onClick={resetAdd}>
                            <X className="size-4" aria-hidden="true" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ) : (
                    <tr>
                      <td colSpan={7} className="px-3 py-2">
                        <button type="button" className={btnIcon} aria-label={t('team.add.submit')} disabled={busy} onClick={() => setAdding(true)}>
                          <UserRoundPlus className="size-4" aria-hidden="true" />
                        </button>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}

          <form id="add-member" onSubmit={addMember} />
          {addError && (
            <p role="alert" className="text-red-700">
              {addError}
            </p>
          )}
        </>
      )}
    </main>
  )
}
