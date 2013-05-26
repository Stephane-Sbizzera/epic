package epic.parser.kbest

import epic.trees._
import breeze.config.{CommandLineParser, Help}
import java.io.{PrintWriter, File}
import breeze.util._
import epic.parser.SimpleChartParser
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.forkjoin.ForkJoinPool
import epic.trees.ProcessedTreebank
import epic.trees.TreeInstance

object KBestParseTreebank {
  /**
   * The type of the parameters to read in via dlwh.epic.config
   */
  case class Params(treebank: ProcessedTreebank,
                    @Help(text="Path to write parses. Will write (train, dev, test)")
                    dir: File,
                    @Help(text="Size of kbest list. Default: 200")
                    k: Int = 200,
                    @Help(text="Path to the parser file. Look in parsers/")
                    parser: File,
                    @Help(text="Should we evaluate on the test set? Or just the dev set?")
                    evalOnTest: Boolean = false,
                    @Help(text="Print this and exit.")
                    help: Boolean = false,
                    @Help(text="How many threads to parse with. Default is whatever Scala wants")
                    threads: Int = -1)

  def main(args: Array[String]) = {
    val params = CommandLineParser.readIn[Params](args)
    println("Evaluating Parser...")
    println(params)


    val parser = readObject[SimpleChartParser[AnnotatedLabel,String]](params.parser)
    val kbest = new AStarKBestParser(parser.augmentedGrammar)
    params.dir.mkdirs()

    def fullyUnannotate(binarized: BinarizedTree[AnnotatedLabel]) = {
      Trees.debinarize(Trees.deannotate(AnnotatedLabelChainReplacer.replaceUnaries(binarized).map(_.label)))
    }

    def parse(trainTrees: IndexedSeq[TreeInstance[AnnotatedLabel, String]], out: PrintWriter) = {
      val parred = trainTrees.par
      if(params.threads > 0)
        parred.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(params.threads))
      parred
        .map(ti => ti.words -> kbest.bestKParses(ti.words, params.k))
        .map{case (words,seq) => seq.map{case (tree, score) => fullyUnannotate(tree).render(words, newline = false) + " " + score}.mkString("\n")}
        .seq.foreach{str => out.println(str); out.println()}
    }

    parse(params.treebank.trainTrees, new PrintWriter(new File(params.dir, "train.kbest")))
    parse(params.treebank.devTrees, new PrintWriter(new File(params.dir, "dev.kbest")))
    parse(params.treebank.testTrees, new PrintWriter(new File(params.dir, "test.kbest")))
  }

}
