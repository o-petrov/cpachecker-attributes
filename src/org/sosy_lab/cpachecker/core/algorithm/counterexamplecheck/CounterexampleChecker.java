// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck;

import java.nio.file.Path;
import java.util.Set;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.exceptions.CounterexampleAnalysisFailed;

/**
 * Interface for components that can verify the feasibility of a counterexample.
 *
 * <p>A counterexample is a finite set of loop-free paths in the ARG that form a DAG with a single
 * source (the root state of the ARG) and a single sink (the target state).
 */
public interface CounterexampleChecker {

  /**
   * Check feasibility of counterexample.
   *
   * @param rootState The source of the counterexample paths.
   * @param errorState The sink of the counterexample paths.
   * @param errorPathStates All state that belong to the counterexample paths.
   * @return True if the counterexample is feasible.
   * @throws CounterexampleAnalysisFailed If something goes wrong.
   * @throws InterruptedException If the thread was interrupted.
   */
  boolean checkCounterexample(
      ARGState rootState, ARGState errorState, Set<ARGState> errorPathStates)
      throws CounterexampleAnalysisFailed, InterruptedException;

  void writeCexFile(
      ARGState pRootState, ARGState pErrorState, Set<ARGState> pErrorPathStates, Path cFile)
      throws CounterexampleAnalysisFailed, InterruptedException;
}
