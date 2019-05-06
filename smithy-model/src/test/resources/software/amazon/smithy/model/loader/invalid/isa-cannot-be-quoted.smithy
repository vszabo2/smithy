// Parse error at line 8, column 15 near `isa`: Expected LBRACE(
namespace smithy.example

structure Foo {
  baz: String
}

structure Baz "isa" Foo {
  bar: String,
}
