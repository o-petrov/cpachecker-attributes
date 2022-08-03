// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import org.sosy_lab.common.log.LogManager;

enum DeltaDebuggingStage {
  INIT,
  REMOVE_COMPLEMENT,
  REMOVE_DELTA,
  REMOVE_SAFE,
  DONE
}

/**
 * Generic strategy that chooses how to mutate a CFA using Delta Debugging approach.
 *
 * <p>See Zeller, A. (1999). Yesterday, my Program Worked. Today, it Does Not. Why?. In: Nierstrasz,
 * O., Lemoine, M. (eds) Software Engineering — ESEC/FSE ’99. ESEC SIGSOFT FSE 1999 1999. Lecture
 * Notes in Computer Science, vol 1687. Springer, Berlin, Heidelberg. <a
 * href="https://doi.org/10.1007/3-540-48166-4_16">https://doi.org/10.1007/3-540-48166-4_16</a>
 *
 * <p>or <a href="https://www.youtube.com/watch?v=SzRqd4YeLlM">Learning from Code History</a>, a
 * video presentation at Google Tech Talk
 *
 * <p>ddmin algorithm for finding cause of analysis exception:
 *
 * <ol>
 *   <li>Divide objects in CFA into parts, "deltas". A complement is all objects not included the
 *       corresponding delta.
 *   <li>Remove a complement, or to put it the other way, let one delta remain. If the bug remains,
 *       we have to divide remained objects next time in new deltas. If the bug disappears, restore
 *       the complement and remove another one.
 *   <li>After trying all complements, try deltas the same way.
 *   <li>After trying all complements and all deltas divide the remaining in smaller deltas and
 *       repeat the algorithm.
 * </ol>
 *
 * Algorithm ends when there are no unresolved objects. Every tried delta consisting of one object
 * is part of the cause.
 *
 * <p>Algorithm minimizes the cause, but CFA minimization for exception should minimize safe part
 * too (TODO).
 */
// TODO better documentation, choose terms and use them consistently
// (remove delta from CFA vs. from unresolved objects)
abstract class GenericDeltaDebuggingStrategy<RemoveObject, RestoreObject>
    implements CFAMutationStrategy {

  /**
   * Return all possible mutations of this kind in a list, that will be mutated by the strategy.
   * Mutations need to be applicable in arbitrary combinations.
   */
  protected abstract List<RemoveObject> getAllObjects(FunctionCFAsWithMetadata pCfa);
  /** How to apply chosen mutation */
  protected abstract RestoreObject removeObject(
      FunctionCFAsWithMetadata pCfa, RemoveObject pChosen);
  /** How to rollback a mutation */
  protected abstract void restoreObject(FunctionCFAsWithMetadata pCfa, RestoreObject pRemoved);

  /** All objects to investigate in next {@link #mutate} calls */
  private List<RemoveObject> unresolvedObjects = null;
  /** All objects that remain in CFA, but appear to be safe */
  private List<RemoveObject> safeObjects = new ArrayList<>();
  /** All objects that appear to be the cause, i.e. result of DD algorithm */
  private List<RemoveObject> causeObjects = new ArrayList<>();
  /** Objects that are removed in current round */
  private ImmutableList<RemoveObject> currentMutation = null;
  /** Store info to rollback current mutation if needed */
  private ImmutableList<RestoreObject> rollbackInfos = null;

  /** Save stage of DD algorithm between calls to {@link #mutate} */
  private DeltaDebuggingStage stage = DeltaDebuggingStage.INIT;

  private List<ImmutableList<RemoveObject>> deltaList = null;
  private Iterator<ImmutableList<RemoveObject>> deltaIter = null;
  private ImmutableList<RemoveObject> currentDelta = null;

  protected final LogManager logger;
  private final String objectsTitle;

  public GenericDeltaDebuggingStrategy(LogManager pLogger, String pObjectsTitle) {
    Preconditions.checkArgument(!Strings.isNullOrEmpty(pObjectsTitle));
    logger = Preconditions.checkNotNull(pLogger);
    objectsTitle = pObjectsTitle;
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    // corner cases
    switch (stage) {
      case INIT:
        // set up if it is first call
        unresolvedObjects = getAllObjects(pCfa);
        if (unresolvedObjects.isEmpty()) {
          // nothing to do
          logger.log(Level.INFO, "No", objectsTitle, "to mutate");
          causeObjects = ImmutableList.copyOf(causeObjects);
          safeObjects = ImmutableList.copyOf(safeObjects);
          return false;
        }
        logger.log(Level.INFO, "Got", unresolvedObjects.size(), objectsTitle, "to remove");
        resetDeltaListWithOneDelta(ImmutableList.copyOf(unresolvedObjects));
        stage = DeltaDebuggingStage.REMOVE_DELTA;
        return true;

      case REMOVE_DELTA:
        if (!deltaIter.hasNext()) {
          // tried all deltas, partition again
          logger.log(
              Level.INFO,
              "halving remained",
              deltaList.size(),
              "deltas with total",
              unresolvedObjects.size(),
              objectsTitle);
          stage = DeltaDebuggingStage.REMOVE_COMPLEMENT;
          halveDeltas();
        }

        // DD can end only in delta-removing stage, when deltaList is empty
        if (unresolvedObjects.isEmpty()) {
          logger.log(
              Level.INFO,
              "All",
              objectsTitle,
              "are resolved,",
              causeObjects.size(),
              "cause",
              objectsTitle,
              "and",
              safeObjects.size(),
              "safe",
              objectsTitle,
              "remain");
          logger.log(Level.INFO, "Cause", objectsTitle, "are:", causeObjects);
          logger.log(Level.INFO, "Safe", objectsTitle, "are:", safeObjects);
          stage = DeltaDebuggingStage.REMOVE_SAFE;
          resetDeltaListWithOneDelta(ImmutableList.copyOf(safeObjects));
        }

        return true;

      case REMOVE_COMPLEMENT:
        assert !unresolvedObjects.isEmpty();
        if (!deltaIter.hasNext()) {
          // tried all complements, switch to deltas
          stage = DeltaDebuggingStage.REMOVE_DELTA;
          deltaIter = deltaList.iterator();
          currentDelta = null;
        }
        return true;

      case REMOVE_SAFE:
        assert unresolvedObjects.isEmpty();
        if (safeObjects.isEmpty()) {
          safeObjects = ImmutableList.of();
          logger.log(Level.INFO, "No safe", objectsTitle, "to remove");
          stage = DeltaDebuggingStage.DONE;
          return false;
        }
        return true;

      case DONE: // everything was already done
        return false;

      default:
        throw new AssertionError();
    }
  }

  /**
   * Choose some objects from the CFA using Delta Debugging approach and remove them from the CFA.
   *
   * <p>Although this class is described in terms of removing an object, this can actually mean any
   * mutation of some part in CFA, e.g. replacing an edge with another one. Then restoring such an
   * object is replacing new edge back with the old one.
   */
  @Override
  public void mutate(FunctionCFAsWithMetadata pCfa) {
    // set next mutation
    // not in the switch of #canMutate because stage can be changed there
    switch (stage) {
      case REMOVE_COMPLEMENT:
        currentDelta = deltaIter.next();
        currentMutation =
            ImmutableList.copyOf(
                unresolvedObjects.stream().filter(o -> !currentDelta.contains(o)).iterator());
        logger.log(Level.INFO, "removing a complement of", currentMutation.size(), objectsTitle);
        break;


      case REMOVE_DELTA:
        currentDelta = deltaIter.next();
        currentMutation = currentDelta;
        logger.log(Level.INFO, "removing a delta of", currentMutation.size(), objectsTitle);
        break;

      case REMOVE_SAFE:
        currentDelta = deltaIter.next();
        currentMutation = currentDelta;
        logger.log(Level.INFO, "removing a delta of", currentMutation.size(), "safe", objectsTitle);
        break;

      default:
        throw new AssertionError();
    }
    // remove objects (apply mutations)
    rollbackInfos =
        ImmutableList.copyOf(
            currentMutation.stream().map(mutation -> removeObject(pCfa, mutation)).iterator());
  }

  @Override
  public void setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {

    if (stage == DeltaDebuggingStage.REMOVE_SAFE) {
      if (pResult == DDResultOfARun.FAIL) {
        safeObjects.removeAll(currentMutation);
        logger.log(
            Level.INFO, "Successfully removed", currentMutation.size(), "safe", objectsTitle);
        deltaIter.remove();
      } else {
        logger.log(
            Level.WARNING,
            "Can not remove all safe",
            objectsTitle,
            "at once. Removing them as deltas");
        // restore objects
        rollbackInfos.reverse().forEach(r -> restoreObject(pCfa, r));
      }

      if (deltaList.size() == 1) {
        logger.log(
            Level.INFO, "halving single delta of", deltaList.get(0).size(), "safe", objectsTitle);
        halveDeltas();
      } else if (!deltaIter.hasNext()) {
        // tried all deltas, partition again
        logger.log(
            Level.INFO,
            "halving remained",
            deltaList.size(),
            "deltas with total",
            safeObjects.size(),
            "safe",
            objectsTitle);
        halveDeltas();
      }
      return;
    }

    // update safe and unresolved sets if possible
    switch (pResult) {
      case FAIL:
        // Error is present, do not restore removed objects.
        // Removed objects were safe, but are already removed.
        logger.log(
            Level.INFO, "Removed", objectsTitle, "are safe. Cause is inside remain", objectsTitle);
        unresolvedObjects.removeAll(currentMutation);
        break;

      case PASS:
        // No problems occur, last mutations hid the error.
        // Unresolved objects minus applied mutations are actually safe
        // No need to check them, but they remain in CFA.
        logger.log(
            Level.INFO,
            "Cause is inside removed",
            objectsTitle + ". Remained",
            objectsTitle,
            "are safe");
        safeObjects.addAll(unresolvedObjects);
        safeObjects.removeAll(currentMutation);
        unresolvedObjects.retainAll(currentMutation);

        // restore objects
        rollbackInfos.reverse().forEach(r -> restoreObject(pCfa, r));
        break;

      case UNRESOLVED:
        // some other problem, just undo mutation
        logger.log(
            Level.INFO,
            "Some of removed",
            objectsTitle,
            "are needed for correct run. No",
            objectsTitle,
            "resolved");
        rollbackInfos.reverse().forEach(r -> restoreObject(pCfa, r));
        break;

      default:
        throw new AssertionError();
    }

    // TODO REMOVE_WHOLE AS DELTA
    // update delta list too
    if ((stage == DeltaDebuggingStage.REMOVE_DELTA && pResult == DDResultOfARun.FAIL)
        || (stage == DeltaDebuggingStage.REMOVE_COMPLEMENT && pResult == DDResultOfARun.PASS)) {
      logger.log(Level.INFO, "This delta is safe");
      // delta is safe, complement has the cause
      // Remove delta from list
      deltaIter.remove();

    } else if ((stage == DeltaDebuggingStage.REMOVE_DELTA && pResult == DDResultOfARun.PASS)
        || (stage == DeltaDebuggingStage.REMOVE_COMPLEMENT && pResult == DDResultOfARun.FAIL)) {
      logger.log(Level.INFO, "This complement is safe");
      // delta has the cause, complement is safe
      // Remove complement from list, i.e. make list of one delta, and then split it.
      resetDeltaListWithOneDelta(currentDelta);
      logger.log(Level.INFO, "halving single delta of", deltaList.get(0).size(), objectsTitle);
      halveDeltas();
      // switch from REMOVE_COMPLEMENT
      stage = DeltaDebuggingStage.REMOVE_DELTA;

    } else {
      // Can neither assume safe/cause nor update delta list if pResult == UNRESOLVED
      assert stage != DeltaDebuggingStage.INIT;
      assert pResult == DDResultOfARun.UNRESOLVED;
    }

    currentMutation = null;
    rollbackInfos = null;
  }

  /**
   * Return the CFA parts that cause exception in analysis fail. Do not call this method until
   * {@link #canMutate} returns false, as the cause may be not identified.
   *
   * @return all the objects that remain in CFA and are not safe.
   * @throws IllegalStateException if called before this strategy has resolved all objects.
   */
  public ImmutableList<RemoveObject> getCauseObjects() {
    Preconditions.checkState(
        unresolvedObjects.isEmpty(),
        "Can not identify cause, as there are",
        unresolvedObjects.size(),
        objectsTitle,
        "to investigate");
    return ImmutableList.copyOf(causeObjects);
  }

  /**
   * Return the safe part that remains in the CFA. Do not call this method until {@link #canMutate}
   * returns false, as the cause is not identified (and it will throw).
   *
   * @return all the objects that remain in CFA and are safe.
   * @throws IllegalStateException if called before this strategy has resolved all objects.
   */
  public ImmutableList<RemoveObject> getRemainedSafeObjects() {
    Preconditions.checkState(
        stage == DeltaDebuggingStage.DONE,
        "Can not identify remained safe",
        objectsTitle,
        "as strategy has not finished");
    return ImmutableList.copyOf(safeObjects);
  }

  // XXX Some objects are exclusive to others, e.g.
  // 1. We can't exactly remove both branches of one branching node.
  // 2. See SingleNodeRemover
  // So, number of objects is likely to be not consistent during run.
  // XXX mark exclusive alternatives? or just abstract count?
  private void halveDeltas() {
    var result = new ArrayList<ImmutableList<RemoveObject>>(deltaList.size() * 2);

    for (var delta : deltaList) {
      assert !delta.isEmpty();
      if (delta.size() == 1) {
        // This delta is one object, that is not cause by itself
        // and can not be safe by itself. So, it is part of the cause.
        causeObjects.add(delta.get(0));
        (stage == DeltaDebuggingStage.REMOVE_SAFE ? safeObjects : unresolvedObjects)
            .remove(delta.get(0));
      } else {
        int half = (1 + delta.size()) / 2;
        result.add(delta.subList(0, half));
        result.add(delta.subList(half, delta.size()));
      }
    }

    resetDeltaList(result);
  }

  private void resetDeltaList(List<ImmutableList<RemoveObject>> pDeltaList) {
    deltaList = pDeltaList;
    deltaIter = deltaList.iterator();
    currentDelta = null;
  }

  private void resetDeltaListWithOneDelta(ImmutableList<RemoveObject> pOnlyDelta) {
    List<ImmutableList<RemoveObject>> list = new ArrayList<>(1);
    list.add(pOnlyDelta);
    resetDeltaList(list);
  }
}
