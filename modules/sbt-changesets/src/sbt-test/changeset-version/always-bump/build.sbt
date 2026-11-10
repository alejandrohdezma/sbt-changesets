ThisBuild / organization := "com.example"
ThisBuild / scalaVersion := "3.3.7"

lazy val `module-a` = module
lazy val `module-b` = module
lazy val `my-bom`   = module.settings(changesetAlwaysBump := true)

TaskKey[Unit]("checkVersion") := {
  val actual = (`module-a` / version).value
  assert(actual == "1.1.0-abc123-SNAPSHOT", s"module-a/version is $actual")
}
