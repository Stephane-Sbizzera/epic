package epic.logo
import breeze.util.Index
import breeze.math.MutableInnerProductModule

class MulticlassLossAugmentedArgmaxInferencer[L, F, W](validLabels: IndexedSeq[L], labelConjoiner: (L, F) => W)
(implicit space: MutableInnerProductModule[W, Double])
    extends LossAugmentedArgmaxInferencer[LabeledDatum[L, F], L, W] {

  def lossAugmentedArgmax(weights: Weights[W], instance: LabeledDatum[L, F],
                          weightsWeight: Double, lossWeight: Double): (L, FeatureVector[W], Double) = {
    validLabels.map(label => {
      val loss = if (instance.label == null || label.equals(instance.label)) 0.0 else 1.0
      val labeledFeatureVector = FeatureVector(labelConjoiner(label, instance.features))
      (label,labeledFeatureVector, loss)
    }).maxBy { case (label, features, loss) => weightsWeight * (weights * features) + lossWeight * loss }
  }

}
