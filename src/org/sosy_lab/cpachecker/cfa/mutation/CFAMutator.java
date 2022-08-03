// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.CFACreator;
import org.sosy_lab.cpachecker.cfa.ParseResult;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.exceptions.ParserException;

/**
 * Mutates the CFA before next analysis run, mainly to minimize and simplify CFA. Operates on {@link
 * FunctionCFAsWithMetadata}. All processings in {@link CFACreator#createCFA} are applied after this to
 * get proper CFA for analysis run.
 */
public class CFAMutator extends CFACreator {
  /** local CFA of functions before processing */
  private FunctionCFAsWithMetadata localCfa = null;
  /** Strategy that decides how to change the CFA and implements this change */
  private final CFAMutationStrategy strategy;

  private int round = 0;

  private final Path cfaExportDirectory = exportDirectory;

  public CFAMutator(Configuration pConfig, LogManager pLogger, ShutdownNotifier pShutdownNotifier)
      throws InvalidConfigurationException {
    super(pConfig, pLogger, pShutdownNotifier);
    strategy =
        new CompositeCFAMutationStrategy(
            pLogger,
            ImmutableList.of(
                new FunctionBodyRemover(pLogger),
                new SimpleBranchingRemover(pLogger, 1),
                new SimpleBranchingRemover(pLogger, 0),
                new ChainRemover(pLogger),
                new StatementRemover(pLogger)));
  }

  /**
   * Use once while parsing the source, initialize CFA that will be mutated. Use {@link
   * CFACreator#createCFA} to fully create CFA from function CFA.
   */
  @Override
  protected CFA createCFA(ParseResult pParseResult, FunctionEntryNode pMainFunction)
      throws InvalidConfigurationException, InterruptedException, ParserException {
    localCfa =
        FunctionCFAsWithMetadata.fromParseResult(
            pParseResult, machineModel, pMainFunction, language);
    return super.createCFA(pParseResult, pMainFunction);
  }

  @Override
  protected void exportCFAAsync(CFA pCfa) {
    // do not export asynchronously as CFA will be mutated
    if (round == 0) {
      exportDirectory = cfaExportDirectory.resolve("original-cfa");
      super.exportCFA(pCfa);
    }
  }

  public boolean canMutate() {
    // start next round of work with CFA -- clear processings
    localCfa.resetEdgesInNodes();
    return strategy.canMutate(localCfa);
  }

  /** Apply some mutation to the CFA */
  public CFA mutate() throws InterruptedException, InvalidConfigurationException, ParserException {
    round += 1;
    strategy.mutate(localCfa);
    CFA result = super.createCFA(localCfa.copyAsParseResult(), localCfa.getMainFunction());
    exportDirectory = cfaExportDirectory.resolve(String.valueOf(round) + "-mutation-round");
    super.exportCFA(result);
    return result;
  }

  /** Undo last mutation if needed */
  public CFA setResult(DDResultOfARun pResult)
      throws InvalidConfigurationException, InterruptedException, ParserException {
    // XXX write result?
    // undo createCFA before possible mutation rollback
    localCfa.resetEdgesInNodes();
    strategy.setResult(localCfa, pResult);
    CFA rollbackedCfa = null;
    if (pResult != DDResultOfARun.FAIL) {
      // export after rollback
      rollbackedCfa = super.createCFA(localCfa.copyAsParseResult(), localCfa.getMainFunction());
      exportDirectory =
          cfaExportDirectory.resolve(String.valueOf(round) + "-mutation-round-rollbacked");
      super.exportCFA(rollbackedCfa);
    }
    return rollbackedCfa;
  }
}
