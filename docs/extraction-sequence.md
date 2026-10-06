# Extraction sequence

The dependency-ordered list of middleware extractions for the plugin: which
subsystem moves when, and what each move touches. Technical order is strict;
upstream PR publication runs on a separate lane gated by review headroom —
integration timing decouples from merge timing. The subsystem inventory
lives in [architecture-survey.md](architecture-survey.md); the target
structure in [architecture-target.md](architecture-target.md).

## Ordering

| Order | Subsystem | Status | Upstream PRs |
|-------|-----------|--------|--------------|
| 1 | Requirement middleware | landed | #707, #708, #709, #710, #711 |

Further rows land as the survey's boundary records confirm each dependency
edge.
