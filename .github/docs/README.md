@DESCRIPTION@

`sbt-changesets` provides a better (and easier) way to manage libraries. Specially useful for company-internal monorepos, bringing changeset-based versioning to Scala multi-module builds. Developers describe their changes in small markdown files, and the plugin takes care of version bumps, dependency cascade, and changelog generation.

> `sbt-changesets` is an SBT-opinionated take on [changesets](https://github.com/changesets/changesets).

**Looking for the GitHub Action?** Jump to [GitHub Actions](#github-actions).

## Installation

Add the plugin to your `project/plugins.sbt`:

```sbt
addSbtPlugin("@ORGANIZATION@" % "@NAME@" % "@VERSION@")
```

This plugin depends on [sbt-modules](https://github.com/alejandrohdezma/sbt-modules), which is pulled in automatically. It expects modules to be defined using `module` instead of `project` in your `build.sbt`, with source code living under `modules/<module-name>/`. See the [sbt-modules documentation](https://github.com/alejandrohdezma/sbt-modules) for details.

## How it works

Each module in your build has a `VERSION` file and a `CHANGELOG.md`. Instead of bumping versions manually, developers create small `.changeset/*.md` files describing their changes. When it's time to release, the plugin reads those files, bumps versions (cascading through the dependency graph), updates changelogs, and cleans up.

<details><summary><b>All available commands</b></summary><br/>

| Command | Description |
|---|---|
| `changesetAdd <bump> <description>` | Create a changeset for changed modules |
| `changesetAffected` | Validate + output affected `(module, scala-version)` rows as JSON |
| `changesetVersion` | Apply version bumps with cascade through dependency graph |
| `extractLatestChangelog <module>` | Extract a single module's CHANGELOG body for its current `VERSION` |
| `extractSnapshotCoordinates <module>...` | Output JSON of resolved snapshot coordinates for the given modules |
| `changesetMatrix` | Output JSON array of `(module, scala-version)` rows whose VERSION changed in last commit |
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

On pull requests, run `changesetAffected` to ensure every modified module has at least one changeset entry and emit `target/changeset/affected.json` — a JSON array of `{module, scala-version}` rows (one per Scala version in the module's `crossScalaVersions`, including transitive dependents) that you can feed into a CI matrix as `matrix.include`. It fails if any module is missing coverage or if a description still contains the placeholder text.

```json
[
  { "module": "module-a", "scala-version": "2.13.18" },
  { "module": "module-a", "scala-version": "3.3.7" },
  { "module": "module-b", "scala-version": "3.3.7" }
]
```

If you need the affected rows without requiring changeset entries (e.g. for snapshot publishing or local development), set the `CHANGESET_SKIP_VALIDATION` environment variable to `true`. The command will skip validation and still output all affected rows.

### 3. Publishing snapshots (CI)

On feature branches, the affected rows from `changesetAffected` feed a CI matrix that publishes each `(module, scala-version)` snapshot on its own runner via `sbt "++<scala-version> <module>/publish"`. The version is the default `<base>-<suffix>-SNAPSHOT` from the module's `VERSION` file (suffix from `SNAPSHOT_SUFFIX` env / sys-prop, else a memoised JVM timestamp).

A follow-up job runs `extractSnapshotCoordinates <m1> <m2> ...` to resolve each module's `organization` and snapshot version, write `target/changeset/snapshot-coordinates.json`, and post a PR comment listing the Maven coordinates.

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

This repository also provides a composite GitHub Action that orchestrates the full CI workflow. Reference it as `alejandrohdezma/sbt-changesets@AT_VERSION@` and choose a mode depending on the context.

### `detect` mode

Validates that all changed modules have changeset entries and outputs everything the rest of the workflow needs:

- `affected`: JSON array of `{module, scala-version}` rows (changed modules and their transitive dependents, expanded over each module's `crossScalaVersions`). Plug directly into `matrix.include` for the PR validate / snapshot publish job.
- `affected-modules`: distinct module names derived from `affected`. Use for empty-set gating and per-module steps.
- `matrix`: JSON array of `{module, scala-version}` rows whose `VERSION` file changed in the last commit. Plug into `matrix.include` for the publish job that runs after a Version Packages PR merge.
- `release-modules`: distinct module names derived from `matrix`. Feed into the per-module `release-tag` job that creates GitHub releases.
- `changesets-count`: number of pending `.changeset/*.md` files. Use on push-to-main to dispatch between `apply-changesets` (count != 0) and the release pipeline (count == 0).
- `coordinates`: resolved Maven coordinates for each affected module's snapshot (one per module, not per Scala version — `%%` lets the consumer pick the suffix).

Run on every event. The consumer routes the relevant outputs into the right downstream jobs based on `event_name` and `changesets-count`.

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
      affected: ${{ steps.changesets.outputs.affected }}
      matrix: ${{ steps.changesets.outputs.matrix }}
      release-modules: ${{ steps.changesets.outputs.release-modules }}
      changesets-count: ${{ steps.changesets.outputs.changesets-count }}
      coordinates: ${{ steps.changesets.outputs.coordinates }}
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
    if: github.event_name == 'pull_request' && needs.detect.outputs.affected != '[]'
    runs-on: ubuntu-latest
    strategy:
      matrix:
        include: ${{ fromJson(needs.detect.outputs.affected) }}
    env:
      SNAPSHOT_SUFFIX: ${{ github.run_id }}-${{ github.run_attempt }}
    steps:
      - uses: actions/checkout@@v4

      - run: sbt "++${{ matrix.scala-version }} ${{ matrix.module }}/test"

      - run: sbt "++${{ matrix.scala-version }} ${{ matrix.module }}/publish"

  snapshot-comment:
    needs: [detect, validate]
    if: github.event_name == 'pull_request' && needs.detect.outputs.affected != '[]'
    runs-on: ubuntu-latest
    steps:
      - uses: alejandrohdezma/sbt-changesets@AT_VERSION@
        with:
          mode: snapshot-comment
          coordinates: ${{ needs.detect.outputs.coordinates }}
```

`SNAPSHOT_SUFFIX` (e.g. `${{ github.run_id }}-${{ github.run_attempt }}`) is set on both `detect` and `validate` so the coordinates resolved up-front in `detect` match the artifacts published by the `validate` matrix. Because every matrix cell in a single workflow run shares the same `SNAPSHOT_SUFFIX`, the per-Scala-version publishes that make up one module produce consistent versions. `extractSnapshotCoordinates` (run by the action in `detect` mode) reads each module's `organization` from the build, so per-module org overrides (e.g. `com.permutive.metrics`) are respected without any consumer-side hardcoding. Snapshot publishes are intended for private monorepos only — exposing publishing credentials on PRs in public repositories is a security risk.

### `snapshot-comment` mode

Posts (or edits) a PR comment listing snapshot coordinates produced by a matrix snapshot publish. Pass the JSON output of `extractSnapshotCoordinates` (typically computed once in the `detect` job and surfaced via `needs.detect.outputs.coordinates`).

```yaml
  snapshot-comment:
    needs: [detect, validate]
    runs-on: ubuntu-latest
    steps:
      - uses: alejandrohdezma/sbt-changesets@AT_VERSION@
        with:
          mode: snapshot-comment
          coordinates: ${{ needs.detect.outputs.coordinates }}
```

### `apply-changesets` mode

Bumps `VERSION` files via `changesetVersion` and upserts the **Version Packages** pull request on the `changeset-release/main` branch. Run on push to main when `detect.changesets-count != 0` (i.e. a developer's PR carrying changeset files just merged).

Pass `extra-command` to chain additional sbt commands after `changesetVersion` in the same sbt invocation — useful for regenerating doc files (e.g. `mdoc`) so they're committed as part of the same version-PR commit.

### Putting it together: release workflow

The push-to-main pipeline is dispatched by `detect.changesets-count`: when there are pending changeset files, `apply-changesets` runs to upsert the Version Packages PR; once that PR is merged, the same `detect` job emits `matrix` / `release-modules`, and `publish` fans out one runner per `(module, Scala version)` followed by `release-tag` creating one GitHub release per module.

```yaml
# .github/workflows/ci.yaml (continued)
jobs:
  # ...detect, validate, snapshot-comment from above...

  apply-changesets:
    needs: detect
    if: github.event_name == 'push' && needs.detect.outputs.changesets-count != '0'
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
    if: github.event_name == 'push' && needs.detect.outputs.changesets-count == '0' && needs.detect.outputs.matrix != '[]'
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
    if: github.event_name == 'push' && needs.detect.outputs.changesets-count == '0' && needs.detect.outputs.release-modules != '[]'
    runs-on: ubuntu-latest
    permissions:
      contents: write
    strategy:
      fail-fast: false
      matrix:
        module: ${{ fromJson(needs.detect.outputs.release-modules) }}
    steps:
      - uses: actions/checkout@@v4

      - name: Extract changelog
        run: sbt "extractLatestChangelog ${{ matrix.module }}"

      - name: Create GitHub release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          MODULE: ${{ matrix.module }}
        run: |
          TAG="$MODULE@$(cat "modules/$MODULE/VERSION")"
          gh release create "$TAG" \
            --title "$TAG" \
            --notes-file target/changeset/changelog.md \
            --target main
```

`release-tag` `needs: publish` so a publish failure on any matrix cell (e.g. one Scala version fails to compile) blocks all GitHub release creation — preventing half-published modules from getting tagged. Re-running after a fix proceeds cleanly because re-publishing the same `RELEASE` version to a Maven repo is rejected, surfacing the partial-state.

### Inputs

| Input | Required | Default | Description |
|---|---|---|---|
| `mode` | yes | — | `detect`, `apply-changesets`, or `snapshot-comment` |
| `github-token` | no | `github.token` | GitHub token for API operations |
| `error-help-url` | no | — | URL shown on changeset validation failure |
| `skip-validation` | no | `false` | Skip changeset validation in `detect` mode while still computing affected modules |
| `extra-command` | no | — | sbt command(s) chained after `changesetVersion` in `apply-changesets` mode (e.g. `documentation/mdoc`) |
| `coordinates` | no | — | JSON array of snapshot coordinates consumed by `snapshot-comment` mode |
| `pr-number` | no | `github.event.pull_request.number` | PR number to comment on in `snapshot-comment` mode |

### Outputs

| Output | Modes | Description |
|---|---|---|
| `affected` | `detect` | JSON array of `{module, scala-version}` rows for PR validation. Consume as `matrix.include` |
| `affected-modules` | `detect` | JSON array of distinct affected module names |
| `matrix` | `detect` | JSON array of `{module, scala-version}` rows to publish after a Version Packages PR merge. Consume as `matrix.include` |
| `release-modules` | `detect` | JSON array of distinct module names being released |
| `changesets-count` | `detect` | Number of pending `.changeset/*.md` files |
| `coordinates` | `detect` | JSON array of `{module, version, coordinate}` snapshot coordinates |
