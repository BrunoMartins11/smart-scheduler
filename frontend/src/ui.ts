// Class recipes from UI_MOCKS.md. Plain strings, no component library.
export const focusRing = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-700'
const control = 'transition-colors disabled:opacity-50 disabled:cursor-not-allowed ' + focusRing

export const btnPrimary = `h-10 px-4 inline-flex items-center justify-center gap-2 rounded-md bg-cyan-700 text-white font-bold hover:bg-cyan-800 ${control}`
export const btnSecondary = `h-10 px-4 inline-flex items-center justify-center gap-2 rounded-md border border-slate-300 bg-white text-slate-900 font-bold hover:bg-slate-50 ${control}`
const iconBase = `size-10 inline-flex items-center justify-center rounded-md border border-slate-300 bg-white text-slate-900 ${control}`
export const btnIcon = `${iconBase} hover:bg-slate-50`
export const btnIconDanger = `${iconBase} hover:bg-red-50 hover:text-red-700 hover:border-red-200`
export const btnIconPrimary = `size-10 inline-flex items-center justify-center rounded-md bg-cyan-700 text-white hover:bg-cyan-800 ${control}`
export const btnRow = `h-10 px-3 w-full inline-flex items-center justify-center gap-2 font-bold text-cyan-700 hover:bg-cyan-50 ${control}`

export const input = `h-10 px-3 rounded-md border border-slate-300 bg-white text-slate-900 ${control}`
// Input group: the input's right edge joins a suffix (see `suffix`).
export const inputGrouped = `h-10 px-3 rounded-l-md border border-slate-300 bg-white text-slate-900 ${control}`
export const suffix = 'inline-flex items-center px-3 rounded-r-md border border-l-0 border-slate-300 bg-slate-50 text-sm text-slate-600'
export const checkbox = `size-5 accent-cyan-700 ${focusRing}`

export const card = 'bg-white border border-slate-200 rounded-lg'
export const label = 'font-bold text-slate-900'
export const muted = 'text-slate-600'
export const alertText = 'text-red-700'

const pillBase = 'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-bold'
// Shift type pills also carry an icon, so the type never relies on colour alone.
export const pillAmber = `${pillBase} gap-1 bg-amber-100 text-amber-900`
export const pillIndigo = `${pillBase} gap-1 bg-indigo-100 text-indigo-900`
export const pillGreen = `${pillBase} bg-green-100 text-green-800`
export const pillSlate = `${pillBase} bg-slate-100 text-slate-700`
export const pillCyan = `${pillBase} bg-cyan-50 text-cyan-800`
