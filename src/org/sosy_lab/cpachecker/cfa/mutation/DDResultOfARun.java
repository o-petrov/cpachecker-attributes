// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

/**
 * Result of a run in terms of Delta Debugging approach.
 *
 * <p>When DD was used for failing test minimization, so results of a test run were formulated as
 *
 * <ol>
 *   <li>Pass: test passes;
 *   <li>Fail: input has the sought-for fail-inducing problem;
 *   <li>Unresolved: there are some other problems.
 * </ol>
 *
 * <p>Then DD approach was generalized, so outcomes are
 *
 * <ol>
 *   <li>Property is preserved: the desired property holds (previously this was Fail, as property
 *       was "the same fail is reproduced");
 *   <li>Property is not preserved: analysis ends correctly, but the desired property does not hold;
 *   <li>Unresolved: analysis ends with an error.
 * </ol>
 *
 * <p>To use Delta Debugging algorithm we need to determine when results of an analysis run hold the
 * desired property. Example of a property: "Analysis ends with same exception as the analysis of
 * the original input program".
 */
public enum DDResultOfARun {
  @Deprecated
  PASS,
  @Deprecated
  FAIL,
  @Deprecated
  UNRESOLVED,
  MINIMIZATION_PROPERTY_HOLDS,
  MAXIMIZATION_PROPERTY_HOLDS,
  NEITHER_PROPERTY_HOLDS;

  @Override public String toString() {
    switch (this) {
      case MINIMIZATION_PROPERTY_HOLDS:
        return "The minimization property holds.";
      case MAXIMIZATION_PROPERTY_HOLDS:
        return "The maximization property holds.";
      case NEITHER_PROPERTY_HOLDS:
        return "The minimization and maximization properties do not hold.";
      default:
        throw new AssertionError();
    }
  }
}
