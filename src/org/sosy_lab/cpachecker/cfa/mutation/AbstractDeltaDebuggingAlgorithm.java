// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.defaults.MultiStatistics;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;

/** General strategy that chooses how to mutate a CFA using Delta Debugging approach. */
abstract class AbstractDeltaDebuggingAlgorithm<Element> implements CFAMutationStrategy {

  protected enum DeltaDebuggingStage {
    INIT,
    REMOVE_COMPLEMENT,
    REMOVE_DELTA,
    DONE
  }

  /** All elements to investigate in next {@link #mutate} calls */
  private List<Element> unresolvedElements = null;
  /** All elements that remain in CFA, but appear to be safe */
  private List<Element> safeElements = null;
  /** All elements that appear to be the cause, i.e. result of DD algorithm */
  private List<Element> causeElements = null;
  /** Elements that are removed in current round */
  private ImmutableList<Element> currentMutation = null;

  /** Save stage of DD algorithm between calls to {@link #mutate} */
  private DeltaDebuggingStage stage = DeltaDebuggingStage.INIT;

  private final PartsToRemove mode;

  private List<ImmutableList<Element>> deltaList = null;
  private Iterator<ImmutableList<Element>> deltaIter = null;
  private ImmutableList<Element> currentDelta = null;

  private DeltaDebuggingStatistics stats;
  private final MultiStatistics multiStats;

  protected final LogManager logger;
  private final CFAElementManipulator<Element> elementManipulator;

  protected String getElementTitle() {
    return elementManipulator.getElementTitle();
  }

  /** how to log result */
  protected abstract void logFinish();

  /** what to do when a test fails */
  protected abstract void testFailed(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage);

  /** what to do when a test passes */
  protected abstract void testPassed(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage);

  /** what to do when a test run is unresolved */
  protected void testUnresolved(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    switch (pStage) {
      case REMOVE_COMPLEMENT:
        logger.log(
            Level.INFO,
            "Something in the removed complement is needed for a test run to be resolved. "
                + "Nothing is resolved. Mutation is rollbacked.");
        break;

      case REMOVE_DELTA:
        logger.log(
            Level.INFO,
            "Something in the removed delta is needed for a test run to be resolved. "
                + "Nothing is resolved. Mutation is rollbacked.");
        break;

      default:
        throw new AssertionError();
    }

    rollback(pCfa);
  }

  public AbstractDeltaDebuggingAlgorithm(
      LogManager pLogger,
      CFAElementManipulator<Element> pElementManipulator,
      PartsToRemove pMode) {

    logger = Preconditions.checkNotNull(pLogger);
    elementManipulator = Preconditions.checkNotNull(pElementManipulator);
    mode = Preconditions.checkNotNull(pMode);
    stats =
        new DeltaDebuggingStatistics(
            this.getClass().getSimpleName(), elementManipulator.getElementTitle());
    multiStats =
        new MultiStatistics(logger) {
          @Override
          public @Nullable String getName() {
            return this.getClass().getSimpleName();
          }
        };
  }

  /**
   * Set up this algorithm to work on the given elements. By default the algorithm will work on all
   * elements that can be retrieved from the CFA, so this method does not have to be called. But
   * more sophisticated algorithms (e.g. HDD) can use an algorithm on different parts of elements
   * separately.
   */
  public void workOn(Collection<Element> pElements) {
    if (stage == DeltaDebuggingStage.DONE) {
      // reset stats
      multiStats.getSubStatistics().add(stats);
      stats =
          new DeltaDebuggingStatistics(
              this.getClass().getSimpleName(), elementManipulator.getElementTitle());
    } else {
      Preconditions.checkState(
          stage == DeltaDebuggingStage.INIT,
          "Cannot reset DD that was already setup and has not finished yet");
    }
    unresolvedElements = new ArrayList<>(Preconditions.checkNotNull(pElements));
    stats.elementsFound(unresolvedElements.size());

    if (unresolvedElements.isEmpty()) {
      // nothing to do
      logger.log(Level.INFO, "No", elementManipulator.getElementTitle(), "given to mutate");
      stage = DeltaDebuggingStage.DONE;
      safeElements = ImmutableList.of();
      causeElements = ImmutableList.of();
      return;
    }

    safeElements = new ArrayList<>();
    causeElements = new ArrayList<>();

    logger.log(
        Level.INFO,
        this.getClass().getSimpleName(),
        "got",
        unresolvedElements.size(),
        elementManipulator.getElementTitle());

    resetDeltaListWithOneDelta(ImmutableList.copyOf(unresolvedElements));
    if (mode == PartsToRemove.ONLY_COMPLEMENTS) {
      resetDeltaListWithHalvesOfCurrentDelta();
      stage = DeltaDebuggingStage.REMOVE_COMPLEMENT;
    } else {
      stage = DeltaDebuggingStage.REMOVE_DELTA;
    }
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    multiStats.getSubStatistics().add(stats);
    pStatsCollection.add(multiStats);
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    stats.startPremath();

    if (stage == DeltaDebuggingStage.INIT) {
      // setup for all elements in the CFA
      workOn(elementManipulator.getAllElements(pCfa).nodes());
    }

    // corner cases
    switch (stage) {
      case REMOVE_DELTA:
        if (!deltaIter.hasNext()) {
          // tried all deltas, partition again
          if (mode != PartsToRemove.ONLY_DELTAS) {
            stage = DeltaDebuggingStage.REMOVE_COMPLEMENT;
          }
          logger.log(
              Level.INFO,
              "Halving remained",
              deltaList.size(),
              "deltas with total",
              unresolvedElements.size(),
              elementManipulator.getElementTitle());
          halveDeltas();
        }
        break;

      case REMOVE_COMPLEMENT:
        if (!deltaIter.hasNext()) {
          // tried all complements
          if (mode == PartsToRemove.ONLY_COMPLEMENTS) {
            halveDeltas();
          } else {
            stage = DeltaDebuggingStage.REMOVE_DELTA;
            deltaIter = deltaList.iterator();
            currentDelta = null;
          }
        }
        break;

      case DONE: // nothing to do
        break;

      default:
        throw new AssertionError();
    }

    stats.stopTimers();
    return stage != DeltaDebuggingStage.DONE;
  }

  /**
   * Choose and remove some elements from the CFA using Delta Debugging approach.
   *
   * <p>Although this class is described in terms of removing an element, this can actually mean any
   * mutation of some part in CFA, e.g. replacing an edge with another one. Then restoring such an
   * element is replacing new edge back with the old one.
   */
  @Override
  public void mutate(FunctionCFAsWithMetadata pCfa) {
    stats.startMutation();

    // set next mutation
    // not in the switch of #canMutate because stage can be changed there
    switch (stage) {
      case REMOVE_COMPLEMENT:
        currentDelta = deltaIter.next();
        currentMutation =
            ImmutableList.copyOf(
                unresolvedElements.stream().filter(o -> !currentDelta.contains(o)).iterator());
        logger.log(
            Level.INFO,
            "Removing a complement of",
            currentMutation.size(),
            elementManipulator.getElementTitle());
        break;

      case REMOVE_DELTA:
        currentDelta = deltaIter.next();
        currentMutation = currentDelta;
        logger.log(
            Level.INFO,
            "Removing a delta of",
            currentMutation.size(),
            elementManipulator.getElementTitle());
        break;

      default:
        throw new AssertionError();
    }
    // remove elements (apply mutations)
    currentMutation.forEach(mutation -> elementManipulator.remove(pCfa, mutation));

    stats.stopTimers();
  }

  @Override
  public void setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {
    stats.startAftermath();

    // update resolved elements
    switch (pResult) {
      case FAIL:
        stats.incFail();
        testFailed(pCfa, stage);
        break;

      case PASS:
        stats.incPass();
        testPassed(pCfa, stage);
        break;

      case UNRESOLVED:
        stats.incUnres();
        testUnresolved(pCfa, stage);
        break;

      default:
        throw new AssertionError("unexpected result " + pResult);
    }

    currentMutation = null;
    stats.stopTimers();
  }

  /**
   * Return the elements marked as cause. Do not call this method until {@link #canMutate} returns
   * false, as the cause may be not identified.
   *
   * @return all the objects that remain in CFA and are not safe.
   * @throws IllegalStateException if called before algorithm has finished
   */
  public ImmutableList<Element> getCauseElements() {
    Preconditions.checkState(stage == DeltaDebuggingStage.DONE);
    return (ImmutableList<Element>) causeElements;
  }

  /**
   * Return the remaining elements considered safe. Do not call this method until {@link #canMutate}
   * returns false, as the cause may be not identified.
   *
   * @return all the objects that remain in CFA and seem to be safe.
   * @throws IllegalStateException if called before algorithm has finished
   */
  public ImmutableList<Element> getSafeElements() {
    Preconditions.checkState(stage == DeltaDebuggingStage.DONE);
    return (ImmutableList<Element>) safeElements;
  }

  protected void halveDeltas() {
    List<ImmutableList<Element>> result = new ArrayList<>(deltaList.size() * 2);

    for (var delta : deltaList) {
      assert !delta.isEmpty();
      if (delta.size() == 1) {
        // This delta is one object, that is not cause by itself
        // and can not be safe by itself. So, it is part of the cause.
        causeElements.add(delta.get(0));
        unresolvedElements.remove(delta.get(0));
        stats.elementsResolvedToCause(1);

      } else {
        int half = (1 + delta.size()) / 2;
        result.add(delta.subList(0, half));
        result.add(delta.subList(half, delta.size()));
      }
    }

    resetDeltaList(result);
    finishIfNeeded(); // DD can end only after halving deltas
  }

  private void resetDeltaList(List<ImmutableList<Element>> pDeltaList) {
    deltaList = pDeltaList;
    deltaIter = deltaList.iterator();
    currentDelta = null;
  }

  protected void resetDeltaListWithOneDelta(ImmutableList<Element> pOnlyDelta) {
    List<ImmutableList<Element>> list = new ArrayList<>(1);
    list.add(pOnlyDelta);
    resetDeltaList(list);
  }

  protected void resetDeltaListWithHalvesOfCurrentDelta() {
    // switch from REMOVE_COMPLEMENT
    if (mode != PartsToRemove.ONLY_COMPLEMENTS) {
      stage = DeltaDebuggingStage.REMOVE_DELTA;
    }
    resetDeltaListWithOneDelta(currentDelta);
    logger.log(
        Level.INFO,
        "halving single delta of",
        deltaList.get(0).size(),
        elementManipulator.getElementTitle());
    halveDeltas();
  }

  protected void removeCurrentDeltaFromDeltaList() {
    deltaIter.remove();
  }

  private void finishIfNeeded() {
    if (unresolvedElements.isEmpty()) {
      stage = DeltaDebuggingStage.DONE;
      safeElements = ImmutableList.copyOf(safeElements);
      causeElements = ImmutableList.copyOf(causeElements);
      logFinish();
    }
  }

  protected void rollback(FunctionCFAsWithMetadata pCfa) {
    currentMutation.reverse().forEach(r -> elementManipulator.restore(pCfa, r));
  }

  protected void markRemainingElementsAsSafe() {
    safeElements.addAll(unresolvedElements);
    safeElements.removeAll(currentMutation);
    stats.elementsResolvedToSafe(unresolvedElements.size() - currentMutation.size());
    unresolvedElements.retainAll(currentMutation);
  }

  protected void markRemovedElementsAsResolved() {
    stats.elementsRemoved(currentMutation.size());
    unresolvedElements.removeAll(currentMutation);
  }

  protected String shortListToLog(ImmutableList<?> pList) {
    String shortList =
        pList.size() > 4
            ? Joiner.on(", ").join(pList.get(0), pList.get(1), pList.get(2), "...")
            : Joiner.on(", ").join(pList);
    if (shortList.isEmpty()) {
      shortList = "no elements";
    }
    return '(' + shortList + ')';
  }
}
