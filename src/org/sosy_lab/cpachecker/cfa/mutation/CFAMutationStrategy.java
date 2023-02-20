// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;

/**
 * A strategy capable to mutate a CFA in form of function CFAs (without interprocedure edges) with
 * separate list of global declarations. Main function entry, language, and machine model are also
 * supplied.
 */
interface CFAMutationStrategy extends StatisticsProvider {
  public enum MutationRollback {
    ROLLBACK,
    NO_ROLLBACK
  }

  /**
   * Whether this strategy can mutate given CFA. Has to be called before {@link #mutate} at least
   * once (subsequent calls do not affect anything). Strategy either can mutate the CFA, or can
   * return cause and safe parts in it (all elements remaining in CFA will fall in one of these
   * categories).
   */
  public boolean canMutate(FunctionCFAsWithMetadata pCfa);

  /**
   * Mutates given CFA. {@link #canMutate} must be called before each call. {@link #setResult} must
   * be called after each call.
   */
  public void mutate(FunctionCFAsWithMetadata pCfa);

  /**
   * Gives this strategy information about analysis run after last call to {@link #mutate}. Strategy
   * rewinds unsuccessful mutation and prepares for next mutation.
   */
  public MutationRollback setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult);
}
