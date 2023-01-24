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
import java.util.List;
import org.sosy_lab.common.log.LogManager;

/**
 * A delta debugging algorithm that repeatedly finds minimal fail-inducing difference on remaining
 * CFA. This way it finds a minimal failing test.
 */
class DDStarAlgorithm<Element> extends DDAlgorithm<Element> {
  private List<Element> causeElements = new ArrayList<>();

  public DDStarAlgorithm(
      LogManager pLogger, CFAElementManipulator<Element> pManipulator, PartsToRemove pMode) {
    super(pLogger, pManipulator, pMode);
  }

  @Override
  public boolean canMutate(FunctionCFAsWithMetadata pCfa) {
    if (super.canMutate(pCfa)) {
      return true;
    }

    // found cause
    causeElements.addAll(super.getCauseElements());
    ImmutableList<Element> remaining = super.getSafeElements();
    if (remaining.isEmpty()) {
      return false;
    }

    super.workOn(remaining);
    boolean result = super.canMutate(pCfa);
    assert result;
    return true;
  }
}
