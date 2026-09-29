# Diagrams

C4 diagrams are generated from `docs/workspace.dsl`; they are not drawn by hand and are
not committed as images. CI builds the browsable site (all views, the SDD chapters and the
ADRs) as a build artifact.

Other diagrams (sequence diagrams, screen flows, whiteboard photos from design sessions)
live here:

- `source/`: the editable source (Mermaid `.mmd`, draw.io `.drawio`, PlantUML `.puml`).
- Rendered images next to their source only when a document needs a static image.

Entity relationship diagrams live in `docs/sdd/06-database-design.md` as Mermaid, so they
change in the same pull request as the tables.
