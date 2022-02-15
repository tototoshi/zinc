/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Lightbend, Inc. and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt
package internal
package inc
package javac

import java.io.File
import java.nio.file.{ Path, Paths }
import java.net.URLClassLoader
import java.util.Optional
import scala.collection.mutable.HashSet

import xsbt.api.SameAPI
import xsbti.{ PathBasedFile, Problem, Severity }
import xsbti.compile.{
  ClassFileManager,
  IncToolOptions,
  IncToolOptionsUtil,
  JavaTools => XJavaTools
}
import sbt.io.IO
import sbt.util.LoggerContext
import org.scalatest.matchers._
import org.scalatest.diagrams.Diagrams

class JavaCompilerSpec extends UnitSpec with Diagrams {

  "Compiling a java file with local javac" should "compile a java file" in works(local)
  it should "issue errors and warnings" in findsErrors(local)

  "Compiling a file with forked javac" should "compile a java file" in works(forked, forked = true)
  it should "issue errors and warnings" in findsErrors(forked)
  it should "yield the same errors as local javac" in forkSameAsLocal()

  "Documenting a file with forked javadoc" should "document a java file" in docWorks(forked)
  it should "find errors in a java file" in findsDocErrors(forked)

  "Analyzing classes generated by javac" should "matching APIs for stable static-final fields" in
    analyzeStaticDifference("String", "\"A\"", "\"A\"")
  it should "different APIs for static-final fields with changed values" in
    analyzeStaticDifference("String", "\"A\"", "\"B\"")
  it should "different APIs for static-final fields with changed types" in
    analyzeStaticDifference("String", "\"1\"", "int", "1")
  it should "\"safe\" singleton type names " in
    analyzeStaticDifference("float", "0.123456789f", "0.123456789f")

  def docWorks(compiler: XJavaTools) = IO.withTemporaryDirectory { out =>
    val (result, _) = doc(compiler, Seq(knownSampleGoodFile), Seq(), out.toPath)
    assert(result)
    val idx = new File(out, "index.html")
    assert(idx.exists)
    val good = new File(out, "good.html")
    assert(good.exists)
  }

  def dealiasSymlinks(xs: HashSet[File]): HashSet[String] =
    xs map { x =>
      x.getCanonicalPath
    }

  def works(compiler: XJavaTools, forked: Boolean = false) = IO.withTemporaryDirectory { out =>
    val classfileManager = new CollectingClassFileManager()
    val (result, _) = compile(
      compiler,
      Seq(knownSampleGoodFile),
      Seq("-deprecation"),
      out.toPath,
      incToolOptions = IncToolOptionsUtil
        .defaultIncToolOptions()
        .withClassFileManager(Optional.of(classfileManager: ClassFileManager))
        .withUseCustomizedFileManager(true)
    )

    assert(result)
    val classfile = new File(out, "good.class")
    assert(classfile.exists)
    assert(
      dealiasSymlinks(classfileManager.generatedClasses map {
        case vf: PathBasedFile => vf.toPath.toFile
      }) ==
        (if (forked) HashSet() else dealiasSymlinks(HashSet(classfile)))
    )

    val cl = new URLClassLoader(Array(out.toURI.toURL))
    val clazzz = cl.loadClass("good")
    val mthd = clazzz.getDeclaredMethod("test")
    assert(mthd.invoke(null) == "Hello")
  }

  def findsErrors(compiler: XJavaTools) = IO.withTemporaryDirectory { out =>
    val (result, problems) =
      compile(compiler, Seq(knownSampleErrorFile), Seq("-deprecation"), out.toPath)
    assert(result == false)
    assert(problems.size == {
      sys.props("java.specification.version") match {
        case "1.8" | "9" => 6
        case _           => 5
      }
    })
    val importWarn = warnOnLine(lineno = 12, lineContent = Some("java.rmi.RMISecurityException"))
    val enclosingError = errorOnLine(lineno = 25, message = Some("not an enclosing class: C.D"))
    val beAnExpectedError =
      List(
        importWarn,
        errorOnLine(14),
        errorOnLine(15),
        warnOnLine(18),
        enclosingError
      ) reduce (_ or _)
    problems foreach { p =>
      p should beAnExpectedError
    }
  }

  def findsDocErrors(compiler: XJavaTools) = IO.withTemporaryDirectory { out =>
    val (result, problems) =
      doc(compiler, Seq(knownSampleErrorFile), Seq(), out.toPath)
    // exit code for `javadoc` commandline is JDK dependent
    assert(result == {
      sys.props("java.specification.version") match {
        case "1.8" | "9" => true
        case _           => false
      }
    })
    assert(problems.size == 2)
    val beAnExpectedError = List(errorOnLine(14), errorOnLine(15)) reduce (_ or _)
    problems foreach { p =>
      p should beAnExpectedError
    }
  }

  /**
   * Compiles with the given constant values, and confirms that if the strings mismatch, then the
   * the APIs mismatch.
   */
  def analyzeStaticDifference(typeName: String, left: String, right: String): Unit =
    analyzeStaticDifference(typeName, left, typeName, right)

  def analyzeStaticDifference(
      leftType: String,
      left: String,
      rightType: String,
      right: String
  ): Unit = {
    def compileWithPrimitive(templateType: String, templateValue: String) =
      IO.withTemporaryDirectory { out =>
        // copy the input file to a temporary location and change the templateValue
        val input = out.toPath.resolve(hasStaticFinalFile.getFileName.toString)
        IO.writeLines(
          input.toFile,
          IO.readLines(hasStaticFinalFile.toFile).map { line =>
            line.replace("TYPE", templateType).replace("VALUE", templateValue)
          }
        )

        // then compile it
        val (result, _) = compile(local, Seq(input), Seq(), out.toPath)
        assert(result)
        val clazzz = new URLClassLoader(Array(out.toURI.toURL)).loadClass("hasstaticfinal")
        ClassToAPI(Seq(clazzz))
      }

    // compile with two different primitive values, and confirm that they match if their
    // values match
    val leftAPI = compileWithPrimitive(leftType, left)
    val rightAPI = compileWithPrimitive(rightType, right)
    assert(leftAPI.size == rightAPI.size)
    assert(((leftAPI, rightAPI).zipped forall SameAPI.apply) == (left == right))
    ()
  }

  def messageMatches(p: Problem, lineno: Int, message: Option[String] = None): Boolean = {
    def messageCheck = message forall (message => p.message contains message)
    def lineNumberCheck = p.position.line.isPresent && (p.position.line.get == lineno)
    lineNumberCheck && messageCheck
  }

  def lineMatches(p: Problem, lineno: Int, lineContent: Option[String] = None): Boolean = {
    def lineContentCheck = lineContent forall (content => p.position.lineContent contains content)
    def lineNumberCheck = p.position.line.isPresent && (p.position.line.get == lineno)
    lineNumberCheck && lineContentCheck
  }

  def errorOnLine(lineno: Int, message: Option[String] = None, lineContent: Option[String] = None) =
    problemOnLine(lineno, Severity.Error, message, lineContent)

  def warnOnLine(lineno: Int, message: Option[String] = None, lineContent: Option[String] = None) =
    problemOnLine(lineno, Severity.Warn, message, lineContent)

  private def problemOnLine(
      lineno: Int,
      severity: Severity,
      message: Option[String],
      lineContent: Option[String]
  ) = {
    val problemType = severityToProblemType(severity)
    val msg = message.fold("")(s => s""" with message = "$s"""")
    val content = lineContent.fold("")(s => s""" with content = "$s"""")
    Matcher { (p: Problem) =>
      MatchResult(
        messageMatches(p, lineno, message) && lineMatches(
          p,
          lineno,
          lineContent
        ) && p.severity == severity,
        s"Expected $problemType on line $lineno$msg$content, but found $p",
        "Problem matched: " + p
      )
    }
  }

  private def severityToProblemType(s: Severity) = s match {
    case Severity.Error => "error"
    case Severity.Warn  => "warning"
    case Severity.Info  => "info"
  }

  def forkSameAsLocal() = IO.withTemporaryDirectory { out =>
    val (fresult, fproblems) =
      compile(forked, Seq(knownSampleErrorFile), Seq("-deprecation"), out.toPath)
    val (lresult, lproblems) =
      compile(local, Seq(knownSampleErrorFile), Seq("-deprecation"), out.toPath)
    assert(fresult == lresult)

    (fproblems zip lproblems) foreach {
      case (f, l) =>
        // TODO - We should check to see if the levenshtein distance of the messages is close...
        // if (f.position.sourcePath.isPresent)
        //   assert(f.position.sourcePath.get == l.position.sourcePath.get)
        // else assert(!l.position.sourcePath.isPresent)

        if (f.position.line.isPresent) assert(f.position.line.get == l.position.line.get)
        else assert(!l.position.line.isPresent)

        assert(f.severity == l.severity)
    }
  }

  def compile(
      c: XJavaTools,
      sources: Seq[Path],
      args: Seq[String],
      output: Path,
      incToolOptions: IncToolOptions = IncToolOptionsUtil.defaultIncToolOptions()
  ): (Boolean, Array[Problem]) = {
    val log = LoggerContext.globalContext.logger("JavaCompilerSpec", None, None)
    val reporter = new ManagedLoggedReporter(10, log)
    val result = c.javac.run(
      sources.map(x => PlainVirtualFile(x)).toArray,
      args.toArray,
      CompileOutput(output),
      incToolOptions,
      reporter,
      log
    )
    (result, reporter.problems)
  }

  def doc(
      c: XJavaTools,
      sources: Seq[Path],
      args: Seq[String],
      output: Path,
      incToolOptions: IncToolOptions = IncToolOptionsUtil.defaultIncToolOptions()
  ): (Boolean, Array[Problem]) = {
    val log = LoggerContext.globalContext.logger("JavaCompilerSpec", None, None)
    val reporter = new ManagedLoggedReporter(10, log)
    val result = c.javadoc.run(
      sources.map(x => PlainVirtualFile(x)).toArray,
      args.toArray,
      CompileOutput(output),
      incToolOptions,
      reporter,
      log
    )
    (result, reporter.problems)
  }

  // TODO - Create one with known JAVA HOME.
  def forked = JavaTools(JavaCompiler.fork(), Javadoc.fork())

  def local =
    JavaTools(
      JavaCompiler.local.getOrElse(sys.error("This test cannot be run on a JRE, but only a JDK.")),
      Javadoc.local.getOrElse(Javadoc.fork())
    )

  def cwd =
    new File(new File(".").getAbsolutePath).getCanonicalFile

  def knownSampleErrorFile = loadTestResource("test1.java")
  def knownSampleGoodFile = loadTestResource("good.java")
  def hasStaticFinalFile = loadTestResource("hasstaticfinal.java")

  def loadTestResource(name: String): Path = Paths.get(getClass.getResource(name).toURI)

}
