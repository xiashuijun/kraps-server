package org.krapsh

import com.typesafe.scalalogging.slf4j.{StrictLogging => Logging}

import org.apache.spark.sql.Row

/**
 * The identifier of a spark session.
 *
 * @param id
 */
case class SessionId(id: String) extends AnyVal

/**
 * The local path in a computation. Does not include the session or the computation.
 */
case class Path(repr: Seq[String]) extends AnyVal

/**
 * The ID of a computation.
 * @param repr
 */
case class ComputationId(repr: String) extends AnyVal

case class GlobalPath(
    session: SessionId,
    computation: ComputationId,
    local: Path) {
  override def toString: String = {
    val p = local.repr.mkString("/")
    s"//${session.id}/${computation.repr}/$p"
  }
}

/**
 * A computation is a unit of computations (as the name suggests).
 *
 * It has a number of inputs, all with no parents, and it has a single output that is
 * an observable.
 *
 * @param id
 * @param items
 */
class Computation private (
    val id: ComputationId,
    // The items are assumed to form a DAG and be presented in topological order.
    // (the first elements are the first to be processed, the last is the final
    // observable).
    val items: Seq[ExecutionItem]) {

  import Computation._

  // TODO(?) should be extended to all the elements we need to track such as caching, files, ...
  lazy val trackedItems: Seq[ExecutionItem] = items.filter(_.locality == Local)

  lazy val output: ExecutionItem = {
    items.filter(_.locality == Local).lastOption.getOrElse {
      throw new Exception(s"computation $id: Could not find a local node in the list $items")
    }
  }

  lazy val trackedItemDependencies: Map[GlobalPath, Seq[GlobalPath]] = {
    // We do not track nodes that have side effects for now.
    val trackedPaths = trackedItems.map(_.path)
    // Include all the dependencies (parents and logical)
    val deps = items.map { item =>
      item.path -> (item.logicalDependencies ++ item.dependencies).map(_.path)
    }.toMap
    trackedItemDeps(trackedPaths, items.map(_.path), deps)
  }

  override def toString: String = {
    s"Computation(id=${id.repr}, items=$items)"
  }
}

object Computation {

  /**
   * Checks that all the items are presented in topological order.
   */
  @throws[KrapshException]
  private def checkTopological(items: Seq[ExecutionItem]): Unit = {
    var seen: Set[GlobalPath] = Set.empty
    for (item <- items) {
      for (parentPath <- item.dependencies.map(_.path)) {
        if (!seen.contains(parentPath)) {
          KrapshException.fail(s"Element out of topological order:" +
            s"parent dependency $parentPath expected before ${item.path}")
        }
      }
      for (parentPath <- item.logicalDependencies.map(_.path)) {
        if (!seen.contains(parentPath)) {
          KrapshException.fail(s"Element out of topological order:" +
            s" logical dependency $parentPath expected before ${item.path}")
        }
      }
      seen = seen + item.path
    }
  }

  def create(id: ComputationId, items: Seq[ExecutionItem]): Computation = {
    checkTopological(items)
    assert(items.last.locality == Local, items)
    new Computation(id, items)
  }

  private type DepMap = Map[GlobalPath, Seq[GlobalPath]]

  // A map of dependencies between local elements.
  private def trackedItemDeps(
      localPaths: Seq[GlobalPath],
      allPaths: Seq[GlobalPath],
      deps: DepMap): DepMap = {
    val targetSet = localPaths.toSet
    // Compute a map for all the nodes, and then filter out for the useful elements.
    var intermediateDep: DepMap = Map.empty
    var finalDep: DepMap = Map.empty
    // Assume a topological order here.
    for (path <- allPaths) {
      // Compute the set of dependencies in all cases:
      val localDeps = deps.getOrElse(path, Seq.empty)
      // All the immediate dependencies in the target set are added
      // For the intermediate dependencies, we add their closure
      val inTarget = localDeps.filter(targetSet.contains)
      val outTarget = localDeps.filterNot(targetSet.contains)
        .flatMap(intermediateDep.get)
        .flatten
      val depClosure = inTarget ++ outTarget

      val filtDepClosure = depClosure.distinct
      if (targetSet.contains(path)) {
        finalDep += path -> filtDepClosure
      } else {
        intermediateDep += path -> filtDepClosure
      }
    }
    finalDep
  }
}

class ResultCache(
  private val map: Map[GlobalPath, ComputationResult] = Map.empty) extends Logging {

  def status(p: GlobalPath): Option[ComputationResult] = map.get(p)

  def finalResult(path: GlobalPath): Option[Row] = {
    map.get(path) match {
      case Some(ComputationDone(row)) => Some(row)
      case _ => None
    }
  }

  def update(ups: Seq[(GlobalPath, ComputationResult)]): ResultCache = {
    var m = this
    for ((p, cr) <- ups) {
      m = this.update(p, cr)
    }
    m
  }

  override def toString: String = {
    s"ResultCache: $map"
  }

  private def update(path: GlobalPath, computationResult: ComputationResult): ResultCache = {
    logger.debug(s"New result for $path: $computationResult")
    val m = map + (path -> computationResult)
    new ResultCache(m)
  }
}

sealed trait Locality
case object Distributed extends Locality
case object Local extends Locality

/**
 * The state of a computation on an observable.
 */
sealed trait ComputationResult
case object ComputationScheduled extends ComputationResult
case object ComputationRunning extends ComputationResult
case class ComputationDone(result: Row) extends ComputationResult
case class ComputationFailed(msg: Throwable) extends ComputationResult