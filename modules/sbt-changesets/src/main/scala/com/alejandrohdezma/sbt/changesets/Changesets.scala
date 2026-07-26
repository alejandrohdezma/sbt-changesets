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

import scala.annotation.tailrec

import sbt._

import com.alejandrohdezma.sbt.modules.ModuleDependency
import com.alejandrohdezma.sbt.modules.ModuleMetadata

/** A collection of parsed changeset entries keyed by module name.
  *
  * Wraps a `Map[String, Entry]` with convenience accessors and operations for validation and cascade expansion.
  *
  * @param value
  *   the underlying map from module name to changeset entry
  */
case class Changesets(value: Map[String, Changesets.Entry]) {

  /** Returns the changeset entry for the given module name, if present. */
  def get(name: String): Option[Changesets.Entry] = value.get(name)

  /** Returns the number of changeset entries. */
  def size: Int = value.size

  /** Applies `f` to each `(module name, entry)` pair. */
  def foreach(f: ((String, Changesets.Entry)) => Unit): Unit = value.foreach(f)

  /** Returns the set of module names that have changeset entries. */
  def keys: Set[String] = value.keySet

  /** Validates that all modules referenced in the changesets are known.
    *
    * @param knownModules
    *   the set of valid module names from the SBT build
    * @return
    *   `Right` with this instance if all modules are known, or `Left` with sorted error messages for unknown modules
    */
  def validate(knownModules: Set[String]): Either[List[String], Changesets] = {
    val unknown = value.keySet.diff(knownModules)
    if (unknown.isEmpty) Right(this)
    else Left(unknown.toList.sorted.map(name => s"Unknown module in changeset: '$name'"))
  }

  /** Validates that no changeset entries still use the template description.
    *
    * @return
    *   `Right` with this instance if all descriptions have been filled in, or `Left` with sorted error messages for
    *   modules that still have the placeholder
    */
  def validateDescriptions: Either[List[String], Changesets] = {
    val template = value.filter(_._2.description == Changesets.TemplateDescription).keySet
    if (template.isEmpty) Right(this)
    else
      Left(
        template.toList.sorted
          .map(name => s"Changeset for '$name' still has the template description. Please update it.")
      )
  }

  /** Expands changeset entries by cascading version bumps through the dependency graph.
    *
    * Starting from the explicit bump levels in this instance, propagates bumps to transitive dependents using
    * early-semver rules (see [[VersionBump.cascadeBump]]), following only dependency edges whose scope is in
    * `affectedScopes` (see `Changesets.affects`) — so, by default, dependents that rely on a changed module only via
    * test scope are not bumped. Propagation repeats until a fixed point is reached (no new bumps are added or
    * increased).
    *
    * Modules that receive a bump only through cascading get an auto-generated description listing the dependency
    * updates that triggered them.
    *
    * @param modules
    *   the full module metadata map from the SBT build, used for dependency graph traversal and version lookups
    * @param affectedScopes
    *   the dependency-edge scopes through which bumps cascade (the left-hand side of each `dependsOn` mapping; `"*"`
    *   matches any scope). Defaults to `Set("compile", "bom")`.
    * @return
    *   a new [[Changesets]] containing both the original and cascaded entries
    */
  def cascadeExpand(
      modules: Map[String, ModuleMetadata],
      affectedScopes: Set[String] = Set("compile", "bom")
  ): Changesets = {

    // Immutable snapshot of cascading bump computation.
    //
    // @param bumps   accumulated version bumps per module (explicit + cascaded)
    // @param triggers   for cascade-only bumps, the list of dependencies that triggered them
    case class State(bumps: Map[String, VersionBump], triggers: Map[String, List[String]]) {

      // Performs a single propagation pass over all bumped modules.
      //
      // For each (name, bump) in bumps, iterates over name's direct dependents and calculates whether the
      // dependent needs a cascading bump (using early-semver rules). If the cascade produces a higher bump than the
      // dependent already has, the state is updated. Modules that receive a bump only through cascading (not from an
      // explicit changeset) also get their trigger recorded.
      lazy val propagate: State = bumps.foldLeft(this) { case (acc, (name, bump)) =>
        // Walk each direct dependent of the bumped module whose dependency edge is in one of the affected scopes
        modules(name).dependents.foldLeft(acc) {
          case (acc, dep) if Changesets.affects(dep, affectedScopes) =>
            // Determine what bump level this dependency change implies for the dependent
            val cascade = bump.cascadeBump(modules(name).version, modules(dep.name).version)

            val existingBump = acc.bumps.get(dep.name)

            // Take the maximum of the existing bump (if any) and the cascaded bump
            val newBump = existingBump.map(_.max(cascade)).getOrElse(cascade)

            // Skip if the dependent already has an equal or higher bump
            if (existingBump.contains(newBump)) acc
            else {
              // Only track triggers for modules without an explicit changeset entry
              val newTriggers =
                if (value.contains(dep.name)) acc.triggers
                else acc.triggers.updated(dep.name, (name :: acc.triggers.getOrElse(dep.name, Nil)).distinct)

              State(acc.bumps.updated(dep.name, newBump), newTriggers)
            }

          case (acc, _) =>
            acc
        }
      }

    }

    // Fixed-point: keep propagating until no new bumps are added or increased
    @tailrec def loop(state: State): State = if (state == state.propagate) state else loop(state.propagate)

    val State(bumps, triggers) = loop(State(value.map { case (name, entry) => name -> entry.bump }, Map.empty))

    Changesets(
      bumps.map { case (name, bump) =>
        val description = get(name).map(_.description).getOrElse {
          triggers
            .getOrElse(name, Nil)
            .map { dep =>
              val oldVer = modules(dep).version
              val newVer = bumps(dep)(oldVer)
              s"- Updated dependency: $dep ($oldVer \u2192 $newVer)"
            }
            .mkString("\n")
        }

        (name, Changesets.Entry(bump, description))
      }
    )
  }

  /** Ensures every module in `names` receives at least a patch bump whenever this instance contains any bump.
    *
    * Intended for modules that must be re-released with every release train (e.g. a BOM aggregating the build's
    * artifacts) without declaring `dependsOn` edges on the rest of the build. Modules already bumped \u2014 explicitly
    * or through cascading \u2014 keep their existing entry. When this instance is empty, nothing is added, so an
    * always-bumped module on its own never starts a release train.
    *
    * Added entries get an auto-generated description listing the releases they accompany.
    *
    * @param names
    *   the module names to always bump
    * @param modules
    *   the full module metadata map from the SBT build, used for version lookups in the generated description
    * @return
    *   a new [[Changesets]] including a patch entry for each name not already present
    */
  def alwaysBump(names: Seq[String], modules: Map[String, ModuleMetadata]): Changesets =
    if (value.isEmpty) this
    else {
      val alongside = value.toList
        .sortBy(_._1)
        .map { case (name, entry) => s"`$name@${entry.bump(modules(name).version)}`" }
        .mkString(", ")

      val entry = Changesets.Entry(VersionBump.Patch, s"- Released alongside: $alongside")

      Changesets(names.distinct.filterNot(value.contains).foldLeft(value)(_.updated(_, entry)))
    }

}

/** Parsing utilities for changeset markdown files.
  *
  * Changeset files live in `.changeset/` and have YAML frontmatter specifying module bump types:
  * {{{
  * ---
  * "module-name": patch
  * ---
  *
  * Description of the change.
  * }}}
  */
object Changesets {

  /** Placeholder description written by `changesetAdd`. Must be replaced before merging. */
  final val TemplateDescription = "TODO: Describe your changes here"

  /** A parsed changeset entry for a single module.
    *
    * @param bump
    *   the version bump
    * @param description
    *   the markdown body describing the change
    */
  case class Entry(bump: VersionBump, description: String)

  /** Matches a YAML frontmatter entry like `"module-name": patch` or `module-name: minor`. */
  final val EntryPattern = """^"?([^"]+)"?\s*:\s*(\S+)$""".r

  /** Parses changeset files from the provided directory.
    *
    * Each changeset file is a markdown file with YAML frontmatter specifying module bump types. The markdown body after
    * the frontmatter is preserved as the description. A single changeset file can reference multiple modules — they all
    * share the same description.
    *
    * Malformed frontmatter lines are accumulated as errors rather than throwing. Returns a `Left` with all error
    * messages if any lines fail to parse, or a `Right` with the parsed entries if all lines are valid.
    *
    * @param changesetDir
    *   the directory containing changeset markdown files
    * @return
    *   `Right` with a map from module name to [[Entry]], or `Left` with accumulated error messages
    */
  def parseFrom(changesetDir: File): Either[List[String], Changesets] =
    if (!changesetDir.exists()) Left(List(s"Changeset directory does not exist: ${changesetDir.absolutePath}"))
    else if (!changesetDir.isDirectory) Left(List(s"Not a directory: ${changesetDir.absolutePath}"))
    else
      changesetDir
        .listFiles()
        .filter(file => file.getName.endsWith(".md") && file.getName != "README.md")
        .flatMap { file =>
          val afterFirst = IO.readLines(file).dropWhile(_.trim != "---").drop(1)

          val frontmatter = afterFirst.takeWhile(_.trim != "---").map(_.trim).filter(_.nonEmpty)

          val body = afterFirst.dropWhile(_.trim != "---").drop(1).dropWhile(_.trim.isEmpty).mkString("\n").trim

          frontmatter.map {
            case EntryPattern(name, VersionBump(bump)) =>
              Right(name -> Entry(bump, body))
            case line =>
              Left(s"Malformed frontmatter line in `${file.getName}`: '$line'")
          }
        }
        .toList
        .foldLeft[Either[List[String], Map[String, Entry]]](Right(Map.empty)) {
          case (Right(entries), Right(entry)) => Right(entries + entry)
          case (Left(errors), Left(error))    => Left(errors :+ error)
          case (Left(errors), Right(_))       => Left(errors)
          case (Right(_), Left(error))        => Left(List(error))
        }
        .map(Changesets(_))

  /** Extracts the changelog entry for a specific version from a CHANGELOG.md file.
    *
    * Reads the content between `## <version>` and the next `## ` header (or end of file). Returns an empty string if
    * the file does not exist or the version is not found.
    */
  def extractChangelogEntry(file: File, version: String): String =
    if (!file.exists()) ""
    else {
      val lines      = IO.readLines(file)
      val header     = s"## $version"
      val startIndex = lines.indexWhere(_.trim == header)
      if (startIndex < 0) ""
      else {
        val afterHeader = lines.drop(startIndex + 1)
        val endIndex    = afterHeader.indexWhere(_.trim.startsWith("## "))
        val body        = if (endIndex < 0) afterHeader else afterHeader.take(endIndex)
        body.mkString("\n").trim
      }
    }

  /** Removes all changeset markdown files from the provided directory. */
  def clean(changesetDir: File): Unit =
    changesetDir
      .listFiles()
      .filter(f => f.getName.endsWith(".md") && f.getName != "README.md")
      .foreach(_.delete())

  /** Returns whether a dependency edge with the given `dependsOn` configuration matches one of the affected scopes.
    *
    * The left-hand side of each `;`-separated mapping is the depending module's configuration (e.g. `"compile"` for a
    * plain `dependsOn`, `"test"` for `% Test`, both for `"compile->compile;test->test"`). The configuration matches
    * when any of those left-hand-side configurations is in `affectedScopes`. `"*"` — either as an edge configuration or
    * within `affectedScopes` — matches any scope.
    *
    * @param configuration
    *   the raw `dependsOn` configuration string
    * @param affectedScopes
    *   the scopes that count as affecting (`"*"` matches any scope)
    */
  def affects(configuration: String, affectedScopes: Set[String]): Boolean =
    affectedScopes.contains("*") || configuration.split(";").exists { mapping =>
      val scope = mapping.split("->").headOption.getOrElse("").trim
      scope == "*" || affectedScopes.contains(scope)
    }

  /** Returns whether the given dependency edge makes the depending module affected, given the set of `affectedScopes`
    * that count. Convenience overload that delegates to the configuration-string overload using
    * `dependency.configuration`.
    *
    * @param dependency
    *   the direct dependency edge to test
    * @param affectedScopes
    *   the scopes that count as affecting (`"*"` matches any scope)
    */
  def affects(dependency: ModuleDependency, affectedScopes: Set[String]): Boolean =
    affects(dependency.configuration, affectedScopes)

  /** Returns every module transitively reachable from `seed` by following the given direct-dependents graph. The result
    * includes `seed` itself. Filtering the graph by scope before calling this is what keeps test-only dependents out of
    * the closure.
    */
  def affectedClosure(seed: Set[String], directDependents: Map[String, Set[String]]): Set[String] = {
    @tailrec
    def loop(frontier: Set[String], acc: Set[String]): Set[String] =
      if (frontier.isEmpty) acc
      else {
        val next = frontier.flatMap(directDependents.getOrElse(_, Set.empty)).diff(acc)
        loop(next, acc ++ next)
      }

    loop(seed, seed)
  }

}
