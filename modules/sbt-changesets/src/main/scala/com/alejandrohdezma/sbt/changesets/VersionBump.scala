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

/** A semver version bump level. */
sealed trait VersionBump {

  /** Applies this bump to a version string. Strips any pre-release suffix (e.g. `-SNAPSHOT`) before parsing. */
  def apply(current: String): String = {
    val parts = VersionBump.semverParts(current)

    this match {
      case VersionBump.Major => s"${parts(0) + 1}.0.0"
      case VersionBump.Minor => s"${parts(0)}.${parts(1) + 1}.0"
      case VersionBump.Patch => s"${parts(0)}.${parts(1)}.${parts(2) + 1}"
    }
  }

  /** Calculates the cascading bump type for a dependent when one of its dependencies is bumped.
    *
    * Following early-semver conventions, first determines whether the dependency's bump is breaking:
    *   - '''0.x''': `minor` or `major` is breaking
    *   - '''1.x+''': only `major` is breaking
    *
    * If the dependency's bump is breaking, returns the appropriate breaking bump for the dependent's version:
    *   - '''0.x dependent''': `Minor` (breaking in early-semver)
    *   - '''1.x+ dependent''': `Major` (breaking in standard semver)
    *
    * If the dependency's bump is non-breaking, returns `Patch`.
    */
  def cascadeBump(depVersion: String, dependentVersion: String): VersionBump = {
    val depMajor       = VersionBump.semverParts(depVersion)(0)
    val dependentMajor = VersionBump.semverParts(dependentVersion)(0)

    val isBreaking =
      (depMajor == 0 && (this == VersionBump.Minor || this == VersionBump.Major)) ||
        (depMajor >= 1 && this == VersionBump.Major)

    if (isBreaking && dependentMajor == 0) VersionBump.Minor
    else if (isBreaking && dependentMajor >= 1) VersionBump.Major
    else VersionBump.Patch
  }

  /** Returns the higher of two bumps, where `Major` > `Minor` > `Patch`. */
  def max(other: VersionBump): VersionBump = (this, other) match {
    case (a, b) if a == b       => a
    case (VersionBump.Major, _) => VersionBump.Major
    case (_, VersionBump.Major) => VersionBump.Major
    case (VersionBump.Minor, _) => VersionBump.Minor
    case (_, VersionBump.Minor) => VersionBump.Minor
    case (VersionBump.Patch, _) => VersionBump.Patch
    case (_, VersionBump.Patch) => VersionBump.Patch
  }

}

object VersionBump {

  case object Major extends VersionBump { override def toString: String = "major" }

  case object Minor extends VersionBump { override def toString: String = "minor" }

  case object Patch extends VersionBump { override def toString: String = "patch" }

  /** Parses the numeric semver parts from a version string, stripping any pre-release suffix (e.g. `-SNAPSHOT`). */
  private[changesets] def semverParts(version: String): Array[Int] =
    version.split("-", 2)(0).split("\\.").map(_.toInt)

  /** Extracts a bump type from a string (`"major"`, `"minor"`, or `"patch"`). */
  def unapply(value: String): Option[VersionBump] = value match {
    case "major" => Some(Major)
    case "minor" => Some(Minor)
    case "patch" => Some(Patch)
    case _       => None
  }

}
