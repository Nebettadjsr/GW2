# Vergleich: KI-Entwicklungs-Flow im Video vs. GW2

Verglichen wird der **automatisierte Flow, der die Anwendung baut**, nicht die Anwendung selbst.

- **Quelle A:** Video-Transkript „Der Moment, der die Softwareentwicklung geändert hat!" (Selbstversuch eines Enterprise-Beraters, Backoffice-System, ca. 420.000 Zeilen Code).
- **Quelle B:** das GW2-Repository (`AGENTS.md`, `CLAUDE.md`, `agent/`, `.github/`, `docs/`, `backend/pom.xml`, `frontend/package.json`)

Berücksichtigt ist nur der **aktuelle** Flow (Git, GitHub-Issues, PRs, Actions). Archiv-Dateien unter `docs/archive/`, der Tag `pre-github-flow` und der frühere Python-Orchestrator sind bewusst nicht Teil des Vergleichs.

Fakten aus dem Repository und Aussagen des Videos sind getrennt gekennzeichnet. Wertungen sind als **Einschätzung** markiert.

---

## 1. Der Flow im Video

### 1.1 Aufbau in einem Satz

Ein Coding-Agent (anfangs Codex, später fast nur Claude Code) arbeitet lokal mit direktem Zugriff auf drei Dinge. Das sind das Code-Repo, ein Spec-Ordner (WAS gebaut wird) und der **Harness** (WIE gebaut wird). Dazu kommen statische Analyse-Tools, die jede Änderung automatisch prüfen. Zusätzlich hat der Agent Docker- und SSH-Zugriff auf die Testumgebung. Gebaut wird in einer Pipeline Gitea → Build-Agent → NAS (Test und Produktion).

```
Idee (per Sprache) ─► Specs/Stories + Akzeptanzkriterien ─► Prompt(s)
                                                              │
   Harness (Architektur-/Coding-/Test-/Spec-/Ideen-Regeln) ───┤ als System-Prompt
                                                              ▼
                                           Coding-Agent erzeugt Code + Tests
                                                              │
        NDepend (Architektur) + ReSharper (Code) ◄────────────┤ nach JEDER Änderung
        API-Tests + Workflow-Tests (WireMock) ◄───────────────┤ Tests aus Akzeptanzkriterien
                                                              ▼
                              Abweichung? ─► Harness korrigieren (nicht nur den Code)
```

### 1.2 Die Bausteine

| # | Baustein | Was er im Video ist |
|---|---|---|
| 1 | **Harness** | Mehrere Dateien mit Leitplanken, die als System-Prompt in jede neue Session geladen werden. Sie beschreiben Architektur, Codierung, Test-Aufbau, Zerlegung von Anforderungen und das Format von Ideen. |
| 2 | **Architekturrichtlinie** | ca. 880 Regeln, aus einem Kundenprojekt übernommen. |
| 3 | **Coding-Richtlinie** | Sehr umfangreich, für C# und Blazor. |
| 4 | **Statische Tools als Gate** | NDepend (Struktur und Architektur) und ReSharper (Code). Sie laufen nach jeder Änderung, sind deterministisch und schnell. Der Agent prüft sich nicht selbst. |
| 5 | **Harness-Correction-Development** | Weicht der Output vom Harness ab, wird der Harness korrigiert. Der Code wird nicht nur nachgebessert. |
| 6 | **Spec-Driven** | 213 User Stories mit 4.083 Akzeptanzkriterien in einem Spec-Ordner. Der Harness bringt dem Agenten bei, wie er Anforderungen in Aufgaben zerlegt. |
| 7 | **Test-Driven aus Akzeptanzkriterien** | Eine Test-Beschreibung im Harness legt Aufbau, Umfang und Ausführung fest. Tests entstehen zuerst und aus der Spec, nicht aus dem Code. |
| 8 | **Zwei Testebenen** | API-Tests (2.900) mit eigener DB pro Service. Sie prüfen Response, Domain-Events auf dem Bus und die DB. Dazu Workflow-Tests (392) gegen die ganze Umgebung. WireMock ersetzt Office 365, Google und Trello. |
| 9 | **Idea-Driven / Voice-Driven** | Der Harness kennt das Format einer „Idee". Der Mensch diktiert per Sprache, der Agent diskutiert kritisch mit Kenntnis aller Specs und Tests und erzeugt daraus Anforderungen. Im Video dauerte eine Session 4 Stunden. |
| 10 | **Agent-Autonomie** | Docker for Desktop lokal. SSH aufs NAS: in Test fast alles, in Produktion nur Logs lesen. |
| 11 | **Nächtliche Doku-Neugenerierung** | Ein Nightly-Task auf dem Build-Server lässt Claude Code die Doku komplett neu erzeugen. Sie ist morgens synchron zum Code (1.300 Seiten). |
| 12 | **Mess-KPIs** | Strukturelle Schulden (Ziel 0 %), Testabdeckung (85 %), Doku-Abdeckung (100 %), Aufwand, Kosten. |
| 13 | **Rolle des Menschen** | Wandert von Entwickler über Architekt und PO zum Stakeholder. Code wird irgendwann nicht mehr gelesen, weil die Tools Struktur und Stil garantieren. |

### 1.3 Die Kernthese des Videos

> Die Qualität kommt aus dem **Harness plus deterministischen Prüfwerkzeugen**. Das LLM allein erzeugt sie nicht. Dazu kommen Spec- und Test-Ableitung direkt aus Akzeptanzkriterien.

---

## 2. Der Flow in GW2

### 2.1 Aufbau in einem Satz

GW2 setzt auf einen **GitHub-nativen, rollenbasierten Flow**: Planner (Codex) → Supervisor (Codex interaktiv) → Programmierer (Claude Code in GitHub Actions) → CI → nativer Codex-PR-Review → Auto-Merge. Der Zustand liegt in GitHub-Issues und -PRs. Es gibt keinen Spec-Ordner.

```
Owner-Wunsch ─► Planner (Codex) schlägt Issue vor
             ─► Supervisor (Codex) gibt EIN Issue frei (Label `auto`)
             ─► Owner startet manuell: workflow_dispatch "Run next ready story"
                  select_claude_story.py wählt Issue (Abhängigkeiten, Holds)
                  claude-code-action implementiert (max. 40 Turns, 45 min)
                  Workflow committet, pusht, erstellt PR + Report
             ─► CI: Maven, Vitest, Typecheck, Playwright, Workflow-Checks, Coverage-KPI
             ─► Codex-PR-Review (exakter Head-SHA)
             ─► merge_ready_pr.py: Squash-Merge, wenn alle Gates grün
             ─► Fehler ─► Label `needs-rework` ─► nächster Dispatch macht Rework
             ─► Deploy: separat, manuell per SSH/git archive
```

### 2.2 Vorhandene Bausteine (Fakten)

| Baustein | Dateien / Mechanismus |
|---|---|
| Gemeinsame Regeln | `AGENTS.md` (Rollen, Dokumentations-Besitz, Architekturgrenzen, Review-Regeln), `CLAUDE.md` (Programmierer) |
| Rollen-Verträge | `agent/SUPERVISOR_INSTRUCTIONS.md`, `agent/PLANNER_INSTRUCTIONS.md`, `agent/DEVELOPMENT_WORKFLOW.md` |
| Fachliche Quellen | `docs/DOMAIN_SPEC.md` (2.139 Zeilen), `ARCHITECTURE.md` (402), `CODING_GUIDELINES.md` (198), `TEST_STRATEGY.md` (1.190), `FRONTEND_UX_GUIDELINES.md` (405), `KNOWN_PROBLEMS.md`, `CURRENT_STATE_SPEC.md` |
| Lernspeicher | `tasks/lessons.md` (aktuell relevante Lessons) |
| Story-Auswahl | `.github/scripts/select_claude_story.py` (Titel `STORY-XXX-N`, Abhängigkeiten, Holds, Rework zuerst) |
| Implementierung | `.github/workflows/claude.yml` (`anthropics/claude-code-action@v1`, Subscription-Token, kein API-Billing) |
| Wiederherstellung | `save_claude_recovery.py` (7-Tage-Artefakt mit Patch und Report) |
| Testtreiber | JUnit 5, jqwik (Property-Tests), Spring-Test; ca. 285 Test-Dateien mit ca. 1.885 `@Test`/`@Property`; Vitest (ca. 76 Spec-Dateien); Playwright-Smoke-Skripte (ca. 20); DB-Tests gegen echtes PostgreSQL 17 |
| Gates | CI: `Backend tests (Maven)`, `Frontend tests (Vitest)`, `Workflow checks`, `Coverage KPI report` |
| Review | Nativer Codex-Review auf exakten Head-SHA (`has_unresolved_findings: false` ist Merge-Bedingung) |
| Auto-Merge | `review-merge.yml` + `merge_ready_pr.py` (SHA-gebunden, geschützte Pfade ausgenommen) |
| Evidenz | PR-Report mit Marker `gw2-claude-run-report:v1 head=<SHA>`, Zeilen `Local-only checks still needed: none` und `Unresolved gaps: none` |
| Coverage | JaCoCo + Vitest-Coverage, `publish_coverage_kpi.py` (Ziel nur als Warnung, kein Fail) |
| API-Drift | `ApiCompatibilityLiveIT` und Owner-Status-Monitoring gegen die echte GW2-API |

---

## 3. Gegenüberstellung

### 3.1 Baustein für Baustein

| Aspekt | Video | GW2 | Einschätzung |
|---|---|---|---|
| Harness (Regelwerk für den Agenten) | Mehrere gezielt gebaute Dateien als System-Prompt, laufend korrigiert | `AGENTS.md` + `CLAUDE.md` + `docs/CODING_GUIDELINES.md` | **Vorhanden**, aber eher prosaisch und ohne messbare Regelanzahl. CLAUDE.md sagt „read only relevant docs", der Agent lädt also nicht automatisch alles. |
| Deterministische Architektur-Prüfung | NDepend, 880 Regeln, nach jeder Änderung | **Nichts Entsprechendes.** Weder ArchUnit noch Checkstyle, PMD, SpotBugs, Spotless oder Error Prone in `pom.xml`. Auch kein ESLint oder Prettier im Frontend. | **Größte Lücke** (siehe 4.1). |
| Statische Code-Analyse | ReSharper | Nur Compiler und `vue-tsc` | **Lücke** |
| Architekturregeln | Maschinell erzwungen | Regeln nur in Text (`AGENTS.md`: „Domain darf nicht von HTTP/JDBC/Docker abhängen"). Nur der Review prüft das. | **Schwächer.** Verstöße fallen erst im Review auf (oder gar nicht). |
| Specs | 213 Stories, 4.083 Akzeptanzkriterien als Dateien | Specs sind `DOMAIN_SPEC.md` (ein Dokument) plus Akzeptanzkriterien im GitHub-Issue. | **Anders, nicht schlechter.** Issues sind der Zustandsträger. Es fehlt eine durchsuchbare, versionierte Kriterien-Liste im Repo. |
| Tests aus Akzeptanzkriterien | Test-Beschreibung im Harness. Tests zuerst, aus der Spec abgeleitet. | `TEST_STRATEGY.md` (sehr ausführlich). `CLAUDE.md`: „Normally implement meaningful tests with code". Code und Test entstehen zusammen. | **Schwächer im Prinzip.** Es gibt kein Test-First-Gebot und keine Rückverfolgbarkeit Kriterium → Test. |
| Teststrategie / Ebenen | API-Tests und Workflow-Tests, DB-Assertions, Event-Assertions, WireMock | Domain-Unit, Property-Tests (jqwik), Application, Spring-Controller-Tests, echte DB, Vitest, Playwright-Journeys | **Gleichwertig bis breiter.** GW2 hat Property-Tests und Browser-Journeys, die im Video fehlen. Mocks für die externe GW2-API sind Fixtures. |
| Idee → Spec | Voice-/Idea-Driven: Harness kennt das Ideenformat | Planner (Codex) schlägt Issues vor, Supervisor gibt frei, Owner entscheidet | **Menschlich stärker kontrolliert**, aber ohne Ideen-Format und Voice-Flow. Mehr Reibung, mehr Kontrolle. |
| Rollentrennung | Ein Agent, Mensch steuert | 3 Rollen (Planner, Supervisor, Programmierer) + unabhängiger Reviewer | **Mehr Struktur als das Video**, aber auch mehr Overhead und mehr Handoffs. |
| Unabhängiges Review | Nicht beschrieben (Tools prüfen) | Codex reviewt jeden PR, Auto-Merge nur ohne offene Findings | **Besser.** Ein zweites Modell ist ein echter Zusatzschutz. |
| Merge-Automatik | Nicht beschrieben | SHA-gebundener Auto-Merge mit mehreren Gates | **Besser und sicherer geregelt.** |
| Wiederherstellung | Nicht beschrieben | Recovery-Artefakt, Rework-Label | **Besser** |
| Harness-Korrektur | Explizite Schleife: Abweichung → Harness ändern | `tasks/lessons.md` gibt es, aber nirgends ein Gebot, nach Abweichungen den Harness anzupassen. | **Teilweise.** Der Mechanismus fehlt als Prozessschritt. |
| Doku-Sync | Nightly-Neugenerierung | Doku ist „owning documents", wird per PR gepflegt, keine nächtliche Prüfung. `AGENTS.md` erzwingt Update nur bei Änderung. | **Lücke** (Drift-Risiko, siehe 4.4). |
| Agent in der Umgebung | Docker lokal, SSH aufs NAS, Logs aus Produktion | Läuft in Actions-Runner mit Java 25 und disposable Postgres. Kein Zugriff auf Test-/Prod-Umgebung. | **Sicherer, aber weniger autonom.** Der Agent kann nicht gegen eine echte Deployment-Umgebung testen. |
| Deployment | Gitea → Build-Agent → NAS, Test und Prod | Manuell: `git archive` + SSH, Owner im sichtbaren Terminal | **Bewusst manuell**, kein Test-Stage-Gate dazwischen. |
| Kosten-Steuerung | Zwei Max-Abos, ca. 1.620 € | Subscription-Token, Turn-Limit (max. 100), Timeout 45 min, `ANTHROPIC_API_KEY` leer | **Gut abgesichert.** |
| Messung / KPIs | Strukturelle Schulden, Test-Coverage, Doku-Abdeckung, Aufwand | Nur Coverage-KPI (Warnung, kein Gate) | **Schwächer.** Kein Qualitätsmaß für Struktur, keine Zeit-/Kosten-Auswertung. |
| Trigger | Mensch arbeitet interaktiv | Nur manueller `workflow_dispatch`, eine Story pro Lauf | **Bewusst konservativ.** Das ist langsam, aber sicher. |

### 3.2 Kurzbilanz

- **GW2 ist stärker bei:** Governance, unabhängigem Review, SHA-gebundenem Merge, Kosten- und Recovery-Kontrollen, Testbreite (Property-, Browser-, Live-API-Compat-Tests).
- **Das Video ist stärker bei:** deterministischer Durchsetzung der Architektur und des Code-Stils, Test-First aus Akzeptanzkriterien, Harness-Korrekturschleife, Doku-Sync, Messbarkeit.
- Das Video baut auf **Prüfen durch Werkzeuge**. GW2 baut auf **Prüfen durch Prozess und zweites Modell**. Beides lässt sich kombinieren.

---

## 4. Was in GW2 fehlt

Nach Wirkung sortiert.

### 4.1 Architektur- und Stil-Gate (fehlt komplett) — höchste Priorität

Im Video ist das der Grund, warum der Code „wie selbst geschrieben" wurde. GW2 hat die Regeln in `AGENTS.md` und `CODING_GUIDELINES.md`, aber nichts, was sie ausführt.

Konkret fehlen:
- **ArchUnit** (Java) mit Tests wie „Domain-Pakete importieren nichts aus `web`, JDBC oder Spring". Das ist die direkte Entsprechung zu NDepend. Es läuft als normaler JUnit-Test und braucht kein Extra-Tooling in CI.
- **Checkstyle / PMD / Spotless / Error Prone** für Java-Konventionen (Dateigröße, Namensregeln aus Abschnitt 5 und 6 der Coding Guidelines).
- **ESLint + Prettier** (oder Biome) fürs Frontend. Aktuell prüft nur `vue-tsc`.
- Optional **Sonar/Qodana** für strukturelle Schulden als Zahl.
- Ein CI-Job oder Teil von `Backend tests (Maven)`, der diese Prüfungen hart (nicht als Warnung) ausführt, und eine Zeile in `CLAUDE.md`: „Vor Abschluss die Architektur-Checks lokal laufen lassen".

### 4.2 Harness-Korrekturschleife (fehlt als Prozess)

Das Video ändert bei Abweichung den Harness, nicht nur den Code. In GW2 gibt es `tasks/lessons.md` und die Regel, Dokus zu aktualisieren, aber keinen Schritt „Review-Finding oder Rework-Grund → Regel in `CODING_GUIDELINES.md` oder ArchUnit-Test ergänzen". Vorschlag: Ein Rework-PR soll in der Checkliste angeben, ob eine neue Regel nötig ist.

### 4.3 Tests aus Akzeptanzkriterien mit Rückverfolgbarkeit

Im Video sind Kriterien die Quelle der Tests. In GW2 gibt es `TEST_STRATEGY.md`, aber keinen Mechanismus, der zeigt, welches Kriterium durch welchen Test abgedeckt ist. Das PR-Template und der Run-Report könnten eine Tabelle „Kriterium → Test" verlangen. Das ist günstig umzusetzen (Prompt in `claude.yml`) und der Review kann es prüfen.

### 4.4 Doku-Drift-Kontrolle

Das Video erzeugt die Doku nachts neu. GW2 hat viel Doku (ca. 5.000 Zeilen allein in `docs/` und `agent/`). Mit `schedule:` könnte ein Workflow wöchentlich prüfen, ob `CURRENT_STATE_SPEC.md`, `ARCHITECTURE.md` und die Docs zum Code passen. Ergebnis wäre ein Issue und kein automatischer Commit. Das passt besser zu GW2s Governance als blindes Neugenerieren.

### 4.5 Test gegen eine echte Umgebung

Das Video lässt den Agenten in einer Testumgebung deployen und Logs lesen. GW2 testet nur in einem frischen Runner. Es gibt kein automatisches Staging, und das Deployment liegt komplett beim Owner. `docker/compose.yaml` und `scripts/compose-smoke.sh` existieren, aber keiner der drei Workflows ruft sie auf (geprüft per Suche in `.github/workflows/`). Ein Compose-Smoke-Job in CI wäre der naheliegende nächste Schritt.

### 4.6 Ideen-Intake

Das Video hat ein definiertes Format und einen Dialog-Modus für Ideen. GW2 hat Issue-Templates (`feature_request.yml`), aber kein Format, das den Agenten zwingt, eine Idee kritisch gegen `DOMAIN_SPEC.md` zu prüfen, bevor ein Issue entsteht. Das macht der Planner teilweise, ist aber nicht als wiederholbarer Ablauf beschrieben.

### 4.7 Messbare Qualitätszahlen

Es fehlen strukturelle Schulden, Doku-Abdeckung und Aufwand pro Story (Turns, Tokens, Dauer). Der Coverage-KPI ist nur eine Warnung. Ohne Zahlen lässt sich nicht sagen, ob der Flow besser wird. Die Token- und Turn-Daten könnte der Workflow aus der Action-Ausgabe in den Run-Report schreiben.

---

## 5. Was bei GW2 anders ist, besser oder schlechter

### Besser als im Video

1. **Zweites Modell als Reviewer** auf exaktem Head-SHA. Ein Autor-Modell prüft sich nicht selbst.
2. **Merge-Gates sind maschinell und SHA-gebunden.** Veraltete Grünstände zählen nicht.
3. **Geschützte Pfade** (`.github/**`, `AGENTS.md`, `CLAUDE.md`) können nicht automatisch gemergt werden. Der Agent kann seine eigenen Regeln nicht ändern.
4. **Kosten- und Missbrauchsschutz:** Subscription-Token, leerer API-Key, Turn- und Zeitlimit, nur Owner-Dispatch.
5. **Recovery-Mechanismus** bei abgebrochenen Läufen.
6. **Ehrliche Evidenz:** Der Report muss „none" nur dann schreiben, wenn es stimmt. Der Merge hängt daran.
7. **Property-Tests (jqwik)** und **Browser-Journeys** (Playwright) gehen über das hinaus, was das Video testet.
8. **Live-API-Kompatibilitätsprüfung** gegen die GW2-API. Das Video hat dafür WireMock, aber keinen Drift-Schutz.
9. **Abhängigkeitsmodell:** Der Selektor erzwingt Reihenfolge, Holds und Rework-Vorrang.

### Schlechter als im Video

1. **Kein deterministisches Qualitäts-Gate** (siehe 4.1). Das ist der größte Unterschied.
2. **Der Agent sieht den Harness nicht verlässlich vollständig.** `CLAUDE.md` sagt „Read only the issue's relevant documentation". Das spart Tokens, kann aber Regeln auslassen. Im Video wird der ganze Harness als System-Prompt geladen.
3. **Mehr Handoffs, mehr Reibung.** Planner → Supervisor → Owner-Dispatch → Claude → CI → Review → Merge. Jeder Übergang kostet Zeit und Kontext. Jede Rolle braucht ihren eigenen Kontext, und der Programmierer sieht Planner- und Supervisor-Überlegungen nur über das Issue.
4. **Ein Story-Lauf pro manuellem Dispatch.** Das Video arbeitet interaktiv und kontinuierlich.
5. **Keine Kriterium → Test-Verfolgung** und kein Test-First-Gebot.
6. **Abhängigkeit von zwei Anbietern** (Claude als Programmierer, Codex als Planner, Supervisor und Reviewer). Beide Limits können blockieren. Das alte Setup nennt das Claude-Limit als praktischen Engpass.
7. **Doku-Umfang ohne Drift-Schutz.** Viel Text ohne automatische Prüfung wird veralten.

### Einfach anders (keine Wertung)

- **Zustand in GitHub statt in Dateien.** Das Video hat einen Spec-Ordner. GW2 hat `AGENTS.md`: „Do not recreate an executable file-based story queue".
- **Cloud-Runner statt lokaler Maschine.** Das ist besser isoliert, aber der Agent kann nichts „ausprobieren", was nicht im Runner geht.
- **Single-User-Projekt mit Produktivsystem** statt eines Experiment-Systems. Das erklärt die vorsichtige Governance.

---

## 6. Empfohlene Reihenfolge

| Prio | Maßnahme | Aufwand | Wirkung |
|---|---|---|---|
| 1 | **ArchUnit-Tests** für die Schichtregeln aus `AGENTS.md` (Domain ohne Web/JDBC/Spring) | niedrig | hoch: macht den wichtigsten Textregel-Satz prüfbar |
| 2 | **ESLint/Biome + Prettier** im Frontend und **Spotless/Checkstyle** im Backend, als hartes CI-Gate | mittel | hoch: Stil und Struktur wie im Video |
| 3 | **Kriterium → Test-Tabelle** im Run-Report und im PR-Template | niedrig | mittel: bessere Review-Grundlage |
| 4 | **Harness-Korrektur als Pflichtfrage** im Rework: „Braucht es eine neue Regel oder einen neuen Architektur-Test?" | niedrig | mittel: der Flow lernt |
| 5 | **Token-/Turn-/Dauer-Metriken** in den Run-Report | niedrig | mittel: macht Verbesserung messbar |
| 6 | **Wöchentlicher Doku-Drift-Check** als geplanter Workflow, der ein Issue eröffnet | mittel | mittel |
| 7 | Staging/Compose-Smoke als CI-Stufe | mittel | mittel |
| 8 | Code-Qualitätsmaß (Sonar/Qodana) als Zahl für strukturelle Schulden | mittel | niedrig bis mittel |

**Einschätzung:** Punkte 1 bis 4 liefern den größten Teil dessen, was das Video den „Game Changer" nennt, und kosten zusammen wenig. Der übrige Aufbau von GW2 (Review, Merge-Gates, Recovery, Kostenkontrolle) ist dem Video in der Governance überlegen und sollte bleiben.

---

## 7. Grenzen dieses Vergleichs

- Das Video beschreibt den Flow auf hohem Niveau. Der Harness selbst wurde nicht gezeigt, der Autor kündigt das für spätere Videos an. Details wie Prompt-Aufbau und Dateistruktur sind daher nicht überprüfbar.
- Die Zahlen des Videos (Faktor 93, 0 % strukturelle Schulden) stammen vom Autor selbst. Die Vergleichswerte der Teams beruhen auf Schätzungen aus einem Tag Arbeit.
- Zu GW2 habe ich Repository-Dateien gelesen, aber keine CI-Läufe, keine GitHub-Issues, PRs, Labels oder Repository-Einstellungen (Branch-Protection, Secrets). Aussagen wie „Codex-Review läuft auf jedem PR" stützen sich auf die Workflow-Dokumentation und `merge_ready_pr.py`, nicht auf beobachtete Läufe.
- Die Testzahlen für GW2 sind grobe Zählungen (`@Test`/`@Property`, Dateien), keine CI-Ergebnisse.
- Dass kein Workflow `compose-smoke.sh` aufruft, beruht auf einer Textsuche in `.github/workflows/`. Ein manueller Aufruf außerhalb von CI ist möglich.
