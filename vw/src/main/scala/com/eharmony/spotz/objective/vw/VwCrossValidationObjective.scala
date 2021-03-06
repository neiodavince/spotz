package com.eharmony.spotz.objective.vw

import com.eharmony.spotz.Preamble.Point
import com.eharmony.spotz.objective.Objective
import com.eharmony.spotz.objective.vw.util.{FSVwDatasetFunctions, SparkVwDatasetFunctions, VwCrossValidation}
import com.eharmony.spotz.util.{FileUtil, Logging, SparkFileUtil}
import org.apache.spark.SparkContext

/**
  * Perform K Fold cross validation given a dataset formatted for Vowpal Wabbit.
  *
  * @param numFolds
  * @param vwDataset
  * @param vwTrainParamsString
  * @param vwTestParamsString
  */
abstract class AbstractVwCrossValidationObjective(
    val numFolds: Int,
    @transient val vwDataset: Iterator[String],
    vwTrainParamsString: Option[String],
    vwTestParamsString: Option[String])
  extends Objective[Point, Double]
  with VwFunctions
  with VwCrossValidation
  with Logging {

  val vwTrainParamsMap = parseVwArgs(vwTrainParamsString)
  val vwTestParamsMap = parseVwArgs(vwTestParamsString)

  val foldToVwCacheFiles = kFold(vwDataset, numFolds, vwTrainParamsMap)

  /**
    * This method can run on the driver and/or the executor.  It performs a k-fold cross validation
    * over the vw input dataset passed through the class constructor.  The dataset has been split in
    * such a way that every fold has its own training and test set in the form of VW cache files.
    *
    * @param point a point object representing the hyper parameters to evaluate upon
    * @return Double the cross validated average loss
    */
  override def apply(point: Point): Double = {
    val vwTrainParams = getTrainVwParams(vwTrainParamsMap, point)
    val vwTestParams = getTestVwParams(vwTestParamsMap, point)

    info(s"Vw Training Params: $vwTrainParams")
    info(s"Vw Testing Params: $vwTestParams")

    val avgLosses = (0 until numFolds).map { fold =>
      // Retrieve the training and test set cache for this fold.
      val (vwTrainFilename, vwTestFilename) = foldToVwCacheFiles(fold)
      val vwTrainFile = getCache(vwTrainFilename)
      val vwTestFile = getCache(vwTestFilename)

      // Initialize the model file on the filesystem.  Just reserve a unique filename.
      val modelFile = FileUtil.tempFile(s"model-fold-$fold.vw")

      // Train
      val vwTrainingProcess = VwProcess(s"-f ${modelFile.getAbsolutePath} --cache_file ${vwTrainFile.getAbsolutePath} $vwTrainParams")
      info(s"Executing training: ${vwTrainingProcess.toString}")
      val vwTrainResult = vwTrainingProcess()
      info(s"Train stderr ${vwTrainResult.stderr}")
      assert(vwTrainResult.exitCode == 0, s"VW Training exited with non-zero exit code s${vwTrainResult.exitCode}")

      // Test
      val vwTestProcess = VwProcess(s"-t -i ${modelFile.getAbsolutePath} --cache_file ${vwTestFile.getAbsolutePath} $vwTestParams")
      info(s"Executing testing: ${vwTestProcess.toString}")
      val vwTestResult = vwTestProcess()
      assert(vwTestResult.exitCode == 0, s"VW Testing exited with non-zero exit code s${vwTestResult.exitCode}")
      info(s"Test stderr ${vwTestResult.stderr}")
      val loss = vwTestResult.loss.getOrElse(throw new RuntimeException("Unable to obtain avg loss from test result"))

      // Delete the model.  We don't need these sitting around on the executor's filesystem.
      modelFile.delete()

      loss
    }

    info(s"Avg losses for all folds: $avgLosses")
    val crossValidatedAvgLoss = avgLosses.sum / numFolds
    info(s"Cross validated avg loss: $crossValidatedAvgLoss")

    crossValidatedAvgLoss
  }
}

class SparkVwCrossValidationObjective(
    @transient val sc: SparkContext,
    numFolds: Int,
    vwDataset: Iterator[String],
    vwTrainParamsString: Option[String],
    vwTestParamsString: Option[String])
  extends AbstractVwCrossValidationObjective(numFolds, vwDataset, vwTrainParamsString, vwTestParamsString)
  with SparkVwDatasetFunctions {

  def this(sc: SparkContext,
           numFolds: Int,
           vwDataset: Iterable[String],
           vwTrainParamsString: Option[String],
           vwTestParamsString: Option[String]) = {
    this(sc, numFolds, vwDataset.toIterator, vwTrainParamsString, vwTestParamsString)
  }

  def this(sc: SparkContext,
           numFolds: Int,
           vwDatasetPath: String,
           vwTrainParamsString: Option[String],
           vwTestParamsString: Option[String]) = {
    this(sc, numFolds, SparkFileUtil.loadFile(sc, vwDatasetPath), vwTrainParamsString, vwTestParamsString)
  }
}

class VwCrossValidationObjective(
    numFolds: Int,
    vwDataset: Iterator[String],
    vwTrainParamsString: Option[String],
    vwTestParamsString: Option[String])
  extends AbstractVwCrossValidationObjective(numFolds, vwDataset, vwTrainParamsString, vwTestParamsString)
    with FSVwDatasetFunctions {

  def this(numFolds: Int,
           vwDataset: Iterable[String],
           vwTrainParamsString: Option[String],
           vwTestParamsString: Option[String]) = {
    this(numFolds, vwDataset.toIterator, vwTrainParamsString, vwTestParamsString)
  }

  def this(numFolds: Int,
           vwDatasetPath: String,
           vwTrainParamsString: Option[String],
           vwTestParamsString: Option[String]) = {
    this(numFolds, FileUtil.loadFile(vwDatasetPath), vwTrainParamsString, vwTestParamsString)
  }
}