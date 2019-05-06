namespace smithy.example

structure Foo {
  baz: String
}

structure Baz isa Foo {
  bar: String,
}

structure Qux
isa
Baz
{
  bam: String,
}
