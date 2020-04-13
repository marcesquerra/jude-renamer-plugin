package jude.plugins.renamer

import scala.tools.nsc
import nsc.Global
import nsc.Phase
import nsc.plugins._
import nsc.transform._

class JudeRenamer(val global: Global) extends Plugin {
  import global._

  val name = "Renamer"
  val description =
    "alters the default behaviour of the standard scala compiler"
  val components =
    List[PluginComponent](
      AfterParserComponent,
      AfterTyperComponent,
      AfterPatmatComponent
    )

  def quote(s: String): (String, String) =
    s -> s"'$s'"

  def extern(s: String): (String, String) =
    ("extern$u0020" + s) -> s

  def method(s: String): List[(String, String)] =
    List(quote(s), extern(s))

  private object AfterParserComponent
      extends PluginComponent
      with TypingTransformers {

    val runsAfter = List[String]("parser")
    val phaseName = "AfterParser" + JudeRenamer.this.name

    val mappings: Map[String, String] = List(
      method("$eq$eq"),
      method("$bang$eq"),
      method("toString"),
      method("clone"),
      method("finalize"),
      method("getClass"),
      method("hashCode"),
      method("notify"),
      method("notifyAll"),
      method("equals"),
      method("wait")
    ).flatten.toMap

    def enabled[T <: NameTreeApi]: PartialFunction[T, Boolean] = {
      case _ => true
    }

    private def doit[T <: NameTreeApi](t: T): Boolean =
      enabled.isDefinedAt(t) && enabled(t) && mappings.contains(
        t.name.encodedName.toString
      )

    val global: JudeRenamer.this.global.type = JudeRenamer.this.global
    def newPhase(_prev: Phase) = new JudeRenamerPhase(_prev)

    class JudeRenamerTransformer(unit: CompilationUnit)
        extends TypingTransformer(unit) {
      override def transform(tree: Tree) = {
        tree match {
          case s @ Select(lhs, TermName(id)) if doit(s) =>
            Select(transform(lhs), TermName(mappings(id)))

          case i @ Ident(TermName(id)) if doit(i) =>
            Ident(TermName(mappings(id)))
          case dd @ DefDef(
                modifiers,
                TermName(id),
                tparams,
                params,
                retType,
                rhs
              ) if doit(dd) =>
            DefDef(
              modifiers,
              TermName(mappings(id)),
              tparams,
              params,
              retType,
              transform(rhs)
            )
          case _ =>
            // I'll keep this in here. It's good to run experiments
            // println(s"""|
            //   |=================
            //   |$tree
            //   |-----------------
            //   |${showRaw(tree)}
            //   |=================
            //   |""".stripMargin)
            super.transform(tree)
        }
      }
    }

    def newTransformer(unit: CompilationUnit) =
      new JudeRenamerTransformer(unit)

    class JudeRenamerPhase(prev: Phase) extends StdPhase(prev) {

      type PublicCompilationUnit = CompilationUnit
      override def name = JudeRenamer.this.name

      override def apply(unit: CompilationUnit): Unit = {
        unit.body
          .filter {
            case _: DefDef => false
            case _         => true
          }
          .foreach {
            case tree @ (Select(_, PAT_MAT_EQ) | Ident(PAT_MAT_EQ)) =>
              global.reporter.error(
                tree.pos,
                s"`${PAT_MAT_EQ.decodedName}` is a restricted method name that can only be invoked by the compiler"
              )
            case _ =>
          }
        unit.body = new JudeRenamerTransformer(unit).transform(unit.body)
      }

    }
  }

  var prexisting: Set[Tree] = Set.empty
  private object AfterTyperComponent
      extends PluginComponent
      with TypingTransformers {
    val runsAfter = List[String]("typer")
    val phaseName = "AfterTyper" + JudeRenamer.this.name
    val global: JudeRenamer.this.global.type = JudeRenamer.this.global

    class JudeRenamerPhase(prev: Phase) extends StdPhase(prev) {

      type PublicCompilationUnit = CompilationUnit
      override def name = JudeRenamer.this.name

      override def apply(unit: CompilationUnit): Unit = {
        unit.body.foreach {
          case tree @ q"$_.==($_)" =>
            prexisting += tree
          case _ =>
        }
      }

    }
    def newPhase(_prev: Phase) = new JudeRenamerPhase(_prev)
  }

  val PAT_MAT_EQ = TermName("restricted$u0020patternMatchingEquals")

  private object AfterPatmatComponent
      extends PluginComponent
      with TypingTransformers {
    val runsAfter = List[String]("patmat")
    val phaseName = "AfterPatmat" + JudeRenamer.this.name
    val global: JudeRenamer.this.global.type = JudeRenamer.this.global

    class JudeRenamerTransformer(unit: CompilationUnit)
        extends TypingTransformer(unit) {
      override def transform(tree: Tree) = {
        if (prexisting.contains(tree)) tree
        else
          tree match {
            case q"$pattern.==($obj)" =>
              try {
                val typecheck = localTyper.typedPos(tree.pos) _
                val typeTree = pattern.tpe.dealiasWiden
                typecheck(q"$pattern : ${obj.tpe}") // This ensures the case is compatible with the match
                val typeclassSource =
                  q"_root_.scala.Predef.implicitly[_root_.jude.Eq[$typeTree]]"
                val tc = typecheck(typeclassSource)
                val source =
                  q"($obj.isInstanceOf[$typeTree] && ($tc.$PAT_MAT_EQ($pattern, $obj).asInstanceOf[_root_.scala.Boolean]))"
                typecheck(source)
              } catch {
                case t: Throwable =>
                  global.reporter.error(
                    pattern.pos,
                    t.getMessage()
                  )
                  tree
              }
            case _ =>
              super.transform(tree)
          }
      }
    }
    class JudeRenamerPhase(prev: Phase) extends StdPhase(prev) {

      type PublicCompilationUnit = CompilationUnit
      override def name = JudeRenamer.this.name

      override def apply(unit: CompilationUnit): Unit = {
        unit.body = new JudeRenamerTransformer(unit).transform(unit.body)
      }

    }
    def newPhase(_prev: Phase) = new JudeRenamerPhase(_prev)
  }

}
