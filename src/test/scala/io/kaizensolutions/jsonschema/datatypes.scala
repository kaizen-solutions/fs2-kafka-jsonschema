package io.kaizensolutions.jsonschema

import sttp.tapir.Schema.annotations.*
import sttp.tapir.json.pickler.Pickler

final case class Book(
  @description("name of the book") name: String,
  @description("international standard book number") isbn: Int
)
object Book:
  given Pickler[Book] = Pickler.derived

final case class PersonV1(
  @description("name of the person") name: String,
  @description("age of the person") age: Int,
  @description("A list of books that the person has read") books: List[Book]
)
object PersonV1:
  given Pickler[PersonV1] = Pickler.derived

// V2 is backwards incompatible with V1 because the key has changed
final case class PersonV2Bad(
  @description("name of the person") name: String,
  @description("age of the person") age: Int,
  @description("A list of books that the person has read") booksRead: List[Book]
)
object PersonV2Bad:
  given Pickler[PersonV2Bad] = Pickler.derived

final case class PersonV2Good(
  @description("name of the person") name: String,
  @description("age of the person") age: Int,
  @description("A list of books that the person has read") books: List[Book],
  @description("A list of hobbies") @default(Nil)
  hobbies: List[String],
  @description("An optional field to add extra information") @default(Option.empty[String])
  optionalField: Option[String]
)
object PersonV2Good:
  given Pickler[PersonV2Good] = Pickler.derived
