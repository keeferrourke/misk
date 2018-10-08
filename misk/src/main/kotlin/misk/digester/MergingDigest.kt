package misk.digester

import misk.proto.tdigest.Centroid
import misk.proto.tdigest.MergingDigestData
import kotlin.math.PI
import kotlin.math.asin

class MergingDigest {

  interface MergingDigestStruct {
    var compression: Double

    //main list of centroids
    var mainCentroids: MutableList<Centroid> //this list can be a mutableList instead?
    //total weight of unmerged main centroids
    var mainWeight: Double

    //centroids that have been added but not yet merged into main list
    var tempCentroids: MutableList<Centroid>
    //total weight of unmerged temp centroids
    var tempWeight: Double

    var min: Double
    var max: Double
  }

  /**
   * Initializes a new merging t-digest using the given compression parameter.
   * Lower compression values result in reduced memory consumption and less
   * precision, especially at the median. Values from 20 to 1000 are recommended
   * in Dunning's paper.
   */
  fun newMerging(compressionValue: Double): MergingDigestStruct {
    // this is a provable upper bound on the size of the centroid list

    //Todo: Restrict site of mainCentroid - is this even necessary?
   // var sizeBound = ((PI * compressionValue / 2) + 0.5).toInt()

    return object : MergingDigestStruct {
      override var compression = compressionValue
      override var mainCentroids =  mutableListOf<Centroid>()
      override var tempCentroids = mutableListOf<Centroid>()
      override var min = Double.POSITIVE_INFINITY
      override var max = Double.NEGATIVE_INFINITY
      override var mainWeight = 0.0
      override var tempWeight = 0.0
    }
  }

  /**
   *  NewMergingFromData returns a MergingDigest with values initialized from
   * MergingDigestData.  This should be the way to generate a MergingDigest
   * from a serialized protobuf.
   */
  fun newMergingFromData(mergingDigestData: MergingDigestData): MergingDigestStruct {
    var td = object : MergingDigestStruct {
      override var compression = mergingDigestData.compression
      //todo: this is pass by reference? probably should be by value
      override var mainCentroids = mergingDigestData.main_centroids.toMutableList()
      override var tempCentroids = mutableListOf<Centroid>()
      override var min = mergingDigestData.min
      override  var max = mergingDigestData.max
      override var mainWeight = 0.0
      override var tempWeight = 0.0
    }

    td.mainWeight = 0.0
    for (c in td.mainCentroids) {
      td.mainWeight += c.weight
    }

    return td
  }

  /**
   * function taken from stripes implementation of merging_digest
   * https://github.com/stripe/veneur/blob/master/tdigest/merging_digest.go
   */
  fun estimateTempBuffer(compressionValue: Double): Int {
    // this heuristic comes from Dunning's paper
    // 925 is the maximum point of this quadratic equation
    var tempCompression = minOf(925.0, maxOf(20.0, compressionValue))
    return (7.5 + 0.37*tempCompression - 2e-4*tempCompression*tempCompression).toInt()
  }

  /**
   * Adds a new value to the t-digest, with a given weight that must be positive.
   * Infinities and NaN cannot be added.
   */
  fun add(mergingDigestObject: MergingDigestStruct, value: Double, weight: Double) {
    if (Double.NaN == value || Double.POSITIVE_INFINITY == value || weight <= 0) {
      Exception("invalid value added")
    }

    if (mergingDigestObject.tempCentroids.size == estimateTempBuffer(mergingDigestObject.compression)) {
          mergeAllTemps(mergingDigestObject) //<- will we still need this?
    }

    mergingDigestObject.min = minOf(mergingDigestObject.min, value)
    mergingDigestObject.max = maxOf(mergingDigestObject.max, value)

    var next = Centroid(
      value,
      weight,
      mutableListOf<Double>()
    )

    mergingDigestObject.tempCentroids.add(next)
    mergingDigestObject.tempWeight += weight

  }

  /**
   * combine the mainCentroids and tempCentroids in-place into mainCentroids
   */
  fun mergeAllTemps(mergingDigestObject: MergingDigestStruct) {
    // this optimization is really important! if you remove it, the main list
    // will get merged into itself every time this is called
    if (mergingDigestObject.tempCentroids.size == 0) {
      return
    }

    // we iterate over both centroid lists from least to greatest mean, so first
    // we have to sort this one
    mergingDigestObject.tempCentroids.sortBy { it.mean }
    var tempIndex = 0

    // total weight that the final t-digest will have, after everything is merged
    var totalWeight = mergingDigestObject.mainWeight + mergingDigestObject.tempWeight
    // how much weight has been merged so far
    var mergedWeight = 0.0
    // the index of the last quantile to be merged into the previous centroid
    // this value gets updated each time we split a new centroid out instead of
    // merging into the current one
    var lastMergedIndex = 0.0
    // since we will be merging in-place into td.mainCentroids, we need to keep
    // track of the indices of the remaining elements
    var actualMainCentroids = mergingDigestObject.mainCentroids.toMutableList()
    mergingDigestObject.mainCentroids = mergingDigestObject.mainCentroids.subList(0, 0) //this should be clear list
    // to facilitate the in-place merge, we will need a place to store the main
    // centroids that would be overwritten - we will use space from the start
    // of tempCentroids for this
    var swappedCentroids = mutableListOf<Centroid>()

    while ((actualMainCentroids.size + swappedCentroids.size != 0) || tempIndex < mergingDigestObject.tempCentroids.size) {
      var nextTemp: Centroid = Centroid (
        Double.POSITIVE_INFINITY,
        0.0,
        mutableListOf<Double>()
      )
      if (tempIndex < mergingDigestObject.tempCentroids.size) {
        nextTemp = mergingDigestObject.tempCentroids[tempIndex]
      }

      var nextMain: Centroid = Centroid (
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        mutableListOf<Double>()
      )
      if (swappedCentroids.size != 0) {
        nextMain = swappedCentroids[0]
      } else if (actualMainCentroids.size != 0) {
        nextMain = actualMainCentroids[0]
      }

      if (nextMain.mean < nextTemp.mean) {
        if (actualMainCentroids.size != 0) {
          if (swappedCentroids.size != 0) {
            // if this came from swap, before merging, we have to save
            // the next main centroid at the end
            // this copy is probably the most expensive part of the
            // in-place merge, compared to merging into a separate buffer
            swappedCentroids.removeAt(0)
            swappedCentroids[(swappedCentroids.size)-1] = actualMainCentroids[0]
          }
          actualMainCentroids.removeAt(0)
        } else {
          // the real main has been completely exhausted, so we're just
          // cleaning out swapped mains now
          swappedCentroids.removeAt(0)
        }

        lastMergedIndex = mergeOne(mergingDigestObject, mergedWeight, totalWeight, lastMergedIndex, nextMain)
        mergedWeight += nextMain.weight
      } else {
        // before merging, we have to save the next main centroid somewhere
        // else, so that we don't overwrite it
        if (actualMainCentroids.size != 0) {
          swappedCentroids.add(actualMainCentroids[0])
          actualMainCentroids.removeAt(0)
        }
        tempIndex++

        lastMergedIndex = mergeOne(mergingDigestObject, mergedWeight, totalWeight, lastMergedIndex, nextTemp)
        mergedWeight += nextTemp.weight
      }
    }

    mergingDigestObject.tempCentroids.clear()
    mergingDigestObject.tempWeight = 0.0
    mergingDigestObject.mainWeight = totalWeight
  }

  /**
   * merges a single centroid into the mergedCentroids list
   * note that "merging" sometimes creates a new centroid in the list, however
   * the length of the list has a strict upper bound (see constructor)
   */
  fun mergeOne(
    mergingDigestObject : MergingDigestStruct,
      beforeWeight: Double,
      totalWeight: Double,
      beforeIndex: Double,
      next: Centroid
  ): Double {

    // compute the quantile index of the element we're about to merge
    var nextIndex = indexEstimate(mergingDigestObject, (beforeWeight + next.weight) / totalWeight)

    if (nextIndex-beforeIndex > 1 || mergingDigestObject.mainCentroids.size == 0) {
      // the new index is far away from the last index of the current centroid
      // therefore we cannot merge into the current centroid or it would
      // become too wide, so we will append a new centroid
      mergingDigestObject.mainCentroids.add(next)
      // return the last index that was merged into the previous centroid
      return indexEstimate(mergingDigestObject,beforeWeight / totalWeight)
    } else {
      // the new index fits into the range of the current centroid, so we
      // combine it into the current centroid's values
      // this computation is known as welford's method, the order matters
      // weight must be updated before mean

      //TODO: is this thread safe? doublecheck that this successfully updates all properties
      var mainCentroidsTemp = mergingDigestObject.mainCentroids[mergingDigestObject.mainCentroids.size-1]
      var builder = mainCentroidsTemp.newBuilder()
      builder.weight += next.weight
      builder.mean +=
          (next.mean - mergingDigestObject.mainCentroids[mergingDigestObject.mainCentroids.size-1].mean) *
          next.weight / mergingDigestObject.mainCentroids[mergingDigestObject.mainCentroids.size-1].weight

      // we did not create a new centroid, so the trailing index of the previous
      // centroid remains
      return beforeIndex
    }
  }

  /**
   * given a quantile, estimate the index of the centroid that contains it using
   * the given compression
   */
  fun indexEstimate(mergingDigestObject: MergingDigestStruct, quantile: Double) : Double {
    return mergingDigestObject.compression * ((asin(2*quantile-1) / PI) + 0.5).toFloat()
  }

  /**
   * Returns a value such that the fraction of values in td below that value is
   * approximately equal to quantile. Returns NaN if the digest is empty.
   */
  fun quantile(mergingDigestObject: MergingDigestStruct, quantile: Double): Double {
    if (quantile < 0 || quantile > 1) {
      throw IndexOutOfBoundsException("quantile out of bounds")
    }
    mergeAllTemps(mergingDigestObject)

    // add up the weights of centroids in ascending order until we reach a
    // centroid that pushes us over the quantile
    var q = quantile * mergingDigestObject.mainWeight
    var weightSoFar = 0.0
    var lowerBound = mergingDigestObject.min

    val iterator = mergingDigestObject.mainCentroids.iterator()

    for((i, c) in iterator.withIndex()) {
      var upperBound = centroidUpperBound(mergingDigestObject, i)
      if (q <= weightSoFar+c.weight) {
        // the target quantile is somewhere inside this centroid
        // we compute how much of this centroid's weight falls into the quantile
        var proportion = (q - weightSoFar) / c.weight
        // and interpolate what value that corresponds to inside a uniform
        // distribution
        return lowerBound + (proportion * (upperBound - lowerBound))
      }

      // the quantile is above this centroid, so sum the weight and carry on
      weightSoFar += c.weight
      lowerBound = upperBound
    }

    // should never be reached unless empty, since the final comparison is
    // q <= td.mainWeight
    return Double.NaN
  }

  /**
   *  we assume each centroid contains a uniform distribution of values
   * the lower bound of the distribution is the midpoint between this centroid and
   * the previous one (or the minimum, if this is the lowest centroid)
   * similarly, the upper bound is the midpoint between this centroid and the
   * next one (or the maximum, if this is the greatest centroid)
   * this function returns the position of the upper bound (the lower bound is
   * equal to the upper bound of the previous centroid)
   * this assumption is justified empirically in dunning's paper
   * TODO: does this assumption actually apply to our implementation?
   */
  fun centroidUpperBound(mergingDigestObject: MergingDigestStruct, i: Int): Double {
    if (i != mergingDigestObject.mainCentroids.size-1) {
      return (mergingDigestObject.mainCentroids[i+1].mean + mergingDigestObject.mainCentroids[i].mean) / 2
    } else {
      return mergingDigestObject.max
    }
  }

  /**
   * merge another digest into this one. Neither td nor other can be shared
   * concurrently during the execution of this method.
   */
  fun merge( mergingDigestObject: MergingDigestStruct, other: MergingDigestStruct) {

    //end inclusive
    var shuffledIndices = IntArray(other.mainCentroids.size) { it }.toMutableList()
    shuffledIndices.shuffle()

    for (i in shuffledIndices) {
        add(mergingDigestObject, other.mainCentroids[i].mean, other.mainCentroids[i].weight)
    }

    // we did not merge other's temps, so we need to add those too
    // they're unsorted so there's no need to shuffle them
    for (centroid in other.tempCentroids) {
      add(mergingDigestObject, centroid.mean, centroid.weight)
    }
  }

  /**
   * MergingDigestData contains all fields necessary to generate a MergingDigest.
   * This type should generally just be used when serializing MergingDigest's,
   * and doesn't have much of a purpose on its own.
   */
  fun data(mergingDigestObject: MergingDigestStruct): MergingDigestData {
    mergeAllTemps(mergingDigestObject)
    return MergingDigestData(
        mergingDigestObject.mainCentroids,
        mergingDigestObject.compression,
        mergingDigestObject.min,
        mergingDigestObject.max
    )
  }
}