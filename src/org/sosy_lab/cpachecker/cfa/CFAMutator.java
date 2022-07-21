// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa;

import org.sosy_lab.common.configuration.Configuration;

/**
 * Stub class. Will be mutating CFA before next analysis run, mainly to minimize and simplify CFA.
 */
public class CFAMutator {
  public CFAMutator(Configuration pConfig) {
    // TODO Auto-generated constructor stub
  }

  public boolean canMinimize(@SuppressWarnings("unused") CFA cfa) {
    return false;
  }

  /** Apply some mutation to the CFA */
  public CFA mutate(CFA cfa) {
    return cfa;
  }

  /** Rollback last mutation, if it appeared to something. */
  public CFA rollback(CFA cfa) {
    return cfa;
  }

  /**
   * Analyze CFA before mutations.
   *
   * @param cfa the original CFA before any mutation rounds
   */
  public void setCFA(@SuppressWarnings("unused") CFA cfa) {
    // TODO Auto-generated constructor stub
  }
}
