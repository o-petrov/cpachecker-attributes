// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.collect.ImmutableList;
import com.google.common.io.MoreFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.ClassOption;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.log.LoggingOptions;
import org.sosy_lab.common.log.TimestampedLogFormatter;
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
@Options(prefix = "cfaMutation")
public class CFAMutator extends CFACreator implements StatisticsProvider {

  @Option(
      secure = true,
      name = "ddKind",
      description = "which DD algorithm implementation to use to mutate CFA")
  @ClassOption(packagePrefix = "org.sosy_lab.cpachecker.cfa.mutation")
  private Class<? extends CFAMutationStrategy> ddClass = null;

  @Option(
      secure = true,
      name = "manipulator",
      description = "which elements to remove from CFA (one manipulator class)")
  @ClassOption(packagePrefix = "org.sosy_lab.cpachecker.cfa.mutation")
  private Class<? extends CFAElementManipulator<?>> manipulatorClass = null;

  @Option(
      secure = true,
      name = "oldStrategies",
      description = "which strategies to use subsequently to mutate CFA")
  @ClassOption(packagePrefix = "org.sosy_lab.cpachecker.cfa.mutation")
  private List<Class<? extends CFAMutationStrategy>> oldStrategies =
      ImmutableList.of(
          FunctionBodyRemover.class,
          AssumeEdgesRemover.class,
          StatementChainRemover.class,
          StatementEdgeRemover.class,
          ExpressionRemover.class,
          DeclarationEdgeRemover.class,
          GlobalDeclarationRemover.class);

  @Option(
      secure = true,
      name = "logFilesLevel",
      description =
          "Create log file for every round with this logging level.\n"
              + "Use main file logging level if that is lower.")
  private Level fileLogLevel = Level.FINE;

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

    if (ddClass == null) {
      // use previous impl
      if (oldStrategies.isEmpty()) {
        throw new InvalidConfigurationException("No CFA mutation strategies were specified");
      }

      ImmutableList.Builder<CFAMutationStrategy> strategiesList = ImmutableList.builder();
      for (Class<? extends CFAMutationStrategy> cls : oldStrategies) {
        try {
          if (cls == FunctionBodyRemover.class) {
            strategiesList.add(new FunctionBodyRemover(pConfig, pLogger));
          } else if (cls == ExpressionRemover.class) {
            strategiesList.add(new ExpressionRemover(pConfig, pLogger));
          } else {
            strategiesList.add(cls.getConstructor(LogManager.class).newInstance(pLogger));
          }
        } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException e) {
          throw new InvalidConfigurationException(
              "Can not generate CFA mutation strategies list from option", e);
        }
      }

      if (oldStrategies.size() == 1) {
        strategy = strategiesList.build().get(0);
      } else {
        strategy = new CompositeCFAMutationStrategy(pLogger, strategiesList.build());
      }

    } else {
      // use experimental impl
      CFAElementManipulator<?> elementManipulator;
      try {
        elementManipulator =
            manipulatorClass
                .getConstructor(Configuration.class, LogManager.class)
                .newInstance(config, logger);
      } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException e) {
        throw new InvalidConfigurationException("Can not generate CFA manipulator from option", e);
      }

      try {
        strategy =
            ddClass
                .getConstructor(LogManager.class, CFAElementManipulator.class)
                .newInstance(logger, elementManipulator);
      } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException e) {
        throw new InvalidConfigurationException(
            "Can not generate DD CFA mutation strategy from option", e);
      }
    }

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

  public LogManager createRoundLogger(LoggingOptions pLogOptions) {
    LogManager result = LogManager.createNullLogManager();

    Path logFile = pLogOptions.getOutputFile();
    if (logFile == null || pLogOptions.getFileLevel() == Level.OFF) {
      return result;
    }
    logFile = exportDirectory.resolve(logFile.getFileName());

    // create logger to given file
    Level fileLevel =
        fileLogLevel.intValue() <= pLogOptions.getFileLevel().intValue()
            ? fileLogLevel
            : pLogOptions.getFileLevel();

    try {
      MoreFiles.createParentDirectories(logFile);
      Handler outfileHandler = new FileHandler(logFile.toAbsolutePath().toString(), false);
      outfileHandler.setFilter(null);
      outfileHandler.setFormatter(TimestampedLogFormatter.withoutColors());
      outfileHandler.setLevel(fileLevel);
      result = BasicLogManager.createWithHandler(outfileHandler);

    } catch (IOException e) {
      // redirect log messages to console
      logger.logUserException(Level.WARNING, e, "Can not log to file");
    }

    return result;
  }
}
