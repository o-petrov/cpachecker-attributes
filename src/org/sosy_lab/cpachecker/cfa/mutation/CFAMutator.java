// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cfa.mutation;

import com.google.common.base.Joiner;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.MoreFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.ClassOption;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.configuration.TimeSpanOption;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.log.LoggingOptions;
import org.sosy_lab.common.log.TimestampedLogFormatter;
import org.sosy_lab.common.time.TimeSpan;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.CFACreator;
import org.sosy_lab.cpachecker.cfa.ParseResult;
import org.sosy_lab.cpachecker.cfa.export.DOTBuilder2;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.mutation.CFAMutationStrategy.MutationRollback;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.exceptions.ParserException;
import org.sosy_lab.cpachecker.util.Pair;

/**
 * Mutates the CFA before next analysis run, mainly to minimize and simplify CFA. Operates on {@link
 * FunctionCFAsWithMetadata}. All processings in {@link CFACreator#createCFA} are applied after this
 * to get proper CFA for analysis run.
 */
@Options(prefix = "cfaMutation")
public class CFAMutator extends CFACreator implements StatisticsProvider {

  @Option(
      secure = true,
      name = "dd.direction",
      description =
          "Delta Debugging can minimize input program or maximize it while preserving given property."
              + "Here properties are results of analysis run:\n"
              + "1. True;\n"
              + "2. False with same description as False for original program;\n"
              + "3. Any False;\n"
              + "4. Timeout;\n"
              + "5. Exception similar to the exception original program causes;\n"
              + "6. Any exception;\n"
              + "7. Unknown (or Not yet started) not because of exception or time limit.\n"
              + "Express property for minimized/maximized program as a set of allowed outcomes."
              + "Also DD can find minimal difference ('cause') between given properties, "
              + "I.e. if 'cause' is removed, property for minimization ceases, and property for "
              + "maximization holds. If 'cause' is present, property for minimization holds, and "
              + "property for maximization does not.\n"
              + "By default, dd minimizes for same exception, so maximization property is 'turned off'."
              + "If you specify {True, Any False} for maximization property with {Same exception} for "
              + "minimization property, DD will find a minimal difference that changes a program from "
              + "reproducing the exception to correct analysis run with conclusive verdict.\n"
              + "The two properties should be mutually exclusive (impossible together); minimization "
              + "property should hold for original input program, and maximization property should "
              + "hold for 'empty' program `int main() {return 0;}`.")
  private DDDirection ddDirection = DDDirection.MINIMIZATION;

  @Option(
      secure = true,
      name = "dd.minProperty",
      description =
          "Delta Debugging can minimize input program or maximize it while preserving given property."
              + "Here properties are results of analysis run:\n"
              + "1. True;\n"
              + "2. False with same description as False for original program;\n"
              + "3. Any False;\n"
              + "4. Timeout;\n"
              + "5. Exception similar to the exception original program causes;\n"
              + "6. Any exception;\n"
              + "7. Unknown (or Not yet started) not because of exception or time limit.\n"
              + "Express property for minimized/maximized program as a set of allowed outcomes."
              + "Also DD can find minimal difference ('cause') between given properties, "
              + "I.e. if 'cause' is removed, property for minimization ceases, and property for "
              + "maximization holds. If 'cause' is present, property for minimization holds, and "
              + "property for maximization does not.\n"
              + "By default, dd minimizes for same exception, so maximization property is 'turned off'."
              + "If you specify {True, Any False} for maximization property with {Same exception} for "
              + "minimization property, DD will find a minimal difference that changes a program from "
              + "reproducing the exception to correct analysis run with conclusive verdict.\n"
              + "The two properties should be mutually exclusive (impossible together); minimization "
              + "property should hold for original input program, and maximization property should "
              + "hold for 'empty' program `int main() {return 0;}`.")
  private ImmutableSet<AnalysisOutcome> ddMinProperty =
      ImmutableSet.of(AnalysisOutcome.FAILURE_BECAUSE_OF_SAME_EXCEPTION);

  @Option(
      secure = true,
      name = "dd.maxProperty",
      description =
          "Delta Debugging can minimize input program or maximize it while preserving given property."
              + "Here properties are results of analysis run:\n"
              + "1. True;\n"
              + "2. False with same description as False for original program;\n"
              + "3. Any False;\n"
              + "4. Timeout;\n"
              + "5. Exception similar to the exception original program causes;\n"
              + "6. Any exception;\n"
              + "7. Unknown (or Not yet started) not because of exception or time limit.\n"
              + "Express property for minimized/maximized program as a set of allowed outcomes."
              + "Also DD can find minimal difference ('cause') between given properties, "
              + "I.e. if 'cause' is removed, property for minimization ceases, and property for "
              + "maximization holds. If 'cause' is present, property for minimization holds, and "
              + "property for maximization does not.\n"
              + "By default, dd minimizes for same exception, so maximization property is 'turned off'."
              + "If you specify {True, Any False} for maximization property with {Same exception} for "
              + "minimization property, DD will find a minimal difference that changes a program from "
              + "reproducing the exception to correct analysis run with conclusive verdict.\n"
              + "The two properties should be mutually exclusive (impossible together); minimization "
              + "property should hold for original input program, and maximization property should "
              + "hold for 'empty' program `int main() {return 0;}`.")
  private ImmutableSet<AnalysisOutcome> ddMaxProperty =
      ImmutableSet.of(AnalysisOutcome.VERDICT_TRUE, AnalysisOutcome.VERDICT_FALSE);

  @SuppressWarnings("rawtypes")
  private enum DDVariant {
    OLD(null),
    DD(FlatDeltaDebugging.class),
    DDSTAR(DDStar.class),
    DDSEARCH(DDSearch.class),
    HDD(HierarchicalDeltaDebugging.class);

    private final Class<? extends FlatDeltaDebugging> ddClass;

    DDVariant(Class<? extends FlatDeltaDebugging> pClass) {
      ddClass = pClass;
    }
  }

  @Option(
      secure = true,
      name = "dd",
      description =
          "which DD algorithm implementation to use to mutate CFA: my previous implementation of "
              + "original (flat) DD; new implementation of original (flat) DD; DD*, that iterates "
              + "cause-isolating DD to minimize or maximize; DDSearch, that iterates over optimums "
              + "found by DD*; or hierarchical DD.")
  private DDVariant ddVariant = DDVariant.DD;

  @Option(
      secure = true,
      name = "manipulator",
      description = "which elements to remove from CFA (one manipulator class)")
  @ClassOption(packagePrefix = "org.sosy_lab.cpachecker.cfa.mutation")
  private Class<? extends CFAElementManipulator<?, ?>> manipulatorClass =
      FunctionBodyManipulator.class;

  @Option(
      secure = true,
      name = "dd.parts",
      description = "which parts to remove? DD can remove deltas, complements, or both")
  private PartsToRemove mode = PartsToRemove.DELTAS_AND_COMPLEMENTS;

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

  @Option(
      secure = true,
      name = "timelimit.forExport",
      description =
          "Time limit for exporting a CFA. If an export exceeds this limit, "
              + "CFA exports are disabled for the following rounds. "
              + "This time limit is not checked if this option is set to zero.")
  @TimeSpanOption(codeUnit = TimeUnit.SECONDS, defaultUserUnit = TimeUnit.SECONDS, min = 0)
  private TimeSpan exportTimelimit = TimeSpan.ofSeconds(2);

  boolean exportWasTooLong = false;

  /** local CFA of functions before processing */
  private FunctionCFAsWithMetadata localCfa;
  /** Strategy that decides how to change the CFA and implements this change */
  private final CFAMutationStrategy strategy;

  private final Path cfaExportDirectory;

  private final CFAMutatorStatistics mutatorStats;

  private enum CanMutate {
    CAN_MUTATE,
    CAN_NOT_MUTATE,
    UNKNOWN;
  }

  private CanMutate strategyCanMutate = CanMutate.UNKNOWN;

  public CFAMutator(Configuration pConfig, LogManager pLogger, ShutdownNotifier pShutdownNotifier)
      throws InvalidConfigurationException {
    super(pConfig, pLogger, pShutdownNotifier);
    config.inject(this, CFAMutator.class);

    if (exportDirectory == null) {
      throw new InvalidConfigurationException("Enable output to get results of CFA mutation");
    }
    cfaExportDirectory = exportDirectory;

    if (ddVariant == DDVariant.OLD) {
      // use previous impl
      if (oldStrategies.isEmpty()) {
        throw new InvalidConfigurationException("No CFA mutation strategies were specified");
      }
      strategy = buildOldStrategies(pConfig, pLogger);

    } else {
      // new impl
      ddMinProperty = normalize(ddMinProperty);
      ddMaxProperty = normalize(ddMaxProperty);
      if (!areMutuallyExclusive(ddMinProperty, ddMaxProperty)) {
        throw new InvalidConfigurationException(
            "Properties "
                + toString(ddMinProperty)
                + " and "
                + toString(ddMaxProperty)
                + " should not intersect");
      }
      if (ddDirection == DDDirection.ISOLATION
          && (ddVariant == DDVariant.DDSTAR || ddVariant == DDVariant.DDSEARCH)) {
        throw new InvalidConfigurationException("DD* and DD** cannot isolate cause");
      }
      strategy = buildNewStrategy();
    }

    mutatorStats = new CFAMutatorStatistics(pLogger);
  }

  private CFAMutationStrategy buildNewStrategy() throws InvalidConfigurationException {
    CFAElementManipulator<?, ?> manipulator = null;
    try {
      manipulator =
          manipulatorClass
              .getConstructor(Configuration.class, LogManager.class)
              .newInstance(config, logger);
    } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException e) {
      throw new InvalidConfigurationException("Can not generate CFA manipulator from option", e);
    }

    try {
      return ddVariant
          .ddClass
          .getConstructor(
              LogManager.class, CFAElementManipulator.class, DDDirection.class, PartsToRemove.class)
          .newInstance(logger, manipulator, ddDirection, mode);

    } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException e) {
      logger.logUserException(
          Level.SEVERE, e.getCause(), "Can not generate DD CFA mutation strategy from option");
      throw new InvalidConfigurationException(
          "Can not generate DD CFA mutation strategy from option", e.getCause());
    }
  }

  private CFAMutationStrategy buildOldStrategies(Configuration pConfig, LogManager pLogger)
      throws InvalidConfigurationException {
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
      return strategiesList.build().get(0);
    } else {
      return new CompositeCFAMutationStrategy(pLogger, strategiesList.build());
    }
  }

  @Override
  protected void exportCFA(CFA pCfa) {
    if (exportWasTooLong) {
      return;
    }

    mutatorStats.startExport();
    super.exportCFA(pCfa);
    mutatorStats.stopExport();

    if (exportTimelimit.compareTo(TimeSpan.empty()) > 0
        && mutatorStats.getMaxExportTime().compareTo(exportTimelimit) > 0) {
      logger.log(
          Level.WARNING,
          "Following exports are disabled because they take longer than",
          exportTimelimit);
      exportWasTooLong = true;
    }

  }

  private CFA exportCreateExportCFA()
      throws InvalidConfigurationException, InterruptedException, ParserException {

    if (mutatorStats.getRound() == 0) {
      exportDirectory = cfaExportDirectory.resolve("0-original-cfa");
    } else {
      exportDirectory =
          cfaExportDirectory.resolve(String.valueOf(mutatorStats.getRound()) + "-mutation-round");
    }

    if (!exportWasTooLong) {
      mutatorStats.startExport();
      try {
        new DOTBuilder2(localCfa)
            .writeGraphs(exportDirectory.resolve("function-cfas-before-processing"));
      } catch (IOException e) {
        logger.logUserException(
            Level.WARNING, e, "Could not write function CFAs before processing to dot files");
      }
      // new BlockToDotWriter(localCfa).dump(exportDirectory.resolve("function-cfa-blocks"),
      // logger);
      mutatorStats.stopExport();
    }

    mutatorStats.setCfaStats(localCfa);
    CFA result = super.createCFA(localCfa.copyAsParseResult(), localCfa.getMainFunction());
    mutatorStats.setCfaStats(result);

    exportCFA(result);
    return result;
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
    return exportCreateExportCFA();
  }

  @Override
  protected void exportCFAAsync(CFA pCfa) {
    // do not export asynchronously as CFA will be mutated
  }

  public void clearCreatorStats() {
    // reset CFACreator stats so following variable classification
    // and pointer resolving wont add a lot of similar stats to print
    stats = new CFACreatorStatistics(logger);
  }

  public boolean canMutate() {
    if (strategyCanMutate != CanMutate.UNKNOWN) {
      return strategyCanMutate == CanMutate.CAN_MUTATE;
    }

    // clear processings before first #canMutate and after rollbacks
    mutatorStats.startCfaReset();
    localCfa.resetEdgesInNodes();
    mutatorStats.stopCfaReset();

    mutatorStats.startPreparations();
    boolean result = strategy.canMutate(localCfa);
    strategyCanMutate = result ? CanMutate.CAN_MUTATE : CanMutate.CAN_NOT_MUTATE;
    mutatorStats.stopPreparations();

    if (strategy instanceof AbstractDeltaDebuggingStrategy<?>) {
      mutatorStats.startExport();
      ((AbstractDeltaDebuggingStrategy<?>) strategy).exportGraph(exportDirectory);
      mutatorStats.stopExport();
    }

    return result;
  }

  /** Apply some mutation to the CFA */
  public CFA mutate() throws InterruptedException, InvalidConfigurationException, ParserException {
    mutatorStats.startMutation();
    strategyCanMutate = CanMutate.UNKNOWN;
    strategy.mutate(localCfa);
    mutatorStats.stopMutation();

    return exportCreateExportCFA();
  }

  /** Undo last mutation if needed */
  public CFA setResult(AnalysisOutcome pLastOutcome)
      throws InvalidConfigurationException, InterruptedException, ParserException {

    // XXX write result?
    // undo createCFA before possible mutation rollback
    mutatorStats.startCfaReset();
    localCfa.resetEdgesInNodes();
    mutatorStats.stopCfaReset();

    mutatorStats.startAftermath(pLastOutcome);
    MutationRollback mutationRollback =
        strategy.setResult(localCfa, outcomeToDDResult(pLastOutcome));
    mutatorStats.stopAftermath();

    if (mutationRollback == MutationRollback.NO_ROLLBACK) {
      return null;
    }

    // export after rollback as there may be no more mutations
    mutatorStats.setCfaStats(localCfa);
    CFA rollbackedCfa = super.createCFA(localCfa.copyAsParseResult(), localCfa.getMainFunction());
    mutatorStats.setCfaStats(rollbackedCfa);

    exportDirectory =
        cfaExportDirectory.resolve(
            String.valueOf(mutatorStats.getRound())
                + "-mutation-round-"
                + (mutationRollback == MutationRollback.ROLLBACK ? "rollbacked" : "irregular"));
    exportCFA(rollbackedCfa);

    return mutationRollback == MutationRollback.ROLLBACK ? rollbackedCfa : null;
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

  public String getApproachName() {
    switch (ddDirection) {
      case ISOLATION:
        return Joiner.on(' ')
            .join("isolate what cause a change from", ddMaxProperty, "to", ddMinProperty);

      case MAXIMIZATION:
        return "maximize for " + ddMaxProperty;

      case MINIMIZATION:
        return "minimize for " + ddMinProperty;

      default:
        throw new AssertionError();
    }
  }

  public void verifyOutcome(AnalysisOutcome pAnalysisOutcome) {
    if (ddDirection == DDDirection.MAXIMIZATION && !canMutate()) {
      Verify.verify(
          ddMaxProperty.contains(pAnalysisOutcome),
          "max-property after maximization was finished %s does not hold: %s",
          ddMaxProperty,
          pAnalysisOutcome);
    } else {
      Verify.verify(
          ddMinProperty.contains(pAnalysisOutcome),
          "min-property %s does not hold: %s",
          ddMinProperty,
          pAnalysisOutcome);
    }
  }

  private DDResultOfARun outcomeToDDResult(AnalysisOutcome pOutcome) {
    boolean inMin = ddMinProperty.contains(pOutcome);
    boolean inMax = ddMaxProperty.contains(pOutcome);
    Verify.verify(!inMax || !inMin, "Properties should be mutually exclusive.");
    if (inMin) {
      return DDResultOfARun.MINIMIZATION_PROPERTY_HOLDS;
    } else if (inMax) {
      return DDResultOfARun.MAXIMIZATION_PROPERTY_HOLDS;
    } else {
      return DDResultOfARun.NEITHER_PROPERTY_HOLDS;
    }
  }

  private ImmutableSet<AnalysisOutcome> normalize(ImmutableSet<AnalysisOutcome> pOutcomes) {
    ImmutableSet.Builder<AnalysisOutcome> builder =
        ImmutableSet.<AnalysisOutcome>builder().addAll(pOutcomes);

    if (pOutcomes.contains(AnalysisOutcome.FAILURE_BECAUSE_OF_EXCEPTION)) {
      builder.add(AnalysisOutcome.FAILURE_BECAUSE_OF_SAME_EXCEPTION);
    }

    if (pOutcomes.contains(AnalysisOutcome.VERDICT_FALSE)) {
      builder.add(AnalysisOutcome.SAME_VERDICT_FALSE);
    }

    return builder.build();
  }

  private boolean areMutuallyExclusive(
      ImmutableSet<AnalysisOutcome> pLeft, ImmutableSet<AnalysisOutcome> pRight) {
    return !pLeft.stream().anyMatch(outcome -> pRight.contains(outcome));
  }

  public String toString(ImmutableSet<AnalysisOutcome> pOutcomes) {
    List<String> parts = new ArrayList<>();
    for (AnalysisOutcome outcome : pOutcomes) {
      if (outcome == AnalysisOutcome.SAME_VERDICT_FALSE
          && pOutcomes.contains(AnalysisOutcome.VERDICT_FALSE)) {
        continue;
      }
      if (outcome == AnalysisOutcome.FAILURE_BECAUSE_OF_SAME_EXCEPTION
          && pOutcomes.contains(AnalysisOutcome.FAILURE_BECAUSE_OF_EXCEPTION)) {
        continue;
      }

      parts.add(outcome.name().toLowerCase().replace('_', ' '));
    }
    return Joiner.on(' ').join(parts);
  }

  public boolean shouldCheckFeasibiblity(AnalysisOutcome pLastOutcome) {
    boolean result =
        ddVariant == DDVariant.DDSEARCH
            && (pLastOutcome == AnalysisOutcome.VERDICT_FALSE
                || pLastOutcome == AnalysisOutcome.SAME_VERDICT_FALSE);

    if (!result) {
      return false;
    }

    Pair<ParseResult, FunctionEntryNode> pair = FunctionCFAsWithMetadata.originalCopy();

    try {
      super.createCFA(pair.getFirst(), pair.getSecond());
      return true;

    } catch (InvalidConfigurationException | InterruptedException | ParserException e) {
      logger.logUserException(Level.WARNING, e, "Cannot restore original CFA");
      return false;
    }
  }

  public boolean shouldReturnWithoutMutation(AnalysisOutcome pOriginalOutcome) {
    if (ddMaxProperty.contains(pOriginalOutcome)) {
      logger.log(Level.WARNING, "CFA is not mutated.");
      return true;
    }

    verifyOutcome(pOriginalOutcome);
    return false;
  }
}
