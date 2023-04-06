// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2023 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

public enum AnalysisOutcome {
  VERDICT_TRUE,
  SAME_VERDICT_FALSE,
  VERDICT_FALSE,
  VERDICT_UNKNOWN_BECAUSE_OF_TIMEOUT,
  FAILURE_BECAUSE_OF_SAME_EXCEPTION,
  FAILURE_BECAUSE_OF_EXCEPTION,
  ANOTHER_VERDICT_UNKNOWN;
}