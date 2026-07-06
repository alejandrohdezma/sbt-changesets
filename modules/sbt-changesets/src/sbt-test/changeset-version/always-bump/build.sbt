ThisBuild / organization        := "com.example"
ThisBuild / scalaVersion        := "3.3.7"
ThisBuild / changesetAlwaysBump := Seq("my-bom")

lazy val `module-a` = module
lazy val `module-b` = module
lazy val `my-bom`   = module
