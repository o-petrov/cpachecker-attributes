// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;

/**
 * This delta debugging algorithm finds a minimal fail-inducing difference and then minimizes
 * remaining 'safe' part by removing only deltas (usually DD tries to remove complements too).
 */
class DeltaRemovingAfterDDAlgorithm<Element> extends AbstractDeltaDebuggingStrategy<Element> {

  private enum Stage {
    ISOLATE_CAUSE,
    REMOVE_DELTAS,
    DONE
  }

  private Stage stage = Stage.ISOLATE_CAUSE;
  private final DDAlgorithm<Element> delegate1;
  private final DDMinAlgorithm<Element> delegate2;
  private final List<Element> causeElements = new ArrayList<>();
  private final List<Statistics> twoStats = new ArrayList<>(2);

  public DeltaRemovingAfterDDAlgorithm(
      LogManager pLogger, CFAElementManipulator<Element> pManipulator) {
    super(pLogger, pManipulator, PartsToRemove.DUMMY);
    delegate1 = new DDAlgorithm<>(pLogger, pManipulator, PartsToRemove.DELTAS_AND_COMPLEMENTS);
    delegate2 = new DDMinAlgorithm<>(pLogger, pManipulator, PartsToRemove.ONLY_DELTAS);
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.addAll(twoStats);
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    switch (stage) {
      case ISOLATE_CAUSE:
        if (delegate1.canMutate(pCfa)) {
          return true;
        }
        // else d1 is done
        // collect results from d1
        causeElements.addAll(delegate1.getCauseElements());
        ImmutableList<Element> remaining = delegate1.getSafeElements();
        delegate1.collectStatistics(twoStats);

        if (remaining.isEmpty()) {
          stage = Stage.DONE;
          return false;
        }

        // setup d2
        stage = Stage.REMOVE_DELTAS;
        delegate2.workOn(remaining);
        boolean result = delegate2.canMutate(pCfa);
        assert result;
        return true;

      case REMOVE_DELTAS:
        if (delegate2.canMutate(pCfa)) {
          return true;
        }
        // else d2 is done
        // collect results from d2
        causeElements.addAll(delegate2.getCauseElements());
        delegate2.collectStatistics(twoStats);
        stage = Stage.DONE;
        return false;

      case DONE:
        return false;

      default:
        throw new AssertionError();
    }
  }

  @Override
  public void mutate(FunctionCFAsWithMetadata pCfa) {
    (stage == Stage.ISOLATE_CAUSE ? delegate1 : delegate2).mutate(pCfa);
  }

  @Override
  public void setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult) {
    (stage == Stage.ISOLATE_CAUSE ? delegate1 : delegate2).setResult(pCfa, pResult);
  }
}
