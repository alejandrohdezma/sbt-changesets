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

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.collection.JavaConverters._
import scala.sys.process._
import scala.util.Try

import sbt.Keys._
import sbt._

import com.alejandrohdezma.sbt.changesets.ChangesetPlugin.autoImport._
import com.alejandrohdezma.sbt.changesets.Json._
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
       |      "dependencies": ["dep-1"],
       |      "transitive_dependencies": ["dep-0", "dep-1"],
       |      "dependents": ["dep-2"],
       |      "transitive_dependents": ["dep-2", "dep-3"]
       |    },
       |    ...
       |  }
       |
       |Module list and dependency graph are derived from the SBT build structure.""".stripMargin
  ) { state =>
    val modules = ModuleMetadata.from(state)

    def asJson(metadata: ModuleMetadata) = Json.obj(
      "version"                 := metadata.version,
      "dependencies"            -> Json.arr(metadata.dependencies.toList.sorted: _*),
      "transitive_dependencies" -> Json.arr(metadata.transitiveDependencies.toList.sorted: _*),
      "dependents"              -> Json.arr(metadata.dependents.toList.sorted: _*),
      "transitive_dependents"   -> Json.arr(metadata.transitiveDependents.toList.sorted: _*)
    )

    val json = Json.obj(modules.mapValues(asJson))

    val file = Project.extract(state).get(ThisBuild / baseDirectory) / "target" / "changeset" / "config.json"

    IO.write(file, json.show())

    state.log.info(s"Wrote changeset config to ${Colors.path(file)}")

    state
  }

  val changesetStatus: Command = Command.command(
    "changesetStatus",
    "Validates that changeset files exist for all changed modules.",
    """|Compares changed files against the base branch and checks that every modified
       |module has a corresponding entry in a .changeset/*.md file.
       |
       |Fails the build if any modules are missing changeset entries.""".stripMargin
  ) { state =>
    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    val changed = changedModules(state)

    if (changed.isEmpty) state.log.info("No modules changed.")
    else {
      val moduleNames = extractModuleNames(state)
      val changesets  = parseAndValidate(base / ".changeset", moduleNames, state.log)

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

    state
  }

  val changesetAffected: Command = Command.command(
    "changesetAffected",
    "Validates changesets and outputs affected module names as JSON.",
    """|First validates that all changed modules have changeset entries (same as
       |changesetStatus). Then writes a JSON array to target/changeset/affected.json
       |containing the names of all modules that have changed files (compared to
       |the base branch) plus their transitive dependents in the internal dependency
       |graph.
       |
       |Fails the build if any modules are missing changeset entries.
       |Set CHANGESET_SKIP_VALIDATION=true to skip validation while still
       |computing affected modules.
       |Suitable as input for a GitHub Actions matrix strategy.""".stripMargin
  ) { state =>
    val base    = Project.extract(state).get(ThisBuild / baseDirectory)
    val modules = ModuleMetadata.from(state)

    val changed = changedModules(state)

    // Validate changesets for changed modules
    val skipValidation = sys.env.get("CHANGESET_SKIP_VALIDATION").contains("true")

    if (changed.nonEmpty && skipValidation)
      state.log.warn("CHANGESET_SKIP_VALIDATION is set. Skipping changeset validation.")
    else if (changed.nonEmpty) {
      val moduleNames = extractModuleNames(state)
      val changesets  = parseAndValidate(base / ".changeset", moduleNames, state.log)

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

    val affected = changed ++ changed.flatMap(n => modules.get(n).map(_.transitiveDependents).getOrElse(Set.empty))

    val json = Json.arr(affected.toList.sorted: _*)

    val file = base / "target" / "changeset" / "affected.json"

    IO.write(file, json.show())

    state.log.info(s"Wrote affected modules to ${Colors.path(file)}")

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
       |major is breaking. Dependents receive at least the same bump level.""".stripMargin
  ) { state =>
    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    val modules     = ModuleMetadata.from(state)
    val moduleNames = extractModuleNames(state)
    val changesets  = parseAndValidate(base / ".changeset", moduleNames, state.log).cascadeExpand(modules)

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
    IO.write(summaryFile, Json.arr(summary: _*)(DummyImplicit.dummyImplicit).show())
    state.log.info(s"Wrote version summary to ${Colors.path(summaryFile)}")

    // Remove processed changeset files
    Changesets.clean(base / ".changeset")

    state.log.info(s"Processed ${changesets.size} changeset(s), bumped ${changesets.size} module(s).")

    state
  }

  val changesetRelease: Command = Command.command(
    "changesetRelease",
    "Outputs release info for modules with .publish markers.",
    """|Reads .publish marker files under modules/*/ and writes
       |target/changeset/release.json with tag names and changelog bodies.
       |
       |JSON format:
       |  [
       |    { "module": "my-module", "version": "1.3.0", "tag": "my-module@1.3.0", "body": "..." }
       |  ]""".stripMargin
  ) { state =>
    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    val modulesDir = base / "modules"

    val releases = Option(modulesDir.listFiles())
      .fold(List.empty[java.io.File])(_.toList)
      .sorted
      .filter(dir => dir.isDirectory && (dir / ".publish").exists())
      .map { dir =>
        val name    = dir.getName
        val version = IO.read(dir / "VERSION").trim
        val tag     = s"$name@$version"
        val body    = Changesets.extractChangelogEntry(dir / "CHANGELOG.md", version)

        state.log.info(s"Release: ${Colors.module(name)}@${Colors.version(version)}")

        Json.obj("module" := name, "version" := version, "tag" := tag, "body" := body)
      }

    val file = base / "target" / "changeset" / "release.json"
    IO.write(file, Json.arr(releases: _*)(DummyImplicit.dummyImplicit).show())
    state.log.info(s"Wrote release info to ${Colors.path(file)}")

    state
  }

  val generatePublishMarkers: Command = Command.command(
    "generatePublishMarkers",
    "Creates .publish markers for modules whose VERSION file changed in the last commit.",
    """|Detects which modules need publishing by running git diff on the last
       |commit. For each module whose VERSION file was modified, creates an
       |empty .publish marker file so that `publish / skip` evaluates to false.
       |
       |Intended to run in CI before `+publish` so that markers are local to
       |the runner and never committed to the repository.""".stripMargin
  ) { state =>
    val base = Project.extract(state).get(ThisBuild / baseDirectory)

    val diff = Process(Seq("git", "diff", "--name-only", "HEAD~1", "--", "modules/*/VERSION"), base).!!.trim

    val modules = diff.linesIterator
      .filter(_.startsWith("modules/"))
      .flatMap(_.stripPrefix("modules/").split("/").headOption)
      .toSet

    if (modules.isEmpty) state.log.info("No VERSION files changed in last commit. No markers created.")
    else {
      modules.toList.sorted.foreach { name =>
        IO.write(base / "modules" / name / ".publish", "")
        state.log.info(s"Created .publish marker for ${Colors.module(name)}")
      }
    }

    state
  }

  val publishSnapshot: Command = Command.command(
    "publishSnapshot",
    "Publishes affected modules as snapshots.",
    """|Detects which modules have changed (compared to the base branch), computes
       |transitive dependents, enables publishing for those modules, and runs
       |+publish. Writes snapshot coordinates to target/changeset/snapshot-coordinates.json.
       |
       |The version is the default timestamp-SNAPSHOT version (from VERSION file).
       |Non-affected modules remain skipped.""".stripMargin
  ) { state =>
    val extracted = Project.extract(state)
    val base      = extracted.get(ThisBuild / baseDirectory)
    val modules   = ModuleMetadata.from(state)
    val changed   = changedModules(state)

    if (changed.isEmpty) {
      state.log.info("No modules changed. Nothing to publish.")
      state
    } else {
      val affected = changed ++ changed.flatMap(n => modules.get(n).map(_.transitiveDependents).getOrElse(Set.empty))

      state.log.info(s"Affected modules: ${affected.toList.sorted.map(Colors.module).mkString(", ")}")

      // Build project ref lookup: module name -> project ref
      val moduleRefsByName = extracted.structure.allProjectRefs
        .filter(ref => extracted.get(ref / packageIsModule))
        .map(ref => extracted.get(ref / Keys.name) -> ref)
        .toMap

      // Create .publish marker files for affected modules so that
      // `publish / skip` evaluates to false even after `+` reapplies settings.
      affected.toList.sorted.foreach { name =>
        moduleRefsByName.get(name).foreach { ref =>
          IO.touch(extracted.get(ref / Keys.baseDirectory) / ".publish")
        }
      }

      // Write coordinates JSON (version includes timestamp-SNAPSHOT by default)
      val coordinates = affected.toList.sorted.flatMap { name =>
        moduleRefsByName.get(name).map { ref =>
          val moduleVersion = extracted.get(ref / version)
          val moduleOrg     = extracted.get(ref / organization)

          Json.obj(
            "module"     := name,
            "version"    := moduleVersion,
            "coordinate" := s""""$moduleOrg" %% "$name" % "$moduleVersion""""
          )
        }
      }

      val coordinatesFile = base / "target" / "changeset" / "snapshot-coordinates.json"
      IO.write(coordinatesFile, Json.arr(coordinates: _*)(DummyImplicit.dummyImplicit).show())
      state.log.info(s"Wrote snapshot coordinates to ${Colors.path(coordinatesFile)}")

      // Reload to pick up .publish markers, then run +publish
      "reload" :: "+publish" :: state
    }
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
       |module present in the diff.
       |
       |Modules not recognized by the current build are silently skipped.""".stripMargin
  ) { state =>
    val base                   = Project.extract(state).get(ThisBuild / baseDirectory)
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

    val affected = projects.intersect(moduleNames)

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

  val all: Seq[Command] = Seq(changesetConfig, changesetStatus, changesetAffected, changesetVersion, changesetRelease,
    generatePublishMarkers, publishSnapshot, changesetAdd, changesetFromDependencyDiff)

  // ─── Internal helpers ─────────────────────────

  /** Detects which modules have changed files compared to the base branch.
    *
    * Combines three sources of changes so the result is accurate on feature branches, on the base branch, and for
    * uncommitted work:
    *   - '''Committed''': `git diff <baseBranch>...HEAD` (falls back gracefully if the base branch is unavailable)
    *   - '''Uncommitted''': `git diff HEAD` (staged and unstaged changes to tracked files)
    *   - '''Untracked''': `git ls-files --others` (new files not yet added to git)
    *
    * Only returns names that correspond to actual SBT modules (validated via `packageIsModule` setting).
    */
  def changedModules(state: State): Set[String] = {
    val extracted = Project.extract(state)

    val base = extracted.get(ThisBuild / baseDirectory)

    val moduleNames = extracted.structure.allProjectRefs
      .filter(ref => extracted.get(ref / packageIsModule))
      .map(ref => extracted.get(ref / Keys.name))
      .toSet

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
      case path if path.startsWith("modules/") =>
        path.stripPrefix("modules/").split("/").headOption

      case _ => None
    }.toSet.intersect(moduleNames)
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
