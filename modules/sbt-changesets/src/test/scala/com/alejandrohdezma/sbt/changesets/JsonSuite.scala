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

class JsonSuite extends munit.FunSuite {

  // --- Json.apply (strings) ---

  test("Json.apply - simple string renders with quotes") {
    assertEquals(Json("hello").show(), """"hello"""")
  }

  test("Json.apply - string with quotes and backslashes escapes correctly") {
    assertEquals(Json("""say "hi"""").show(), """"say \"hi\""""")
    assertEquals(Json("""path\to""").show(), """"path\\to"""")
  }

  // --- Json.arr ---

  test("Json.arr - empty array") {
    assertEquals(Json.arr(Seq.empty[String] *).show(), "[]")
  }

  test("Json.arr - single element") {
    val expected =
      """|[
         |  "a"
         |]""".stripMargin

    assertEquals(Json.arr("a").show(), expected)
  }

  test("Json.arr - multiple elements") {
    val expected =
      """|[
         |  "a",
         |  "b",
         |  "c"
         |]""".stripMargin

    assertEquals(Json.arr("a", "b", "c").show(), expected)
  }

  // --- Json.obj ---

  test("Json.obj - empty object") {
    assertEquals(Json.obj().show(), "{}")
  }

  test("Json.obj - single entry") {
    val expected =
      """|{
         |  "k": "v"
         |}""".stripMargin

    assertEquals(Json.obj("k" -> Json("v")).show(), expected)
  }

  test("Json.obj - nested objects and arrays") {
    val nested = Json.obj(
      "arr" -> Json.arr("x"),
      "obj" -> Json.obj("inner" -> Json("val"))
    )

    val expected =
      """|{
         |  "arr": [
         |    "x"
         |  ],
         |  "obj": {
         |    "inner": "val"
         |  }
         |}""".stripMargin

    assertEquals(nested.show(), expected)
  }

  test("Json.obj - from Map") {
    val result = Json.obj(Map("a" -> Json("1")))

    val expected =
      """|{
         |  "a": "1"
         |}""".stripMargin

    assertEquals(result.show(), expected)
  }

  // --- Json.arr (Json elements) ---

  test("Json.arr - empty array of Json elements") {
    assertEquals(Json.arr(Seq.empty[Json] *).show(), "[]")
  }

  test("Json.arr - Json elements renders nested objects") {
    val items = Seq(
      Json.obj("module" -> Json("a"), "version" -> Json("1.0.0")),
      Json.obj("module" -> Json("b"), "version" -> Json("2.0.0"))
    )

    val expected =
      """|[
         |  {
         |    "module": "a",
         |    "version": "1.0.0"
         |  },
         |  {
         |    "module": "b",
         |    "version": "2.0.0"
         |  }
         |]""".stripMargin

    assertEquals(Json.arr(items *).show(), expected)
  }

  // --- Json.showEscaped ---

  test("Json.showEscaped - every character of a string becomes a \\uXXXX escape") {
    assertEquals(Json("ab").showEscaped, "\"\\u0061\\u0062\"")
  }

  test("Json.showEscaped - keys are escaped, booleans and structure stay literal") {
    val json = Json.arr(Json.obj("a" -> Json("b"), "c" -> Json(true)))

    assertEquals(json.showEscaped, "[{\"\\u0061\":\"\\u0062\",\"\\u0063\":true}]")
  }

  test("Json.showEscaped - leaves no literal substring of the values it carries") {
    val json = Json.arr(Json.obj("module" -> Json("readers take a TypeName")))

    assert(!json.showEscaped.contains("reader"))
  }

  test("Json.showEscaped - characters that `show` escapes need no special case") {
    assertEquals(Json("\"\\\n").showEscaped, "\"\\u0022\\u005c\\u000a\"")
  }

  test("Json.showEscaped - empty array and object") {
    assertEquals(Json.arr(Seq.empty[Json] *).showEscaped, "[]")
    assertEquals(Json.obj().showEscaped, "{}")
  }

  // --- := syntax ---

  test(":= syntax produces correct pair") {
    import Json._

    val pair: (String, Json) = "key" := "value"

    assertEquals(pair._1, "key")
    assertEquals(pair._2.show(), """"value"""")
  }

}
