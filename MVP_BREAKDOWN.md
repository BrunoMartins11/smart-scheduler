# Smart Scheduler: MVP Product Breakdown

_Date: 2026-10-17 · Status: scope agreed, pre-development · Related: [EARLY_PRODUCT_DISCOVERY.md](EARLY_PRODUCT_DISCOVERY.md)_

---

## 0. Decisions log

| # | Decision | Outcome |
|---|---|---|
| D1 | Availability states | **Available** (can do the shift) · **If needed** (OK but not preferred: used only to complete the schedule, and confirmed with the member, D9) · **No mark** (not available, the default; hard) |
| D2 | Overriding a hard rule | **Allowed**, with a mandatory comment. It is recorded in the audit log and flagged in the schedule. |
| D3 | What the MVP balances for fairness | Total hours, nights, weekends, public holidays |
| D4 | Where owed compensatory rest goes | **Immediately** after the work period (EU Jaeger ruling). Making this configurable (next period) is deferred. |
| D5 | Pilot customer | **ER doctors team, Portugal** |
| D6 | Platform | Responsive **web app**: **React** frontend, **Kotlin + Ktor** backend |
| D7 | Authentication | **Auth0 by Okta** (EU region). Access by invite only, no self-signup. |
| D8 | Manager entering availability for a member | **Allowed** while the period is `OPEN` or `CLOSED`. Each mark records who entered it, and the change is in the audit log. |
| D9 | Using an "If needed" shift | The manager **confirms it with the member** before publishing. The schedule can't be published while any such shift is unconfirmed. |

What follows from D5 and D6:
- The UI is in **PT-PT** first, with internationalization (i18n) built in from the start so EN can follow.
- Time zone: **Europe/Lisbon** (it has daylight-saving changes).
- Holiday calendar: Portuguese national holidays, plus a municipal holiday set per team.
- Engine: **OR-Tools CP-SAT through its Java bindings**, running inside the Ktor backend JVM. No separate Python service is needed.

---

## 1. MVP goal

> **One real team schedules one real month, start to finish, without a spreadsheet.**

That means:
- The manager sets the team up once.
- Members mark the shifts they can do, or the manager enters them for members who don't use the app.
- The engine produces a draft that never breaks a hard rule.
- The manager adjusts and publishes it.
- Everyone can see the result and why they got it.

### Pilot success criteria

| Metric | Target |
|---|---|
| Hard-rule violations in the published schedule (excluding audited overrides) | **0** |
| Slots filled (when filling is feasible) | ≥ 98% |
| "If needed" shifts used, and how many members declined | Measured and reported (baseline) |
| Manager time to produce the monthly rota | ≥ 50% less than the current process |
| Members who rate the schedule as fair (survey) | ≥ 70% |
| Engine runtime (30 members × 31 days × 2 shifts) | < 60 seconds |

---

## 2. Personas and roles

| Role | What they do in the MVP |
|---|---|
| **Company admin** | Creates the company and teams, sets each team's rules, assigns managers |
| **Manager** | Sets up shifts and coverage, opens and closes periods, enters availability for members who don't use the app, generates, edits, confirms "If needed" shifts and publishes schedules |
| **Member** | Marks their availability, sees their schedule and fairness stats |

- A person can hold more than one role (for example a manager who also works shifts).
- In the MVP each member belongs to **one team**. The data model must still allow several teams later.

---

## 3. Pilot rule profile: Portuguese ER doctors

### Team-level defaults (pilot)

| Rule | Pilot default | Source / note |
|---|---|---|
| Maximum consecutive ER work | **12 hours** | ER rules for doctors; shifts of 24 hours are incompatible with EU law (CJEU, Commission v Greece) |
| Minimum rest between shifts | **12 hours** | Doctors' ER rules (stricter than the general 11 hours in the Labour Code) |
| Maximum average weekly hours | **48 hours** over a reference period | EU Working Time Directive; DL 103/2023 uses a 6-month reference period for _dedicação plena_ |
| Maximum consecutive nights | Configurable (pilot: ask the team) | Hospital policy |
| Rest after a run of nights | Configurable (pilot: ask the team) | Hospital policy |

> ⚠️ **To verify with the pilot hospital before go-live.** These figures come from union and secondary sources (SIM, SMZC). The consolidated legal texts weren't checked, and individual hospitals have internal ER rules. That's why every value is **configurable data, not code**.

---

## 4. The core flow: lifecycle of a schedule period

```
SETUP ──► DRAFT ──► OPEN ──► CLOSED ──► PUBLISHED
(once)    manager   members  manager runs   members see it;
          sets      submit   the engine,    edits re-validated
          dates,    inputs   edits
          coverage
```

Each state change is explicit, is done by the manager, and is recorded in the audit log.

Members change their inputs only while the period is `OPEN`. The manager can also enter them on a member's behalf while it is `OPEN` or `CLOSED` (E6, D8), so late answers given by phone still reach the engine.

---

## 5. Epics, in build order

### E1: Accounts, tenancy and roles
- A user logs in through Auth0 (D7). Access is by invite only: the first login is matched to the invite by verified email.
- An admin creates a company. All data belongs to one company, and no request can read another company's data.
- An admin invites managers, and a manager invites members by email.
- **Acceptance criteria:** permission tests cover each role and each resource. Trying to access another company's data returns 404.

### E2: Team and member setup
- A manager creates a team and adds members.
- Each member has a **contract load** (FTE %, or target hours per period).
- **Acceptance criteria:** a member at 50% FTE gets a fair share target half that of a member at 100%.

### E3: Team rules
- Each team has its own rules, as described in §3, all of them editable settings. A new team copies them from an existing team.
- **Acceptance criteria:** **one rule validator** is the single source of truth, used by the engine, by manual edits and by owed-rest detection. This is the most important design decision in the MVP.

### E4: Shift templates and coverage
- A manager defines templates: name, start, end (the end may fall on the next day), and type tags (day, night, weekend, holiday).
- Each template has default coverage: minimum and maximum headcount.
- A manager can override coverage for specific dates.
- **Acceptance criteria:**
  - A 20:00–08:00 night shift is stored as one shift spanning two dates.
  - A daylight-saving night is counted at its real length (11 or 13 hours).

### E5: Schedule period
- A manager creates a period (date range). The system generates slots from the templates and coverage.
- The manager opens it for input with a deadline, then closes it.
- **Acceptance criteria:** members can't change their inputs after the period closes (the manager still can, see E6). The manager can reopen it.

### E6: Member availability
- A member sees a calendar of the period's slots and marks the ones they can do as **Available** or **If needed** (D1). A shift with no mark means not available.
- Shortcuts: "available the whole day" and "available all nights".
- No reasons are collected (health data, GDPR).
- There is no limit on how few shifts a member marks. The manager sees each member's submission status and follows up.
- Emails go out when the period opens and 48 hours before the deadline.
- **Acceptance criteria:**
  - This works well on a mobile browser.
  - The engine never assigns a member to a shift they didn't mark.

#### Entered by the manager on a member's behalf (D8)
Some doctors won't use the app and will tell the manager by phone or message instead. Without their marks, the engine can't schedule them at all.
- The manager sees each member's submission status for the period: **not submitted**, **submitted by the member**, or **entered by the manager**.
- The manager opens any member of their team and marks shifts using the same calendar and shortcuts.
- Allowed while the period is `OPEN` or `CLOSED`, so late answers can be entered before the engine runs. Not allowed once the schedule is published.
- Each mark records who entered it. The member sees "entered by your manager" on those marks and can change them while the period is open. The latest change wins.
- Every change made on a member's behalf is recorded in the audit log (E13).
- **Acceptance criteria:**
  - A member can't set another member's availability. A manager can set it only for members of their own team.
  - The same period-state checks apply whether the member or the manager enters a mark. There is one availability service.
  - Clearing the mark on a shift the member is already assigned to flags that assignment as a hard-rule violation, like any other (E9). It is never silently ignored.

### E7: Scheduling engine
- **Inputs:** slots, coverage, members (load), availability, rules, fairness record (last N periods), owed rest, and any assignments the manager has fixed.
- **Priorities, in strict order:**
  1. Hard constraints: never break any. A member is only assigned to shifts they marked.
  2. Fill as many slots as possible.
  3. Use as few "If needed" shifts as possible. When some are needed, give them to people who had fewer last time.
  4. Fairness (D3): spread hours, nights, weekends and holidays in proportion to each person's load, including the carry-over.
- **Output:** assignments, unfilled slots, and per-member stats.
- **Acceptance criteria:**
  - Every run is deterministic: the same input gives the same output.
  - There is a timeout.
  - If the time limit is reached, the run returns the best solution found so far and reports it as a partial result.
- Runs as a background job. The UI checks the run's status.

### E8: Gap explanation
- For every unfilled slot, show the candidates who were excluded, grouped by reason: "not available", "rest rule", "max hours".
- Suggest one fix, for example: "ask X, who didn't mark this shift, or raise Y's max hours → fills the slot".
- **Acceptance criteria:** every unfilled slot has at least one reason. No gap is unexplained.

### E9: Manager review and publish
- A grid view (members × days) with the draft, unfilled slots highlighted, and fairness stats per member.
- Assign or move people manually. Every edit goes through the rule validator:
  - A soft-rule issue shows a warning.
  - A hard-rule violation is **allowed only as an override with a mandatory comment** (D2). It is recorded in the audit log and stays visibly flagged.
- The manager can re-run the engine while keeping their manual assignments fixed.
- **"If needed" shifts (D9):** any assignment on a shift the member marked "If needed", whether from the engine or a manual edit, is flagged **needs confirmation**.
  - The manager checks with the member (phone, in person) and marks it confirmed.
  - If the member declines, the manager records it in one step: the mark is cleared on the member's behalf (D8) and the shift is unassigned. The manager then reassigns it or re-runs the engine.
  - If the member later changes the mark to "Available", no confirmation is needed.
- **Acceptance criteria:** publishing is refused while any assignment needs confirmation. After publishing, an edit that uses an "If needed" shift stays flagged until it is confirmed.
- Publishing notifies members by email.
- After publishing, edits are still possible. They are re-validated, recorded in the audit log, and the affected members are notified.

### E10: Member schedule and transparency
- "My schedule" as a list and a calendar.
- "My stats":
  - shifts, hours, nights, weekends, holidays
  - "If needed" shifts used
  - how each compares with the team average adjusted for load
- A read-only view of the whole team schedule.
- **Acceptance criteria:** a member can answer "is this fair to me?" without asking the manager.

### E11: Fairness record across periods
- Per-member totals (nights, weekends, holidays, hours versus target, "If needed" shifts used) are **computed from past assignments and availability**, not stored.
- The engine uses the last 3 periods as carry-over.
- **Acceptance criteria:** someone who had more nights than their share last month gets fewer this month, all else being equal.

### E12: Compensatory rest (owed rest)
- After publishing, the manager records the **actual start and end** of a shift that ran differently (extended, overtime).
- The rule validator works out whether minimum rest was missed. If it was, the owed rest is a blocked interval **immediately** after the work (D4). It is computed, not stored.
- Shifts that overlap the owed rest are flagged for the manager to fix.
- Recording a call-in **outside** any shift is deferred.
- **Acceptance criteria:** owed rest is never silently ignored. It is either scheduled or flagged.

### E13: Audit log
- Append-only events:
  - rule changes
  - period state changes
  - engine runs (who ran it, the seed, a results summary)
  - manual edits
  - availability entered on a member's behalf
  - "If needed" shifts confirmed
  - **overrides with their comments**
  - publishing and post-publish edits
- Viewable by managers and admins.

---

## 6. Release plan

| Milestone | Contents | Why ship it on its own |
|---|---|---|
| **M1: Digital rota** | E1–E6, manual assignment with rule validation and overrides (part of E9), publishing, a minimal E13 | Already replaces Excel and WhatsApp. Proves the validator on real use and starts collecting real availability data. |
| **M2: Auto-scheduling** | E7, E8, the full E9 | The core value. Compare it with the manager's manual rota. |
| **M3: Trust and fairness** | E10, E11, the full E13 | Makes fairness visible and keeps it balanced over time. |
| **M4: Pilot-ready** | E12, notification polish, PT-PT copy review, onboarding, feedback survey | Run with the ER team for 2 periods |

Building M1 first is deliberate. The rule validator is needed both for manual edits and inside the engine, so building it first and testing it on real use makes the engine safer.

---

## 7. Out of the MVP (on purpose)

- Shift swaps
- Open-shift marketplace
- Leave management (members leave those days unmarked instead)
- Calendar sync
- Native mobile apps
- Payroll export
- Multi-team members
- Float pools
- Bidding points
- Forecasting
- SSO
- Integrations
- Non-email notifications
- Member notes to the manager
- Recording call-ins outside any shift
- Owed rest placed in the next period (only "immediately" in the MVP)
- Shared company-wide rule sets (rules are per team)

---

## 8. Deferred to further development (from the pilot research)

These came out of researching the Portuguese ER rules. They are **not** in the MVP; design the data model so they can be added later without a rewrite.

### F1: Contract regimes per member
In Portugal, ER limits depend on each doctor's contract regime, so the rule profile would gain a second level: regimes assigned per member that override or add to the team rules.

| Regime | Normal ER hours / week | Supplementary ER hours | Annual supplementary cap |
|---|---|---|---|
| Interno (resident) | 12 | up to +12 / week | 150 hours |
| Regime geral (general) | 12 | up to +12 / week | 150 hours |
| Dedicação plena (full dedication) | 18 (to be confirmed) | within the 48-hour average | 250 hours |

> ⚠️ From union and secondary sources (SIM, SMZC, FNAM). Verify against DL 103/2023, DL 45-A/2024 and the hospital's internal rules before building.

### F2: Normal vs supplementary hours in the engine
The engine fills each member's normal weekly quota first and spreads supplementary hours fairly on top of that.

### F3: Yearly supplementary-hours total
The fairness record keeps a running yearly total of supplementary hours per member, so the annual caps in F1 can be enforced.

### F4: Skill-based coverage
Coverage can require skills, for example "at least 1 _especialista_ per shift", so residents are never on shift without a specialist. Requires a skills/roles attribute on members.

---

## 9. Open questions for the pilot team

1. Does the hospital have internal ER rules (_regulamento interno do serviço de urgência_), for example a maximum number of nights in a row or time off after nights?
2. Coverage per shift: how many doctors are needed for day versus night and weekday versus weekend?
3. How much notice is needed: how far ahead must the rota be published, and when should the availability window open and close?
4. Which municipal holiday applies?
5. How are on-call (_prevenção_) or standby periods handled today? Are they in scope for the pilot?
6. _(For F1, later)_ Which contract regime is each doctor on, and which weekly and annual ER limits does the hospital actually apply?

---

## Sources

- [SIM: Horários médicos, trabalho no serviço de urgência, descansos e folgas](https://simedicos.pt/pt/noticias/1779/horarios-medicos-na-carreira-medica-hospitalar-trabalho-prestado-no-servico-de-urgencia-descansos-e-folgas)
- [SIM: Horários médicos na carreira médica hospitalar](https://simedicos.pt/pt/noticias/778/horarios-medicos-na-carreira-medica-hospitalar)
- [SRNOM: Regras do Internato Médico no Serviço de Urgência](https://nortemedico.pt/srnom/comunicados/comunicados-cne/51)
- [SMZC: Guia do Internato Médico (2026)](https://www.smzc.pt/internato-medico/guia-do-internato-medico)
- [SMZC: Direitos dos trabalhadores médicos](https://www.smzc.pt/12-informacao-geral/758-direitos-dos-trabalhadores-medicos-em-estado-de-emergencia-garantir-a-seguranca-dos-doentes-e-prevenir-a-exaustao)
- [DL n.º 103/2023 (PGDL)](https://www.pgdlisboa.pt/leis/lei_mostra_articulado.php?artigo_id=3718A0018&nid=3718&tabela=leis&pagina=1&ficha=1&so_miolo=S)
- [FNAM: proposta para equipas dedicadas nas urgências](https://www.fnam.pt/seccoes/comunicados/minist%C3%A9rio-apresenta-proposta-de-trabalho-indigno-para-os-m%C3%A9dicos-em-equipas-dedicadas-nas-urg%C3%AAncias)
- [FNAM: pedido de fiscalização da constitucionalidade (DL 103/2023)](https://www.fnam.pt/images/2024/01/2023_12_22_FNAM_DP_Fiscaliza%C3%A7ao_Constitucionalidade_PGR_signed.pdf)
- [Montepio: descanso diário no Código do Trabalho](https://www.montepio.org/?p=94304)
- [CJEU: Commission v Greece, working time of doctors](https://curia.europa.eu/jcms/jcms/P_188771/fr)
