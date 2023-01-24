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
import com.google.common.graph.MutableValueGraph;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import org.sosy_lab.common.log.LogManager;

/** General strategy that chooses how to mutate a CFA using Delta Debugging approach. */
abstract class FlatDeltaDebugging<Element> extends AbstractDeltaDebuggingStrategy<Element> {

  protected enum DeltaDebuggingStage {
    NO_INIT,
    READY,
    REMOVE_WHOLE,
    REMOVE_HALF1,
    REMOVE_HALF2,
    REMOVE_COMPLEMENT,
    REMOVE_DELTA,
    DONE;

    public String nameThis() {
      switch (this) {
        case REMOVE_COMPLEMENT:
          return "complement";
        case REMOVE_DELTA:
          return "delta";
        case REMOVE_HALF1:
        case REMOVE_HALF2:
          return "half";
        case REMOVE_WHOLE:
          return "whole";
        default:
          throw new AssertionError(this);
      }
    }

    public String nameOther() {
      switch (this) {
        case REMOVE_COMPLEMENT:
          return "delta";
        case REMOVE_DELTA:
          return "complement";
        case REMOVE_HALF1:
        case REMOVE_HALF2:
          return "other half";
        case REMOVE_WHOLE:
          return "nothing";
        default:
          throw new AssertionError(this);
      }
    }
  }

  /** All elements to investigate in next {@link #mutate} calls */
  private List<Element> unresolvedElements = null;
  /** All elements that remain in CFA, but appear to be safe */
  private List<Element> safeElements = null;
  /** All elements that appear to be the cause, i.e. result of DD algorithm */
  private List<Element> causeElements = null;
  /** Elements that are removed in current round */
  private ImmutableList<Element> currentMutation = null;
  /** All elements remaining in CFA. See {@link HierarchicalDeltaDebugging} for more */
  private MutableValueGraph<Element, ? extends Enum<?>> graph = null;

  /** Save stage of DD algorithm between calls to {@link #mutate} */
  private DeltaDebuggingStage stage = DeltaDebuggingStage.NO_INIT;

  private List<ImmutableList<Element>> deltaList = null;
  private Iterator<ImmutableList<Element>> deltaIter = null;
  private ImmutableList<Element> currentDelta = null;

  public FlatDeltaDebugging(
      LogManager pLogger, CFAElementManipulator<Element> pManipulator, PartsToRemove pMode) {
    super(pLogger, pManipulator, pMode);
  }

  /**
   * Set up this algorithm to work on the given elements. By default the algorithm will work on all
   * elements that can be retrieved from the CFA, so this method does not have to be called. But
   * more sophisticated algorithms (e.g. HDD) can use an algorithm on different parts of elements
   * separately.
   */
  public void workOn(Collection<Element> pElements) {
    Preconditions.checkNotNull(pElements);
    Preconditions.checkArgument(!pElements.isEmpty(), "Can not DD on no elements");
    if (stage == DeltaDebuggingStage.DONE) {
      useNewStats();
    } else {
      Preconditions.checkState(
          stage == DeltaDebuggingStage.NO_INIT,
          "Cannot reset DD that was already setup and has not finished yet");
    }

    unresolvedElements = new ArrayList<>(Preconditions.checkNotNull(pElements));
    getCurrStats().elementsFound(unresolvedElements.size());

    if (unresolvedElements.isEmpty()) {
      // nothing to do
      logInfo("No", getElementTitle(), "given to mutate");
      stage = DeltaDebuggingStage.DONE;
      safeElements = ImmutableList.of();
      causeElements = ImmutableList.of();
      return;
    }

    safeElements = new ArrayList<>();
    causeElements = new ArrayList<>();

    logInfo(this.getClass().getSimpleName(), "got", unresolvedElements.size(), getElementTitle());

    resetDeltaListWithOneDelta(ImmutableList.copyOf(unresolvedElements));
    stage = DeltaDebuggingStage.READY;
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    getCurrStats().startPremath();

    // switch to next stage
    switch (stage) {
      case NO_INIT:
        // setup for all elements in the CFA
        setupFromCfa(pCfa);
        workOn(manipulator.getAllElements());
        if (stage == DeltaDebuggingStage.READY) {
          stage = DeltaDebuggingStage.REMOVE_WHOLE;
        }
        break;

      case READY:
        // elements are already given
        stage = DeltaDebuggingStage.REMOVE_WHOLE;
        break;

      case REMOVE_WHOLE:
        stage = DeltaDebuggingStage.REMOVE_HALF1;
        halveDeltas();
        break;

      case REMOVE_HALF1:
        if (deltaList.size() == 1) {
          // successfully removed fist half, only second half remained
          currentDelta = deltaList.get(0);
          resetDeltaListWithHalvesOfCurrentDelta();
          break; // remove first subhalf...
        }
        stage = DeltaDebuggingStage.REMOVE_HALF2;
        break;

      case REMOVE_HALF2:
        if (getMode() != PartsToRemove.ONLY_DELTAS) {
          stage = DeltaDebuggingStage.REMOVE_COMPLEMENT;
        } else {
          stage = DeltaDebuggingStage.REMOVE_DELTA;
        }
        halveDeltas();
        break;

      case REMOVE_DELTA:
        if (!deltaIter.hasNext()) {
          // tried all deltas, partition again
          if (getMode() != PartsToRemove.ONLY_DELTAS) {
            stage = DeltaDebuggingStage.REMOVE_COMPLEMENT;
          }
          halveDeltas();
        }
        break;

      case REMOVE_COMPLEMENT:
        if (!deltaIter.hasNext()) {
          // tried all complements
          if (deltaList.size() == 1) {
            currentDelta = deltaList.get(0);
            resetDeltaListWithHalvesOfCurrentDelta();
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

    getCurrStats().stopTimers();
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
    getCurrStats().startMutation();
    assert deltaIter.hasNext() : "no next delta for delta list " + deltaList;

    // set next mutation
    // not in the switch of #canMutate because stage can be changed there
    switch (stage) {
      case REMOVE_COMPLEMENT:
        currentDelta = deltaIter.next();
        currentMutation =
            ImmutableList.copyOf(
                unresolvedElements.stream().filter(o -> !currentDelta.contains(o)).iterator());
        break;

      case REMOVE_WHOLE:
      case REMOVE_HALF1:
      case REMOVE_HALF2:
      case REMOVE_DELTA:
        currentDelta = deltaIter.next();
        currentMutation = currentDelta;
        break;

      default:
        throw new AssertionError();
    }
    logInfo("Removing a", stage.nameThis(), "of", currentMutation.size(), getElementTitle());
    mutate(pCfa, currentMutation);
    getCurrStats().stopTimers();
  }

  @Override
  public void setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {
    getCurrStats().startAftermath();

    // update resolved elements
    switch (pResult) {
      case FAIL:
        getCurrStats().incFail();
        testFailed(pCfa, stage);
        break;

      case PASS:
        getCurrStats().incPass();
        testPassed(pCfa, stage);
        break;

      case UNRESOLVED:
        getCurrStats().incUnres();
        testUnresolved(pCfa, stage);
        break;

      default:
        throw new AssertionError("unexpected result " + pResult);
    }

    currentMutation = null;
    getCurrStats().stopTimers();
  }

  protected void resetDeltaListWithHalvesOfCurrentDelta() {
    //    switch (stage) {
    //      case REMOVE_WHOLE:
    //        stage = DeltaDebuggingStage.REMOVE_HALF1;
    //        break;
    //      case REMOVE_HALF1:
    //        stage = DeltaDebuggingStage.REMOVE_HALF2;
    //        break;
    //      case REMOVE_HALF2:
    //      default:
    //        stage = DeltaDebuggingStage.REMOVE_DELTA;
    //        break;
    //    }
    resetDeltaListWithOneDelta(currentDelta);
    halveDeltas();
  }

  protected void removeCurrentDeltaFromDeltaList() {
    deltaIter.remove();
  }

  protected void halveDeltas() {
    logInfo(
        "Halving remained",
        deltaList.size(),
        "deltas with total",
        unresolvedElements.size(),
        getElementTitle());
    List<ImmutableList<Element>> result = new ArrayList<>(deltaList.size() * 2);

    for (var delta : deltaList) {
      assert !delta.isEmpty();
      if (delta.size() == 1) {
        // This delta is one object, that is not cause by itself
        // and can not be safe by itself. So, it is part of the cause.
        causeElements.add(delta.get(0));
        unresolvedElements.remove(delta.get(0));
        getCurrStats().elementsResolvedToCause(1);

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

  private void finishIfNeeded() {
    if (unresolvedElements.isEmpty()) {
      stage = DeltaDebuggingStage.DONE;
      safeElements = ImmutableList.copyOf(safeElements);
      causeElements = ImmutableList.copyOf(causeElements);
      logFinish();
    }
  }

  protected void markRemainingElementsAsSafe() {
    safeElements.addAll(unresolvedElements);
    safeElements.removeAll(currentMutation);
    getCurrStats().elementsResolvedToSafe(unresolvedElements.size() - currentMutation.size());
    unresolvedElements.retainAll(currentMutation);
  }

  protected void markRemovedElementsAsResolved() {
    getCurrStats().elementsRemoved(currentMutation.size());
    unresolvedElements.removeAll(currentMutation);
    if (graph != null) {
      currentMutation.forEach(node -> graph.removeNode(node));
    }
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

  /** how to log result */
  protected abstract void logFinish();

  /** what to do when a test fails */
  protected abstract void testFailed(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage);

  /** what to do when a test passes */
  protected abstract void testPassed(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage);

  /** what to do when a test run is unresolved */
  protected void testUnresolved(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    logInfo(
        "Something in the removed",
        pStage,
        "is needed for a test run to be resolved.",
        "Nothing is resolved. Mutation is rollbacked.");
    manipulator.rollback(pCfa);
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
}
