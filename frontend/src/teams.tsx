import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { api } from './api'
import { t } from './i18n/messages'
import { input } from './ui'

export type Team = { id: string; name: string }

// Selected team comes from ?team=; unknown or missing falls back to the first team.
// oxlint-disable-next-line react/only-export-components -- the hook lives beside the picker it feeds
export function useTeams() {
  const [teams, setTeams] = useState<Team[] | null>(null)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [params, setParams] = useSearchParams()
  const teamId = teams?.find((team) => team.id === params.get('team'))?.id ?? teams?.[0]?.id ?? ''

  useEffect(() => {
    let ignore = false
    api<Team[]>('/api/teams')
      .then((list) => !ignore && setTeams(list))
      .catch((err) => !ignore && setLoadError(err))
    return () => {
      ignore = true
    }
  }, [])

  const selectTeam = (id: string) => setParams({ team: id })
  return { teams, setTeams, teamId, selectTeam, loadError }
}

export function TeamPicker({ teams, teamId, onSelect }: { teams: Team[] | null; teamId: string; onSelect: (id: string) => void }) {
  if (!teams?.length) return null
  return (
    <div className="flex items-center gap-2">
      <label htmlFor="team-select" className="text-sm font-bold text-slate-600">
        {t('team.pick')}
      </label>
      <select id="team-select" className={input} value={teamId} onChange={(e) => onSelect(e.target.value)}>
        {teams.map((team) => (
          <option key={team.id} value={team.id}>
            {team.name}
          </option>
        ))}
      </select>
    </div>
  )
}
