# Flyway migrations

Only the integrator creates migrations here (`docs/plan.md` §6): versions are strictly
sequential (`V001__…`, `V002__…`), forward-only, and a merged migration is never edited.
Stage 2 of Phase 1 adds `V001` with the complete M1 schema of chapter 08 §2.3.

This directory is intentionally empty until then; Flyway runs at start-up with
`fail-on-missing-locations: true`, so the location must exist.
