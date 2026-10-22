# Smart Scheduler: UI Mocks, Tailwind Handoff

_Date: 2026-10-17 · Mocks: https://claude.ai/artifact/84XeQxyAobznCFrLN3Y4un · Related: [MVP_BREAKDOWN.md](MVP_BREAKDOWN.md)_

The mocks use **only Tailwind's default palette and spacing scale**, so every style in them maps directly to a utility class. The only custom value is the font.

## Screens

| Screen | Who | Epics | Notes |
|---|---|---|---|
| Schedule review | Manager, desktop | E7, E9 | Google Calendar-style: sidebar (mini month, doctors as toggleable calendars with fairness stats), Week time grid and 2-week view, shift popover, Run engine, Publish |
| Unfilled shift | Manager, side drawer | E8, E9, D2 | Suggested fix, exclusion reasons grouped by rule, "Assign anyway" with a mandatory comment |
| Availability | Doctor, mobile | E6 | Pick a mark (Available or If needed), then tap shifts. Unmarked shifts mean not available. Shows the deadline. |
| My shifts | Doctor, mobile | E10 | Next shifts, plus a "Is this fair to me?" comparison with the team |

Copy in the mocks is English. The pilot UI ships in **PT-PT** through i18n.

## Tokens (Tailwind v4)

```css
@import "tailwindcss";

@theme {
  --font-sans: "Atkinson Hyperlegible", ui-sans-serif, system-ui, sans-serif;
}
```

Load the font with `@fontsource/atkinson-hyperlegible` (400, 700) or the Google Fonts link. Atkinson Hyperlegible was designed for legibility, which suits tired eyes on a night shift.

| Role | Classes |
|---|---|
| Page / card | `bg-slate-50` / `bg-white border border-slate-200 rounded-lg` |
| Text / muted text | `text-slate-900` / `text-slate-600` |
| Primary (actions, selection) | `cyan-700` (white text passes 4.5:1); hover `cyan-800`; tint `bg-cyan-50` |
| Day shift | `bg-amber-100 text-amber-900` |
| Night shift | `bg-indigo-100 text-indigo-900` |
| Unfilled | `bg-red-100 text-red-700` |
| Override | `ring-2 ring-inset ring-orange-600`; action `bg-orange-700` |
| Published | `bg-green-100 text-green-800` |

Day and night chips also differ by their letter (D/N), so meaning never depends on color alone.

On the manager calendar, events are colored **per doctor** (like Google calendars), and day vs night is shown by time and a sun/moon icon. Doctor colors (white text passes 4.5:1): `blue-700`, `emerald-700`, `violet-600`, `amber-700`, `pink-700`, `teal-700`, `stone-700`, `lime-700` (no two blues/purples side by side; names are always printed, so color is never the only cue). A rule break is a white gap ring plus `orange-600` ring (`ring-2 ring-orange-600 ring-offset-2`). An unfilled slot is `border-2 border-dashed border-red-600 bg-red-50 text-red-700`.

## Component recipes

```tsx
// Buttons
const btnPrimary   = "h-10 px-4 rounded-md bg-cyan-700 text-white font-bold hover:bg-cyan-800 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-700";
const btnSecondary = "h-10 px-4 rounded-md border border-slate-300 bg-white font-bold hover:bg-slate-50 transition-colors";

// Status pill
const pill = "px-2.5 py-0.5 rounded-full text-xs font-bold"; // + bg-amber-100 text-amber-800 (Closed) | bg-green-100 text-green-800 (Published)

// Shift chip (grid cell)
const chip = { D: "bg-amber-100 text-amber-900", N: "bg-indigo-100 text-indigo-900" };
<button className={`w-9 h-8 rounded-md text-xs font-bold ${chip[kind]} ${override ? "ring-2 ring-inset ring-orange-600" : ""}`}>{kind}</button>

// Coverage cell
gap ? <a className="px-1.5 h-5 rounded bg-red-100 text-red-700 text-xs font-bold">1/2</a>
    : <span className="text-xs text-slate-600 tabular-nums">2/2</span>

// Week time grid: 56px hour gutter + 7 day columns, 30px per hour (720px tall).
// Events are absolutely positioned: top = start*30, height = (end-start)*30.
// Doctors on the same shift split the column into lanes (left = i/n, width = 1/n).
// A night is drawn twice: 20–24 on its day, 00–08 on the next.
<div className="overflow-x-auto"><div className="min-w-[840px]">
  <div className="grid grid-cols-[56px_repeat(7,minmax(0,1fr))] h-[720px]">
    <div className="relative">{/* hour labels */}</div>
    <div className="relative border-l border-slate-200 bg-[repeating-linear-gradient(to_bottom,theme(colors.slate.200)_0_1px,transparent_1px_30px)]">
      <button className="absolute rounded-md px-1.5 py-1 text-xs text-white text-left" style={{ top, height, left, width, background: doctorColor }}>…</button>
    </div>
  </div>
</div></div>

// 2-week view: grid-cols-7, each day cell min-h-44 with h-6 chips: icon + "08"/"20" + name (truncate)

// Availability states (shift buttons on mobile, h-11 = 44px touch target)
const avail = {
  AVAILABLE: "border-emerald-600 bg-emerald-50 text-emerald-800",  // + Check icon
  IF_NEEDED: "border-amber-500 bg-amber-50 text-amber-800",        // + Minus icon
  NONE:      "border-slate-200 bg-white text-slate-600",           // not available
};
<button className={`w-full h-11 rounded-md border text-xs font-bold ${avail[state]}`}>…</button>

// Fairness bar: you vs team average
<div className="relative h-1.5 rounded-full bg-slate-200">
  <div className="absolute inset-y-0 left-0 rounded-full bg-cyan-700" style={{ width: `${mine}%` }} />
  <div className="absolute -top-0.5 h-3 w-0.5 bg-slate-900" style={{ left: `${team}%` }} />
</div>
```

Icons: **lucide-react** (`ChevronLeft`, `ChevronRight`, `X`, `Check`, `Minus`, `AlertCircle`). The mocks draw the same shapes.

## Suggested React components

```
AppShell            header + nav (Schedule, Team, Shifts, Rules)
CalendarSidebar     Run engine button, MiniMonth (period dates jump to their week), DoctorList
  DoctorList        checkbox per doctor (accent = doctor color) toggles visibility; hours, delta, nights, wknd, if-needed used
CalendarToolbar     prev/next week, range title, status pill, Week | 2 weeks switch, Publish
PeriodSummary       filled · unfilled · overrides · to confirm
WeekView            day headers with per-day coverage, hour gutter, ShiftEvent / UnfilledSlot blocks
TwoWeekView         day cells with ShiftChip / UnfilledSlot, "over max" badge
ShiftPopover        doctor, date, time, rule break; Change to day/night, Unassign
ShiftDrawer         slot header, assigned list, SuggestedFix, ReasonGroup[]
  OverrideForm      required comment → assignment.override_comment
AvailabilityBrush   2 marks: Available, If needed (aria-pressed); tapping a marked shift again clears it
AvailabilityDayRow  date + Day/Night AvailabilityButton
MyShiftsList        next 4 shifts + "See all"
FairnessCard        one-line verdict + 3 numbers (hours, nights, if-needed used)
```

The mock data stays consistent with the data model: the gap reasons in the drawer match the rest rules in `team` (12 h between shifts, 48 h after a run of nights) and the assignments in the grid.

## Accessibility notes

- Real `<button>`, `<a>`, `<label>` and `<textarea>` elements throughout. Icon-only buttons have an `aria-label`.
- Touch targets are 44 px on mobile. On the desktop calendar, each event is a `<button>` whose `aria-label` names the doctor, date, shift and any rule break. The popover closes on Esc; in production also move focus into it on open, trap it there, and return it to the event on close. Every control gets `focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-700`. Rule breaks show a warning icon as well as the orange ring. The smallest text on the calendar is 12 px.
- The availability brush uses `aria-pressed`. Each shift button should announce its state, for example "Day, Available" or "Night, Not available".
