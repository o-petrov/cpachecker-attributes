// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

enum PartsToRemove {
  /** DD will remove only deltas (whole and halfs too). */
  ONLY_DELTAS,
  /** DD will remove only complements (whole and halfs too?). */
  ONLY_COMPLEMENTS,
  /** DD will remove both deltas and complements. */
  DELTAS_AND_COMPLEMENTS
}