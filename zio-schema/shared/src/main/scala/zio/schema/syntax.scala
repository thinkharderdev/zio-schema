package zio.schema

object syntax extends SchemaSyntax

trait SchemaSyntax {
  implicit class DiffOps[A: Schema](a: A) {
    def diff(that: A): Diff = Schema[A].diff(a, that)
  }

  implicit class DynamicValueOps[A: Schema](a: A) {
    def dynamic: DynamicValue = Schema[A].toDynamic(a)
  }
}
