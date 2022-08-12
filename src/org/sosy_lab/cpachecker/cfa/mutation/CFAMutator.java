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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.CFACreator;
import org.sosy_lab.cpachecker.cfa.ParseResult;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.exceptions.ParserException;

/**
 * Mutates the CFA before next analysis run, mainly to minimize and simplify CFA. Operates on {@link
 * FunctionCFAsWithMetadata}. All processings in {@link CFACreator#createCFA} are applied after this
 * to get proper CFA for analysis run.
 */
@Options
public class CFAMutator extends CFACreator implements StatisticsProvider {
  @Option(secure = true, name = "cfaMutation.order", description = "for debug")
  private String order = null;

  /** local CFA of functions before processing */
  private FunctionCFAsWithMetadata localCfa = null;
  /** Strategy that decides how to change the CFA and implements this change */
  private final CFAMutationStrategy strategy;

  private final Path cfaExportDirectory;

  private final CFAMutatorStatistics mutatorStats;

  public CFAMutator(Configuration pConfig, LogManager pLogger, ShutdownNotifier pShutdownNotifier)
      throws InvalidConfigurationException {
    super(pConfig, pLogger, pShutdownNotifier);
    config.inject(this, CFAMutator.class);

    if (exportDirectory == null) {
      throw new InvalidConfigurationException("Enable output to get results of CFA mutation");
    }
    cfaExportDirectory = exportDirectory;
    List<CFAMutationStrategy> subs;
    if (order == null) {
      subs =
          ImmutableList.of(
              new FunctionBodyRemover(pLogger),
              new SimpleBranchingRemover(pLogger, 1),
              new SimpleBranchingRemover(pLogger, 0),
              new StatementChainRemover(pLogger),
              new StatementEdgeRemover(pLogger),
              new ExpressionRemover(pLogger),
              new DeclarationEdgeRemover(pLogger),
              new GlobalDeclarationRemover(pLogger));
    } else {
      subs = new ArrayList<>(order.length());
      for (int i = 0; i < order.length(); i++) {
        if (order.charAt(i) == 'f') {
          subs.add(new FunctionBodyRemover(pLogger));
        } else if (order.charAt(i) == 'b') {
          subs.add(new SimpleBranchingRemover(pLogger, 1));
          subs.add(new SimpleBranchingRemover(pLogger, 0));
        } else if (order.charAt(i) == 'c') {
          subs.add(new StatementChainRemover(pLogger));
        } else if (order.charAt(i) == 's') {
          subs.add(new StatementEdgeRemover(pLogger));
        } else if (order.charAt(i) == 'e') {
          subs.add(new ExpressionRemover(pLogger));
        } else if (order.charAt(i) == 'd') {
          subs.add(new DeclarationEdgeRemover(pLogger));
        } else if (order.charAt(i) == 'g') {
          subs.add(new GlobalDeclarationRemover(pLogger));
        } else {
          throw new AssertionError("wrong strategy code");
        }
      }
    }

    strategy = new CompositeCFAMutationStrategy(pLogger, subs);
    mutatorStats = new CFAMutatorStatistics(pLogger);
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
    mutatorStats.setCfaStats(localCfa);
    CFA result = super.createCFA(pParseResult, pMainFunction);
    mutatorStats.setCfaStats(result);
    return result;
  }

  @Override
  protected void exportCFAAsync(CFA pCfa) {
    // do not export asynchronously as CFA will be mutated
    if (mutatorStats.getRound() == 0) {
      mutatorStats.startExport();
      exportDirectory = cfaExportDirectory.resolve("0-original-cfa");
      super.exportCFA(pCfa);
      mutatorStats.stopExport();
    }
  }

  public void setup() {
    // reset CFACreator stats so following variable classification
    // and pointer resolving wont add a lot of similar stats to print
    stats = new CFACreatorStatistics(logger);
  }

  public boolean canMutate() {
    // clear processings before first #canMutate and after rollbacks
    mutatorStats.startCfaReset();
    localCfa.resetEdgesInNodes();
    mutatorStats.stopCfaReset();

    mutatorStats.startPreparations();
    boolean result = strategy.canMutate(localCfa);
    mutatorStats.stopPreparations();
    return result;
  }

  /** Apply some mutation to the CFA */
  public CFA mutate() throws InterruptedException, InvalidConfigurationException, ParserException {
    mutatorStats.startMutation();
    strategy.mutate(localCfa);
    mutatorStats.stopMutation();

    mutatorStats.setCfaStats(localCfa);
    CFA result = super.createCFA(localCfa.copyAsParseResult(), localCfa.getMainFunction());
    mutatorStats.setCfaStats(result);

    mutatorStats.startExport();
    exportDirectory = cfaExportDirectory.resolve(mutatorStats.getRound() + "-mutation-round");
    super.exportCFA(result);
    mutatorStats.stopExport();

    return result;
  }

  /** Undo last mutation if needed */
  public CFA setResult(DDResultOfARun pResult)
      throws InvalidConfigurationException, InterruptedException, ParserException {

    // XXX write result?
    // undo createCFA before possible mutation rollback
    mutatorStats.startCfaReset();
    localCfa.resetEdgesInNodes();
    mutatorStats.stopCfaReset();

    mutatorStats.startAftermath(pResult);
    strategy.setResult(localCfa, pResult);
    mutatorStats.stopAftermath();

    CFA rollbackedCfa = null;
    if (pResult != DDResultOfARun.FAIL) {
      // export after rollback as there may be no more mutations
      mutatorStats.setCfaStats(localCfa);
      rollbackedCfa = super.createCFA(localCfa.copyAsParseResult(), localCfa.getMainFunction());
      mutatorStats.setCfaStats(rollbackedCfa);

      mutatorStats.startExport();
      exportDirectory =
          cfaExportDirectory.resolve(mutatorStats.getRound() + "-mutation-round-rollbacked");
      super.exportCFA(rollbackedCfa);
      mutatorStats.stopExport();
    }

    return rollbackedCfa;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(mutatorStats);
    strategy.collectStatistics(pStatsCollection);
  }
}
