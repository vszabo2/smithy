// Parse error at line 5, column 19 near `{`: Expected UNQUOTED

namespace smithy.example

structure Foo isa {
  baz: String
}

// Missing "isa".
structure Baz Foo {
  bar: String,
}

// Invalid quoting
structure Qux isa "Foo" {
  bam: String,
}

// Invalid quoting
structure Fizz "isa" Foo {
  bam: String,
}
