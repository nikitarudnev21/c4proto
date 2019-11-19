package ee.cone.c4generator

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.nio.charset.StandardCharsets.UTF_8
import scala.meta.parsers.Parsed.{Error, Success}
import scala.meta._
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.jdk.CollectionConverters.IteratorHasAsScala

object Main {
  def defaultGenerators: List[Generator] = List(ImportGenerator,AssembleGenerator,ProtocolGenerator,FieldAccessGenerator,LensesGenerator,AppGenerator) //,UnBaseGenerator
  def main(args: Array[String]): Unit = new RootGenerator(defaultGenerators).run()
  def toPrefix = "c4gen."
  def env(key: String): String = Option(System.getenv(key)).getOrElse(s"missing env $key")
  def version: String = s"-w${env("C4GENERATOR_VER")}"
  def rootPath = Paths.get(env("C4GENERATOR_PATH"))
}
import Main.{toPrefix,rootPath,version}

case class WillGeneratorContext(fromFiles: List[Path])
trait WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path,Array[Byte])]
}

class RootGenerator(generators: List[Generator]) {
  def willGenerators: List[WillGenerator] = List(
    new DefaultWillGenerator(generators),
    new PublicPathsGenerator
  )
  //
  def isGenerated(path: Path): Boolean = path.getFileName.toString.startsWith(toPrefix)
  def run(): Unit = {
    //println(s"1:${System.currentTimeMillis()}") //130
    val rootFromPath = rootPath.resolve("src")
    val(wasFiles,fromFiles) = Files.readAllLines(rootFromPath).asScala.map(Paths.get(_)).toList.partition(isGenerated)
    //println(s"5:${System.currentTimeMillis()}") //150
    val was = (for { path <- wasFiles } yield path -> Files.readAllBytes(path)).toMap
    //println(s"4:${System.currentTimeMillis()}") //1s
    val willGeneratorContext = WillGeneratorContext(fromFiles)
    val will = willGenerators.flatMap(_.get(willGeneratorContext))
    assert(will.forall{ case(path,_) => isGenerated(path) })
    //println(s"2:${System.currentTimeMillis()}")
    for(path <- was.keySet -- will.toMap.keys) {
      println(s"removing $path")
      Files.delete(path)
    }
    for{
      (path,data) <- will if !java.util.Arrays.equals(data,was.getOrElse(path,Array.empty))
    } {
      println(s"saving $path")
      Files.write(path,data)
    }
    //println(s"3:${System.currentTimeMillis()}")
  }
}

class DefaultWillGenerator(generators: List[Generator]) extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path,Array[Byte])] = {
    val rootCachePath = rootPath.resolve("cache")
    val fromPostfix = ".scala"
    Files.createDirectories(rootCachePath)
    withIndex(for {
      path <- ctx.fromFiles.filter(_.toString.endsWith(fromPostfix))
      toData <- Option(pathToData(path,rootCachePath)) if toData.length > 0
    } yield path.getParent.resolve(s"$toPrefix${path.getFileName}") -> toData)
  }
  def withIndex: List[(Path,Array[Byte])] => List[(Path,Array[Byte])] = args => {
    val pattern = """\((\S+)\s(\S+)\s(\S+)\)""".r
    val indexes = args.groupBy(_._1.getParent).toList.sortBy(_._1).flatMap{ case (parentPath,args) =>
      val links: Seq[GeneratedAppLink] = args.flatMap{ case (path,data) =>
        val pos = data.indexOf('\n'.toByte)
        // println((pos,path,new String(data)))
        val firstLine = new String(data,0,pos,UTF_8)
        pattern.findAllMatchIn(firstLine).map{ mat =>
          val Seq(pkg,app,expr) = mat.subgroups
          GeneratedAppLink(pkg,app,expr)
        }
      }
      if(links.isEmpty) Nil else {
        val Seq(pkg) = links.map(_.pkg).distinct
        val content =
          s"\n// THIS FILE IS GENERATED; C4APPS: ${links.filter(_.expr=="CLASS").map(l=>s"$pkg.${l.app}").mkString(" ")}" +
            s"\npackage $pkg" +
            links.groupBy(_.app).toList.collect{ case (app,links) =>
              val(classLinks,exprLinks) = links.partition(_.expr=="CLASS")
              val tp = if(classLinks.nonEmpty) "class" else "trait"
              val base = if(app.endsWith("DefApp")) "ee.cone.c4proto.ComponentsApp"
              else s"${app}Base with ee.cone.c4proto.ComponentsApp"
              s"\n$tp $app extends $base {" +
                s"\n  override def components: List[ee.cone.c4proto.Component] = " +
                exprLinks.map(c=> s"\n    ${c.expr} ::: ").mkString +
                s"\n    super.components" +
                s"\n}"
            }.sorted.mkString
        List(parentPath.resolve("c4gen.scala") -> content.getBytes(UTF_8))
      }
    }
    args ++ indexes
  }
  def pathToData(path: Path, rootCachePath: Path): Array[Byte] = {
    val fromData = Files.readAllBytes(path)
    val uuid = UUID.nameUUIDFromBytes(fromData).toString
    val cachePath = rootCachePath.resolve(s"$uuid$version")
    val Name = """.+/(\w+)\.scala""".r
    if(Files.exists(cachePath)) Files.readAllBytes(cachePath) else {
      println(s"parsing $path")
      val content = new String(fromData,UTF_8).replace("\r\n","\n")
      val source = dialects.Scala213(content).parse[Source]
      val Parsed.Success(source"..$sourceStatements") = source
      val resStatements: List[Generated] = for {
        sourceStatement <- (sourceStatements:Seq[Stat]).toList
        q"package $n { ..$packageStatements }" = sourceStatement
        res <- {
          val packageStatementsList = (packageStatements:Seq[Stat]).toList
          val parseContext = new ParseContext(packageStatementsList, path.toString, n.syntax)
          val generatedWOComponents: List[Generated] = generators.flatMap(_.get(parseContext))
          val parsedGenerated = generatedWOComponents.collect{ case c: GeneratedCode => c.content.parse[Stat] match { // c.content.parse[Source].get.stats}.flatten
            case Success(stat) => stat
            case Error(position, str, exception) =>
              println(c.content)
              throw exception
          }}
          val parsedAll = packageStatementsList ::: parsedGenerated
          val compParseContext = new ParseContext(parsedAll, path.toString, n.syntax)
          val generated: List[Generated] = generatedWOComponents ::: ComponentsGenerator.get(compParseContext)
          // val patches: List[Patch] = generated.collect{ case p: Patch => p }
          val statements: List[Generated] = generated.reverse.dropWhile(_.isInstanceOf[GeneratedImport]).reverse
          // if(patches.nonEmpty) patches else
          if(statements.isEmpty) statements
            else List(GeneratedCode(s"\npackage $n {")) ::: statements ::: List(GeneratedCode("\n}"))
        }
      } yield res;
      {
        val warnings = Lint.process(sourceStatements)
        val code = resStatements.flatMap{
          case c: GeneratedImport => List(c.content)
          case c: GeneratedCode => List(c.content)
          case c: GeneratedAppLink => Nil
          case c => throw new Exception(s"$c")
        }
        val content = (warnings ++ code).mkString("\n")
        val contentWithLinks = if(content.isEmpty) "" else
          s"// THIS FILE IS GENERATED; APPLINKS: " +
          resStatements.collect{ case s: GeneratedAppLink =>
            s"(${s.pkg} ${s.app} ${s.expr})"
          }.mkString +
          "\n" +
          content
        val toData = contentWithLinks.getBytes(UTF_8)
        Files.write(cachePath,toData)
        toData
      }
    }
  }
}

object ImportGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case q"import ..$importers" =>
      val nextImporters = importers.map{
        case initialImporter@importer"$eref.{..$importeesnel}" =>
          importer"$eref._" match {
            case j@importer"ee.cone.c4assemble._" => j
            case j@importer"ee.cone.c4proto._" => j
            case _ => initialImporter
          }
      }
      List(GeneratedImport("\n" + q"import ..$nextImporters".syntax))
    case _ => Nil
  }
}

object AppGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = for {
    cl <- Util.matchClass(parseContext.stats) if cl.mods.collectFirst{ case mod"@c4app" => true }.nonEmpty
    res <- cl.name match {
      case Util.UnBase(app) => List(GeneratedAppLink(parseContext.pkg,app,"CLASS"))
      case _ => Nil
    }
  } yield res
}

object Util {
  val UnBase = """(\w+)Base""".r
  /*
  def comment(stat: Stat): String=>String = cont =>
    cont.substring(0,stat.pos.start) + " /* " +
      cont.substring(stat.pos.start,stat.pos.end) + " */ " +
      cont.substring(stat.pos.end)*/

  def unBase(name: String, pos: Int)(f: String=>Seq[Generated]): Seq[Generated] =
    name match {
      case UnBase(n) => f(n)
      case n => throw new Exception(s"can not unBase $n") //List(Patch(pos,"Base"))
    }
  def matchClass(stats: List[Stat]): List[ParsedClass] = stats.flatMap{
    case q"..$cMods class ${nameNode@Type.Name(name)}[..$typeParams] ..$ctorMods (...$params) extends ..$ext { ..$stats }" =>
      List(new ParsedClass(cMods.toList,nameNode,name,typeParams.toList,params.map(_.toList).toList,ext.map{ case t:Tree=>t }.toList,stats.toList))
    case _ => Nil
  }


  def singleSeq[I](l: Seq[I]): Seq[I] = {
    assert(l.size<=1)
    l
  }
}

class ParsedClass(
  val mods: List[Mod], val nameNode: Type.Name, val name: String,
  val typeParams: List[Type.Param], val params: List[List[Term.Param]],
  val ext: List[Tree], val stats: List[Stat]
)
class ParseContext(val stats: List[Stat], val path: String, val pkg: String)
trait Generator {
  def get(parseContext: ParseContext): List[Generated]
}

sealed trait Generated
case class GeneratedImport(content: String) extends Generated
case class GeneratedCode(content: String) extends Generated
//case class Patch(pos: Int, content: String) extends Generated
case class GeneratedTraitDef(name: String) extends Generated
case class GeneratedTraitUsage(name: String) extends Generated
case class GeneratedInnerCode(content: String) extends Generated
case class GeneratedAppLink(pkg: String, app: String, expr: String) extends Generated

object Lint {
  def process(stats: Seq[Stat]): Seq[String] =
    stats.flatMap{ stat =>
      stat.collect{
        case q"..$mods class $tname[..$tparams] ..$ctorMods (...$paramss) extends $template"
          if mods.collect{ case mod"abstract" => true }.nonEmpty =>
          ("abstract class",tname,template)
        case q"..$mods trait $tname[..$tparams] extends $template" =>
          ("trait",tname,template)
      }.flatMap{
        case (tp,tName,template"{ ..$statsA } with ..$inits { $self => ..$statsB }") =>
          if(statsA.nonEmpty) println(s"warn: early initializer in $tName")
          statsB.collect{
            case q"..$mods val ..$patsnel: $tpeopt = $expr"
              if mods.collect{ case mod"lazy" => true }.isEmpty =>
              s"/*\nwarn: val ${patsnel.mkString(" ")} in $tp $tName \n*/"
          }
        case _ => Nil
      }
    }
}

case class PublicPathRoot(basePath: Path, genPath: Path, pkgName: String, publicPath: Path, modName: String)
class PublicPathsGenerator extends WillGenerator {
  def matchThisOrParent(cond: Path=>Boolean): Path=>Option[Path] = {
    def inner(path: Path): Option[Path] =
      if(cond(path)) Option(path) else Option(path.getParent).flatMap(inner)
    inner
  }
  def get(ctx: WillGeneratorContext): List[(Path, Array[Byte])] = {
    val roots: List[PublicPathRoot] = for {
      path <- ctx.fromFiles.filter(_.getFileName.toString == "ht.scala")
      scalaPath <- matchThisOrParent(_.getFileName.toString=="scala")(path).toList
    } yield {
      val basePath = scalaPath.getParent
      val genPath = path.resolveSibling("c4gen.htdocs.scala")
      val pkgPath = scalaPath.relativize(path.getParent)
      val publicPath = basePath.resolve("htdocs").resolve(pkgPath)
      val pkgName = pkgPath.iterator.asScala.mkString(".")
      val modName = s"${basePath.getFileName}.$pkgName"
      PublicPathRoot(basePath,genPath,pkgName,publicPath,modName)
    }
    val isModRoot = roots.map(_.publicPath).toSet
    val toModRoot = matchThisOrParent(isModRoot)
    val pathByRoot = ctx.fromFiles.groupBy(toModRoot)
    val RefOk = """([\w\-\./]+)""".r
    val links = roots.groupMap(_.basePath.resolve("c4gen.ht.links"))(
      root=>s"mod/${root.modName}/src/main/htdocs ${root.publicPath}"
    ).transform((path,lines)=>lines.mkString("\n").getBytes(UTF_8)).toList.sortBy(_._1)
    val code = roots.map { (root:PublicPathRoot) =>
      val defs = pathByRoot.getOrElse(Option(root.publicPath),Nil)
        .map(root.publicPath.relativize(_).toString).map{
          case RefOk(r) => s"""    def `/$r` = "/mod/${root.modName}/$r" """
        }
      val lines = if(defs.isEmpty) Nil else
        "/** THIS FILE IS GENERATED; CHANGES WILL BE LOST **/" ::
        s"package ${root.pkgName}" ::
        "object PublicPath {" :: defs ::: "}" :: Nil
      root.genPath -> lines.mkString("\n").getBytes(UTF_8)
    }
    links ::: code
  }
}



/*
git --git-dir=../.git --work-tree=target/c4generator/to  diff
perl run.pl
git --git-dir=../.git --work-tree=target/c4generator/to  diff target/c4generator/to/c4actor-base
*/
