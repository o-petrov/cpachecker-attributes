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
  // cause is a list of elements
  private final List<ImmutableList<ImmutableList<Element>>> listOfListsOfCauses = new ArrayList<>();
  // cause that was forced in CFA in this round
  private final List<ImmutableList<Element>> forcedCauses = new ArrayList<>();
  private int indexOfListOfCauses;
  private Iterator<ImmutableList<Element>> itOverListOfCauses;
  private ImmutableList<Element> forcedCause;
  private ImmutableList<Element> elementsToResolve;

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
    updateElementsToResolve(pCfa);
    // reset DD*
    clear();

    if (elementsToResolve.isEmpty()) {
      // nothing to do
      return;
    }

    workOnElements(elementsToResolve);
    // first, check if min-property still holds
    stage = DeltaDebuggingStage.CHECK_WHOLE;
    return;
  }

  private void updateElementsToResolve(FunctionCFAsWithMetadata pCfa) {
    if (getAllCauseElements().isEmpty()) {
      logInfo("Repeating DD* on same elements, but with other forced cause");
      return;
    }

    switch (getStarDirection()) {
      case MAXIMIZATION:
        // no elements were removed
        elementsToResolve = getAllSafeElements();

        if (elementsToResolve.isEmpty()) {
          stage = DeltaDebuggingStage.FINISHED;
          logInfo("No safe elements to search for another maximum");

        } else {
          logInfo(
              "Searching for another maximum with a cause forced in on safe:",
              shortListToLog(elementsToResolve));
        }
        return;

      case MINIMIZATION:
        // no elements were marked safe
        elementsToResolve = getAllRemovedElements();

        if (elementsToResolve.isEmpty()) {
          stage = DeltaDebuggingStage.FINISHED;
          logInfo("No removed elements to search for another minimum");

        } else {
          logInfo(
              "Searching for another minimum with a cause forced out on restored:",
              shortListToLog(elementsToResolve));
          manipulator.restore(pCfa, elementsToResolve.reverse());
        }
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

    logInfo("Current list of causes:", shortListToLog(getListOfCauses()));
    if (!getListOfCauses().isEmpty()) {
      listOfListsOfCauses.add(getListOfCauses());
    }

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
