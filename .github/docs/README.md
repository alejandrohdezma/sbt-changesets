@DESCRIPTION@

`sbt-changesets` provides a better (and easier) way to manage libraries. Specially useful for company-internal monorepos, bringing changeset-based versioning to Scala multi-module builds. Developers describe their changes in small markdown files, and the plugin takes care of version bumps, dependency cascade, and changelog generation.

> `sbt-changesets` is an SBT-opinionated take on [changesets](https://github.com/changesets/changesets).

**Looking for the GitHub Action?** Jump to [GitHub Actions](#github-actions).

## Installation

Add the plugin to your `project/plugins.sbt`:

```sbt
addSbtPlugin("@ORGANIZATION@" % "@NAME@" % "@VERSION@")
```

The same coordinate is published for both **sbt 1.x** (Scala 2.12) and **sbt 2.x** (Scala 3).

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

A module can also be listed as `validate-only` instead of a bump:

```markdown
---
"my-module": patch
"other-module": validate-only
---

Update the retry defaults.
```

`validate-only` means "build and test this module, never release it". Such a module joins the validate-stage matrix as a `validate-only` row, and `changesetVersion` consumes the entry without touching its `VERSION` or `CHANGELOG.md`. It fits a module that the change affects without altering what it publishes — an update to one of its test-scoped dependencies, say. It is not a way to opt out of releasing a module whose own `src/main` changed: that still fails validation until it gets a real bump.

### 2. Validating changesets (CI)

On pull requests, run `changesetMatrix validate` to ensure every modified module has at least one changeset entry and emit `target/changeset/matrix.json` — a JSON array of `{module, scala-version, version, coordinate, validate-only}` rows (one per Scala version in the module's `crossScalaVersions`, including affected dependents) that you can feed into a CI matrix as `matrix.include`. It fails if any module is missing coverage or if a description still contains the placeholder text.

```json
[
  { "module": "module-a", "scala-version": "2.13.18", "version": "1.2.3-abc-SNAPSHOT", "coordinate": "\"com.example\" %% \"module-a\" % \"1.2.3-abc-SNAPSHOT\"", "validate-only": false },
  { "module": "module-a", "scala-version": "3.3.7",   "version": "1.2.3-abc-SNAPSHOT", "coordinate": "\"com.example\" %% \"module-a\" % \"1.2.3-abc-SNAPSHOT\"", "validate-only": false },
  { "module": "module-b", "scala-version": "3.3.7",   "version": "2.0.0-abc-SNAPSHOT", "coordinate": "\"com.example\" %% \"module-b\" % \"2.0.0-abc-SNAPSHOT\"", "validate-only": true }
]
```

If you need the matrix without requiring changeset entries (e.g. for snapshot publishing or local development), set the `CHANGESET_SKIP_VALIDATION` environment variable to `true`. The command will skip validation and still output the matrix.

The matrix covers every module that transitively depends on a changed module through **any** `dependsOn` scope, plus every module whose own sources changed in **any** source set, plus every module a changeset marks `validate-only`: tests that won't compile against a changed dependency must be caught by CI, not by the next unrelated pull request. Whether a row will also be *released* is a separate question, answered by `changesetAffectedScopes` (default `Seq("compile", "bom")`): rows that this change will never release are marked `"validate-only": true`, so you can build and test them while skipping the snapshot publish (`if: ${{ !matrix.validate-only }}`). A module depending on a changed one only in **test** scope (e.g. `dependsOn(other % Test)`) is therefore validated but neither published nor version-bumped, and the same goes for a module whose own `src/test` changed — which is also why such a change needs no changeset. `changesetAffectedScopes` likewise gates `changesetFromDependencyDiff`: a dependency-update PR only creates a patch bump for a module when at least one updated dep in that module is in one of these scopes — so a `munit:test` bump in a module whose only use of munit is test-scoped does not trigger a release of that module, while a bump of an imported BOM (`sbt-dependencies`' `bom` configuration) does, since it moves the versions that module publishes. Those modules still get a `validate-only` entry, so CI builds and tests them against the new versions without releasing them. Add a scope (`ThisBuild / changesetAffectedScopes += "test"`) to make it release-worthy; use `Seq("*")` to match every scope.

### 3. Publishing snapshots (CI)

On feature branches, the rows from `changesetMatrix validate` feed a CI matrix that publishes each `(module, scala-version)` snapshot on its own runner via `sbt "++<scala-version> <module>/publish"`. The version is the default `<base>-<suffix>-SNAPSHOT` from the module's `VERSION` file (suffix from `SNAPSHOT_SUFFIX` env / sys-prop, else a memoised JVM timestamp); each row carries the resolved Maven `coordinate` so a follow-up job can post a PR comment listing them. Gate the publish step on `if: ${{ !matrix.validate-only }}` — `validate-only` rows are there to be built and tested, and publishing a snapshot of a module whose own sources didn't change would only add a coordinate nobody needs.

### 4. Applying version bumps (CI)

When changesets are merged to main branch, run `changesetVersion`. This:

1. Parses all `.changeset/*.md` files.
2. Cascades bumps through the dependency graph following [early-semver](https://www.scala-lang.org/blog/2021/02/16/preventing-version-conflicts-with-versionscheme.html) rules.
3. Updates each module's `VERSION` file.
4. Prepends new entries to each module's `CHANGELOG.md`.
5. Removes processed changeset files.
6. Writes `target/changeset/version-summary.json` with old/new versions.

Modules that are only bumped through cascade get auto-generated descriptions listing which dependencies changed.

Modules with `changesetAlwaysBump := true` (default `false`) receive at least a **patch** bump whenever `changesetVersion` applies any bump — without needing `dependsOn` edges on the rest of the build. This fits modules that must be re-released with every release train, like a BOM aggregating the build's artifacts: they ride every train but never start one on their own (with no pending changesets, nothing is bumped). Modules already bumped — explicitly or through cascading — keep their existing bump; added entries get an auto-generated description listing the releases they accompany.

### 5. Publishing releases (CI)

After version bumps are committed, `changesetMatrix release` writes `target/changeset/matrix.json` — a JSON array of `{module, scala-version, version}` rows for every (module, Scala version) whose `VERSION` file just changed — which feeds a `publish` matrix that publishes each pair on its own runner in parallel via `sbt "++<scala-version> <module>/publish"`. It also writes `target/changeset/changelogs.json`, a `{"<module>": "<notes>"}` object holding each module's `## <version>` entry, which a downstream `release-tag` job uses as the GitHub release notes.

Both stages write one more file, `target/changeset/matrix-escaped.json`: the same matrix on a single line with every string character written as a `\uXXXX` escape. That is the copy meant for a GitHub Actions job output — see [Handing the matrix to a job output](#handing-the-matrix-to-a-job-output).

The composite [GitHub Action](#github-actions) bundles this flow into `detect` mode, which (when the workflow runs on a Version Packages PR merge) emits `matrix` (for `publish`) alongside its PR-side outputs, and hands the notes to `release-tag` as an artifact.

## GitHub Actions

This repository also provides a composite GitHub Action that orchestrates the full CI workflow. Reference it as `alejandrohdezma/sbt-changesets@AT_VERSION@` and choose a mode depending on the context.

### `detect` mode

Validates that all changed modules have changeset entries and emits exactly two outputs:

- `stage`: a single dispatch classification — `validate` (PR with affected modules), `apply-changesets` (push-to-main with pending changesets), `release` (push-to-main with VERSION bumps to publish), or empty (nothing to do). Use as the single gating condition for every downstream job.
- `matrix`: the work matrix for this run; shape depends on `stage`.
  - `stage == 'validate'`: array of `{module, scala-version, version, coordinate, validate-only}` rows. Each row carries the publish-matrix dimensions, the snapshot Maven coordinate, and whether the module is validated without being published. Plug into `matrix.include` for the validate job; pass to `snapshot-comment` mode.
  - `stage == 'release'`: array of `{module, scala-version, version}` rows carrying the publish-matrix dimensions. Plug into `matrix.include` for the publish job; pass to `release-tag` mode.
  - Otherwise: empty array.

The release notes of those modules do **not** travel through the `matrix` output. They are written to `target/changeset/changelogs.json` and uploaded as the `changeset-changelogs` artifact, which `release-tag` mode downloads on the other side, keeping unbounded prose out of an output that GitHub caps at 1 MB.

Run on every event. The consumer gates each downstream job on `stage` and feeds `matrix` directly into all matrix-style consumers and post-processing modes.

#### Handing the matrix to a job output

The `matrix` output is `\uXXXX`-escaped JSON: `[{"\u006d\u006f\u0064\u0075\u006c\u0065":"\u0061"}]` rather than `[{"module":"a"}]`. Equivalent JSON — `fromJson`, `jq` and `JSON.parse` all decode it to the same values, and `matrix.include` fans out from it exactly as before — but the text carries no literal substring of the values inside it.

That matters because of how the runner treats job outputs: it **silently discards** one whose value contains a masked secret, warning only inside the producing job's log (`Skip output 'matrix' since it may contain secret`). A `publish` job whose matrix came from that output then fails to evaluate `fromJson('')`, so the job is never created and the whole run is marked failed **with no failing job** — nothing to click on, nothing in the summary. A module named after one of the repository's secrets, or a version string matching one, is enough to trigger it. With every character escaped there is nothing left for the masker to match, so the case cannot arise and consumers need no guard job.

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
        uses: alejandrohdezma/sbt-changesets@AT_VERSION@
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
      SNAPSHOT_MODULES: ${{ needs.detect.outputs.matrix }}
    steps:
      - uses: actions/checkout@@v4

      - run: sbt "++${{ matrix.scala-version }} ${{ matrix.module }}/test"

      - if: ${{ !matrix.validate-only }}
        run: sbt "++${{ matrix.scala-version }} ${{ matrix.module }}/publish"

  snapshot-comment:
    needs: [detect, validate]
    if: needs.detect.outputs.stage == 'validate'
    runs-on: ubuntu-latest
    steps:
      - uses: alejandrohdezma/sbt-changesets@AT_VERSION@
        with:
          mode: snapshot-comment
          matrix: ${{ needs.detect.outputs.matrix }}
```

`SNAPSHOT_SUFFIX` (e.g. `${{ github.run_id }}-${{ github.run_attempt }}`) is set on both `detect` and `validate` so the coordinates resolved up-front in `detect` match the artifacts published by the `validate` matrix. Because every matrix cell in a single workflow run shares the same `SNAPSHOT_SUFFIX`, the per-Scala-version publishes that make up one module produce consistent versions. The `coordinate` field carried in each matrix row is rendered from each module's sbt `organization` setting, so per-module org overrides (e.g. `com.permutive.metrics`) are respected without any consumer-side hardcoding. Snapshot publishes are intended for private monorepos only — exposing publishing credentials on PRs in public repositories is a security risk.

`SNAPSHOT_MODULES` (optional, read by `versionFromFile` with the same env / system-property fallback as `SNAPSHOT_SUFFIX`) carries the validate-stage matrix itself — set it to `${{ needs.detect.outputs.matrix }}`, as above. `versionFromFile` reads the `module` of every row to learn which modules are being snapshot-published and suffixes only those; every other module reports the raw `VERSION` content instead. This matters whenever an affected module `dependsOn` an *unchanged* sibling: without it every module is suffixed, so the published POM would reference the sibling at `…-SNAPSHOT` even though no such snapshot was published (it never changed), and resolution fails. With it, the sibling keeps its released version and the coordinate resolves. An empty or absent `SNAPSHOT_MODULES` (e.g. a local `publishLocal`) means every module is suffixed, as before.

#### Customising the `coordinate` per module

The default coordinate is `"org" %% "moduleName" % "version"` for Scala modules and `"org" % "moduleName" % "version"` for Java modules (`crossPaths := false`); it tracks `moduleName`, so a module published under a different artifactId than its project name (via `moduleName := ...`) renders that artifactId. Override the `changesetCoordinate` sbt setting on individual projects when you want the snapshot-comment to render something different — for example, testkit modules that consumers always import in the `test` configuration:

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
      - uses: alejandrohdezma/sbt-changesets@AT_VERSION@
        with:
          mode: snapshot-comment
          matrix: ${{ needs.detect.outputs.matrix }}
```

### `apply-changesets` mode

Bumps `VERSION` files via `changesetVersion` and upserts the **Version Packages** pull request on the `changeset-release/main` branch. Run on push to main when `detect.changesets-count != 0` (i.e. a developer's PR carrying changeset files just merged).

Pass `extra-command` to chain additional sbt commands after `changesetVersion` in the same sbt invocation — useful for regenerating doc files (e.g. `mdoc`) so they're committed as part of the same version-PR commit.

### `release-tag` mode

Loops over the release-stage `matrix` (as produced by `detect`) and creates one GitHub release per distinct module — `module@version` as the tag and title, and the module's entry in the `changeset-changelogs` artifact (downloaded by the action itself) as the notes body. Duplicate rows from cross-built modules are deduped in-script. No sbt, no checkout — pure API calls. Reruns are idempotent: tags that already exist are skipped.

```yaml
  release-tag:
    needs: [detect, publish]
    if: needs.detect.outputs.stage == 'release'
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: alejandrohdezma/sbt-changesets@AT_VERSION@
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

      - uses: alejandrohdezma/sbt-changesets@AT_VERSION@
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
      - uses: alejandrohdezma/sbt-changesets@AT_VERSION@
        with:
          mode: release-tag
          matrix: ${{ needs.detect.outputs.matrix }}
```

`release-tag` `needs: publish` so a publish failure on any matrix cell (e.g. one Scala version fails to compile) blocks all GitHub release creation — preventing half-published modules from getting tagged. Re-running after a fix proceeds cleanly because the action's loop skips tags that already exist; new tags get created as expected.

The same `matrix` output feeds both `publish` (as `matrix.include`, ignoring the extra `version` field) and `release-tag` (as a per-row loop, deduping by module). One source of truth for "what work needs to happen this run".

### Inputs

| Input | Required | Default | Description |
|---|---|---|---|
| `mode` | yes | — | `detect`, `apply-changesets`, `snapshot-comment`, or `release-tag` |
| `github-token` | no | `github.token` | GitHub token for API operations |
| `error-help-url` | no | — | URL shown on changeset validation failure |
| `skip-validation` | no | `false` | Skip changeset validation in `detect` mode while still computing affected modules |
| `extra-command` | no | — | sbt command(s) chained after `changesetVersion` in `apply-changesets` mode (e.g. `documentation/mdoc`) |
| `matrix` | no | — | JSON array produced by `detect`'s `matrix` output. Consumed by `snapshot-comment` (validate-stage rows with `coordinate`) and `release-tag` (release-stage rows with `version`) |
| `pr-number` | no | `github.event.pull_request.number` | PR number to comment on in `snapshot-comment` mode |
| `target` | no | `main` | Branch / commit SHA to target for the GitHub releases created by `release-tag` mode |

### Outputs

| Output | Modes | Description |
|---|---|---|
| `matrix` | `detect` | Stage-dependent work matrix, as [`\uXXXX`-escaped JSON](#handing-the-matrix-to-a-job-output). Validate stage: `{module, scala-version, version, coordinate, validate-only}` rows. Release stage: `{module, scala-version, version}` rows. Empty otherwise |
| `stage` | `detect` | Dispatch classification: `validate`, `apply-changesets`, `release`, or empty. Gate every downstream job on this |
