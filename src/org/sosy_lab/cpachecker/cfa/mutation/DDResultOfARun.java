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
  UNRESOLVED
}