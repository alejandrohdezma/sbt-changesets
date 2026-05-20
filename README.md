Changeset-based versioning for Scala multi-module builds (sbt plugin + GitHub Action)

`sbt-changesets` provides a better (and easier) way to manage libraries. Specially useful for company-internal monorepos, bringing changeset-based versioning to Scala multi-module builds. Developers describe their changes in small markdown files, and the plugin takes care of version bumps, dependency cascade, and changelog generation.

> `sbt-changesets` is an SBT-opinionated take on [changesets](https://github.com/changesets/changesets).

**Looking for the GitHub Action?** Jump to [GitHub Actions](#github-actions).

## Installation

Add the plugin to your `project/plugins.sbt`:

```sbt
addSbtPlugin("com.alejandrohdezma" % "sbt-changesets" % "0.5.2")
```

This plugin depends on [sbt-modules](https://github.com/alejandrohdezma/sbt-modules), which is pulled in automatically. It expects modules to be defined using `module` instead of `project` in your `build.sbt`, with source code living under `modules/<module-name>/`. See the [sbt-modules documentation](https://github.com/alejandrohdezma/sbt-modules) for details.

## How it works

Each module in your build has a `VERSION` file and a `CHANGELOG.md`. Instead of bumping versions manually, developers create small `.changeset/*.md` files describing their changes. When it's time to release, the plugin reads those files, bumps versions (cascading through the dependency graph), updates changelogs, and cleans up.

<details><summary><b>All available commands</b></summary><br/>

| Command | Description |
|---|---|
| `changesetAdd <bump> <description>` | Create a changeset for changed modules |
| `changesetVersion` | Apply version bumps with cascade through dependency graph |
| `changesetMatrix <validate\|release>` | Output the stage-appropriate work matrix as JSON |
| `changesetConfig` | Output module dependency graph as JSON |

</details>

### 1. Creating a changeset

After making changes, run:

```
sbt> changesetAdd minor add-retry-logic
```

This detects which modules you changed (via `git diff`) and creates a file like `.changeset/add-retry-logic.md`:

```markdown
---
"my-module": minor
"other-module": minor
---

TODO: Describe your changes here
```

The first argument is the bump type (`patch`, `minor`, or `major`) and the rest becomes the filename. Edit the file to replace the placeholder with a meaningful description — this will end up in the changelog.

### 2. Validating changesets (CI)

On pull requests, run `changesetMatrix validate` to ensure every modified module has at least one changeset entry and emit `target/changeset/matrix.json` — a JSON array of `{module, scala-version, version, coordinate}` rows (one per Scala version in the module's `crossScalaVersions`, including transitive dependents) that you can feed into a CI matrix as `matrix.include`. It fails if any module is missing coverage or if a description still contains the placeholder text.

```json
[
  { "module": "module-a", "scala-version": "2.13.18", "version": "1.2.3-abc-SNAPSHOT", "coordinate": "\"com.example\" %% \"module-a\" % \"1.2.3-abc-SNAPSHOT\"" },
  { "module": "module-a", "scala-version": "3.3.7",   "version": "1.2.3-abc-SNAPSHOT", "coordinate": "\"com.example\" %% \"module-a\" % \"1.2.3-abc-SNAPSHOT\"" },
  { "module": "module-b", "scala-version": "3.3.7",   "version": "2.0.0-abc-SNAPSHOT", "coordinate": "\"com.example\" %% \"module-b\" % \"2.0.0-abc-SNAPSHOT\"" }
]
```

If you need the matrix without requiring changeset entries (e.g. for snapshot publishing or local development), set the `CHANGESET_SKIP_VALIDATION` environment variable to `true`. The command will skip validation and still output the matrix.

### 3. Publishing snapshots (CI)

On feature branches, the rows from `changesetMatrix validate` feed a CI matrix that publishes each `(module, scala-version)` snapshot on its own runner via `sbt "++<scala-version> <module>/publish"`. The version is the default `<base>-<suffix>-SNAPSHOT` from the module's `VERSION` file (suffix from `SNAPSHOT_SUFFIX` env / sys-prop, else a memoised JVM timestamp); each row carries the resolved Maven `coordinate` so a follow-up job can post a PR comment listing them.

### 4. Applying version bumps (CI)

When changesets are merged to main branch, run `changesetVersion`. This:

1. Parses all `.changeset/*.md` files.
2. Cascades bumps through the dependency graph following [early-semver](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html) rules.
3. Updates each module's `VERSION` file.
4. Prepends new entries to each module's `CHANGELOG.md`.
5. Removes processed changeset files.
6. Writes `target/changeset/version-summary.json` with old/new versions.

Modules that are only bumped through cascade get auto-generated descriptions listing which dependencies changed.

### 5. Publishing releases (CI)

After version bumps are committed, `changesetMatrix` writes `target/changeset/matrix.json` — a JSON array of `{module, scala-version}` rows for every (module, Scala version) whose `VERSION` file just changed — which feeds a `publish` matrix that publishes each pair on its own runner in parallel via `sbt "++<scala-version> <module>/publish"`. A downstream `release-tag` job then runs once per distinct module, calls `extractLatestChangelog <module>`, and uses the result as the GitHub release notes.

The composite [GitHub Action](#github-actions) bundles this flow into `detect` mode, which (when the workflow runs on a Version Packages PR merge) emits both `matrix` (for `publish`) and `release-modules` (for `release-tag`) alongside its PR-side outputs.

## GitHub Actions

This repository also provides a composite GitHub Action that orchestrates the full CI workflow. Reference it as `alejandrohdezma/sbt-changesets@v0.5.2` and choose a mode depending on the context.

### `detect` mode

Validates that all changed modules have changeset entries and emits exactly two outputs:

- `stage`: a single dispatch classification — `validate` (PR with affected modules), `apply-changesets` (push-to-main with pending changesets), `release` (push-to-main with VERSION bumps to publish), or empty (nothing to do). Use as the single gating condition for every downstream job.
- `matrix`: the work matrix for this run; shape depends on `stage`.
  - `stage == 'validate'`: array of `{module, scala-version, version, coordinate}` rows. Each row carries the publish-matrix dimensions plus the snapshot Maven coordinate. Plug into `matrix.include` for the validate job; pass to `snapshot-comment` mode.
  - `stage == 'release'`: array of `{module, scala-version, version, changelog}` rows. Each row carries the publish-matrix dimensions plus the release-tag note body. Plug into `matrix.include` for the publish job; pass to `release-tag` mode.
  - Otherwise: empty array. Hard-fails the action if the release-stage payload exceeds 400 KB UTF-8 (≈ 800 KB UTF-16, well under GitHub's 1 MB per-job output cap) — if you hit it, reduce changelog verbosity or split the release.

Run on every event. The consumer gates each downstream job on `stage` and feeds `matrix` directly into all matrix-style consumers and post-processing modes.

```yaml
# .github/workflows/ci.yaml
on:
  pull_request:
  push:
    branches: [main]

jobs:
  detect:
    runs-on: ubuntu-latest
    env:
      SNAPSHOT_SUFFIX: ${{ github.run_id }}-${{ github.run_attempt }}
    outputs:
      matrix: ${{ steps.changesets.outputs.matrix }}
      stage: ${{ steps.changesets.outputs.stage }}
    steps:
      - uses: actions/checkout@@v4
        with: { fetch-depth: 0 }

      - id: changesets
        uses: alejandrohdezma/sbt-changesets@v0.5.2
        with:
          mode: detect
          error-help-url: https://your-repo/docs/versioning  # shown on validation failure

  validate:
    needs: detect
    if: needs.detect.outputs.stage == 'validate'
    runs-on: ubuntu-latest
    strategy:
      matrix:
        include: ${{ fromJson(needs.detect.outputs.matrix) }}
    env:
      SNAPSHOT_SUFFIX: ${{ github.run_id }}-${{ github.run_attempt }}
    steps:
      - uses: actions/checkout@@v4

      - run: sbt "++${{ matrix.scala-version }} ${{ matrix.module }}/test"

      - run: sbt "++${{ matrix.scala-version }} ${{ matrix.module }}/publish"

  snapshot-comment:
    needs: [detect, validate]
    if: needs.detect.outputs.stage == 'validate'
    runs-on: ubuntu-latest
    steps:
      - uses: alejandrohdezma/sbt-changesets@v0.5.2
        with:
          mode: snapshot-comment
          matrix: ${{ needs.detect.outputs.matrix }}
```

`SNAPSHOT_SUFFIX` (e.g. `${{ github.run_id }}-${{ github.run_attempt }}`) is set on both `detect` and `validate` so the coordinates resolved up-front in `detect` match the artifacts published by the `validate` matrix. Because every matrix cell in a single workflow run shares the same `SNAPSHOT_SUFFIX`, the per-Scala-version publishes that make up one module produce consistent versions. The `coordinate` field carried in each matrix row is rendered from each module's sbt `organization` setting, so per-module org overrides (e.g. `com.permutive.metrics`) are respected without any consumer-side hardcoding. Snapshot publishes are intended for private monorepos only — exposing publishing credentials on PRs in public repositories is a security risk.

#### Customising the `coordinate` per module

The default coordinate is `"org" %% "name" % "version"` for Scala modules and `"org" % "name" % "version"` for Java modules (`crossPaths := false`). Override the `changesetCoordinate` sbt setting on individual projects when you want the snapshot-comment to render something different — for example, testkit modules that consumers always import in the `test` configuration:

```scala
lazy val `my-testkit` = module.settings(changesetCoordinate ~= { _ + " % \"test\"" })
```

The `~=` transform composes with the default rendering, so you don't have to rebuild the whole coordinate string from `organization` / `name` / `version`. The override applies to every row for that module in the validate-stage matrix.

### `snapshot-comment` mode

Posts (or edits) a PR comment listing snapshot coordinates produced by a matrix snapshot publish. Consumes the validate-stage `matrix` from `detect` and dedupes by module before rendering the markdown blocks.

```yaml
  snapshot-comment:
    needs: [detect, validate]
    runs-on: ubuntu-latest
    steps:
      - uses: alejandrohdezma/sbt-changesets@v0.5.2
        with:
          mode: snapshot-comment
          matrix: ${{ needs.detect.outputs.matrix }}
```

### `apply-changesets` mode

Bumps `VERSION` files via `changesetVersion` and upserts the **Version Packages** pull request on the `changeset-release/main` branch. Run on push to main when `detect.changesets-count != 0` (i.e. a developer's PR carrying changeset files just merged).

Pass `extra-command` to chain additional sbt commands after `changesetVersion` in the same sbt invocation — useful for regenerating doc files (e.g. `mdoc`) so they're committed as part of the same version-PR commit.

### `release-tag` mode

Loops over the release-stage `matrix` (as produced by `detect`) and creates one GitHub release per distinct module — `module@version` as the tag and title, `changelog` as the notes body. Duplicate rows from cross-built modules become no-ops on the second `gh release view`. No sbt, no checkout — pure `gh` API calls. Reruns are idempotent.

```yaml
  release-tag:
    needs: [detect, publish]
    if: needs.detect.outputs.stage == 'release'
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: alejandrohdezma/sbt-changesets@v0.5.2
        with:
          mode: release-tag
          matrix: ${{ needs.detect.outputs.matrix }}
```

Optionally pass `target` to override the branch the releases point at (defaults to `main`).

### Putting it together: release workflow

The push-to-main pipeline is dispatched by `detect.outputs.stage`: when there are pending changeset files `stage` is `apply-changesets` and the version-bump job runs; once that PR is merged `stage` becomes `release`, `publish` fans out one runner per `(module, Scala version)`, and `release-tag` creates one GitHub release per module.

```yaml
# .github/workflows/ci.yaml (continued)
jobs:
  # ...detect, validate, snapshot-comment from above...

  apply-changesets:
    needs: detect
    if: needs.detect.outputs.stage == 'apply-changesets'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@@v4
        with: { fetch-depth: 0 }

      - uses: alejandrohdezma/sbt-changesets@v0.5.2
        with:
          mode: apply-changesets
          # Optional: regenerate docs as part of the same version-PR commit.
          extra-command: mdoc

  publish:
    needs: detect
    if: needs.detect.outputs.stage == 'release'
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      max-parallel: 16
      matrix:
        include: ${{ fromJson(needs.detect.outputs.matrix) }}
    env:
      RELEASE: "true"
    steps:
      - uses: actions/checkout@@v4

      - run: sbt "++${{ matrix.scala-version }} ${{ matrix.module }}/publish"

  release-tag:
    needs: [detect, publish]
    if: needs.detect.outputs.stage == 'release'
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: alejandrohdezma/sbt-changesets@v0.5.2
        with:
          mode: release-tag
          matrix: ${{ needs.detect.outputs.matrix }}
```

`release-tag` `needs: publish` so a publish failure on any matrix cell (e.g. one Scala version fails to compile) blocks all GitHub release creation — preventing half-published modules from getting tagged. Re-running after a fix proceeds cleanly because the action's loop skips tags that already exist; new tags get created as expected.

The same `matrix` output feeds both `publish` (as `matrix.include`, ignoring the extra `version` and `changelog` fields) and `release-tag` (as a per-row loop, deduping by module). One source of truth for "what work needs to happen this run".

### Inputs

| Input | Required | Default | Description |
|---|---|---|---|
| `mode` | yes | — | `detect`, `apply-changesets`, `snapshot-comment`, or `release-tag` |
| `github-token` | no | `github.token` | GitHub token for API operations |
| `error-help-url` | no | — | URL shown on changeset validation failure |
| `skip-validation` | no | `false` | Skip changeset validation in `detect` mode while still computing affected modules |
| `extra-command` | no | — | sbt command(s) chained after `changesetVersion` in `apply-changesets` mode (e.g. `documentation/mdoc`) |
| `matrix` | no | — | JSON array produced by `detect`'s `matrix` output. Consumed by `snapshot-comment` (validate-stage rows with `coordinate`) and `release-tag` (release-stage rows with `version` + `changelog`) |
| `pr-number` | no | `github.event.pull_request.number` | PR number to comment on in `snapshot-comment` mode |
| `target` | no | `main` | Branch / commit SHA to target for the GitHub releases created by `release-tag` mode |

### Outputs

| Output | Modes | Description |
|---|---|---|
| `matrix` | `detect` | Stage-dependent work matrix. Validate stage: `{module, scala-version, version, coordinate}` rows. Release stage: `{module, scala-version, version, changelog}` rows. Empty otherwise |
| `stage` | `detect` | Dispatch classification: `validate`, `apply-changesets`, `release`, or empty. Gate every downstream job on this |
