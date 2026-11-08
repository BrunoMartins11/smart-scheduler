import { CalendarDays, Check, Clock } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { api } from './api'
import { AvailabilityCalendar } from './AvailabilityCalendar'
import { deadlineHint, rangeText } from './dates'
import { t, type MessageKey } from './i18n/messages'
import { EmptyState, PageLayout } from './PageLayout'
import { alertText, card, cardRaised, focusRing, muted, periodStatusPill } from './ui'

type Status = 'DRAFT' | 'OPEN' | 'CLOSED' | 'PUBLISHED'
type Entry = { teamId: string; teamName: string; periodId: string; startDate: string; endDate: string; status: Status; inputDeadline: string | null; submitted: boolean }

const statusLabel: Record<Status, MessageKey> = {
  DRAFT: 'periods.status.DRAFT',
  OPEN: 'periods.status.OPEN',
  CLOSED: 'periods.status.CLOSED',
  PUBLISHED: 'periods.status.PUBLISHED',
}

// The period from ?period=, else the first open one, else the first.
const pick = (list: Entry[], requested: string | null) => list.find((e) => e.periodId === requested) ?? list.find((e) => e.status === 'OPEN') ?? list[0]

// The picker only appears with more than one period.
export function AvailabilityPage({ userId }: { userId: string }) {
  const [entries, setEntries] = useState<Entry[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [params, setParams] = useSearchParams()

  useEffect(() => {
    let ignore = false
    api<Entry[]>('/api/me/availability-periods')
      .then((list) => !ignore && setEntries(list))
      .catch(() => !ignore && setError(t('availability.error')))
    return () => {
      ignore = true
    }
  }, [])

  // The member can only edit an open period, so the deadline is shown for open periods only.
  const current = entries?.length ? pick(entries, params.get('period')) : null
  const hint = current?.status === 'OPEN' && current.inputDeadline ? deadlineHint(current.inputDeadline) : null

  let body
  if (error) {
    body = <p role="alert" className={alertText}>{error}</p>
  } else if (!entries) {
    body = <p className={muted}>{t('team.loading')}</p>
  } else if (entries.length === 0) {
    body = (
      <div className={cardRaised}>
        <EmptyState icon={CalendarDays} text={t('availability.empty')} />
      </div>
    )
  } else {
    const chosen = pick(entries, params.get('period'))
    body = (
      <>
        {entries.length > 1 && (
          <div role="group" aria-label={t('availability.pick')} className="mx-auto flex max-w-md flex-col gap-2">
            {entries.map((e) => {
              const on = e.periodId === chosen.periodId
              return (
                <button
                  key={e.periodId}
                  type="button"
                  aria-pressed={on}
                  onClick={() => setParams({ period: e.periodId })}
                  className={`${card} flex flex-wrap items-center justify-between gap-2 p-3 text-left ${focusRing} ${on ? 'border-cyan-700 bg-cyan-50' : ''}`}
                >
                  <span className="flex items-center gap-2">
                    {on && <Check className="size-4 shrink-0 text-cyan-800" aria-hidden="true" />}
                    <span>
                      <span className="block text-base font-bold">{e.teamName}</span>
                      <span className={`text-sm ${muted}`}>{rangeText(e)}</span>
                    </span>
                  </span>
                  <span className={periodStatusPill[e.status]}>{t(statusLabel[e.status])}</span>
                </button>
              )
            })}
          </div>
        )}
        <AvailabilityCalendar key={chosen.periodId} teamId={chosen.teamId} periodId={chosen.periodId} userId={userId} />
      </>
    )
  }

  return (
    <PageLayout
      title={current ? rangeText(current) : t('availability.heading')}
      description={t('availability.description')}
      controls={
        hint && (
          <p className={`flex items-center gap-2 ${hint.soon ? 'font-bold text-amber-300' : 'text-indigo-200'}`}>
            {hint.soon && <Clock className="size-4 shrink-0" aria-hidden="true" />}
            {t('availability.deadline').replace('{deadline}', hint.text)}
          </p>
        )
      }
    >
      <div className="space-y-6">{body}</div>
    </PageLayout>
  )
}
