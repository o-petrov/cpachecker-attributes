// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

/** Result of a run in terms of Delta Debugging approach. */
public enum DDResultOfARun {
  /** There are no errors */
  PASS,
  /** There is sought-for error */
  FAIL,
  /** There is unexpected problem */
  UNRESOLVED;

  @Override public String toString() {
    switch (this) {
      case FAIL:
        return "Sought-for error occured during analysis";
      case PASS:
        return "Analysis finished correctly, no errors occured";
      case UNRESOLVED:
        return "Some other problem occured during analysis";
      default:
        throw new AssertionError();
    }
  }
}
