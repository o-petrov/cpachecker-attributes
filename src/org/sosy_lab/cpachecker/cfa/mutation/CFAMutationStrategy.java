// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

interface CFAMutationStrategy {

  /**
   * Whether this strategy can mutate given CFA. Prepares strategy for next call to {@link #mutate}.
   * Strategy either can mutate the CFA, or can return cause and safe parts of it.
   */
  public boolean canMutate(FunctionCFAsWithMetadata pCfa);

  /**
   * Mutates given CFA. {@link #canMutate} must be called before each call. {@link #setResult} must
   * be called after each call.
   */
  public void mutate(FunctionCFAsWithMetadata pCfa);

  /**
   * Gives this strategy information about analysis run after last call to {@link #mutate}. Strategy
   * rewinds unsuccessful mutation.
   */
  public void setResult(FunctionCFAsWithMetadata pCfa, DDResultOfARun pResult);
}
