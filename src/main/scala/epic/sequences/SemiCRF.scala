package epic.sequences

import breeze.util.Index
import epic.trees.Span
import breeze.numerics
import epic.sequences.SemiCRF.Marginal
import breeze.features.FeatureVector
import epic.framework.{ModelObjective, Feature}
import java.util
import collection.mutable.ArrayBuffer
import util.concurrent.ConcurrentHashMap
import collection.immutable.BitSet
import breeze.collection.mutable.TriangularArray
import java.io.{ObjectInputStream, IOException}
import breeze.optimize.FirstOrderMinimizer.OptParams
import breeze.optimize.CachedBatchDiffFunction
import breeze.linalg.DenseVector

/**
 * A Semi-Markov Linear Chain Conditional Random Field, that is, the length
 * of time spent in a state may be longer than 1 tick. Useful for field segmentation or NER.
 *
 * As usual in Epic, all the heavy lifting is done in the companion object and Marginals.
 * @author dlwh
 */
@SerialVersionUID(1L)
trait SemiCRF[L, W] extends Serializable {
  def anchor(w: IndexedSeq[W]): SemiCRF.Anchoring[L, W]
  def labelIndex: Index[L]
  def startSymbol: L

  def marginal(w: IndexedSeq[W]) = {
     SemiCRF.Marginal(anchor(w))
  }

  def goldMarginal(segmentation: IndexedSeq[(L,Span)], w: IndexedSeq[W]):Marginal[L, W] = {
    SemiCRF.Marginal.goldMarginal(anchor(w), segmentation)
  }

  def bestSequence(w: IndexedSeq[W], id: String = "") = {
    SemiCRF.posteriorDecode(marginal(w), id)
  }


}

object SemiCRF {

  def buildSimple[L](data: IndexedSeq[Segmentation[L, String]],
                     startSymbol: L, outsideSymbol: L,
                     gazetteer: Gazetteer[Any, String] = Gazetteer.empty[Any, String],
                     opt: OptParams = OptParams()):SemiCRF[L, String] = {
    val model: SemiCRFModel[L, String] = new SegmentationModelFactory[L](startSymbol, outsideSymbol, gazetteer = gazetteer).makeModel(data)

    val obj = new ModelObjective(model, data)
    val cached = new CachedBatchDiffFunction(obj)
    val weights = opt.minimize(cached, obj.initialWeightVector(randomize = true))
    val crf = model.extractCRF(weights)

    crf
  }

  def buildIOModel[L](data: IndexedSeq[Segmentation[L, String]],
                      outsideSymbol: L,
                      gazetteer: Gazetteer[Any, String] = Gazetteer.empty[Any, String],
                      opt: OptParams = OptParams()): SemiCRF[Boolean, String] = {
    val fixedData: IndexedSeq[Segmentation[Boolean, String]] = data.map{s =>
      s.copy(segments=s.segments.map{case (l,span) => (l != outsideSymbol, span)})
    }
    buildSimple(fixedData, false, false, gazetteer, opt)
  }

  def fromCRF[L, W](crf: CRF[L, W]):SemiCRF[L, W] = new SemiCRF[L, W] {
    def startSymbol: L = crf.startSymbol

    def anchor(w: IndexedSeq[W]): Anchoring[L, W] = new Anchoring[L, W] {
      def canStartLongSegment(pos: Int): Boolean = false

      def isValidSegment(begin: Int, end: Int): Boolean = begin == end - 1

      val anch = crf.anchor(w)
      def words: IndexedSeq[W] = w

      def maxSegmentLength(label: Int): Int = 1

      def scoreTransition(prev: Int, cur: Int, begin: Int, end: Int): Double = {
        if(end - begin != 1) {
          Double.NegativeInfinity
        } else {
          anch.scoreTransition(begin, prev, cur)
        }
      }

      def labelIndex: Index[L] = crf.labelIndex

      def startSymbol: L = crf.startSymbol
    }

    def labelIndex: Index[L] = crf.labelIndex
  }


  trait Anchoring[L, W] {
    def words : IndexedSeq[W]
    def length: Int = words.length
    def maxSegmentLength(label: Int): Int
    def scoreTransition(prev: Int, cur: Int, begin: Int, end: Int):Double
    def labelIndex: Index[L]
    def startSymbol: L

    def ignoreTransitionModel: Boolean = false

    def canStartLongSegment(pos: Int):Boolean
    def isValidSegment(begin: Int, end: Int):Boolean
  }

  trait TransitionVisitor[L, W] {
    def apply(prev: Int, cur: Int, begin: Int, end: Int, count: Double)
  }

  trait Marginal[L, W] extends epic.framework.Marginal {

    def anchoring: Anchoring[L, W]
    def words: IndexedSeq[W] = anchoring.words
    def length: Int = anchoring.length
    /** Visits spans with non-zero score, useful for expected counts */
    def visit( f: TransitionVisitor[L, W])

    /** normalized probability of seeing segment with transition */
    def transitionMarginal(prev:Int, cur: Int, begin: Int, end: Int):Double
    def logPartition: Double

    def spanMarginal(cur: Int, begin: Int, end: Int): Double = {
      var prev = 0
      val numLabels: Int = anchoring.labelIndex.size
      var sum = 0.0
      while(prev <  numLabels) {
        sum += transitionMarginal(prev, cur, begin, end)
        prev += 1
      }
      sum
    }
    def spanMarginal(begin: Int, end: Int):DenseVector[Double] = DenseVector.tabulate(anchoring.labelIndex.size)(spanMarginal(_, begin, end))

    def computeSpanConstraints(threshold: Double = 1E-5):SpanConstraints = {
      val spanMarginals = TriangularArray.fill(length+1)(new Array[Double](anchoring.labelIndex.size))

      this visit new TransitionVisitor[L, W] {
        def apply(prev: Int, cur: Int, begin: Int, end: Int, count: Double)  {
          spanMarginals(begin, end)(cur) += count
        }
      }

      val allowedLabels = spanMarginals.map {  arr =>
         BitSet.empty ++ (0 until arr.length).filter(i => arr(i) >= threshold)
//           BitSet.empty ++ (0 until arr.length)
      }

      val maxLengths = new Array[Int](anchoring.labelIndex.size)
      val allowedStarts = Array.fill(length)(collection.mutable.BitSet.empty)
      for(begin <- 0 until length; end <- (begin+1) to length) {
        for(l <- allowedLabels(begin, end)) {
          maxLengths(l) = math.max(maxLengths(l), end - begin)
          allowedStarts(begin) += l
        }
      }


      new SpanConstraints(maxLengths, allowedStarts.map(BitSet.empty ++ _), allowedLabels)
    }
  }

  object Marginal {

    def apply[L, W](scorer: Anchoring[L, W]):Marginal[L, W] = {

      val forwardScores: Array[Array[Double]] = this.forwardScores(scorer)
      val backwardScore: Array[Array[Double]] = this.backwardScores(scorer)
      val partition = numerics.logSum(forwardScores.last)
      val _s = scorer


      new Marginal[L, W] {

        def anchoring: Anchoring[L, W] = _s

        /** Visits spans with non-zero score, useful for expected counts */
        def visit(f: TransitionVisitor[L, W]) {
          val numLabels = scorer.labelIndex.size
          var end = 1
          while (end <= length) {
            var label = 0
            while (label < numLabels) {

              var begin = math.max(end - anchoring.maxSegmentLength(label), 0)
              while (begin < end) {
                if((anchoring.canStartLongSegment(begin) || begin == end - 1) && anchoring.isValidSegment(begin, end)) {
                  var prevLabel = 0
                  while (prevLabel < numLabels) {
                    val score = transitionMarginal(prevLabel, label, begin, end)
                    if(score != 0.0)
                      f(prevLabel, label, begin, end, score)
                    prevLabel += 1
                  }
                }
                begin += 1
              }
              label += 1
            }

            end += 1
          }
        }


        /** Log-normalized probability of seing segment with transition */
        def transitionMarginal(prev: Int, cur: Int, begin: Int, end: Int): Double = {
          val withoutTrans = forwardScores(begin)(prev) + backwardScore(end)(cur)
          if(withoutTrans.isInfinite) 0.0
          else math.exp(withoutTrans + anchoring.scoreTransition(prev, cur, begin, end) - logPartition)
        }

        def logPartition: Double = partition
      }

    }

    def goldMarginal[L, W](scorer: Anchoring[L, W], segmentation: IndexedSeq[(L,Span)]):Marginal[L, W] = {
      var lastSymbol = scorer.labelIndex(scorer.startSymbol)
      var score = 0.0
      var lastEnd = 0
      val goldEnds = Array.fill(segmentation.last._2.end)(-1)
      val goldLabels = Array.fill(segmentation.last._2.end)(-1)
      val goldPrevLabels = Array.fill(segmentation.last._2.end)(-1)
      for( (l,span) <- segmentation) {
        assert(span.start == lastEnd)
        val symbol = scorer.labelIndex(l)
        assert(symbol != -1, s"$l not in index: ${scorer.labelIndex}")
        score += scorer.scoreTransition(lastSymbol, symbol, span.start, span.end)
        assert(!score.isInfinite, " " + segmentation + " " + l + " " + span)
        goldEnds(span.start) = span.end
        goldLabels(span.start) = symbol
        goldPrevLabels(span.start) = lastSymbol
        lastSymbol = symbol
        lastEnd = span.end
      }

      val s = scorer

      new Marginal[L, W] {

        def anchoring: Anchoring[L, W] = s

        /** Visits spans with non-zero score, useful for expected counts */
        def visit(f: TransitionVisitor[L, W]) {
          var lastSymbol = scorer.labelIndex(scorer.startSymbol)
          var lastEnd = 0
          for( (l,span) <- segmentation) {
            assert(span.start == lastEnd)
            val symbol = scorer.labelIndex(l)
            f.apply(lastSymbol, symbol, span.start, span.end, 1.0)
            lastEnd = span.end
            lastSymbol = symbol
          }

        }

        /** normalized probability of seeing segment with transition */
        def transitionMarginal(prev: Int, cur: Int, begin: Int, end: Int): Double = {
          numerics.I(goldEnds(begin) == end && goldLabels(begin) == cur && goldPrevLabels(begin) == prev)
        }


        def logPartition: Double = score
      }
    }

    /**
     *
     * @param anchoring
     * @return forwardScore(end position)(label) = forward score of ending a segment labeled label in position end position
     */
    private def forwardScores[L, W](anchoring: SemiCRF.Anchoring[L, W]): Array[Array[Double]] = {
      val length = anchoring.length
      val numLabels = anchoring.labelIndex.size
      // total weight (logSum) for ending in pos with label l.
      val forwardScores = Array.fill(length+1, numLabels)(Double.NegativeInfinity)
      forwardScores(0)(anchoring.labelIndex(anchoring.startSymbol)) = 0.0

      val accumArray = new Array[Double](numLabels * length)

      var end = 1
      while (end <= length) {
        var label = 0
        while (label < numLabels) {
          var acc = 0
          var begin = math.max(end - anchoring.maxSegmentLength(label), 0)
          while (begin < end) {
            if((anchoring.canStartLongSegment(begin) || begin == end - 1) && anchoring.isValidSegment(begin, end)) {
              var prevLabel = 0
              if (anchoring.ignoreTransitionModel) {
                prevLabel = -1 // ensure that you don't actually need the transition model
                val prevScore = numerics.logSum(forwardScores(begin), forwardScores(begin).length)
                if (prevScore != Double.NegativeInfinity) {
                  val score = anchoring.scoreTransition(prevLabel, label, begin, end) + prevScore
                  if(score != Double.NegativeInfinity) {
                    accumArray(acc) = score
                    acc += 1
                  }
                }
              } else {
                while (prevLabel < numLabels) {
                  val prevScore = forwardScores(begin)(prevLabel)
                  if (prevScore != Double.NegativeInfinity) {
                    val score = anchoring.scoreTransition(prevLabel, label, begin, end) + prevScore
                    if(score != Double.NegativeInfinity) {
                      accumArray(acc) = score
                      acc += 1
                    }
                  }

                  prevLabel += 1
                }
              }
            }

            begin += 1
          }
          forwardScores(end)(label) = numerics.logSum(accumArray, acc)
          label += 1
        }

        end += 1
      }
      forwardScores
    }

    /**
     * computes the sum of all derivations, starting from a label that ends at pos, and ending
     * at the end of the sequence
     * @param anchoring anchoring to score spans
     * @tparam L label type
     * @tparam W word type
     * @return backwardScore(pos)(label)
     */
    private def backwardScores[L, W](anchoring: SemiCRF.Anchoring[L, W]): Array[Array[Double]] = {
      val length = anchoring.length
      val numLabels = anchoring.labelIndex.size
      // total completion weight (logSum) for starting from an end at pos with label l
      val backwardScores = Array.fill(length+1, numLabels)(Double.NegativeInfinity)
      util.Arrays.fill(backwardScores(length), 0.0)

      val maxOfSegmentLengths = (0 until numLabels).map(anchoring.maxSegmentLength _).max

      val accumArray = new Array[Double](numLabels * maxOfSegmentLengths)
      var begin = length - 1
      while(begin >= 0) {
        var prevLabel = 0
        while(prevLabel < numLabels) {
          var acc = 0
          var end = if (!anchoring.canStartLongSegment(begin)) begin + 1 else math.min(length, begin + maxOfSegmentLengths)
          while(end > begin) {
            if(anchoring.isValidSegment(begin, end)) {
              var label = 0
              while(label < numLabels) {
                val prevScore = backwardScores(end)(label)
                if (anchoring.maxSegmentLength(label) >= end - begin && prevScore != Double.NegativeInfinity) {
                  val score = anchoring.scoreTransition(prevLabel, label, begin, end) + prevScore
                  if(score != Double.NegativeInfinity) {
                    accumArray(acc) = score
                    acc += 1
                  }
                }

                label += 1
              }
            }
            end -= 1
          }

          backwardScores(begin)(prevLabel) = numerics.logSum(accumArray, acc)
          prevLabel += 1
        }

        begin -= 1

      }

      backwardScores
    }



  }

  @SerialVersionUID(1L)
  case class SpanConstraints(maxLengths: Array[Int],
                             allowedStarts: Array[BitSet],
                             allowedLabels: TriangularArray[BitSet]) {
    def +(constraints: SpanConstraints) = {
      SpanConstraints(Array.tabulate(maxLengths.length)(i => maxLengths(i) max constraints.maxLengths(i)),
        allowedStarts zip constraints.allowedStarts map {case (a,b) => a | b},
        TriangularArray.tabulate(allowedStarts.length+1)((b,e) => allowedLabels(b,e) | constraints.allowedLabels(b, e))
      )
    }

    def spanAllowed(b: Int, e:Int) = allowedStarts(b).nonEmpty && allowedLabels(b,e).nonEmpty
  }

  trait ConstraintSemiCRF[L, W] extends SemiCRF[L, W] {
    def constraints(w: IndexedSeq[W]): SpanConstraints
    def constraints(seg: Segmentation[L,W], keepGold: Boolean = true): SpanConstraints
  }

  @SerialVersionUID(1L)
  class IdentityConstraintSemiCRF[L, W](val labelIndex: Index[L], val startSymbol: L) extends ConstraintSemiCRF[L, W] with Serializable { outer =>
    def anchor(w: IndexedSeq[W]) = new Anchoring[L,W]() {
      def words = w
      def maxSegmentLength(label: Int) = w.size
      def scoreTransition(prev: Int, cur: Int, begin: Int, end: Int) = 0.0
      def labelIndex = outer.labelIndex
      def startSymbol = outer.startSymbol

      def canStartLongSegment(pos: Int): Boolean = true

      def isValidSegment(begin: Int, end: Int): Boolean = true
    }

    private val allLabels = BitSet.fromBitMask((0L to labelIndex.size).toArray)

    private def emptyConstraint(length: Int) = new SpanConstraints(Array.fill(labelIndex.size)(length),
      Array.fill(length)(allLabels), TriangularArray.fill(length+1)(allLabels))

    def constraints(w: IndexedSeq[W]) = emptyConstraint(w.length)

    def constraints(seg: Segmentation[L, W], keepGold: Boolean) = emptyConstraint(seg.length)
  }

  @SerialVersionUID(1L)
  class BaseModelConstraintSemiCRF[L, W](val crf: SemiCRF[L, W], val threshold: Double = 1E-5) extends ConstraintSemiCRF[L, W] with Serializable {
    def startSymbol: L = crf.startSymbol
    def labelIndex: Index[L] = crf.labelIndex

    // TODO: make weak
    @transient
    private var cache = new ConcurrentHashMap[IndexedSeq[W], SpanConstraints]()

    // Don't delete.
    @throws(classOf[IOException])
    @throws(classOf[ClassNotFoundException])
    private def readObject(oin: ObjectInputStream) {
      oin.defaultReadObject()
      cache = new ConcurrentHashMap[IndexedSeq[W], SpanConstraints]()
    }

    def constraints(w: IndexedSeq[W]): SpanConstraints = {
      var c = cache.get(w)
      if(c eq null) {
        c = crf.marginal(w).computeSpanConstraints(threshold)
        cache.put(w, c)
      }

      c
    }

    def constraints(seg: Segmentation[L,W], keepGold: Boolean = true): SpanConstraints = {
      val orig: SpanConstraints = constraints(seg.words)
      if(keepGold) {
        orig + crf.goldMarginal(seg.segments, seg.words).computeSpanConstraints()
      } else {
        orig
      }
    }


    def anchor(w: IndexedSeq[W]): Anchoring[L, W] = {
      val c = constraints(w)

      new Anchoring[L, W] {
        def words: IndexedSeq[W] = w

        def maxSegmentLength(label: Int): Int = c.maxLengths(label)

        def startSymbol: L = crf.startSymbol
        def labelIndex: Index[L] = crf.labelIndex

        def scoreTransition(prev: Int, cur: Int, begin: Int, end: Int): Double =
          numerics.logI(c.allowedLabels(begin, end).contains(cur))

        def canStartLongSegment(pos: Int): Boolean = c.allowedStarts(pos).nonEmpty

        def isValidSegment(begin: Int, end: Int): Boolean = c.allowedLabels(begin, end).nonEmpty
      }

    }
  }


  trait IndexedFeaturizer[L, W] {
    def anchor(w: IndexedSeq[W]):AnchoredFeaturizer[L, W]

    def startSymbol: L

    def labelIndex: Index[L]
    def featureIndex: Index[Feature]
  }

  trait AnchoredFeaturizer[L, W] {
    def featureIndex: Index[Feature]
    def featuresForTransition(prev: Int, cur: Int, begin: Int, end: Int):FeatureVector
  }


  def viterbi[L, W](anchoring: Anchoring[L ,W], id: String=""):Segmentation[L, W] = {
    val length = anchoring.length
    val numLabels = anchoring.labelIndex.size
    // total weight (logSum) for ending in pos with label l.
    val forwardScores = Array.fill(length+1, numLabels)(Double.NegativeInfinity)
    val forwardLabelPointers = Array.fill(length+1, numLabels)(-1)
    val forwardBeginPointers = Array.fill(length+1, numLabels)(-1)
    forwardScores(0)(anchoring.labelIndex(anchoring.startSymbol)) = 0.0

    var end = 1
    while (end <= length) {
      var label = 0
      while (label < numLabels) {
        var begin = math.max(end - anchoring.maxSegmentLength(label), 0)
        while (begin < end) {
          if((anchoring.canStartLongSegment(begin) || begin == end - 1) && anchoring.isValidSegment(begin, end)) {
            var prevLabel = 0
            while (prevLabel < numLabels) {
              val prevScore = forwardScores(begin)(prevLabel)
              if (prevScore != Double.NegativeInfinity) {
                val score = anchoring.scoreTransition(prevLabel, label, begin, end) + prevScore
                if(score > forwardScores(end)(label)) {
                  forwardScores(end)(label) = score
                  forwardLabelPointers(end)(label) = prevLabel
                  forwardBeginPointers(end)(label) = begin
                }
              }

              prevLabel += 1
            }
          }
          begin += 1
        }
        label += 1
      }

      end += 1
    }
    val segments = ArrayBuffer[(L, Span)]()
    def rec(end: Int, label: Int) {
      if(end != 0) {
        val bestStart = forwardBeginPointers(end)(label)
        segments += (anchoring.labelIndex.get(label) -> Span(bestStart, end))
        rec(bestStart, forwardLabelPointers(end)(label))
      }

    }
    rec(length, (0 until numLabels).maxBy(forwardScores(length)(_)))

    Segmentation(segments.reverse, anchoring.words, id)
  }


  def posteriorDecode[L, W](m: Marginal[L, W], id: String = "") = {
    val length = m.length
    val numLabels = m.anchoring.labelIndex.size
    val forwardScores = Array.fill(length+1, numLabels)(0.0)
    val forwardLabelPointers = Array.fill(length+1, numLabels)(-1)
    val forwardBeginPointers = Array.fill(length+1, numLabels)(-1)
    forwardScores(0)(m.anchoring.labelIndex(m.anchoring.startSymbol)) = 1.0

    var end = 1
    while (end <= length) {
      var label = 0
      while (label < numLabels) {
        var begin = math.max(end - m.anchoring.maxSegmentLength(label), 0)
        while (begin < end) {
          var prevLabel = 0
          while (prevLabel < numLabels) {
            val prevScore = forwardScores(begin)(prevLabel)
            if (prevScore != 0.0) {
              val score = m.transitionMarginal(prevLabel, label, begin, end) + prevScore
              if(score > forwardScores(end)(label)) {
                forwardScores(end)(label) = score
                forwardLabelPointers(end)(label) = prevLabel
                forwardBeginPointers(end)(label) = begin
              }
            }

            prevLabel += 1
          }
          begin += 1
        }
        label += 1
      }

      end += 1
    }
    val segments = ArrayBuffer[(L, Span)]()
    def rec(end: Int, label: Int) {
      if(end != 0) {
        val bestStart = forwardBeginPointers(end)(label)
        segments += (m.anchoring.labelIndex.get(label) -> Span(bestStart, end))
        rec(bestStart, forwardLabelPointers(end)(label))
      }

    }
    rec(length, (0 until numLabels).maxBy(forwardScores(length)(_)))

    Segmentation(segments.reverse, m.words, id)
  }
}

