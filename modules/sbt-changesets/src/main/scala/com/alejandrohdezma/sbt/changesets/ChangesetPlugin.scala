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

import sbt.Keys._
import sbt._

import com.alejandrohdezma.sbt.modules.ModulesPlugin

/** SBT AutoPlugin that provides changeset-based versioning for a multi-module build.
  *
  * Requires `ModulesPlugin` to ensure `packageIsModule` and `publish / skip` settings are applied in the correct order.
  *
  * Provides:
  *
  *   - '''Per-module versioning''' from `VERSION` files in each module directory
  *   - '''SCM metadata''' (`scmInfo`, `homepage`) derived from the git remote URL
  *   - '''Publish gating''' via `.publish` marker files (`publish / skip`)
  *   - '''Changeset commands''' for validating, versioning, and publishing modules
  *
  * ===Version Resolution===
  *
  * For projects under the `modules/` directory with a `VERSION` file, the version is derived from that file:
  *
  *   - '''Default''': `<version>-<timestamp>-SNAPSHOT` (local development and CI snapshots)
  *   - '''`RELEASE=true`''': `<version>` (CI releases)
  *
  * If a module under `modules/` is missing its `VERSION` file, the build fails.
  */
object ChangesetPlugin extends AutoPlugin {

  override def requires = ModulesPlugin

  override def trigger = allRequirements

  object autoImport {

    val changesetBaseBranch = settingKey[String] {
      "Remote branch reference used to detect changed modules (default: origin/main)"
    }

    val changesetAffectedScopes = settingKey[Seq[String]] {
      "Dependency scopes (the left-hand side of a `dependsOn` configuration, e.g. \"compile\" or \"test\") through " +
        "which a change cascades: a module is treated as affected when it depends on a changed module via one of " +
        "these scopes. Defaults to Seq(\"compile\"), so a module depending on a changed module only via test scope " +
        "is not affected. Use \"*\" to match any scope."
    }

    val changesetCoordinate = settingKey[String] {
      "Maven-style coordinate for this module, used as the `coordinate` field in the validate-stage matrix " +
        "(consumed by `snapshot-comment`). Defaults to `\"org\" %% \"name\" % \"version\"` for Scala modules " +
        "and `\"org\" % \"name\" % \"version\"` for Java modules (`crossPaths := false`). Override per-project " +
        "for test-scoped modules (`... % \"test\"`) or custom artifact shapes."
    }.withRank(KeyRanks.Invisible)

  }

  import autoImport.*

  override def projectSettings: Seq[Setting[_]] = Seq(
    publish / skip      := Settings.skipPublish.value,
    version             := Settings.versionFromFile.value,
    versionScheme       := Some("early-semver"),
    homepage            := scmInfo.value.map(_.browseUrl),
    scmInfo             := Settings.scmInfoFromGit.value,
    changesetCoordinate := s""""${organization.value}" ${Settings.separator.value} "${name.value}" % "${version.value}""""
  )

  override def buildSettings: Seq[Setting[_]] = Seq(
    changesetBaseBranch     := "origin/main",
    changesetAffectedScopes := Seq("compile")
  )

  override def globalSettings: Seq[Setting[_]] = Seq(
    commands ++= Commands.all
  )

}
