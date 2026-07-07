/*
 * Copyright 2026 Alejandro Hernández <https://github.com/alejandrohdezma>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alejandrohdezma.sbt.changesets

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.jdk.CollectionConverters._
import scala.sys.process._
import scala.util.Try

import sbt.Keys._
import sbt._

import com.alejandrohdezma.sbt.changesets.ChangesetPlugin.autoImport._
import com.alejandrohdezma.sbt.changesets.Json._
import com.alejandrohdezma.sbt.modules.ModuleDependency
import com.alejandrohdezma.sbt.modules.ModuleMetadata
import com.alejandrohdezma.sbt.modules.ModulesPlugin.autoImport._
import com.typesafe.config.ConfigFactory

/** Command implementations for the changeset-based versioning workflow.
  *
  * Provides SBT commands for:
  *   - Discovering modules and their internal dependency graph from the SBT build structure
  *   - Detecting which modules have changed files (via `git diff`)
  *   - Applying changeset-driven version bumps with cascading through the dependency graph
  *
  * Commands that produce output write JSON files to `target/changeset/`.
  */
object Commands {

  // ─── Commands ─────────────────────────────────

  val changesetConfig: Command = Command.command(
    "changesetConfig",
    "Outputs module names, versions, and dependency graph as JSON.",
    """|Writes to target/changeset/config.json with the following format:
       |
       |  {
       |    "module-name": {
       |      "version": "1.0.0",
       |      "dependencies": [{"name": "dep-1", "configuration": "compile"}],
       |      "transitive_dependencies": ["dep-0", "dep-1"],
       |      "dependents": [{"name": "dep-2", "configuration": "test"}],
       |      "transitive_dependents": ["dep-2", "dep-3"]
       |    },
       |    ...
       |  }
       |
       |Direct `dependencies` and `dependents` carry each `dependsOn` edge's `configuration`
       |(e.g. `"compile"`, `"test"`, `"compile->compile;test->test"`). The `transitive_*`
       |sets are name-only because a transitive path can mix scopes.
       |
       |Module list and dependency graph are derived from the SBT build structure.""".stripMargin
  ) { state =>
    val modules = ModuleMetadata.from(state)

    def asObject(d: ModuleDependency): Json = Json.obj("name" := d.name, "configuration" := d.configuration)

    def asJson(metadata: ModuleMetadata): Json = Json.obj(
      "version"                 := metadata.version,
      "dependencies"            -> Json.arr(metadata.dependencies.toList.sortBy(_.name).map(asObject) *),
      "transitive_dependencies" -> Json.arr(metadata.transitiveDependencies.toList.sorted *),
      "dependents"              -> Json.arr(metadata.dependents.toList.sortBy(_.name).map(asObject) *),
      "transitive_dependents"   -> Json.arr(metadata.transitiveDependents.toList.sorted *)
    )

    val json = Json.obj(modules.toSeq.map { case (name, metadata) => name -> asJson(metadata) }.sortBy(_._1) *)

    val file = Project.extract(state).get(ThisBuild / baseDirectory) / "target" / "changeset" / "config.json"

    IO.write(file, json.show())

    state.log.info(s"Wrote changeset config to ${Colors.path(file)}")

    state
  }

  val changesetVersion: Command = Command.command(
    "changesetVersion",
    "Bumps VERSION files based on changeset files with cascading bumps.",
    """|Parses .changeset/*.md files and for each entry:
       |
       |  1. Applies the specified bump (major/minor/patch) to the module's VERSION file
       |  2. Walks the dependency graph to cascade bumps to transitive dependents
       |  3. Updates each module's CHANGELOG.md with a new version entry
       |  4. Removes processed changeset files
       |  5. Writes a version summary to target/changeset/version-summary.json
       |
       |Cascading follows early-semver: for 0.x, minor is breaking; for 1.x+,
       |major is breaking. Dependents receive at least the same bump level.
       |
       |Modules with `changesetAlwaysBump := true` receive at least a patch
       |bump whenever any bump is applied, without needing `dependsOn` edges.""".stripMargin
  ) { state =>
    val extracted      = Project.extract(state)
    val base           = extracted.get(ThisBuild / baseDirectory)
    val affectedScopes = extracted.get(ThisBuild / changesetAffectedScopes).toSet

    val alwaysBumped = extracted.structure.allProjectRefs
      .filter(ref => extracted.get(ref / packageIsModule))
      .filter(ref => extracted.get(ref / changesetAlwaysBump))
      .map(ref => extracted.get(ref / Keys.name))

    val modules     = ModuleMetadata.from(state)
    val moduleNames = extractModuleNames(state)

    val changesets =
      parseAndValidate(base / ".changeset", moduleNames, state.log)
        .cascadeExpand(modules, affectedScopes)
        .alwaysBump(alwaysBumped, modules)

    // Apply bumps and collect version summary
    val summary = changesets.value.toList.sortBy(_._1).map { case (name, entry) =>
      val versionFile = base / "modules" / name / "VERSION"
      val current     = IO.read(versionFile).trim
      val newVersion  = entry.bump(current)
      IO.write(versionFile, newVersion + "\n")
      state.log.info(s"${Colors.module(name)}: ${Colors.version(current)} -> ${Colors.version(newVersion)} (${Colors.bump(entry.bump.toString)})")

      // Update CHANGELOG
      val changelogFile = base / "modules" / name / "CHANGELOG.md"
      val existing      = if (changelogFile.exists()) IO.read(changelogFile) else ""

      val changelogEntry = s"## $newVersion\n\n${entry.description}\n\n"
      IO.write(changelogFile, changelogEntry + existing)

      Json.obj("module" := name, "old_version" := current, "new_version" := newVersion)
    }

    // Write version summary
    val summaryFile = base / "target" / "changeset" / "version-summary.json"
    IO.write(summaryFile, Json.arr(summary *).show())
    state.log.info(s"Wrote version summary to ${Colors.path(summaryFile)}")

    // Remove processed changeset files
    Changesets.clean(base / ".changeset")

    state.log.info(s"Processed ${changesets.size} changeset(s), bumped ${changesets.size} module(s).")

    state
  }

  val changesetMatrix: Command = Command.single(
    "changesetMatrix",
    "changesetMatrix <validate|release>" -> "Outputs the stage-appropriate work matrix as JSON.",
    """|For `validate`: validates that every changed module has a changeset entry,
       |computes affected modules (changed + transitive dependents), expands each
       |by `crossScalaVersions`, and attaches snapshot `{version, coordinate}` per
       |row. Set CHANGESET_SKIP_VALIDATION=true to skip the entry check while
       |still computing affected.
       |
       |For `release`: detects modules whose VERSION file changed in the last
       |commit, expands by `crossScalaVersions`, and attaches release
       |`{version, changelog}` per row.
       |
       |Either way, writes the result to target/changeset/matrix.json. Suitable
       |as input for a GitHub Actions `matrix.include` strategy.""".stripMargin
  ) {
    case (state, "validate") =>
      computeValidateMatrix(state)

    case (state, "release") =>
      computeReleaseMatrix(state)

    case (state, other) =>
      val msg = s"Unknown stage '$other'; expected 'validate' or 'release'"
      state.log.error(msg)
      throw new MessageOnlyException(msg)
  }

  val changesetAdd: Command = Command.args(
    "changesetAdd",
    "<bump> <description...>"
  ) { (state, args) =>
    args match {
      case VersionBump(bump) :: descriptionWords if descriptionWords.nonEmpty =>
        val base    = Project.extract(state).get(ThisBuild / baseDirectory)
        val changed = changedModules(state)

        if (changed.isEmpty) throw new MessageOnlyException("No modules have changed. Nothing to add.")

        val frontmatter = changed.toList.sorted.map(name => s""""$name": $bump""").mkString("\n")
        val content     = s"---\n$frontmatter\n---\n\n${Changesets.TemplateDescription}\n"

        val filename = descriptionWords
          .mkString("-")
          .toLowerCase
          .replaceAll("[^a-z0-9-]", "-")
          .replaceAll("-+", "-")
          .stripPrefix("-")
          .stripSuffix("-")
        val file = base / ".changeset" / s"$filename.md"

        IO.write(file, content)

        state.log.info(s"Created changeset: ${Colors.path(file.relativeTo(base).getOrElse(file))}")
        changed.toList.sorted.foreach(name =>
          state.log.info(s"  ${Colors.module(name)}: ${Colors.bump(bump.toString)}")
        )
        state.log.info(
          s"Update the description in ${Colors.path(file.relativeTo(base).getOrElse(file))} before merging."
        )

        state

      case _ =>
        throw new MessageOnlyException("Usage: changesetAdd <patch|minor|major> <description...>")
    }
  }

  val changesetFromDependencyDiff: Command = Command.command(
    "changesetFromDependencyDiff",
    "Creates a changeset from a dependency diff file generated by `sbt-dependencies`.",
    """|Reads target/sbt-dependencies/.sbt-dependency-diff (HOCON) and creates
       |a .changeset/dependency-updates.md file with patch bumps for every
       |module whose diff contains at least one entry in one of the configured
       |`changesetAffectedScopes` (default: `Seq("compile")`). Each entry's
       |`configuration` (defaulting to `"compile"` if absent — for diffs produced
       |by older versions of `sbt-dependencies`) is matched against the setting via
       |`Changesets.affects`, so e.g. test-only dependency updates do not produce
       |a module bump under the default configuration.
       |
       |Modules not recognized by the current build are silently skipped.""".stripMargin
  ) { state =>
    val extracted              = Project.extract(state)
    val base                   = extracted.get(ThisBuild / baseDirectory)
    val affectedScopes         = extracted.get(ThisBuild / changesetAffectedScopes).toSet
    val `dependencies.conf`    = base / "project" / "dependencies.conf"
    val `.sbt-dependency-diff` = base / "target" / "sbt-dependencies" / ".sbt-dependency-diff"

    if (!`dependencies.conf`.exists()) {
      state.log.error(s"This project does not use sbt-dependencies (${Colors.path(`dependencies.conf`)} not found).")

      throw new MessageOnlyException(s"${`dependencies.conf`} not found.")
    }

    if (! `.sbt-dependency-diff`.exists()) {
      state.log.error {
        s"Dependency diff not found at ${Colors.path(`.sbt-dependency-diff`)}. Run updateAllDependencies first."
      }

      throw new MessageOnlyException("Dependency diff not found.")
    }

    val moduleNames = extractModuleNames(state)

    val config   = ConfigFactory.parseFile(`.sbt-dependency-diff`)
    val projects = config.root().keySet().asScala.toSet

    def hasAffectingChange(projectName: String): Boolean = {
      val projectCfg = config.getConfig(projectName)
      val entries    = List("added", "updated", "removed").flatMap { key =>
        if (projectCfg.hasPath(key)) projectCfg.getConfigList(key).asScala else Nil
      }
      entries.exists { entry =>
        val cfg = if (entry.hasPath("configuration")) entry.getString("configuration") else "compile"
        Changesets.affects(cfg, affectedScopes)
      }
    }

    val affected = projects.intersect(moduleNames).filter(hasAffectingChange)

    if (affected.isEmpty) {
      state.log.info("No modules affected by dependency changes.")
      state
    } else {
      val frontmatter = affected.toList.sorted.map(name => s""""$name": patch""").mkString("\n")
      val content     = s"---\n$frontmatter\n---\n\nUpdate dependencies.\n"

      val baseFile = base / ".changeset" / "dependency-updates.md"

      val file =
        if (!baseFile.exists()) baseFile
        else {
          val suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yy-MM-dd-HH-mm"))

          base / ".changeset" / s"dependency-updates-$suffix.md"
        }

      IO.write(file, content)

      state.log.info(s"Created changeset: ${Colors.path(file.relativeTo(base).getOrElse(file))}")

      affected.toList.sorted.foreach(name => state.log.info(s"  ${Colors.module(name)}: ${Colors.bump("patch")}"))

      state
    }
  }

  val all: Seq[Command] =
    Seq(changesetConfig, changesetVersion, changesetMatrix, changesetAdd, changesetFromDependencyDiff)

  // ─── Internal helpers ─────────────────────────

  /** Validates changesets, computes affected modules (changed + transitive dependents) and emits a
    * `[{module, scala-version, version, coordinate}, ...]` matrix to `target/changeset/matrix.json`.
    *
    * The `version` and `coordinate` come from sbt's `version` and `organization` settings — i.e. the snapshot version
    * including any `SNAPSHOT_SUFFIX`.
    */
  private def computeValidateMatrix(state: State): State = {
    val extracted      = Project.extract(state)
    val base           = extracted.get(ThisBuild / baseDirectory)
    val affectedScopes = extracted.get(ThisBuild / changesetAffectedScopes).toSet
    val modules        = ModuleMetadata.from(state)
    val refs           = moduleRefs(state)

    val changed    = changedModules(state)
    val changesets = parseAndValidate(base / ".changeset", refs.keySet, state.log)

    val skipValidation = sys.env.get("CHANGESET_SKIP_VALIDATION").contains("true")

    if (changed.nonEmpty && skipValidation)
      state.log.warn("CHANGESET_SKIP_VALIDATION is set. Skipping changeset validation.")
    else if (changed.nonEmpty) {
      val missing = changed.diff(changesets.keys)

      if (missing.nonEmpty) {
        missing.toList.sorted.foreach(m => state.log.error(s"Missing changeset entry for: ${Colors.module(m)}"))
        throw new MessageOnlyException(s"Missing changeset entries for ${missing.size} modified module(s).")
      } else state.log.info(s"Changeset entries found for: ${changed.toList.sorted.map(Colors.module).mkString(", ")}")

      changesets.validateDescriptions match {
        case Left(errors) =>
          errors.foreach(e => state.log.error(e))
          throw new MessageOnlyException(s"${errors.size} changeset(s) still have the template description.")
        case Right(_) => ()
      }
    }

    val directDependents = modules.map { case (name, metadata) =>
      name -> metadata.dependents.filter(Changesets.affects(_, affectedScopes)).map(_.name)
    }

    val seed     = changed ++ changesets.keys
    val affected = Changesets.affectedClosure(seed, directDependents)

    val rows = affected.toList.sorted.flatMap { name =>
      refs.get(name).toList.flatMap { ref =>
        extracted.get(ref / Keys.crossScalaVersions).sorted.map { sv =>
          Json.obj(
            "module"        := name,
            "scala-version" := sv,
            "version"       := extracted.get(ref / version),
            "coordinate"    := extracted.get(ref / changesetCoordinate)
          )
        }
      }
    }

    val json = Json.arr(rows *)
    val file = base / "target" / "changeset" / "matrix.json"
    IO.write(file, json.show())

    state.log.info(s"Wrote validate-stage matrix to ${Colors.path(file)}")
    state
  }

  /** Detects modules whose VERSION file changed in the last commit and emits a
    * `[{module, scala-version, version, changelog}, ...]` matrix to `target/changeset/matrix.json`.
    *
    * The `version` is read directly from `modules/<module>/VERSION` and the `changelog` is the matching `## <version>`
    * entry from `modules/<module>/CHANGELOG.md`.
    */
  private def computeReleaseMatrix(state: State): State = {
    val extracted = Project.extract(state)
    val base      = extracted.get(ThisBuild / baseDirectory)
    val refs      = moduleRefs(state)

    val diff = Process(Seq("git", "diff", "--name-only", "HEAD~1", "--", "modules/*/VERSION"), base).!!.trim

    val changed = diff.linesIterator
      .filter(_.startsWith("modules/"))
      .flatMap(_.stripPrefix("modules/").split("/").headOption)
      .toSet
      .intersect(refs.keySet)

    val rows = changed.toList.sorted.flatMap { name =>
      val dir       = base / "modules" / name
      val ver       = IO.read(dir / "VERSION").trim
      val changelog = Changesets.extractChangelogEntry(dir / "CHANGELOG.md", ver)

      refs.get(name).toList.flatMap { ref =>
        extracted.get(ref / Keys.crossScalaVersions).sorted.map { sv =>
          Json.obj(
            "module"        := name,
            "scala-version" := sv,
            "version"       := ver,
            "changelog"     := changelog
          )
        }
      }
    }

    val json = Json.arr(rows *)
    val file = base / "target" / "changeset" / "matrix.json"
    IO.write(file, json.show())

    state.log.info(s"Wrote release-stage matrix to ${Colors.path(file)}")
    state
  }

  /** Returns a map from module name to its `ProjectRef`, filtered to projects with `packageIsModule := true`. */
  private def moduleRefs(state: State): Map[String, ProjectRef] = {
    val extracted = Project.extract(state)

    extracted.structure.allProjectRefs
      .filter(ref => extracted.get(ref / packageIsModule))
      .map(ref => extracted.get(ref / Keys.name) -> ref)
      .toMap
  }

  /** Detects which modules have changed files compared to the base branch.
    *
    * Combines three sources of changes so the result is accurate on feature branches, on the base branch, and for
    * uncommitted work:
    *   - '''Committed''': `git diff <baseBranch>...HEAD` (falls back gracefully if the base branch is unavailable)
    *   - '''Uncommitted''': `git diff HEAD` (staged and unstaged changes to tracked files)
    *   - '''Untracked''': `git ls-files --others` (new files not yet added to git)
    *
    * A changed file only flags its module when it lives in a source set whose scope is listed in
    * `changesetAffectedScopes` (default `Seq("compile")`); changes confined to other source sets (e.g. `src/test`) are
    * ignored. Files outside every source set (e.g. `build.sbt`, `VERSION`) always flag the module, and `"*"` counts
    * every source set.
    *
    * Only returns names that correspond to actual SBT modules (validated via `packageIsModule` setting).
    */
  def changedModules(state: State): Set[String] = {
    val extracted = Project.extract(state)

    val base   = extracted.get(ThisBuild / baseDirectory)
    val refs   = moduleRefs(state)
    val scopes = extracted.get(ThisBuild / changesetAffectedScopes).toSet

    val projects = extracted.structure.allProjects.map(p => p.id -> p).toMap

    def dirByScope(ref: ProjectRef, config: Configuration): Seq[File] =
      extracted.getOpt(ref / config / unmanagedSourceDirectories).getOrElse(Nil) ++
        extracted.getOpt(ref / config / unmanagedResourceDirectories).getOrElse(Nil)

    // Configs like `Runtime`/`Provided` extend `Compile`, so their source dirs delegate to `src/main`. Tagging each
    // dir by whether its scope is affected and subtracting keeps such a dir out of the excluded set when any
    // affected scope (e.g. `compile`) also owns it.
    val excludedDirs: Set[String] =
      if (scopes.contains("*")) Set.empty[String]
      else {
        val byScope = for {
          ref    <- refs.values.toList
          config <- projects.get(ref.project).toList.flatMap(_.configurations)
          dir    <- dirByScope(ref, config).flatMap(_.relativeTo(base)).map(_.getPath.replace(File.separatorChar, '/'))
        } yield (scopes.contains(config.name), dir)

        val (included, excluded) = byScope.partition(_._1)

        excluded.map(_._2).toSet -- included.map(_._2).toSet
      }

    def ignorable(path: String) = excludedDirs.exists(d => path == d || path.startsWith(d + "/"))

    val baseBranch = extracted.get(ThisBuild / changesetBaseBranch)

    val committed = Try {
      Process(Seq("git", "diff", "--name-only", s"$baseBranch...HEAD"), base).!!(ProcessLogger(_ => ())).trim
    }.recover { case e =>
      state.log.warn(s"Could not diff against $baseBranch: ${e.getMessage}. Is the full git history available?")
      ""
    }.getOrElse("")
    val uncommitted = Process(Seq("git", "diff", "--name-only", "HEAD"), base).!!.trim
    val untracked   = Process(Seq("git", "ls-files", "--others", "--exclude-standard", "modules/"), base).!!.trim

    (committed + "\n" + uncommitted + "\n" + untracked).linesIterator.flatMap {
      case path if path.startsWith("modules/") && !ignorable(path) =>
        path.stripPrefix("modules/").split("/").headOption

      case _ => None
    }.toSet.intersect(refs.keySet)
  }

  /** Extracts the set of module names from the SBT build state. */
  private def extractModuleNames(state: State): Set[String] = {
    val extracted = Project.extract(state)

    extracted.structure.allProjectRefs
      .filter(ref => extracted.get(ref / packageIsModule))
      .map(ref => extracted.get(ref / Keys.name))
      .toSet
  }

  /** Parses changeset files and validates that all referenced modules are known.
    *
    * Throws a [[MessageOnlyException]] if parsing or validation fails.
    */
  private def parseAndValidate(changesetDir: java.io.File, moduleNames: Set[String], log: Logger): Changesets =
    Changesets.parseFrom(changesetDir) match {
      case Left(errors) =>
        errors.foreach(e => log.error(e))
        throw new MessageOnlyException(s"${errors.size} changeset parsing error(s).")

      case Right(cs) =>
        cs.validate(moduleNames) match {
          case Left(errors) =>
            errors.foreach(e => log.error(e))
            throw new MessageOnlyException(s"${errors.size} unknown module(s) in changeset files.")

          case Right(validated) => validated
        }
    }

}
