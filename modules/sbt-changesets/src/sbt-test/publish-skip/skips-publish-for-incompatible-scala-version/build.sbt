ThisBuild / scalaVersion       := "3.3.7"
ThisBuild / crossScalaVersions := Seq("3.3.7", "2.13.18")

@transient val assertSkipped    = taskKey[Unit]("Assert publish / skip is true")
@transient val assertNotSkipped = taskKey[Unit]("Assert publish / skip is false")

def assertionSettings = Seq(
  assertSkipped := {
    val s = (publish / skip).value
    assert(s, s"Expected publish/skip=true but got $s (project: ${thisProject.value.id})")
  },
  assertNotSkipped := {
    val s = (publish / skip).value
    assert(!s, s"Expected publish/skip=false but got $s (project: ${thisProject.value.id})")
  }
)

lazy val `cross-module` = module.settings(assertionSettings)

lazy val `scala3-only-module` = module
  .settings(crossScalaVersions := Seq("3.3.7"))
  .settings(assertionSettings)
