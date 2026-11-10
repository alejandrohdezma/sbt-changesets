ThisBuild / organization       := "com.example"
ThisBuild / scalaVersion       := "3.3.7"
ThisBuild / crossScalaVersions := Seq("3.3.7")

lazy val foo = module.settings(changesetWatchedPaths += (ThisBuild / baseDirectory).value / "templates")

lazy val bar = module
