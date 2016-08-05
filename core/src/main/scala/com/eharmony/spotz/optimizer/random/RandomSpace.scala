package com.eharmony.spotz.optimizer.random

import com.eharmony.spotz.optimizer.{RandomSampler, Space}

import scala.util.Random

/**
  * @author vsuthichai
  */
case class RandomSpace[P]
    (params: Map[String, RandomSampler[_]], seed: Long = 0L)
    (implicit factory: Map[String, _] => P)
  extends Space[P] {

  // Scala 2.10, random is not serializable
  val rng = new Random(seed) with Serializable

  // Get rid of the first value to avoid low entropy in the seed
  rng.nextDouble()

  override def sample: P = {
    val sampledParams = params.map { case (label, sampler) => (label, sampler(rng)) }
    factory(sampledParams)
  }

  def setSeed(newSeed: Long): RandomSpace[P] = {
    this.copy(params = this.params, seed = newSeed)
  }
}