// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.core.defaults.MultiStatistics;

public class CFAMutatorStatistics extends MultiStatistics {

  public CFAMutatorStatistics(LogManager pLogger) {
    super(pLogger);
    // TODO Auto-generated constructor stub
  }

  @Override
  public @Nullable String getName() {
    return "CFA mutator";
  }
}
