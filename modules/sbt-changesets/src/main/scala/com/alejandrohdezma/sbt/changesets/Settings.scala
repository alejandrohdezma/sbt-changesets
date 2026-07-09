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

import scala.jdk.CollectionConverters._
import scala.sys.process._
import scala.util.Try

import sbt.Keys._
import sbt._

import com.alejandrohdezma.sbt.changesets.ChangesetPlugin.autoImport._
import com.alejandrohdezma.sbt.modules.ModulesPlugin.autoImport.packageIsModule
import com.typesafe.config.ConfigFactory

/** Reusable setting implementations for [[ChangesetPlugin]]. */
object Settings {

  /** Memoised snapshot timestamp.
    *
    * Computed once per JVM (lazy val) so that `+publishLocal` runs that span multiple `++` cross-build cycles produce
    * artifacts sharing a single suffix. CI overrides this via the `SNAPSHOT_SUFFIX` env var (see [[versionFromFile]]).
    */
  private object Snapshot {

    lazy val timestamp: String =
      LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))

  }

  /** Derives the version from a `VERSION` file in the module's base directory.
    *
    * By default the version includes a snapshot suffix of the form `<base>-<suffix>-SNAPSHOT`. The suffix is read from
    * the `SNAPSHOT_SUFFIX` env var (or, as a fallback for tests, the system property of the same name); when neither is
    * set, falls back to a timestamp memoised once per JVM. When the `RELEASE` environment variable is set to `"true"`,
    * the version is the raw content of the `VERSION` file (used for CI releases).
    *
    * The `SNAPSHOT_MODULES` env var (again with a system-property fallback) optionally carries the validate-stage
    * `changesetMatrix` JSON (the array of `{module, ...}` rows). When set, the `module` of every row names a module
    * that is being snapshot-published, and only those are suffixed; every other module reports the raw `VERSION`
    * content instead — so an unchanged inter-module dependency is referenced at its already-published release version
    * rather than at a snapshot that was never published. An empty or absent value means all modules are suffixed (the
    * default for local `publishLocal` and any non-CI use); a value that cannot be parsed as that matrix fails the
    * build.
    *
    * If a module is missing its `VERSION` file, the build fails.
    */
  val versionFromFile: Def.Initialize[String] = Def.setting {
    val versionFile = baseDirectory.value / "VERSION"

    if (versionFile.exists()) {
      val versionInFile = IO.read(versionFile).trim

      lazy val suffix = sys.env
        .get("SNAPSHOT_SUFFIX")
        .orElse(sys.props.get("SNAPSHOT_SUFFIX"))
        .filter(_.nonEmpty)
        .getOrElse(Snapshot.timestamp)

      val parseSnapshotModules = (raw: String) =>
        Try(ConfigFactory.parseString(s"rows = $raw").getConfigList("rows").asScala.map(_.getString("module")).toSet)
          .getOrElse(sys.error("Failed to parse SNAPSHOT_MODULES"))

      val snapshotModules =
        sys.env
          .get("SNAPSHOT_MODULES")
          .orElse(sys.props.get("SNAPSHOT_MODULES"))
          .map(parseSnapshotModules)
          .filter(_.nonEmpty)

      if (sys.env.get("RELEASE").contains("true")) versionInFile
      else if (snapshotModules.exists(!_.contains(name.value))) versionInFile
      else s"$versionInFile-$suffix-SNAPSHOT"
    } else if (packageIsModule.value) {
      sys.error(s"Missing VERSION file for module '${name.value}' at `${versionFile.absolutePath}`")
    } else version.value
  }

  /** Derives `scmInfo` from the git remote URL for modules. */
  val scmInfoFromGit: Def.Initialize[Option[ScmInfo]] = Def.setting {
    Try(Process(Seq("git", "remote", "get-url", "origin")).!!.trim).toOption
      .filter(_ => packageIsModule.value)
      .map {
        case s if s.startsWith("git@") =>
          s"https://${s.stripPrefix("git@").replaceFirst(":", "/")}".stripSuffix(".git")

        case s =>
          s.stripSuffix(".git")
      }
      .map { uri =>
        val ref = (ThisBuild / changesetBaseBranch).value

        val branch = ref.indexOf('/') match {
          case -1 => ref
          case i  => ref.substring(i + 1)
        }

        ScmInfo(
          browseUrl = url(s"$uri/tree/$branch/modules/${baseDirectory.value.getName}"),
          connection = s"scm:git:$uri.git"
        )
      }
  }

  /** The separator to use between the organization, name, and version in the changeset coordinate. */
  val separator: Def.Initialize[String] = Def.setting(if (crossPaths.value) "%%" else "%")

}
