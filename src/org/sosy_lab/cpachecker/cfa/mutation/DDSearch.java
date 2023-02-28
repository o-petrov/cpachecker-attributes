// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2023 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.sosy_lab.common.log.LogManager;

/**
 * DDSearch repeats {@link DDStar} to iterate over possible optimums. This way we search for some
 * optimum or some other configuration 'near' optimums that matches the needed criteria (not to be
 * confused with min-/max-properties).
 */
public class DDSearch<Element> extends DDStar<Element> {
  // optimum is a list of elements
  private final List<ImmutableList<Element>> optimums = new ArrayList<>();
  // cause is a list of elements
  private final List<ImmutableList<ImmutableList<Element>>> listOfListsOfCauses = new ArrayList<>();
  // cause that was forced in CFA in this round
  private final List<ImmutableList<Element>> forcedCauses = new ArrayList<>();
  private int indexOfListOfCauses;
  private Iterator<ImmutableList<Element>> itOverListOfCauses;
  private ImmutableList<Element> forcedCause;

  public DDSearch(
      LogManager pLogger,
      CFAElementManipulator<Element, ?> pManipulator,
      DDDirection pDirection,
      PartsToRemove pMode) {
    super(pLogger, pManipulator, pDirection, pMode);
  }

  private ImmutableList<Element> nextForcedCause() {
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < listOfListsOfCauses.size(); i++) {
      parts.add(
          (indexOfListOfCauses == i ? ">>> " : "    ")
              + String.valueOf(i)
              + ". "
              + listOfListsOfCauses.get(i));
    }
    logInfo(Joiner.on('\n').join(parts));

    if (itOverListOfCauses.hasNext()) {
      logInfo("next cause from this list");
      return itOverListOfCauses.next();
    }

    if (++indexOfListOfCauses < listOfListsOfCauses.size()) {
      logInfo("changing list of causes");
      itOverListOfCauses = listOfListsOfCauses.get(indexOfListOfCauses).iterator();
      return nextForcedCause();
    }

    logInfo("no next cause");
    return ImmutableList.of();
  }

  private ImmutableList<Element> getFoundOptimum() {
    switch (getStarDirection()) {
      case MAXIMIZATION:
        // no removed elements, but cause is deleted
        logInfo("Found maximal safe CFA (test passes):", getSafeElements());
        return getSafeElements();
      case MINIMIZATION:
        // no safe elements
        logInfo("Found minimum fail-inducing CFA (test fails):", getCauseElements());
        return getCauseElements();
      default:
        throw new AssertionError();
    }
  }

  private void forceCause(FunctionCFAsWithMetadata pCfa, ImmutableList<Element> pForcedCause) {
    switch (getStarDirection()) {
      case MAXIMIZATION:
        logInfo("Forcing cause in CFA:", shortListToLog(pForcedCause));
        manipulator.restore(pCfa, pForcedCause.reverse());
        return;

      case MINIMIZATION:
        logInfo("Forcing cause out of CFA:", shortListToLog(pForcedCause));
        mutate(pCfa, pForcedCause.reverse());
        return;

      default:
        throw new AssertionError();
    }
  }

  private void unforceCause(FunctionCFAsWithMetadata pCfa, ImmutableList<Element> pForcedCause) {
    switch (getStarDirection()) {
      case MAXIMIZATION:
        logInfo("Removing cause forced in CFA this time:", shortListToLog(pForcedCause));
        mutate(pCfa, pForcedCause.reverse());
        return;

      case MINIMIZATION:
        logInfo("Returning cause forced out of CFA this time:", shortListToLog(pForcedCause));
        manipulator.restore(pCfa, pForcedCause.reverse());
        return;

      default:
        throw new AssertionError();
    }
  }

  private void findNextOptimum(FunctionCFAsWithMetadata pCfa) {
    switch (getStarDirection()) {
      case MAXIMIZATION:
        ImmutableList<Element> safeElements = getSafeElements();
        // no removed
        logInfo(
            "Searching for another maximum with a cause forced in on safe:",
            shortListToLog(safeElements));
        // reset DD*
        clear();
        workOnElements(safeElements);
        return;

      case MINIMIZATION:
        ImmutableList<Element> removedElements = getRemovedElements();
        // no safe
        logInfo(
            "Searching for another minimum with a cause forced out on restored:",
            shortListToLog(removedElements));
        manipulator.restore(pCfa, removedElements.reverse());
        // reset DD*
        clear();
        workOnElements(removedElements);
        // first, check if min-property still holds
        stage = DeltaDebuggingStage.CHECK_WHOLE;
        return;

      default:
        throw new AssertionError();
    }
  }

  @Override
  protected void finalize(FunctionCFAsWithMetadata pCfa) {
    super.finalize(pCfa);

    if (stage != DeltaDebuggingStage.FINISHED) {
      // approaches next optimum
      return;
    }

    optimums.add(getFoundOptimum());
    logInfo("Current list of causes:", shortListToLog(getListOfCauses()));
    listOfListsOfCauses.add(getListOfCauses());

    if (forcedCauses.isEmpty()) {
      // no cause was forced out of CFA
      forcedCauses.add(ImmutableList.of());
      // init
      indexOfListOfCauses = 0;
      itOverListOfCauses = listOfListsOfCauses.get(indexOfListOfCauses).iterator();

    } else {
      unforceCause(pCfa, forcedCause);
    }

    forcedCause = nextForcedCause();
    if (forcedCause.isEmpty()) {
      logInfo("All causes were used to find new optimums");
      return; // no more causes can be forced
    }
    forcedCauses.add(forcedCause);
    forceCause(pCfa, forcedCause);
    findNextOptimum(pCfa);
  }
}
