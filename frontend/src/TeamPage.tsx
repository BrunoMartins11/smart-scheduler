import { UserRoundCheck, UserRoundX } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api } from './api'
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

export function TeamPage() {
  const [teams, setTeams] = useState<Team[] | null>(null)
  const [teamId, setTeamId] = useState('')
  const [members, setMembers] = useState<Member[] | null>(null)
  const [error, setError] = useState(false)
  const [reload, setReload] = useState(0)
  const [busy, setBusy] = useState(false)

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

          {members && members.length > 0 && (
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
                      <td className="px-3 py-2">{roleLabel(m.role)}</td>
                      <td className="px-3 py-2 tabular-nums">{m.ftePercent}</td>
                      <td className="px-3 py-2">{m.worksShifts ? t('team.yes') : t('team.no')}</td>
                      <td className="px-3 py-2">{m.active ? t('team.active') : t('team.inactive')}</td>
                      <td className="px-3 py-2">
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
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </main>
  )
}
