package epic.newfeatures

import breeze.util.Index
import epic.framework.{VisitableMarginal, Feature}

/**
 *
 * @author dlwh
 */
@SerialVersionUID(1L)
class FeatureIndex(val trueIndex: Index[Feature], numHashFeatures: Int=0) extends Index[Feature] with Serializable {
  def apply(t: Feature): Int = {
    val i = trueIndex(t)
    if (i < 0 && numHashFeatures > 0) {
      if(numHashFeatures == 1) {
        trueIndex.size
      } else {
        trueIndex.size + t.hashCode() % numHashFeatures
      }
    } else {
      i
    }
  }

  def unapply(i: Int): Option[Feature] = if(i >= size || i < 0)  None else Some(get(i))

  override def get(i: Int): Feature = {
    if(i >= size || i < 0) {
      throw new NoSuchElementException(s"index $i is not in FeatureIndex of size $size")
    } else if (i < trueIndex.size) {
      trueIndex.get(i)
    } else {
      HashFeature(i - trueIndex.size)
    }
  }

  def pairs: Iterator[(Feature, Int)] = trueIndex.pairs ++ Iterator.range(trueIndex.size,size).map{i => HashFeature(i) -> i}

  def iterator: Iterator[Feature] = pairs.map(_._1)
}

object FeatureIndex {
  def build[Datum, Marg, Vis](data: IndexedSeq[Datum], simpleMarg: Datum=>Marg, vis: (Feature=>Int)=>Vis, numHashFeatures: Int=0)(implicit mm: Marg<:<VisitableMarginal[Vis]):FeatureIndex = {
    val index = Index[Feature]()
    val visitor = vis({index.index _})
    for(d <- data) {
      simpleMarg(d).visit(visitor)
    }
    new FeatureIndex(index, numHashFeatures)
  }
}