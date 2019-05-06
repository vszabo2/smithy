// Parse error at line 8, column 19 near `Foo`: Expected UNQUOTED
namespace smithy.example

structure Foo {
  baz: String
}

structure Baz isa "Foo" {
  bar: String,
}
