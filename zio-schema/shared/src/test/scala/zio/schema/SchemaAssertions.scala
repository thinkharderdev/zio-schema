package zio.schema

import zio.Chunk
import zio.test.Assertion
import zio.test.AssertionM.Render.param

object SchemaAssertions {

  def hasSameSchema[A](expected: Schema[A]): Assertion[Schema[A]] =
    Assertion.assertion("hasSameSchema")(param(expected))(actual => equalsSchema(expected, actual))

  def hasSameMetaSchema(expected: Schema[_]): Assertion[Schema[_]] =
    Assertion.assertion("hasSameMetaSchema")(param(expected))(actual => equalsMetaSchema(expected, actual))

  private def equalsMetaSchema(expected: Schema[_], actual: Schema[_]): Boolean = (expected, actual) match {
    case (Schema.Primitive(StandardType.Duration(_)), Schema.Primitive(StandardType.Duration(_)))             => true
    case (Schema.Primitive(StandardType.Instant(_)), Schema.Primitive(StandardType.Instant(_)))               => true
    case (Schema.Primitive(StandardType.LocalDate(_)), Schema.Primitive(StandardType.LocalDate(_)))           => true
    case (Schema.Primitive(StandardType.LocalTime(_)), Schema.Primitive(StandardType.LocalTime(_)))           => true
    case (Schema.Primitive(StandardType.LocalDateTime(_)), Schema.Primitive(StandardType.LocalDateTime(_)))   => true
    case (Schema.Primitive(StandardType.ZonedDateTime(_)), Schema.Primitive(StandardType.ZonedDateTime(_)))   => true
    case (Schema.Primitive(StandardType.OffsetTime(_)), Schema.Primitive(StandardType.OffsetTime(_)))         => true
    case (Schema.Primitive(StandardType.OffsetDateTime(_)), Schema.Primitive(StandardType.OffsetDateTime(_))) => true
    case (Schema.Primitive(tpe1), Schema.Primitive(tpe2))                                                     => tpe1 == tpe2
    case (Schema.Optional(expected), Schema.Optional(actual))                                                 => equalsMetaSchema(expected, actual)
    case (Schema.Tuple(expectedLeft, expectedRight), Schema.Tuple(actualLeft, actualRight)) =>
      equalsMetaSchema(expectedLeft, actualLeft) && equalsMetaSchema(expectedRight, actualRight)
    case (Schema.EitherSchema(expectedLeft, expectedRight), Schema.EitherSchema(actualLeft, actualRight)) =>
      equalsMetaSchema(expectedLeft, actualLeft) && equalsMetaSchema(expectedRight, actualRight)
    case (Schema.Sequence(expected, _, _), Schema.Sequence(actual, _, _)) => equalsMetaSchema(expected, actual)
    case (expected: Schema.Record[_], actual: Schema.Record[_]) =>
      expected.structure.zipAll(actual.structure).forall {
        case (Some(Schema.Field(expectedLabel, expectedSchema, _)), Some(Schema.Field(actualLabel, actualSchema, _))) =>
          expectedLabel == actualLabel && equalsMetaSchema(expectedSchema, actualSchema)
        case _ => false
      }
    case (expected: Schema.Enum[_], actual: Schema.Enum[_]) =>
      Chunk.fromIterable(expected.structure).zipAll(Chunk.fromIterable(actual.structure)).forall {
        case (Some((expectedId, expectedSchema)), Some((actualId, actualSchema))) =>
          actualId == expectedId && equalsMetaSchema(expectedSchema, actualSchema)
        case _ => false
      }
    case (expected, Schema.Transform(actualSchema, _, _)) =>
      equalsMetaSchema(expected, actualSchema)
    case (Schema.Transform(expected, _, _), actual) =>
      equalsMetaSchema(expected, actual)
    case (expected: Schema.Lazy[_], actual)           => equalsMetaSchema(expected.schema, actual)
    case (expected, actual: Schema.Lazy[_])           => equalsMetaSchema(expected, actual.schema)
    case (Schema.CaseObject(_), Schema.CaseObject(_)) => true
    case _                                            => false
  }

  private def equalsSchema[A](left: Schema[A], right: Schema[A]): Boolean =
    (left: Schema[_], right: Schema[_]) match {
      case (Schema.Transform(codec1, _, _), Schema.Transform(codec2, _, _)) =>
        equalsSchema(codec1, codec2)
      case (Schema.GenericRecord(structure1), Schema.GenericRecord(structure2)) =>
        hasSameFields(structure1, structure2) &&
          structure1.forall {
            case Schema.Field(label, schema, _) =>
              val left: Schema[Any]  = schema.asInstanceOf[Schema[Any]]
              val right: Schema[Any] = structure2.find(_.label == label).asInstanceOf[Schema[Any]]
              equalsSchema(left, right)
          }
      case (left: Schema.Record[_], right: Schema.Record[_]) =>
        hasSameStructure(left.asInstanceOf[Schema.Record[A]], right.asInstanceOf[Schema.Record[A]])
      case (Schema.Sequence(element1, _, _), Schema.Sequence(element2, _, _)) => equalsSchema(element1, element2)
      case (Schema.Enumeration(structure1), Schema.Enumeration(structure2)) =>
        hasSameKeys(structure1, structure2) &&
          structure1.forall { keyAndSchema =>
            val left: Schema[Any]  = keyAndSchema._2.asInstanceOf[Schema[Any]]
            val right: Schema[Any] = structure2(keyAndSchema._1).asInstanceOf[Schema[Any]]
            equalsSchema(left, right)
          }
      case (Schema.Primitive(standardType1), Schema.Primitive(standardType2)) =>
        standardType1 == standardType2
      case (Schema.Tuple(left1, right1), Schema.Tuple(left2, right2)) =>
        equalsSchema(left1, left2) && equalsSchema(right1, right2)
      case (Schema.Optional(codec1), Schema.Optional(codec2))   => equalsSchema(codec1, codec2)
      case (Schema.Enum1(l), Schema.Enum1(r))                   => equalsCase(l, r)
      case (Schema.Enum2(l1, l2), Schema.Enum2(r1, r2))         => hasSameCases(Seq(l1, l2), Seq(r1, r2))
      case (Schema.Enum3(l1, l2, l3), Schema.Enum3(r1, r2, r3)) => hasSameCases(Seq(l1, l2, l3), Seq(r1, r2, r3))
      case (Schema.EnumN(ls), Schema.EnumN(rs))                 => hasSameCases(ls, rs)
      case (Schema.CaseObject(l), Schema.CaseObject(r))         => l == r
      case (l @ Schema.Lazy(_), r @ Schema.Lazy(_)) =>
        equalsSchema(l.schema.asInstanceOf[Schema[Any]], r.schema.asInstanceOf[Schema[Any]])
      case (lazySchema @ Schema.Lazy(_), eagerSchema) =>
        equalsSchema(lazySchema.schema.asInstanceOf[Schema[Any]], eagerSchema.asInstanceOf[Schema[Any]])
      case (eagerSchema, lazySchema @ Schema.Lazy(_)) =>
        equalsSchema(lazySchema.asInstanceOf[Schema[Any]], eagerSchema.asInstanceOf[Schema[Any]])
      case _ => false
    }

  private def equalsCase(left: Schema.Case[_, _], right: Schema.Case[_, _]): Boolean =
    left.id == right.id && equalsSchema(left.codec.asInstanceOf[Schema[Any]], right.codec.asInstanceOf[Schema[Any]])

  private def hasSameCases(ls: Seq[Schema.Case[_, _]], rs: Seq[Schema.Case[_, _]]): Boolean =
    ls.map(l => rs.exists(r => equalsCase(l, r))).reduce(_ && _) && rs
      .map(r => ls.exists(l => equalsCase(l, r)))
      .reduce(_ && _)

  private def hasSameStructure[A](left: Schema.Record[A], right: Schema.Record[A]): Boolean =
    left.structure.zip(right.structure).forall {
      case (Schema.Field(lLabel, lSchema, lAnnotations), Schema.Field(rLabel, rSchema, rAnnotations)) =>
        lLabel == rLabel && lAnnotations.toSet == rAnnotations.toSet && equalsSchema(lSchema, rSchema)
    }

  private def hasSameFields(left: Chunk[Schema.Field[_]], right: Chunk[Schema.Field[_]]): Boolean =
    left.map(_.label) == right.map(_.label)

  private def hasSameKeys[K, V](map1: Map[K, V], map2: Map[K, V]): Boolean =
    map1.keySet.diff(map2.keySet).isEmpty
}
