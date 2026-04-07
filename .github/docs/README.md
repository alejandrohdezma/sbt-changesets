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
| `changesetStatus` | Validate all changed modules have changeset entries |
| `changesetAffected` | Validate + output affected modules as JSON |
| `changesetVersion` | Apply version bumps with cascade through dependency graph |
| `changesetRelease` | Output release info for modules with `.publish` markers |
| `generatePublishMarkers` | Create `.publish` markers for changed VERSION files |
| `publishSnapshot` | Publish snapshots for changed modules |
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

On pull requests, run `changesetStatus` to ensure every modified module has at least one changeset entry. It fails if any module is missing coverage or if a description still contains the placeholder text.

For CI matrix builds, `changesetAffected` does the same validation and writes `target/changeset/affected.json` — a JSON array of affected module names (including transitive dependents) that you can feed into a CI like GitHub Actions matrix.

If you need the list of affected modules without requiring changeset entries (e.g. for snapshot publishing or local development), set the `CHANGESET_SKIP_VALIDATION` environment variable to `true`. The command will skip validation and still output all affected modules.

### 3. Publishing snapshots (CI)

On feature branches, `publishSnapshot` detects changed modules and their transitive dependents, creates `.publish` markers, and publishes snapshot artifacts. It writes `target/changeset/snapshot-coordinates.json` with the published Maven coordinates.

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

After version bumps are committed:

1. `generatePublishMarkers` detects which modules had their `VERSION` file changed in the last commit and creates `.publish` marker files for them.
2. `+publish` publishes only the modules with `.publish` markers.
3. `changesetRelease` writes `target/changeset/release.json` with tag names and changelog bodies for creating releases.

## GitHub Actions

This repository also provides a composite GitHub Action that orchestrates the full CI workflow. Reference it as `alejandrohdezma/sbt-changesets@AT_VERSION@` and choose a mode depending on the context.

### `detect` mode

Validates that all changed modules have changeset entries and outputs the list of affected modules (including transitive dependents). Use this on pull requests to gate CI and build a dynamic test matrix.

```yaml
# .github/workflows/ci.yaml
on:
  pull_request:

jobs:
  detect:
    runs-on: ubuntu-latest
    outputs:
      affected: ${{ steps.changesets.outputs.affected }}
    steps:
      - uses: actions/checkout@@v4

      - id: changesets
        uses: alejandrohdezma/sbt-changesets@AT_VERSION@
        with:
          mode: detect
          error-help-url: https://your-repo/docs/versioning  # shown on validation failure

  test:
    needs: detect
    if: needs.detect.outputs.affected != '[]'
    runs-on: ubuntu-latest
    strategy:
      matrix:
        module: ${{ fromJson(needs.detect.outputs.affected) }}
    steps:
      - uses: actions/checkout@@v4
      - run: sbt "+${{ matrix.module }}/test"
```

### `snapshot` mode

Publishes snapshot artifacts for changed modules and posts a PR comment with the Maven coordinates. Use this on pull requests after tests pass so reviewers can try the changes.

```yaml
  snapshot:
    needs: [detect, test]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@@v4
      - uses: alejandrohdezma/sbt-changesets@AT_VERSION@
        with:
          mode: snapshot
```

> **Note:** This is intended for private monorepos only. Exposing publishing credentials on pull requests in public repositories is a security risk.

### `release` mode

Handles the main branch workflow. It checks for pending changeset files and takes one of two paths:

- **Changesets present:** runs `changesetVersion`, commits the version bumps, and creates (or updates) a "Version Packages" PR on the `changeset-release/main` branch. Outputs `result=version-pr` and `branch=changeset-release/main`.
- **No changesets** (i.e. a version PR was just merged): publishes artifacts, creates GitHub releases with changelog bodies. Outputs `result=published`.

```yaml
# .github/workflows/release.yaml
on:
  push:
    branches: [main]

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@@v4

      - id: changesets
        uses: alejandrohdezma/sbt-changesets@AT_VERSION@
        with:
          mode: release

      # Optional: run repo-specific steps when a version PR is created
      - if: steps.changesets.outputs.result == 'version-pr'
        run: sbt mdoc
```

### Inputs

| Input | Required | Default | Description |
|---|---|---|---|
| `mode` | yes | — | `detect`, `snapshot`, or `release` |
| `pr-number` | no | `github.event.pull_request.number` | PR number for snapshot comments |
| `github-token` | no | `github.token` | GitHub token for API operations |
| `error-help-url` | no | — | URL shown on changeset validation failure |
| `skip-validation` | no | `false` | Skip changeset validation in `detect` mode while still computing affected modules |

### Outputs

| Output | Modes | Description |
|---|---|---|
| `affected` | `detect` | JSON array of affected module names |
| `result` | `release` | `version-pr`, `published`, or `no-changes` |
| `branch` | `release` | Release branch name (when `result` is `version-pr`) |
