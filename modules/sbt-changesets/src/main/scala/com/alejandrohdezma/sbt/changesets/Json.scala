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

/** Minimal JSON AST for SBT meta-plugin output.
  *
  * Provides a type-safe way to build JSON without requiring a library dependency in the SBT build definition. Rendering
  * is handled via `toString`.
  */
sealed trait Json {

  /** Renders the JSON as a string with the given indentation. */
  def show(indent: Int = 0): String = this match {
    case Json.JsonString(value) =>
      Json.str(value)

    case Json.JsonBoolean(value) =>
      value.toString

    case Json.JsonArray(values) if values.isEmpty =>
      "[]"

    case Json.JsonArray(values) =>
      values
        .map(value => " " * (indent + 2) + value.show(indent + 2))
        .mkString("[\n", ",\n", "\n" + " " * indent + "]")

    case Json.JsonObject(entries) if entries.isEmpty =>
      "{}"

    case Json.JsonObject(entries) =>
      entries.map { case (k, v) => " " * (2 + indent) + Json.str(k) + ": " + v.show(indent + 2) }
        .mkString("{\n", ",\n", "\n" + " " * indent + "}")
  }

  /** Renders the JSON on a single line, with every character of every string (keys included) written as a `\\uXXXX`
    * escape.
    *
    * The result is equivalent JSON — any parser, GitHub Actions' `fromJson` included, decodes it back to the same
    * values — but its text contains no literal substring of the strings it carries. That is what makes it safe to hand
    * to a GitHub Actions job output: the runner silently discards an output whose value contains a masked secret, and
    * the resulting failure is invisible, since the consumer's `fromJson` then fails to evaluate before its job is ever
    * created. With every character escaped there is nothing left for the masker to match.
    */
  def showEscaped: String = this match {
    case Json.JsonString(value)   => Json.escapedStr(value)
    case Json.JsonBoolean(value)  => value.toString
    case Json.JsonArray(values)   => values.map(_.showEscaped).mkString("[", ",", "]")
    case Json.JsonObject(entries) =>
      entries.map { case (k, v) => Json.escapedStr(k) + ":" + v.showEscaped }.mkString("{", ",", "}")
  }

}

object Json {

  private case class JsonString(value: String) extends Json

  private case class JsonBoolean(value: Boolean) extends Json

  private case class JsonArray(values: Seq[Json]) extends Json

  private case class JsonObject(entries: Seq[(String, Json)]) extends Json

  /** Escapes special characters in a string for safe embedding in a JSON value. */
  private def escape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

  /** Wraps a string as a JSON string literal (with quotes and escaping). */
  private def str(s: String): String = "\"" + escape(s) + "\""

  /** Wraps a string as a JSON string literal with every character written as a `\\uXXXX` escape. */
  private def escapedStr(s: String): String =
    s.map(c => f"\\u${c.toInt}%04x").mkString("\"", "", "\"")

  /** Creates a JSON string value. */
  def apply(value: String): Json = JsonString(value)

  /** Creates a JSON boolean value. */
  def apply(value: Boolean): Json = JsonBoolean(value)

  /** Creates a JSON array from string items. */
  def arr(items: String*): Json = JsonArray(items.map(JsonString.apply))

  /** Creates a JSON array from JSON items. */
  def arr(items: Json*)(implicit ev: DummyImplicit): Json = JsonArray(items)

  /** Creates a JSON object from key to Json value pairs. */
  def obj(entries: (String, Json)*): Json = JsonObject(entries)

  /** Creates a JSON object from a map of key to Json value pairs. */
  def obj(entries: Map[String, Json]): Json = JsonObject(entries.toSeq)

  implicit class StringJsonOps(val key: String) {

    def :=(value: String): (String, Json) = (key, JsonString(value))

    def :=(value: Boolean): (String, Json) = (key, JsonBoolean(value))

  }

}
