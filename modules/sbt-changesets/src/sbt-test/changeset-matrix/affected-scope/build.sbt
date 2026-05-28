ThisBuild / organization       := "com.example"
ThisBuild / scalaVersion       := "3.3.7"
ThisBuild / crossScalaVersions := Seq("3.3.7")

lazy val base = module

lazy val `compile-dependent` = module.dependsOn(base)

lazy val `test-dependent` = module.dependsOn(base % Test)
