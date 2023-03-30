// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.counterexamplecheck;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.io.PathTemplate;
import org.sosy_lab.common.io.TempFile.DeleteOnCloseFile;
import org.sosy_lab.common.io.TempFile.TempFileBuilder;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.exceptions.CounterexampleAnalysisFailed;

/**
 * Interface for components that can verify the feasibility of a counterexample.
 *
 * <p>A counterexample is a finite set of loop-free paths in the ARG that form a DAG with a single
 * source (the root state of the ARG) and a single sink (the target state).
 */
public abstract class CounterexampleChecker {

  protected abstract TempFileBuilder getTempFileBuilder();

  protected abstract @Nullable PathTemplate getCexFileTemplate();

  private @Nullable Path getCexFile(ARGState pErrorState) {
    PathTemplate t = getCexFileTemplate();
    if (t == null) {
      return null;
    }
    int cexId = pErrorState.getCounterexampleInformation().map(cex -> cex.getUniqueId()).orElse(0);
    return t.getPath(cexId);
  }

  protected abstract void writeCexFile(
      ARGState pRootState, ARGState pErrorState, Set<ARGState> pErrorPathStates, Path cFile)
      throws CounterexampleAnalysisFailed, InterruptedException;

  protected abstract boolean checkCounterexample0(
      ARGState pRootState, ARGState pErrorState, Set<ARGState> pErrorPathStates, Path pFile)
      throws CounterexampleAnalysisFailed, InterruptedException;

  /**
   * Check feasibility of counterexample.
   *
   * @param pRootState The source of the counterexample paths.
   * @param pErrorState The sink of the counterexample paths.
   * @param pErrorPathStates All states that belong to the counterexample paths.
   * @return True if the counterexample is feasible.
   * @throws CounterexampleAnalysisFailed If something goes wrong.
   * @throws InterruptedException If the thread was interrupted.
   */
  public final boolean checkCounterexample(
      ARGState pRootState, ARGState pErrorState, Set<ARGState> pErrorPathStates)
      throws CounterexampleAnalysisFailed, InterruptedException {

    Path file = getCexFile(pErrorState);

    if (file != null) {
      writeCexFile(pRootState, pErrorState, pErrorPathStates, file);
      return checkCounterexample0(pRootState, pErrorState, pErrorPathStates, file);
    }

    try (DeleteOnCloseFile tempFile = getTempFileBuilder().createDeleteOnClose()) {
      writeCexFile(pRootState, pErrorState, pErrorPathStates, tempFile.toPath());
      return checkCounterexample0(pRootState, pErrorState, pErrorPathStates, tempFile.toPath());

    } catch (IOException e) {
      throw new CounterexampleAnalysisFailed(
          "Could not create temporary file " + e.getMessage(), e);
    }
  }
}
