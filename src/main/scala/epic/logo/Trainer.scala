package epic.logo

import java.util.Arrays
import scala.collection.mutable.ArrayBuffer
import scala.runtime.DoubleRef
import scala.util.Random
import breeze.util.HashIndex
import breeze.util.SerializableLogging
import breeze.util.Index
import breeze.linalg.DenseVector
import breeze.math.MutableInnerProductModule
import scala.reflect.ClassTag

object Trainer {
  def newL1MaxMarginTrainer[T, Y, W](oracleInferencer : OracleInferencer[T, Y, W], argmaxer : LossAugmentedArgmaxInferencer[T, Y, W],
                                  iterationCallback : IterationCallback[T, W] = NullIterationCallback[T, W](),
                                  C : Double = 1.0,
                                  maxNumIters : Int = 100, opts : LogoOpts = new LogoOpts(),
                                  addInitialConstraint : Option[FeatureVector[W]] = None)(implicit space: MutableInnerProductModule[W, Double]) =
    {
      val decoder = new LossAugmentedMaxMarginDecoder(oracleInferencer, argmaxer)
      val updater = new L1Updater[W](C)
      val objective = new L1Objective[W](C)
      val convergenceChecker = new ObjectiveFunctionConvergenceChecker(objective, maxNumIters, iterationCallback,opts.convergenceTolerance)
      new Trainer(convergenceChecker, iterationCallback, decoder, updater, opts, online = false,
        initialConstraintAndAlpha = addInitialConstraint.map(c => ((c, 0.0), C)))
    }

  def newL1MIRATrainer[T, Y, W](oracleInferencer : OracleInferencer[T, Y, W], argmaxer : LossAugmentedArgmaxInferencer[T, Y, W],
                             iterationCallback : IterationCallback[T, W], C : Double = 1.0,
                             maxNumIters : Int = 100, opts : LogoOpts = new LogoOpts(), average : Boolean = true,
                             addInitialConstraint : Option[FeatureVector[W]] = None) =
    {
      val decoder = new LossAugmentedMaxMarginDecoder(oracleInferencer, argmaxer)
      val updater = new L1Updater[W](C)
      val convergenceChecker = new FixedIterationConvergenceChecker[W](maxNumIters)
      new Trainer(convergenceChecker, iterationCallback, decoder, updater, opts, online = true, average = average,
        initialConstraintAndAlpha = addInitialConstraint.map(c => ((c, 0.0), C)))
    }

  def newPerceptronTrainer[T, Y, W](oracleInferencer : OracleInferencer[T, Y, W], argmaxer : ArgmaxInferencer[T, Y, W],
                                 iterationCallback : IterationCallback[T, W], learningRate : Double = 1.0,
                                 maxNumIters : Int = 100, opts : LogoOpts = new LogoOpts(), average : Boolean = true) =
    {
      val decoder = new MaxMarginDecoder(oracleInferencer, argmaxer)
      val updater = new FixedStepSizeUpdater[W](_ => learningRate, Double.PositiveInfinity)
      val convergenceChecker = new FixedIterationConvergenceChecker[W](maxNumIters)
      new Trainer(convergenceChecker, iterationCallback, decoder, updater, opts, online = true, average = average)
    }

  def newL2MaxMarginTrainer[T, Y, W](oracleInferencer : OracleInferencer[T, Y, W], argmaxer : LossAugmentedArgmaxInferencer[T, Y, W],
                                  iterationCallback : IterationCallback[T, W], C : Double = 1.0,
                                  maxNumIters : Int = 100, opts : LogoOpts = new LogoOpts(),
                                  addInitialConstraint :  Option[FeatureVector[W]] = None)(implicit space: MutableInnerProductModule[W, Double]) =
    {
      val decoder = new LossAugmentedMaxMarginDecoder(oracleInferencer, argmaxer)
      val updater = new L2Updater[W](C)
      val objective = new L2Objective[W](C)
      val convergenceChecker = new ObjectiveFunctionConvergenceChecker(objective, maxNumIters, iterationCallback,opts.convergenceTolerance)
      new Trainer(convergenceChecker, iterationCallback, decoder, updater, opts, online = false,
        initialConstraintAndAlpha = addInitialConstraint.map(c => ((c, 0.0), C)))
    }

  def newL1LogLossTrainer[T, Y, W](oracleInferencer : OracleInferencer[T, Y, W], summer : ExpectationInferencer[T, W],
                                iterationCallback : IterationCallback[T, W], C : Double = 1.0,
                                maxNumIters : Int = 100, opts : LogoOpts = new LogoOpts(),
                                addInitialConstraint : Option[FeatureVector[W]] = None)(implicit space: MutableInnerProductModule[W, Double]) =
    {
      val decoder = new LogLikelihoodDecoder(oracleInferencer, summer)
      val updater = new L1Updater[W](C)
      val objective = new L1Objective[W](C)
      val convergenceChecker = new ObjectiveFunctionConvergenceChecker(objective, maxNumIters, iterationCallback,opts.convergenceTolerance)
      new Trainer(convergenceChecker, iterationCallback, decoder, updater, opts, online = false,
        initialConstraintAndAlpha = addInitialConstraint.map(c => ((c, 0.0), C)))
    }

  def newL1LogLossMIRATrainer[T, Y, W](oracleInferencer : OracleInferencer[T, Y, W], summer : ExpectationInferencer[T, W],
                                    iterationCallback : IterationCallback[T, W], C : Double = 1.0,
                                    maxNumIters : Int = 100, opts : LogoOpts = new LogoOpts(), average : Boolean = true,
                                    addInitialConstraint: Option[FeatureVector[W]] = None) =
    {
      val decoder = new LogLikelihoodDecoder(oracleInferencer, summer)
      val updater = new L1Updater[W](C)
      val convergenceChecker = new FixedIterationConvergenceChecker[W](maxNumIters)
      new Trainer(convergenceChecker, iterationCallback, decoder, updater, opts, online = true, average = average,
        initialConstraintAndAlpha = addInitialConstraint.map(c => ((c, 0.0), C)))
    }

  def newStochasticGradientDescentTrainer[T, Y, W](oracleInferencer : OracleInferencer[T, Y, W], summer : ExpectationInferencer[T, W],
                                                iterationCallback : IterationCallback[T, W], C : Double = 1.0, learningRate : Double = 1.0,
                                                maxNumIters : Int = 100, opts : LogoOpts = new LogoOpts(), average : Boolean = true) =
    {
      val decoder = new LogLikelihoodDecoder(oracleInferencer, summer)
      val updater = new FixedStepSizeUpdater[W](iter => learningRate / Math.sqrt(iter + 1), C)
      val convergenceChecker = new FixedIterationConvergenceChecker[W](maxNumIters)
      new Trainer(convergenceChecker, iterationCallback, decoder, updater, opts, online = true, average = average)
    }

  def newL1MarginRankTrainer[T, Y, W](argmaxer : LossAugmentedArgmaxInferencer[T, Y, W],
                                   iterationCallback : IterationCallback[T, W], C : Double = 1.0,
                                   gamma : Double = 0.0, maxNumIters : Int = 100, opts : LogoOpts = new LogoOpts(),
                                   addInitialConstraint :  Option[FeatureVector[W]] = None)(implicit space: MutableInnerProductModule[W, Double]) =
    {
      val decoder = new MaxMarginRankingDecoder(argmaxer, gamma)
      val updater = new L1Updater[W](C)
      val objective = new L1Objective[W](C)
      val convergenceChecker = new ObjectiveFunctionConvergenceChecker(objective, maxNumIters, iterationCallback,opts.convergenceTolerance)
      new Trainer(convergenceChecker, iterationCallback, decoder, updater, opts, online = false,
        initialConstraintAndAlpha = addInitialConstraint.map(c => ((c, 0.0), C)))
    }

  def trainL1MaxMarginMulticlassClassifier[L, F, W](
    labels: IndexedSeq[L],
    data: Seq[LabeledDatum[L, F]],
    labelConjoiner: (L, F) => W,
    initialConstraint: FeatureVector[W],
    iterationCallback: IterationCallback[LabeledDatum[L, F], W] = new NullIterationCallback[LabeledDatum[L, F], W](),
    oneSlackFormulation: Boolean = true,
    C: Double = 1.0,
    maxNumIters: Int = 100, opts: LogoOpts = new LogoOpts())(implicit space: MutableInnerProductModule[W, Double]) = {

    val argmaxer = new MulticlassLossAugmentedArgmaxInferencer[L, F, W](labels, labelConjoiner)
    val oracler = new MulticlassOracleInferencer[L, F, W](labels, labelConjoiner)
    val weights = if (oneSlackFormulation) {
      val oneSlackIterCallBack = new IterationCallback[Seq[LabeledDatum[L, F]], W]() {
        override def startIteration(iter: Int, weights: Weights[W]): Unit = iterationCallback.startIteration(iter, weights)

        override def endIteration(iter: Int, weights: Weights[W]): Unit = iterationCallback.endIteration(iter, weights)

        override def objectiveValCheck(primal: Double, dual: Double, iter: Int, weights: Weights[W]): Unit =
          iterationCallback.objectiveValCheck(primal, dual, iter, weights)

        override def converged(weights: Weights[W], data: Seq[Instance[Seq[LabeledDatum[L, F]], W]], iter: Int, numNewConstraints: Int): Boolean = {
          iterationCallback.converged(weights, data.head.x.map(new Instance[LabeledDatum[L, F], W](_)), iter, numNewConstraints)
        }

      }
      val trainer = newL1MaxMarginTrainer(new MulticlassOneSlackOracleInferencer(oracler),
        new MulticlassOneSlackLossAugmentedArgmaxInferencer(argmaxer, initialConstraint),
        oneSlackIterCallBack, C, maxNumIters, opts)
      trainer.train(Seq(new Instance(data)),
        new Weights(space.zeroLike(initialConstraint.data)))
    } else {
      val trainer = newL1MaxMarginTrainer(oracler, argmaxer, iterationCallback, C, maxNumIters, opts)
      trainer.train(data.map(new Instance[LabeledDatum[L, F], W](_)),
        new Weights(space.zeroLike(initialConstraint.data)))
    }
    new MulticlassClassifier[L, F, W](weights, argmaxer)
  }
}

class Trainer[T, W](val convergenceChecker : ConvergenceChecker[W],
                 val iterationCallback : IterationCallback[T, W], val decoder : Decoder[T, W], val updater : Updater[W],
                 val opts : LogoOpts = new LogoOpts(),
                 initialConstraintAndAlpha : Option[((FeatureVector[W], Double), Double)] = None,
                 val online : Boolean = false,
                 val average : Boolean = false) extends SerializableLogging {

  final val eps = opts.constraintEpsilon
  final val numInnerOptimizationLoops = if (online) 1 else opts.numInnerOptimizationLoops
  final val numOuterOptimizationLoops = if (online) 0 else opts.numOuterOptimizationLoops

  def train(data : Seq[Instance[T, W]], initWeights : Weights[W]) = {
    val n = data.length
    val w = if (online) initWeights else {
      if (initWeights.norm != 0.0) {
        // Because we use a primal-dual method, we'd have to solve for the right dual coordinates
        // to get a desired initial weight vector. By always starting at 0, the right dual coordinates
        // are also all 0.
        logger.warn("Warning, in batch mode, forcing initial weights to be all zero. ")
        initWeights.zeroOut()
      }
      initWeights
    }
    val average_w = if (online && average) w else null
    val maxIters = 10
    var iteration = 0
    var numAdded = 0
    val shuffleRand = if (opts.shuffleSeed < 0) new Random() else new Random(opts.shuffleSeed)
    do {
      numAdded = 0
      val coll = data.zipWithIndex.map { case (instance, instanceNum) => MinibatchInput(instance, instanceNum) }
      var iter = coll.iterator
      iterationCallback.startIteration(iteration, w)

      var currStart = 0;
      while (iter.hasNext) {
        val currMiniBatchUnshuffled = consume(iter, Math.min(data.length, opts.miniBatchSize))
        val currMiniBatch =
          if (opts.shuffleMinibatches)
          shuffleRand.shuffle(currMiniBatchUnshuffled.toBuffer).toArray
        else
          currMiniBatchUnshuffled
        iterationCallback.startMinibatch(iteration, w, currMiniBatch)
        val decodedMiniBatch = for (MinibatchInput(instance, instanceNum) <- currMiniBatch) yield {
          val (df, l) = decoder.decode(w, instance.x)
          if (!online) {
            if (iteration == 0 && initialConstraintAndAlpha.isDefined) {
              val ((df_, l_), alpha) = initialConstraintAndAlpha.get
              instance.constraints.append((df_, l_))
              instance.alphas.append(alpha)
            }
            val newSlack = l - df * w
            val currSlack = updater.currentSlack(instance, w)
            if (newSlack < currSlack - eps) {
              logger.warn(
                s"Found new slack $newSlack which should be higher than current slack $currSlack. " +
                  s"This usually means loss-augmented decoding is not returning the true optimum.")
            }
            if (newSlack > currSlack + eps) {
              instance.constraints.append((df, l))
              instance.alphas.append(0.0)
              numAdded += 1
            }
          }
          MinibatchOutput(instance, instanceNum, df, l)
        }
        iterationCallback.endMinibatch(iteration, w, decodedMiniBatch)
        loopWhile(numInnerOptimizationLoops) {
          val numChanges = decodedMiniBatch.count {
            case MinibatchOutput(instance, instanceNum, df, l) =>
              if (online) {
                val constraints = ArrayBuffer((df, l)) ++ initialConstraintAndAlpha.map(_._1).toList
                val alphas = ArrayBuffer(0.0) ++ initialConstraintAndAlpha.map(_._2).toList
                updater.update(constraints, alphas, new DoubleRef(0.0), w, n, iteration)
              } else
                updater.update(instance.constraints, instance.alphas, instance.slack, w, n, iteration)
          }
          numChanges > 0
        }
      }
      loopWhile(numOuterOptimizationLoops) {
        data.count { instance =>
          loopWhile(numInnerOptimizationLoops) {
            updater.update(instance.constraints, instance.alphas, instance.slack, w, n, iteration)
          } > 1
        } > 0
      }
      iterationCallback.endIteration(iteration, w)
      if (average) {
        average_w *= iteration / (iteration + 1)
        average_w.increment(w, 1.0 / (iteration + 1))
      }
      iteration += 1
    } while (!iterationCallback.converged(w, data, iteration, numAdded) && !convergenceChecker.converged(w, data, iteration, numAdded))

    if (average) average_w else w // return

  }

  /**
   * Loop for at most maxLoops iterations until body returns false
   *
   * @return Number of times the body executed
   */
  private def loopWhile(maxLoops : Int)(body : => Boolean): Int = {
    var finished = false
    var loop = 0
    while (loop < maxLoops && !finished) {
      finished = !body
      loop += 1
    }
    loop
  }

  private def consume[A: ClassTag](iter : Iterator[A], num : Int) = {
    val ret = new ArrayBuffer[A]()
    ret.sizeHint(num)
    for (i <- 0 until num if iter.hasNext) {
      ret += iter.next
    }
    ret.toArray
  }

}
