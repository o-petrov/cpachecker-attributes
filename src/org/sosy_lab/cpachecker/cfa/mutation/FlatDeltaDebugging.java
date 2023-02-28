// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.graph.MutableValueGraph;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.sosy_lab.common.log.LogManager;

/** General strategy that chooses how to mutate a CFA using Delta Debugging approach. */
class FlatDeltaDebugging<Element> extends AbstractDeltaDebuggingStrategy<Element> {

  protected enum DeltaDebuggingStage {
    NO_INIT,
    READY,
    CHECK_WHOLE,
    REMOVE_WHOLE,
    REMOVE_HALF1,
    REMOVE_HALF2,
    REMOVE_COMPLEMENT,
    REMOVE_DELTA,
    ALL_RESOLVED,
    FINISHED;

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
        case CHECK_WHOLE:
          return "nothing";
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
        case CHECK_WHOLE:
          return "whole";
        default:
          throw new AssertionError(this);
      }
    }
  }

  /** All elements to investigate in next {@link #mutate} calls */
  private List<Element> unresolvedElements = null;
  /** All elements that were removed */
  private List<Element> removedElements = null;
  /** All elements that remain in CFA, but appear to be safe */
  private List<Element> safeElements = null;
  /** All elements that appear to be the cause, i.e. result of DD algorithm */
  private List<Element> causeElements = null;
  /** Elements that are removed in current round */
  private ImmutableList<Element> currentMutation = null;
  /** All elements remaining in CFA. See {@link HierarchicalDeltaDebugging} for more */
  private MutableValueGraph<Element, ? extends Enum<?>> graph = null;

  /** Save stage of DD algorithm between calls to {@link #mutate} */
  protected DeltaDebuggingStage stage = DeltaDebuggingStage.NO_INIT;

  private List<ImmutableList<Element>> deltaList = null;
  private Iterator<ImmutableList<Element>> deltaIter = null;
  private ImmutableList<Element> currentDelta = null;

  public FlatDeltaDebugging(
      LogManager pLogger,
      CFAElementManipulator<Element, ?> pManipulator,
      DDDirection pDirection,
      PartsToRemove pMode) {
    super(pLogger, pManipulator, pDirection, pMode);
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
    if (stage == DeltaDebuggingStage.FINISHED) {
      useNewStats();
    } else {
      Preconditions.checkState(
          stage == DeltaDebuggingStage.NO_INIT,
          "Cannot reset DD that was already setup and has not finished yet");
    }

    unresolvedElements = new ArrayList<>(Preconditions.checkNotNull(pElements));
    getCurrStats().elementsFound(unresolvedElements.size());

    safeElements = new ArrayList<>();
    causeElements = new ArrayList<>();
    removedElements = new ArrayList<>();

    logInfo(this.getClass().getSimpleName(), "got", unresolvedElements.size(), getElementTitle());

    resetDeltaListWithOneDelta(ImmutableList.copyOf(unresolvedElements));
    stage = DeltaDebuggingStage.READY;
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    switch (stage) {
      case NO_INIT:
        // setup for all elements in the CFA
        setupFromCfa(pCfa);
        workOn(manipulator.getAllElements());
        assert stage == DeltaDebuggingStage.READY;

        // $FALL-THROUGH$
      case READY:
        // elements are already given
        stage = DeltaDebuggingStage.REMOVE_WHOLE;

        // $FALL-THROUGH$
      case CHECK_WHOLE:
      case REMOVE_WHOLE:
      case REMOVE_HALF1:
      case REMOVE_HALF2:
      case REMOVE_DELTA:
      case REMOVE_COMPLEMENT:
        prepareCurrentMutation();
        Optional<DDResultOfARun> cachedResult = getCachedResultWithout(currentMutation);
        if (cachedResult.isEmpty()) {
          return true; // ready to mutate
        }

        logInfo("Current configuration found in cache... no analysis needed.");
        // it's a bit redundant, but analysis is way longer anyway
        setResult(pCfa, cachedResult.orElseThrow());
        return canMutate(pCfa);

      case FINISHED: // nothing to do
        return false;

      default:
        throw new AssertionError();
    }
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
    logInfo("Removing a", stage.nameThis(), "(" + currentMutation.size(), getElementTitle() + ")");
    mutate(pCfa, currentMutation);
    getCurrStats().stopTimers();
  }

  private void prepareCurrentMutation() {
    if (currentMutation != null) {
      return;
    }

    if (stage == DeltaDebuggingStage.CHECK_WHOLE) {
      currentMutation = ImmutableList.of();
      currentDelta = deltaList.get(0);
      return;
    }

    assert deltaIter.hasNext() : "no next delta for delta list " + deltaList;
    currentDelta = deltaIter.next();
    ImmutableList<Element> currentComplement =
        ImmutableList.copyOf(
            unresolvedElements.stream().filter(el -> !currentDelta.contains(el)).iterator());

    // set next mutation
    // not in the switch of #canMutate because stage can be changed there
    switch (stage) {
      case REMOVE_COMPLEMENT:
        currentMutation = currentComplement;
        break;

      case REMOVE_WHOLE:
      case REMOVE_HALF1:
      case REMOVE_HALF2:
      case REMOVE_DELTA:
        currentMutation = currentDelta;
        break;

      default:
        throw new AssertionError();
    }

    assert currentMutation.size() > 0 : "removing no elements makes no sense";
  }

  @Override
  public MutationRollback setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {
    getCurrStats().startAftermath();
    // TODO stats for result
    cacheResult(pResult);

    // update resolved elements
    if (pResult == DDResultOfARun.MINIMIZATION_PROPERTY_HOLDS
        && getDirection() != DDDirection.MAXIMIZATION) {
      reduce(pCfa, stage);

    } else if (pResult == DDResultOfARun.MAXIMIZATION_PROPERTY_HOLDS
        && getDirection() != DDDirection.MINIMIZATION) {
      increase(pCfa, stage);

    } else {
      testUnresolved(pCfa, stage);
    }

    currentMutation = null;

    if (stage == DeltaDebuggingStage.ALL_RESOLVED) {
      finalize(pCfa);
      getCurrStats().stopTimers();
      return MutationRollback.IRREGULAR;
    }

    getCurrStats().stopTimers();
    return pResult == DDResultOfARun.MINIMIZATION_PROPERTY_HOLDS
        ? MutationRollback.NO_ROLLBACK
        : MutationRollback.ROLLBACK;
  }

  protected void resetDeltaListWithHalvesOfCurrentDelta() {
    stage = DeltaDebuggingStage.REMOVE_HALF1;
    resetDeltaListWithOneDelta(currentDelta);
    halveDeltas();
  }

  protected void removeCurrentDeltaFromDeltaList() {
    deltaIter.remove();

    if (deltaIter.hasNext()) {
      return;

    } else if (deltaList.size() == 0) {
      // removed whole
      assert unresolvedElements.isEmpty() : "no deltas but some elements are unresolved";
      stage = DeltaDebuggingStage.ALL_RESOLVED;
      return;

    } else if (deltaList.size() == 1) {
      // successfully removed all other deltas
      // one half remained falls here too
      currentDelta = deltaList.get(0);
      resetDeltaListWithHalvesOfCurrentDelta();
      return;
    }

    switch (stage) {
      case REMOVE_DELTA:
        // tried all deltas, partition again
        if (getMode() != PartsToRemove.ONLY_DELTAS) {
          stage = DeltaDebuggingStage.REMOVE_COMPLEMENT;
        }
        halveDeltas();
        return;

      case REMOVE_COMPLEMENT:
        if (getMode() == PartsToRemove.ONLY_COMPLEMENTS) {
          halveDeltas();
          return;
        }

        // tried all complements -- switch to removing deltas from same list
        stage = DeltaDebuggingStage.REMOVE_DELTA;
        deltaIter = deltaList.iterator();
        currentDelta = null;
        return;

      default:
        throw new AssertionError(stage);
    }
  }

  protected void halveDeltas() {
    logInfo(
        "Halving remained",
        deltaList.size(),
        "deltas (total",
        unresolvedElements.size(),
        getElementTitle() + ")");

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

    deltaList.forEach(d -> logFine(d));

    // DD can end only after halving deltas
    if (unresolvedElements.isEmpty()) {
      stage = DeltaDebuggingStage.ALL_RESOLVED;
      logInfo("All elements were resolved");
    }
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

  protected void finalize(FunctionCFAsWithMetadata pCfa) {
    if (getDirection() == DDDirection.MAXIMIZATION) {
      mutate(pCfa, causeElements);
      causeElements = ImmutableList.of();
    } else {
      causeElements = ImmutableList.copyOf(causeElements);
    }
    safeElements = ImmutableList.copyOf(safeElements);
    stage = DeltaDebuggingStage.FINISHED;
    logInfo("DD has finished");
  }

  protected void markRemainingElementsAsSafe() {
    safeElements.addAll(unresolvedElements);
    safeElements.removeAll(currentMutation);
    getCurrStats().elementsResolvedToSafe(unresolvedElements.size() - currentMutation.size());
    unresolvedElements.retainAll(currentMutation);
  }

  protected void markRemovedElementsAsResolved() {
    getCurrStats().elementsRemoved(currentMutation.size());
    removedElements.addAll(currentMutation);
    unresolvedElements.removeAll(currentMutation);
    if (graph != null) {
      currentMutation.forEach(node -> graph.removeNode(node));
    }
  }

  /** how to log result */
  protected void logFinish() {
    logInfo(
        "All",
        getElementTitle(),
        "are resolved, minimal fail-inducing difference of",
        getCauseElements().size(),
        getElementTitle(),
        shortListToLog(getCauseElements()),
        "found,",
        getSafeElements().size(),
        getElementTitle(),
        "also remain",
        shortListToLog(getSafeElements()));
  }

  /** what to do when a minimization property holds */
  protected void reduce(
      @SuppressWarnings("unused") FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    logInfo(
        "The remaining",
        pStage.nameOther(),
        "is a fail-inducing test. The removed",
        pStage.nameThis(),
        "is not restored.");

    markRemovedElementsAsResolved();

    switch (pStage) {
      case REMOVE_COMPLEMENT:
        // delta has the cause, complement is safe
        // remove complement from list, i.e. make list of one delta, and then split it.
        resetDeltaListWithHalvesOfCurrentDelta();
        break;

      case CHECK_WHOLE:
        // test is failing, so just return to usual
        stage = DeltaDebuggingStage.REMOVE_WHOLE;
        break;

      case REMOVE_WHOLE:
      case REMOVE_HALF1:
      case REMOVE_HALF2:
      case REMOVE_DELTA:
        // delta is safe, complement has the cause
        removeCurrentDeltaFromDeltaList();

        break;
      default:
        throw new AssertionError();
    }
  }

  /** what to do when a maximization property holds */
  protected void increase(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    logInfo(
        "The removed",
        pStage.nameThis(),
        "contains a fail-inducing difference. The remaining",
        pStage.nameOther(),
        "is safe by itself. Mutation is rollbacked.");

    markRemainingElementsAsSafe();
    manipulator.rollback(pCfa);

    switch (pStage) {
      case CHECK_WHOLE:
        // either maximization does not need to remove anything
        // or minimization is pointless
        currentDelta = null;
        assert unresolvedElements.isEmpty();
        stage = DeltaDebuggingStage.ALL_RESOLVED;
        logInfo("All elements were resolved");
        break;

      case REMOVE_COMPLEMENT:
        removeCurrentDeltaFromDeltaList();
        break;

      case REMOVE_WHOLE:
      case REMOVE_HALF1:
      case REMOVE_HALF2:
      case REMOVE_DELTA:
        resetDeltaListWithHalvesOfCurrentDelta();
        break;

      default:
        throw new AssertionError();
    }
  }

  /** what to do when a test run is unresolved */
  protected void testUnresolved(FunctionCFAsWithMetadata pCfa, DeltaDebuggingStage pStage) {
    String desc;
    switch (getDirection()) {
      case ISOLATION:
        desc = "be resolved (min- or max-property";
        break;
      case MAXIMIZATION:
        desc = "pass (max-property";
        break;
      case MINIMIZATION:
        desc = "fail (min-property";
        break;
      default:
        throw new AssertionError();
    }

    logInfo(
        "Something in the removed",
        pStage.nameThis(),
        "is needed for a test to",
        desc,
        "to be preserved).",
        "Nothing is resolved. Mutation is rollbacked.");
    manipulator.rollback(pCfa);

    switch (stage) {
      case CHECK_WHOLE:
        testWholeUnresolved();
        break;

      case REMOVE_WHOLE:
        resetDeltaListWithHalvesOfCurrentDelta();
        break;

      case REMOVE_HALF1:
        if (deltaList.size() == 1) {
          // successfully removed first half, only second half remained
          resetDeltaListWithHalvesOfCurrentDelta();
          break; // remove first subhalf...
        }
        assert deltaList.size() == 2;
        stage = DeltaDebuggingStage.REMOVE_HALF2;
        break;

      case REMOVE_HALF2:
        if (deltaList.size() == 1) {
          // successfully removed second half, only second half remained
          resetDeltaListWithHalvesOfCurrentDelta();
          break; // remove first subhalf...
        }
        assert deltaList.size() == 2;

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

      default:
        throw new AssertionError();
    }
  }

  protected void testWholeUnresolved() {
    throw new UnsupportedOperationException("basic DD should not call this method");
  }

  /**
   * Return the elements that induce fail/minimization property when present. In minimization
   * scenario all remaining elements are considered as one cause. In cause-isolation scenario there
   * can be several elements that are a cause together: when they are present, test 'fails'; when
   * they are absent, test 'passes', when there are only some of them, test is unresolved.
   *
   * <p>Do not call this method until {@link #canMutate} returns false, as the result is not ready.
   *
   * @return All the elements that remain in CFA and marked as inducing min-property.
   * @throws IllegalStateException if called before algorithm has finished.
   */
  public ImmutableList<Element> getCauseElements() {
    Preconditions.checkState(stage == DeltaDebuggingStage.FINISHED);
    return (ImmutableList<Element>) causeElements;
  }

  /**
   * Return the remaining elements that comply to pass/maximization property together. (If all of
   * these elements are present and none other, test passes).
   *
   * <p>Do not call this method until {@link #canMutate} returns false, as the result is not ready.
   *
   * @return All the elements that remain in CFA and seem to induce maximization property together.
   * @throws IllegalStateException if called before algorithm has finished.
   */
  public ImmutableList<Element> getSafeElements() {
    Preconditions.checkState(stage == DeltaDebuggingStage.FINISHED);
    return (ImmutableList<Element>) safeElements;
  }

  /**
   * Return the elements that were removed. You can call this method anytime, currently removed
   * elements are returned.
   *
   * @return All the elements that were removed from the CFA.
   */
  public ImmutableList<Element> getRemovedElements() {
    return ImmutableList.copyOf(removedElements);
  }

  protected void mutate(FunctionCFAsWithMetadata pCfa, Collection<Element> pChosen) {
    manipulator.remove(pCfa, pChosen);
  }
}
