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
| `changesetAffected` | Validate + output affected modules as JSON |
| `changesetVersion` | Apply version bumps with cascade through dependency graph |
| `extractLatestChangelog <module>` | Extract a single module's CHANGELOG body for its current `VERSION` |
| `changesetMatrix` | Output JSON array of module names whose VERSION changed in last commit |
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

On pull requests, run `changesetAffected` to ensure every modified module has at least one changeset entry and emit `target/changeset/affected.json` — a JSON array of affected module names (including transitive dependents) that you can feed into a CI matrix. It fails if any module is missing coverage or if a description still contains the placeholder text.

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

After version bumps are committed, `changesetMatrix` writes `target/changeset/matrix.json` — a JSON string array of changed module names — which feeds a CI matrix that publishes each module on its own runner in parallel. Each cell `touch`es the module's `.publish` marker (so `Settings.skipPublish` lets it through), runs `sbt "+<module>/publish"`, then `extractLatestChangelog <module>` writes the relevant CHANGELOG body that the cell uses as the GitHub release notes.

The composite [GitHub Action](#github-actions) bundles this flow as the `prepare-release` mode (the consumer just adds the matrix job).

## GitHub Actions

This repository also provides a composite GitHub Action that orchestrates the full CI workflow. Reference it as `alejandrohdezma/sbt-changesets@AT_VERSION@` and choose a mode depending on the context.

### `detect` mode

Validates that all changed modules have changeset entries and outputs the list of affected modules (including transitive dependents). Use this on pull requests to gate CI and build a dynamic test matrix.

Run on both pull requests and push to main: on PR it gates the test matrix; on push it lets the workflow dispatch between `apply-changesets` and `prepare-release` (see below) via the `changesets-count` output.

```yaml
# .github/workflows/ci.yaml
on:
  pull_request:
  push:
    branches: [main]

jobs:
  detect:
    runs-on: ubuntu-latest
    outputs:
      affected: ${{ steps.changesets.outputs.affected }}
      changesets-count: ${{ steps.changesets.outputs.changesets-count }}
    steps:
      - uses: actions/checkout@@v4
        with: { fetch-depth: 0 }

      - id: changesets
        uses: alejandrohdezma/sbt-changesets@AT_VERSION@
        with:
          mode: detect
          error-help-url: https://your-repo/docs/versioning  # shown on validation failure

  test:
    needs: detect
    if: github.event_name == 'pull_request' && needs.detect.outputs.affected != '[]'
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

### `apply-changesets` mode

Bumps `VERSION` files via `changesetVersion` and upserts the **Version Packages** pull request on the `changeset-release/main` branch. Run on push to main when `detect.changesets-count != 0` (i.e. a developer's PR carrying changeset files just merged).

Pass `extra-command` to chain additional sbt commands after `changesetVersion` in the same sbt invocation — useful for regenerating doc files (e.g. `mdoc`) so they're committed as part of the same version-PR commit.

### `prepare-release` mode

Computes the per-module publish matrix via `changesetMatrix` and emits the JSON to the action's `matrix` output. Run on push to main when `detect.changesets-count == 0` (i.e. the Version Packages PR just merged).

### Putting it together: release workflow

The two main-branch modes are dispatched by `detect.changesets-count`. The matrix publish job fans out one runner per affected module.

```yaml
# .github/workflows/ci.yaml (continued)
jobs:
  # ...detect, test, snapshot from above...

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

  prepare-release:
    needs: detect
    if: github.event_name == 'push' && needs.detect.outputs.changesets-count == '0'
    runs-on: ubuntu-latest
    outputs:
      matrix: ${{ steps.changesets.outputs.matrix }}
    steps:
      - uses: actions/checkout@@v4
        with: { fetch-depth: 0 }
      - id: changesets
        uses: alejandrohdezma/sbt-changesets@AT_VERSION@
        with:
          mode: prepare-release

  release:
    needs: prepare-release
    if: needs.prepare-release.outputs.matrix != '[]'
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      max-parallel: 16
      matrix:
        module: ${{ fromJson(needs.prepare-release.outputs.matrix) }}
    env:
      RELEASE: "true"
    steps:
      - uses: actions/checkout@@v4

      - name: Touch .publish marker
        run: touch modules/${{ matrix.module }}/.publish

      - name: Publish and extract changelog
        run: sbt "+${{ matrix.module }}/publish; extractLatestChangelog ${{ matrix.module }}"

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

### Inputs

| Input | Required | Default | Description |
|---|---|---|---|
| `mode` | yes | — | `detect`, `snapshot`, `apply-changesets`, or `prepare-release` |
| `pr-number` | no | `github.event.pull_request.number` | PR number for snapshot comments |
| `github-token` | no | `github.token` | GitHub token for API operations |
| `error-help-url` | no | — | URL shown on changeset validation failure |
| `skip-validation` | no | `false` | Skip changeset validation in `detect` mode while still computing affected modules |
| `extra-command` | no | — | sbt command(s) chained after `changesetVersion` in `apply-changesets` mode (e.g. `documentation/mdoc`) |

### Outputs

| Output | Modes | Description |
|---|---|---|
| `affected` | `detect` | JSON array of affected module names |
| `changesets-count` | `detect` | Number of pending `.changeset/*.md` files |
| `matrix` | `prepare-release` | JSON string array of module names to publish |
