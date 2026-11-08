// Class recipes from UI_MOCKS.md. Plain strings, no component library.
export const focusRing = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-700'
const control = 'transition-colors disabled:opacity-50 disabled:cursor-not-allowed ' + focusRing

export const btnPrimary = `h-10 px-4 inline-flex items-center justify-center gap-2 rounded-md bg-cyan-700 text-white font-bold hover:bg-cyan-800 ${control}`
export const btnSecondary = `h-10 px-4 inline-flex items-center justify-center gap-2 rounded-md border border-slate-300 bg-white text-slate-900 font-bold hover:bg-slate-50 ${control}`
const iconBase = `size-10 inline-flex items-center justify-center rounded-md border border-slate-300 bg-white text-slate-900 ${control}`
// Page action on the indigo band: white like btnSecondary, 44 px, focus ring for dark.
export const btnSecondaryOnDark = 'h-11 px-4 inline-flex items-center justify-center gap-2 rounded-md border border-slate-300 bg-white text-slate-900 font-bold hover:bg-slate-50 transition-colors disabled:opacity-50 disabled:cursor-not-allowed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-300'
export const btnIcon = `${iconBase} hover:bg-slate-50`
export const btnIconDanger = `${iconBase} hover:bg-red-50 hover:text-red-700 hover:border-red-200`
export const btnIconPrimary = `size-10 inline-flex items-center justify-center rounded-md bg-cyan-700 text-white hover:bg-cyan-800 ${control}`
export const btnRow = `h-10 px-3 w-full inline-flex items-center justify-center gap-2 font-bold text-cyan-700 hover:bg-cyan-50 ${control}`

export const input = `h-10 px-3 rounded-md border border-slate-300 bg-white text-slate-900 ${control}`
// Input group: the input's right edge joins a suffix (see `suffix`).
export const inputGrouped = `h-10 px-3 rounded-l-md border border-slate-300 bg-white text-slate-900 ${control}`
export const suffix = 'inline-flex items-center px-3 rounded-r-md border border-l-0 border-slate-300 bg-slate-50 text-sm text-slate-600'
export const checkbox = `size-5 accent-cyan-700 ${focusRing}`
// Grid cell at rest (a button); the edit state and the changed-from-template state are separate recipes.
export const btnCell = `h-10 px-3 inline-flex items-center justify-center gap-1.5 rounded-md border border-slate-300 bg-white text-slate-900 tabular-nums hover:bg-slate-50 ${control}`
export const cellText = 'h-10 px-3 inline-flex items-center gap-1.5 tabular-nums'
export const cellChanged = 'ring-1 ring-inset ring-amber-600 bg-amber-50 text-amber-900'
// Availability (mobile): 44 px targets and the three states from UI_MOCKS.md.
export const btnWide = `h-11 px-4 inline-flex items-center justify-center gap-2 rounded-md border border-slate-300 bg-white text-slate-900 font-bold hover:bg-slate-50 ${control}`
// Shift chips stack name, times and tag; slate-500 border is the 3:1 boundary for unmarked state.
export const availBase = `min-h-11 w-full px-2 py-1.5 inline-flex flex-col items-center justify-center gap-0.5 rounded-md border text-sm font-bold ${control}`
export const brushBase = `min-h-11 w-full px-2 py-1.5 inline-flex items-center justify-center rounded-md border text-base font-bold ${control}`
export const availNone = 'border-slate-500 bg-white text-slate-600 hover:bg-slate-50'
export const availYes = 'border-emerald-600 bg-emerald-50 text-emerald-800'
export const availIf = 'border-amber-500 bg-amber-50 text-amber-800'

// Focus on the indigo band (header, band, landing hero): cyan-700 would be too faint there.
export const focusRingDark = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-300'
export const inputOnDark = `h-11 px-3 rounded-md border border-slate-300 bg-white text-slate-900 transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${focusRingDark}`

export const card = 'bg-white border border-slate-200 rounded-xl'
// The first card of a page overlaps the indigo band; it is the only card with a shadow.
export const cardRaised = 'bg-white border border-slate-200 rounded-xl shadow-[0_10px_30px_rgba(30,27,75,0.12)]'
export const label = 'font-bold text-slate-900'
export const muted = 'text-slate-600'
export const alertText = 'text-red-700'
export const th = 'px-4 py-3 text-sm font-bold text-slate-600'
// Destructive actions: text, not icon-only, so the consequence reads at a glance.
export const btnDangerText = `h-10 px-3 inline-flex items-center justify-center gap-2 rounded-md font-bold text-red-700 hover:bg-red-50 ${control}`
export const btnDangerOutline = `h-10 px-4 inline-flex items-center justify-center gap-2 rounded-md border border-red-300 bg-white font-bold text-red-700 hover:bg-red-50 ${control}`

const pillBase = 'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-bold'
// Shift type pills also carry an icon, so the type never relies on colour alone.
export const pillAmber = `${pillBase} gap-1 bg-amber-100 text-amber-900`
export const pillIndigo = `${pillBase} gap-1 bg-indigo-100 text-indigo-900`
export const pillGreen = `${pillBase} bg-green-100 text-green-800`
export const pillSlate = `${pillBase} bg-slate-100 text-slate-700`
export const pillStone = `${pillBase} gap-1 bg-stone-100 text-stone-800`
export const pillViolet = `${pillBase} bg-violet-100 text-violet-800`
// One meaning per hue: slate draft, violet open, amber closed, green published.
export const periodStatusPill = { DRAFT: pillSlate, OPEN: pillViolet, CLOSED: pillAmber, PUBLISHED: pillGreen } as const
