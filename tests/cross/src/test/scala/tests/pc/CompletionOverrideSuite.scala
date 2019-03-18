package tests.pc

import tests.BaseCompletionSuite

object CompletionOverrideSuite extends BaseCompletionSuite {

  override def beforeAll(): Unit = {
    indexJDK()
  }

  checkEdit(
    "basic",
    """
      |object Main extends AutoCloseable {
      |  def close@@
      |}
    """.stripMargin,
    """
      |object Main extends AutoCloseable {
      |  def close(): Unit = ${0:???}
      |}
      |""".stripMargin
  )

  checkEdit(
    "overload",
    """
      |trait Interface {
      |  def foo(a: Int): Unit
      |  def foo(a: String): Unit
      |}
      |object Main extends Interface {
      |  override def foo(a: Int): Unit = ()
      |  override def foo@@
      |}
    """.stripMargin,
    """
      |trait Interface {
      |  def foo(a: Int): Unit
      |  def foo(a: String): Unit
      |}
      |object Main extends Interface {
      |  override def foo(a: Int): Unit = ()
      |  override def foo(a: String): Unit = ${0:???}
      |}
      |""".stripMargin
  )

  checkEdit(
    "seen-from",
    """
      |object Main {
      |  new Iterable[Int] {
      |    def iterato@@
      |  }
      |}
    """.stripMargin,
    """
      |object Main {
      |  new Iterable[Int] {
      |    def iterator: Iterator[Int] = ${0:???}
      |  }
      |}
      |""".stripMargin
  )

  checkEdit(
    "generic",
    """
      |object Main {
      |  new scala.Traversable[Int] {
      |    def foreach@@
      |  }
      |}
    """.stripMargin,
    """
      |object Main {
      |  new scala.Traversable[Int] {
      |    def foreach[U](f: Int => U): Unit = ${0:???}
      |  }
      |}
      |""".stripMargin
  )

  checkEdit(
    "context-bound",
    """
      |trait Context {
      |   def add[T:Ordering]: T
      |}
      |object Main {
      |  new Context {
      |    override def ad@@
      |  }
      |}
    """.stripMargin,
    """
      |trait Context {
      |   def add[T:Ordering]: T
      |}
      |object Main {
      |  new Context {
      |    override def add[T: Ordering]: T = ${0:???}
      |  }
      |}
      |""".stripMargin
  )

  checkEdit(
    "import",
    """
      |object Main {
      |  new java.nio.file.SimpleFileVisitor[java.nio.file.Path] {
      |    def visitFil@@
      |  }
      |}
    """.stripMargin,
    """
      |object Main {
      |  new java.nio.file.SimpleFileVisitor[java.nio.file.Path] {
      |    import java.nio.file.{FileVisitResult, Path}
      |    import java.nio.file.attribute.BasicFileAttributes
      |    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = ${0:???}
      |  }
      |}
      |""".stripMargin,
    assertSingleItem = false
  )

  check(
    "empty",
    """
      |trait SuperAbstract {
      |  def aaa: Int = 2
      |}
      |trait Abstract extends SuperAbstract {
      |  def bbb: Int = 2
      |  type TypeAlias = String // should be ignored
      |}
      |object Main {
      |  new Abstract {
      |    def @@
      |  }
      |}
    """.stripMargin,
    // assert that `isInstanceOf` and friends are not included
    """|override def aaa: Int
       |override def bbb: Int
       |override def equals(obj: Any): Boolean
       |override def hashCode(): Int
       |override def toString(): String
       |override def clone(): Object
       |override def finalize(): Unit
       |""".stripMargin,
    includeDetail = false
  )

  def implement(completion: String): String =
    s"""
       |trait Abstract {
       |  def implementMe: Int
       |}
       |object Main {
       |  new Abstract {
       |    $completion
       |  }
       |}
    """.stripMargin

  checkEdit(
    "implement",
    // assert that `override` is not inserted.
    implement("def implement@@"),
    implement("def implementMe: Int = ${0:???}")
  )
  checkEdit(
    "implement-override",
    // assert that `override` is inserted.
    implement("override def implement@@"),
    implement("override def implementMe: Int = ${0:???}")
  )

  checkEdit(
    "error",
    """
      |object Main {
      |  new scala.Iterable[Unknown] {
      |    def iterato@@
      |  }
      |}
    """.stripMargin,
    // Replace error types with type parameter name `A`. IntelliJ converts the unknown type
    // into `Any`, which is less helpful IMO.
    """
      |object Main {
      |  new scala.Iterable[Unknown] {
      |    def iterator: Iterator[A] = ${0:???}
      |  }
      |}
    """.stripMargin
  )

  check(
    "sort",
    """
      |trait Super {
      |  def a: Int = 2
      |  def b: Int
      |}
      |object Main {
      |  new Super {
      |    def @@
      |  }
      |}
    """.stripMargin,
    // assert that `isInstanceOf` and friends are not included
    """|def b: Int
       |override def a: Int
       |""".stripMargin,
    topLines = Some(2),
    includeDetail = false
  )

  checkEditLine(
    "conflict",
    s"""package a.b
       |abstract class Conflict {
       |  def self: Conflict
       |}
       |object Main {
       |  class Conflict
       |  new a.b.Conflict {
       |    ___
       |  }
       |}
       |""".stripMargin,
    "def self@@",
    "def self: a.b.Conflict = ${0:???}"
  )

  check(
    "conflict2",
    s"""package a.c
       |abstract class Conflict {
       |  type Inner
       |  def self: Conflict
       |  def selfArg: Option[Conflict]
       |  def selfPath: Conflict#Inner
       |}
       |object Main {
       |  class Conflict
       |  val a = 2
       |  new _root_.a.c.Conflict {
       |    def self@@
       |  }
       |}
       |""".stripMargin,
    """|def self: _root_.a.c.Conflict
       |def selfArg: Option[_root_.a.c.Conflict]
       |def selfPath: Inner
       |""".stripMargin,
    includeDetail = false
  )

  checkEditLine(
    "mutable",
    s"""|abstract class Mutable {
        |  def foo: scala.collection.mutable.Set[Int]
        |}
        |object Main {
        |  new Mutable {
        |___
        |  }
        |}
        |""".stripMargin,
    "    def foo@@",
    """    import scala.collection.mutable
      |    def foo: mutable.Set[Int] = ${0:???}""".stripMargin
  )

  checkEditLine(
    "mutable-conflict",
    s"""|abstract class Mutable {
        |  def foo: scala.collection.mutable.Set[Int]
        |}
        |object Main {
        |  new Mutable {
        |    val mutable = 42
        |___
        |  }
        |}
        |""".stripMargin,
    "    def foo@@",
    """    def foo: scala.collection.mutable.Set[Int] = ${0:???}"""
  )

  checkEditLine(
    "jutil",
    s"""|abstract class JUtil {
        |  def foo: java.util.List[Int]
        |}
        |class Main extends JUtil {
        |___
        |}
        |""".stripMargin,
    "  def foo@@",
    """  import java.{util => ju}
      |  def foo: ju.List[Int] = ${0:???}""".stripMargin
  )

  checkEditLine(
    "jutil-conflict",
    s"""|package jutil
        |abstract class JUtil {
        |  def foo: java.util.List[Int]
        |}
        |class Main extends JUtil {
        |  val java = 42
        |___
        |}
        |""".stripMargin,
    "  def foo@@",
    // Ensure we insert `_root_` prefix for import because `val java = 42`
    """  import _root_.java.{util => ju}
      |  def foo: ju.List[Int] = ${0:???}""".stripMargin
  )

  checkEditLine(
    "jutil-conflict2",
    s"""|package jutil2
        |abstract class JUtil {
        |  def foo: java.util.List[Int]
        |}
        |class Main extends JUtil {
        |  val ju = 42
        |___
        |}
        |""".stripMargin,
    "  def foo@@",
    // Can't use `import java.{util => ju}` because `val ju = 42` is in scope.
    """  def foo: java.util.List[Int] = ${0:???}"""
  )

  checkEditLine(
    "jlang",
    s"""|abstract class Mutable {
        |  def foo: java.lang.StringBuilder
        |}
        |class Main extends Mutable {
        |  ___
        |}
        |""".stripMargin,
    "  def foo@@",
    """  def foo: java.lang.StringBuilder = ${0:???}""".stripMargin
  )

  checkEditLine(
    "alias",
    s"""|
        |abstract class Abstract {
        |  def foo: scala.collection.immutable.List[Int]
        |}
        |class Main extends Abstract {
        |  ___
        |}
        |
        |""".stripMargin,
    "  def foo@@",
    """  def foo: List[Int] = ${0:???}""".stripMargin
  )

  checkEditLine(
    "alias2",
    s"""|package alias
        |abstract class Alias {
        |  type Foobar = List[Int]
        |  def foo: Foobar
        |}
        |class Main extends Alias {
        |  ___
        |}
        |
        |""".stripMargin,
    "  def foo@@",
    // NOTE(olafur) I am not sure this is desirable behavior, we might want to
    // consider not dealiasing here.
    """  def foo: List[Int] = ${0:???}""".stripMargin
  )

  checkEditLine(
    "rename",
    s"""|import java.lang.{Boolean => JBoolean}
        |abstract class Abstract {
        |  def foo: JBoolean
        |}
        |class Main extends Abstract {
        |___
        |}
        |
        |""".stripMargin,
    "  def foo@@",
    """  def foo: JBoolean = ${0:???}""".stripMargin
  )

  checkEditLine(
    "path",
    s"""|package path
        |abstract class Path {
        |  type Out
        |  def foo: Out
        |}
        |class Main extends Path {
        |___
        |}
        |
        |""".stripMargin,
    "  def foo@@",
    """  def foo: Out = ${0:???}""".stripMargin
  )

  checkEditLine(
    "path-alias",
    s"""|package paththis
        |abstract class Path {
        |  type Out
        |  def foo: Out
        |}
        |class Main extends Path {
        |  type Out = String
        |___
        |}
        |
        |""".stripMargin,
    "  def foo@@",
    """  def foo: String = ${0:???}""".stripMargin
  )

  checkEditLine(
    "path-this",
    """|package paththis
       |abstract class Path {
       |  type Out
       |}
       |class Main extends Path {
       |  trait Conflict {
       |    def conflict: Out
       |  }
       |  object Conflict extends Conflict {
       |    type Out = Int
       |___
       |  }
       |}
       |""".stripMargin,
    "    def conflict@@",
    """    def conflict: Main.this.Out = ${0:???}""".stripMargin
  )

  check(
    "final",
    """|package f
       |abstract class Final {
       |  def hello1: Int = 42
       |  final def hello2: Int = 42
       |}
       |class Main extends Final {
       |  def hello@@
       |}
       |""".stripMargin,
    """override def hello1: Int
      |""".stripMargin,
    includeDetail = false
  )

  check(
    "default",
    """|package g
       |abstract class Final {
       |  def hello1: Int
       |  final def hello2(hello2: Int = 42)
       |}
       |class Main extends Final {
       |  def hello@@
       |}
       |""".stripMargin,
    """def hello1: Int
      |""".stripMargin,
    includeDetail = false
  )

  checkEditLine(
    "default2",
    """|package h
       |abstract class Final {
       |  def hello(arg: Int = 42): Unit
       |}
       |class Main extends Final {
       |  ___
       |}
       |""".stripMargin,
    "def hello@@",
    """def hello(arg: Int): Unit = ${0:???}""".stripMargin
  )

  checkEditLine(
    "existential",
    """|package i
       |abstract class Exist {
       |  def exist: Set[_]
       |}
       |class Main extends Exist {
       |  ___
       |}
       |""".stripMargin,
    "def exist@@",
    """def exist: Set[_] = ${0:???}""".stripMargin
  )

  checkEditLine(
    "cake",
    """|package i
       |trait Trees { this: Global =>
       |  case class Tree()
       |  def Apply(tree: Tree): Tree = ???
       |}
       |class Global extends Trees  {
       |  ___
       |}
       |""".stripMargin,
    "def Apply@@",
    """override def Apply(tree: Tree): Tree = ${0:???}""".stripMargin
  )

  checkEditLine(
    "cake2",
    """|package i2
       |trait Trees { this: Global =>
       |  case class Tree()
       |  def Apply(tree: Tree): Tree = ???
       |}
       |class Global extends Trees  {
       |}
       |class MyGlobal extends Global  {
       |  ___
       |}
       |""".stripMargin,
    "def Apply@@",
    """override def Apply(tree: Tree): Tree = ${0:???}""".stripMargin
  )

  checkEditLine(
    "cake-generic",
    """|package i
       |trait Trees[T] { this: Global =>
       |  case class Tree()
       |  def Apply(tree: T): Tree = ???
       |}
       |class Global extends Trees[Int] {
       |  ___
       |}
       |""".stripMargin,
    "def Apply@@",
    """override def Apply(tree: Int): Tree = ${0:???}""".stripMargin
  )

  check(
    "val-negative",
    """|package j
       |abstract class Val {
       |  val hello1: Int = 42
       |  var hello2: Int = 42
       |}
       |class Main extends Val {
       |  def hello@@
       |}
       |""".stripMargin,
    ""
  )

  checkEditLine(
    "val",
    """|package k
       |abstract class Val {
       |  val hello1: Int = 42
       |}
       |class Main extends Val {
       |  ___
       |}
       |""".stripMargin,
    "val hello@@",
    "override val hello1: Int = ${0:???}"
  )

  check(
    "var",
    """|package l
       |abstract class Val {
       |  var hello1: Int = 42
       |}
       |class Main extends Val {
       |   override var hello@@
       |}
       |""".stripMargin,
    // NOTE(olafur) assert completion items are empty because it's not possible to
    // override vars.
    ""
  )

  check(
    "val-var",
    """|package m
       |abstract class Val {
       |  var hello1: Int = 42
       |}
       |class Main extends Val {
       |   override val hello@@
       |}
       |""".stripMargin,
    // NOTE(olafur) assert completion items are empty because it's not possible to
    // override vars.
    ""
  )

  check(
    "private",
    """|package n
       |abstract class Val {
       |  private def hello: Int = 2
       |}
       |class Main extends Val {
       |   override val hello@@
       |}
       |""".stripMargin,
    ""
  )

  check(
    "protected",
    """|package o
       |abstract class Val {
       |  protected def hello: Int = 2
       |}
       |class Main extends Val {
       |   override def hello@@
       |}
       |""".stripMargin,
    "override def hello: Int",
    includeDetail = false
  )

  check(
    "filter",
    """|package p
       |abstract class Val {
       |  def hello: Int = 2
       |}
       |class Main extends Val {
       |   override def hel@@
       |}
       |""".stripMargin,
    "override def hello: Int",
    includeDetail = false,
    filterText = "override def hello"
  )

  checkEditLine(
    "lazy",
    """|package q
       |abstract class Val {
       |  lazy val hello: Int = 2
       |}
       |class Main extends Val {
       |   ___
       |}
       |""".stripMargin,
    "override val hel@@",
    "override lazy val hello: Int = ${0:???}"
  )

  checkEditLine(
    "early-init",
    """|package r
       |abstract class Global {
       |  lazy val analyzer = new {
       |    val global: Global.this.type = Global.this
       |  }
       |}
       |class Main extends Global {
       |   ___
       |}
       |""".stripMargin,
    "val analyz@@",
    "override lazy val analyzer: Object{val global: r.Main} = ${0:???}"
  )

}
