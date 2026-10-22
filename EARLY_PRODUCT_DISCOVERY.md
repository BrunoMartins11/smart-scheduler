# Smart Scheduler: Early Product Discovery

_Date: 2026-10-17 · Status: discovery / pre-build_

The goal is a general-purpose app for scheduling teams. A hospital ER rota (day shift 08:00–20:00, night shift 20:00–08:00) is the example used here, but the product is not limited to it. Managers set up the shifts, members say which shifts they want, can't do, or prefer, and an engine builds a schedule that is complete (or as complete as possible) and fair, and that respects rest and compensatory rest rules.

---

## 1. Is it worth building?

**Yes, the problem is real. It just isn't a gap in the market.** What's missing is a mid-market product that is good at two things together: letting people say what they want, and producing a fair schedule automatically.

### Evidence

- **People want it.** In one survey, 49% of nurses said flexible hours would keep them in the profession longer, but only 34% have self-scheduling. In Trusted Health's 2024 report, 64% of nurses ranked self-scheduling as their top flexibility option.
- **The money is real, but the size is unclear.** Estimates for healthcare scheduling software range from about $0.5B to $14B depending on how the market is defined, with growth around 8–20% a year. Use these numbers as a direction only.
- **Fairness is the core of it.** A study of nurses found they judge fairness in two ways. For shared resources like weekends off, they want *equal* shares. For specific requests like "I need this day off," they want *individual need* respected. Unsocial hours were the main thing that lowered perceived fairness.

### Competitive landscape

| Segment | Examples | Weakness |
|---|---|---|
| Generic shift apps | Deputy, When I Work | Weak auto-scheduling. Deputy's own staff admitted its auto-fill doesn't guarantee that people are scheduled only when they're available. |
| Healthcare enterprise | QGenda, Lightning Bolt, UKG | Expensive, US-centric, long sales cycles |
| Simple rota tools | Amion, spreadsheets | No optimization at all |

### Positioning

Preference-first scheduling for the team, where fairness is visible to everyone, rest rules are built in, and the setup works for any industry.

Besides hospitals, the same problem exists in fire and police services, security, call centers, hotels, factories, labs, and software on-call rotations.

### Risks

- Most of the evidence is surveys and vendor blogs, not controlled studies.
- Hospital procurement is slow. Selling directly to team managers may be the faster way in.
- The real competitor is "a manager plus Excel plus WhatsApp."

---

## 2. Core concepts

```
Company ─┬─ Rule profile (labor rules: rest, max hours, nights)
         └─ Team ─┬─ Manager(s)
                  ├─ Members (contract hours/FTE, skills/roles)
                  ├─ Shift templates (Day 08–20, Night 20–08)
                  └─ Schedule period (e.g. Nov 2026)
                       ├─ Coverage needs per slot (min/max, required skills)
                       ├─ Member inputs: Can't / Prefer / Want / Neutral
                       └─ Assignments (draft → published → actual)
```

### Traps to design for early

- **Shifts that cross midnight.** A night shift from 20:00 to 08:00 starts on one day and ends on the next.
- **Daylight-saving changes.** On those nights a 12-hour shift actually lasts 11 or 13 hours, which affects hours and rest calculations.
- **People in more than one team.** Their hours and rest have to be checked across all their teams, not one at a time.

---

## 3. MUST have (MVP)

1. **Organizations and permissions.** Companies, teams, managers and members, with each company's data kept strictly separate.
2. **Shift templates.** Start and end times, duration, and a type (day, night, weekend, holiday) that the fairness rules use.
3. **Coverage requirements.** For each slot: minimum and maximum headcount and required skills (for example, at least 1 senior and 3 nurses).
4. **Collecting preferences:**
   - "Can't do" is a **hard constraint**. If it's ever ignored, people stop trusting the tool, which is exactly the Deputy complaint above.
   - "Prefer" and "want" are weighted soft preferences.
   - **A limit on how many "can't do" days each person can mark per period.** Without one, everyone blocks Christmas and the schedule can't be solved.
   - A deadline for submitting preferences.
5. **A rule engine for rest and hours.** These should be settings per company, not hard-coded, so the product isn't tied to the ER case:
   - Minimum rest between shifts (EU default: 11 hours)
   - Weekly rest
   - Maximum hours per week or averaged over a period
   - Maximum consecutive shifts or nights
   - Mandatory time off after a run of nights
6. **Compensatory rest, handled two ways:**
   - **Up front:** rest rules are hard constraints when the schedule is generated.
   - **Owed rest:** when someone is called in or works overtime and misses rest, the system records it and schedules the owed rest, immediately where the law requires it. Under the EU Jaeger ruling, compensatory rest must follow immediately.
7. **The balancing engine.** It works through goals in strict priority order:
   1. Never break hard constraints.
   2. Fill as many slots as possible.
   3. Spread hours, nights, weekends and holidays fairly, in proportion to each person's contract hours.
   4. Grant as many preferences as possible.

   Recommended technology: OR-Tools CP-SAT, which handles this kind of problem well. Academic work reports month-long nurse rosters solved in seconds to minutes.
8. **A fairness record across periods.** Track nights, weekends and holidays worked, and how many preferences were granted, *across periods*. If someone gets few preferences this month, they get priority next month. Most tools only balance within a single month.
9. **Showing gaps instead of hiding them.** If a slot can't be filled, mark it clearly and explain why ("only 2 eligible; Ana blocked, João at max hours"). Suggest what would fix it ("relaxing João's preference fills it").
10. **Manager review.** The engine produces a draft. The manager edits it, and every edit is re-checked against the rules. Then the manager publishes. Nothing is published automatically.
11. **Transparency for members.** Each person sees why they got their shifts, how many of their preferences were granted, and how their share compares to the team average.
12. **Audit log.** Who changed what and when. You need this to prove compliance with labor rules.

---

## 4. SHOULD have (version 2)

- **Shift swaps.** Members request swaps, the system checks the rules, and the manager approves (or it's auto-approved if no rule is broken).
- **Open shifts and volunteers.** Unfilled slots are offered to eligible members first.
- **Notifications and calendar sync** (iCal and Google), plus a mobile-first interface for members.
- **Absence and leave.** Vacation requests are blocked in before the engine runs.
- **Recurring patterns** (for example 2 days, 2 nights, 4 off) as a starting point for the engine.
- **Exports** for payroll and timesheets, with planned versus actual hours.
- **Skill mix within a shift.** For example, never two juniors alone on a night shift.

---

## 5. COULD have (later)

- **Points-based bidding.** Each person gets N points per period to spend on preferences. This gives people a fair way to signal how much something matters to them.
- **Demand forecasting** from past data, such as ER volume by weekday.
- **Fatigue scoring** based on the shift pattern, not just hours.
- **Sharing staff across teams** (float pools).
- **Natural-language requests** ("I'd rather not do Fridays in December").
- **Integrations** with HR and payroll systems, SSO, and a public API.

---

## 6. What makes this better than existing tools

1. Hard "can't do" constraints the engine never breaks.
2. Fairness tracked across periods and shown to everyone.
3. Gaps explained, with a suggested fix for each.
4. Rest and compensatory rest built into the engine itself.

---

## 7. Open decisions before building

- **First vertical.** Suggested: healthcare teams in the EU or Portugal, where the rules are strict and the need is high. Keep the domain model generic.
- **Who pays.** Individual team managers (self-serve, about €X per member per month) or the whole company (sales-led).
- **Health data.** Absence *reasons* can count as health data under GDPR. Store availability only, not reasons.

---

## Sources

- [Healthcare IT News: nurses and scheduling tech](https://www.healthcareitnews.com/news/nurses-are-fleeing-workforce-can-scheduling-tech-help)
- [Trusted Health 2024 nurse career report](https://works.trustedhealth.com/research/flexibility-is-key-findings-and-recommendations-from-trusted-healths-2024-front-line-nurse-career-report)
- [Medely: schedule flexibility](https://medely.com/blog/?p=5224)
- [HTF: NA medical staff scheduling market](https://www.htfmarketintelligence.com/report/north-america-medical-staff-scheduling-shift-management-software-market)
- [Guideflow: nurse scheduling software](https://www.guideflow.com/blog/nurse-scheduling-software)
- [Report Prime: physician scheduling](https://www.reportprime.com/physician-scheduling-software-r13822)
- [BMA: European Working Time Directive](https://www.bma.org.uk/pay-and-contracts/working-hours/european-working-time-directive-ewtd/doctors-and-the-european-working-time-directive)
- [RCS: Working Time Directive background](https://rcseng.ac.uk/-/media/Files/RCS/Standards-and-research/Standards-and-policy/Background-to-Working-Time-Directive.pdf)
- [Oireachtas: compensatory rest](https://www.oireachtas.ie/en/debates/question/2006-11-15/103/)
- [PerfectServe: QGenda competitors](https://www.perfectserve.com/compare/best-qgenda-competitors/)
- [Durate: QGenda alternatives](https://www.durate.com/blog/qgenda-alternatives)
- [Trustpilot: Deputy reviews](https://ca.trustpilot.com/review/deputy.com)
- [Siegen University: fairness in shift planning](https://dspace.ub.uni-siegen.de/handle/ubsi/2557)
- [Nurse fairness survey](https://pesquisa.bvsalud.org/gim/resource/en/wpr-899641)
- [PetalMD: equity in physician scheduling](https://www.petal-health.com/en/blog/automated-scheduling/solving-the-equity-challenge-in-physician-scheduling/)
- [CP-SAT fairness rostering study](https://jurnal.uinsu.ac.id/index.php/zero/article/download/29388/12962)
- [NYCU: two-phase CP nurse rostering](https://ir.lib.nycu.edu.tw/handle/11536/107984)
