import { t } from './i18n/messages'

// Date-only values are built from parts, so the day never shifts with the timezone.
export const parseDay = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y, m - 1, d)
}
// Moves an ISO day by whole calendar days (DST-safe, unlike adding 86400000 ms).
export const shiftDay = (iso: string, n: number) => {
  const d = parseDay(iso)
  d.setDate(d.getDate() + n)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
export const dateText = (iso: string) => parseDay(iso).toLocaleDateString('pt-PT')
export const rangeText = (p: { startDate: string; endDate: string }) => `${dateText(p.startDate)} – ${dateText(p.endDate)}`
export const dayLabel = (iso: string) => `${parseDay(iso).toLocaleDateString('pt-PT', { weekday: 'short' })} ${iso.slice(8)}/${iso.slice(5, 7)}`
export const deadlineText = (iso: string) =>
  new Date(iso).toLocaleString('pt-PT', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
// A deadline as a member reads it: when, and how many days away. soon: under 48 h, so the screen flags it.
export const deadlineHint = (iso: string, now = new Date()) => {
  const at = new Date(iso)
  const startOf = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  const days = Math.round((startOf(at) - startOf(now)) / 86400000)
  const when = days < 0 ? '' : ` (${days === 0 ? t('deadline.today') : days === 1 ? t('deadline.tomorrow') : t('deadline.inDays').replace('{n}', String(days))})`
  const text = at.toLocaleString('pt-PT', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) + when
  const ms = at.getTime() - now.getTime()
  return { text, soon: ms >= 0 && ms < 48 * 3600 * 1000 }
}
// datetime-local takes local wall-clock time without a zone.
export const toLocalInput = (iso: string) => {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
