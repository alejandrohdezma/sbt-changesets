ThisBuild / scalaVersion       := "3.3.7"
ThisBuild / crossScalaVersions := Seq("3.3.7")

val assertSnapshot = taskKey[Unit]("Assert the module version carries the snapshot suffix")
val assertRelease  = taskKey[Unit]("Assert the module version is the raw release version (no snapshot suffix)")

def assertions = Seq(
  assertSnapshot := {
    val v = version.value
    assert(v.endsWith("-abc123-SNAPSHOT"), s"Expected a snapshot version but got '$v' (project: ${name.value})")
  },
  assertRelease := {
    val v = version.value
    assert(!v.contains("SNAPSHOT"), s"Expected the raw release version but got '$v' (project: ${name.value})")
  }
)

// versionFromFile reads SNAPSHOT_MODULES when the `version` setting is evaluated, so these commands set/clear
// the system property and the test reloads afterwards to force the `version` settings to recompute.
commands += Command.command("setSnapshotModules") { state =>
  val base = Project.extract(state).get(ThisBuild / baseDirectory)
  System.setProperty("SNAPSHOT_MODULES", IO.read(base / "matrix.json"))
  state
}

commands += Command.command("clearSnapshotModules") { state =>
  System.clearProperty("SNAPSHOT_MODULES")
  state
}

lazy val `module-a` = module.settings(assertions)

lazy val `module-b` = module.settings(assertions)
