# Fixture: weekend-surcharge

A small Maven + JUnit 5 project used to calibrate a code graph that includes
dependency injection, events, conditional assembly, reflection, and transactional
proxies. Annotations are handwritten stubs so the build does not pull Spring Boot
or run component scanning.

- **Defect:** `WeekendSurchargePolicy.surchargeCents` applies the weekend surcharge
  on Saturday only; Sunday is billed as a weekday.
- **Issue text:** `ISSUE.md`. The phrase `weekend surcharge` also appears in the
  policy source, so a text search can reach the defect without following injection
  edges.
- Current tests cover Saturday and Monday only; they pass on this buggy revision.

## Run

```bash
./mvnw -o -f fixtures/weekend-surcharge/pom.xml test
```
